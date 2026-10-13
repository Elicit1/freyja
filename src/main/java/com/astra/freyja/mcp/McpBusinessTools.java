package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.entity.BaseEntity;
import com.astra.freyja.service.DramaShotGroupService;
import com.astra.freyja.service.DramaShotService;
import com.astra.freyja.service.ResCharacterLookService;
import com.astra.freyja.service.ShotVideoTakeService;
import com.astra.freyja.dto.drama.ShotVideoTakeQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;

/** Batch business adapters for external Agents. No execution is delegated to the internal Agent. */
@Component
@RequiredArgsConstructor
public class McpBusinessTools {
    private final McpProperties properties;
    private final McpBusinessRecords records;
    private final McpBusinessSchemas schemas;
    private final McpOperationStore journal;
    private final McpScope scope;
    private final McpBusinessView views;
    private final DramaMapper dramas;
    private final DramaShotService shots;
    private final DramaShotGroupService groups;
    private final ResCharacterLookService looks;
    private final ShotVideoTakeService takes;
    private final McpImageUploads imageUploads;

    public List<McpTool<?>> tools() {
        return allTools().stream().filter(tool -> properties.isWriteEnabled() ||
                (tool.effect() == McpTool.Effect.READ && !tool.name().equals("get_business_operation"))).toList();
    }
    public List<McpTool<?>> allTools() {
        List<McpTool<?>> tools = new ArrayList<>();
        tools.add(McpTool.read("batch_read_records", "批量读取业务记录，每次最多 100 项。分镜包含提示词、参考媒体和导演计划数据，不执行内部 AI。非资产记录需 dramaId。", McpBatchInputs.Read.class, this::read));
        tools.add(McpTool.read("list_shot_video_versions", "分页读取分镜已有视频版本，仅返回媒体资产信息，不返回模型、提示词或内部任务。", McpBatchInputs.VideoTakes.class, this::versions));
        {
            tools.add(imageUploads.tool());
            tools.add(write("batch_create_records", "事务批量创建业务记录，最多 100 项；必填字段见各类型 Schema，人物/造型英文 Prompt 和导演计划均可省略。每项 clientKey 唯一；ID 可用 {ref:clientKey} 引用此前新建记录。省略 dramaId 时首项须为 PROJECT，且需新建权限。稳定 requestId 防止重复创建。", McpBatchInputs.Create.class, schemas.create(), (c, i) -> create(c, i, "batch_create_records")));
            Map<String, Object> appendSchema = new LinkedHashMap<>(schemas.create()); appendSchema.put("required", List.of("requestId", "dramaId", "items"));
            tools.add(write("append_storyboard", "将外部 Agent 已生成的资产、剧集、场次、镜头组和分镜追加到授权项目。与 batch_create_records 相同的有序 items；只新增，不覆盖原剧集，不触发内部拆解或模型调用。", McpBatchInputs.Create.class, appendSchema, (c, i) -> {
                if (i.dramaId() == null || i.items().stream().anyMatch(item -> item.type() == McpRecordType.PROJECT)) throw new BizException(400, "追加导入必须指定项目，不能创建或替换项目");
                return create(c, i, "append_storyboard");
            }));
            tools.add(write("batch_update_records", "事务批量配置提示词工作台和业务内容，最多 100 项；参考模式直接写 prompt 与有序 refImages/refAudios，首尾帧模式写 videoPrompt 和帧提示词/URL。导演计划可省略，不调用内部 AI。每项需 expectedFingerprint；省略字段保留原值，媒体数组整组替换，保护创作者锁，requestId 保证幂等。", McpBatchInputs.Update.class, schemas.update(), this::update));
            tools.add(McpTool.read("get_business_operation", "读取 MCP 业务写入请求的已提交结果，便于客户端中断后恢复。不是内部 AI 任务查询接口。", McpBatchInputs.Operation.class, (c, i) -> journal.read(c, i.requestId())));
            tools.add(write("clone_shot", "复制授权项目的分镜，沿用现有业务复制规则，不启动任何 AI。使用读取返回的版本指纹和稳定 requestId。", McpBatchInputs.Clone.class, null, this::cloneShot));
            tools.add(write("set_default_character_look", "将项目人物的一个现有造型设为默认；复用默认造型互斥规则，不生成图片。", McpBatchInputs.DefaultLook.class, null, this::defaultLook));
            tools.add(write("reorder_group_shots", "按给定完整 shotIds 重排镜头组内分镜，不修改镜头内容。不接受跨组/重复/缺少的分镜。", McpBatchInputs.Reorder.class, null, this::reorder));
            tools.add(write("select_shot_video_version", "将分镜已有视频版本选为当前生效视频，复用历史版本选择和尾帧缓存失效规则；不生成或处理视频。", McpBatchInputs.SelectVideo.class, null, this::selectVersion));
        }
        return tools;
    }
    private <I> McpTool<I> write(String name, String description, Class<I> type, Map<String, Object> schema, BiFunction<McpPrincipal, I, Object> action) {
        return new McpTool<>(name, description, type, McpTool.Effect.WRITE, schema, action);
    }

    private Object read(McpPrincipal caller, McpBatchInputs.Read input) {
        batch(input.items());
        List<Object> result = new ArrayList<>();
        for (var item : input.items()) {
            if (item == null) throw new BizException(400, "批次项不能为空");
            try { result.add(Map.of("type", item.type(), "recordId", item.recordId(), "ok", true, "data", records.read(caller, item.type(), item.recordId(), input.dramaId()))); }
            catch (BizException e) { result.add(Map.of("recordId", item.recordId() == null ? "" : item.recordId(), "ok", false, "error", Map.of("code", e.getCode(), "message", e.getMessage()))); }
        }
        return Map.of("records", result);
    }

    private Object create(McpPrincipal caller, McpBatchInputs.Create input, String tool) {
        write(caller); batch(input.items());
        Long existing = input.dramaId() == null ? null : scope.project(caller, input.dramaId());
        if (existing == null && !caller.createProjects()) throw new BizException(403, "MCP_CREATE_PROJECT_DISABLED");
        return journal.execute(input.requestId(), tool, input, () -> {
            Long project = existing;
            if (project != null) mutex(project);
            McpPrincipal effective = caller;
            Map<String, String> references = new LinkedHashMap<>();
            List<Object> result = new ArrayList<>();
            boolean created = false;
            for (int index = 0; index < input.items().size(); index++) {
                var item = input.items().get(index);
                try {
                    if (item == null || item.clientKey() == null || !item.clientKey().matches("[A-Za-z0-9_-]{1,80}") || references.containsKey(item.clientKey()))
                        throw new BizException(400, "clientKey 必须有效且在批次内唯一");
                    schemas.validate(item.type(), item.data(), true, true);
                    if (item.type() == McpRecordType.PROJECT && (index != 0 || project != null)) throw new BizException(400, "新项目只能是无 dramaId 批次的首项");
                    Map<String, Object> data = resolve(item.data(), references);
                    Long id = records.create(effective, item.type(), project, data);
                    if (id == null) throw new IllegalStateException("业务创建未返回 ID");
                    if (item.type() == McpRecordType.PROJECT) {
                        project = id; created = true; Set<Long> access = new HashSet<>(caller.projects()); access.add(id);
                        effective = new McpPrincipal(caller.write(), caller.allProjects(), caller.createProjects(), access);
                        mutex(project);
                    }
                    references.put(item.clientKey(), id.toString());
                    result.add(Map.of("clientKey", item.clientKey(), "type", item.type(), "recordId", id.toString(), "data", records.read(effective, item.type(), id.toString(), project.toString())));
                } catch (BizException e) { throw itemError(index, e); }
            }
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", project.toString(), "records", result, "idMap", references), project, created);
        });
    }

    private Object update(McpPrincipal caller, McpBatchInputs.Update input) {
        write(caller); batch(input.items()); Long project = scope.project(caller, input.dramaId());
        return journal.execute(input.requestId(), "batch_update_records", input, () -> {
            mutex(project); Set<String> seen = new HashSet<>(); List<Object> result = new ArrayList<>();
            for (int index = 0; index < input.items().size(); index++) {
                var item = input.items().get(index);
                try {
                    if (item == null || item.type() == null || !seen.add(item.type() + ":" + item.recordId())) throw new BizException(400, "更新项无效或重复");
                    BaseEntity current = records.lockScoped(caller, item.type(), item.recordId(), input.dramaId());
                    fingerprint(current, item.expectedFingerprint());
                    records.update(caller, item.type(), current, project, item.data());
                    result.add(Map.of("type", item.type(), "recordId", item.recordId(), "data", records.read(caller, item.type(), item.recordId(), input.dramaId())));
                } catch (BizException e) { throw itemError(index, e); }
            }
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", input.dramaId(), "records", result), project, false);
        });
    }

    @SuppressWarnings("unchecked") private Map<String, Object> resolve(Map<String, Object> data, Map<String, String> ids) {
        return (Map<String, Object>) resolveValue(data, ids);
    }
    private Object resolveValue(Object input, Map<String, String> ids) {
        if (input instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get("ref") instanceof String key) {
                if (!ids.containsKey(key)) throw new BizException(400, "引用必须指向此前已创建的 clientKey: " + key);
                return ids.get(key);
            }
            Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, value) -> result.put(key.toString(), resolveValue(value, ids))); return result;
        }
        if (input instanceof List<?> list) return list.stream().map(value -> resolveValue(value, ids)).toList();
        return input;
    }
    private void mutex(Long project) { if (dramas.lockById(project) == null) throw new BizException(404, "项目不存在"); }
    private void fingerprint(BaseEntity entity, String expected) {
        if (expected == null || !expected.equals(views.fingerprint(entity))) throw new BizException(409, "EDIT_CONFLICT: 内容已变动，请重新读取后提交");
    }
    private void write(McpPrincipal caller) { if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED"); }
    private void batch(List<?> items) { if (items == null || items.isEmpty() || items.size() > 100) throw new BizException(400, "每批必须包含 1–100 项"); }
    private BizException itemError(int index, BizException error) { return new BizException(error.getCode(), "BATCH_ROLLED_BACK: items[" + index + "]: " + error.getMessage()); }

    private Object cloneShot(McpPrincipal caller, McpBatchInputs.Clone input) {
        write(caller); Long project = scope.project(caller, input.dramaId());
        return journal.execute(input.requestId(), "clone_shot", input, () -> {
            mutex(project);
            fingerprint(records.lockScoped(caller, McpRecordType.SHOT, input.shotId(), input.dramaId()), input.expectedFingerprint());
            Long id = shots.cloneShot(McpScope.id(input.shotId()));
            records.resetCloneMedia(id);
            return new McpOperationStore.Outcome(records.read(caller, McpRecordType.SHOT, id.toString(), input.dramaId()), project, false);
        });
    }
    private Object defaultLook(McpPrincipal caller, McpBatchInputs.DefaultLook input) {
        write(caller); Long project = scope.project(caller, input.dramaId());
        return journal.execute(input.requestId(), "set_default_character_look", input, () -> {
            mutex(project);
            fingerprint(records.lockScoped(caller, McpRecordType.CHARACTER_LOOK, input.lookId(), input.dramaId()), input.expectedFingerprint());
            looks.setDefault(McpScope.id(input.lookId()));
            return new McpOperationStore.Outcome(records.read(caller, McpRecordType.CHARACTER_LOOK, input.lookId(), input.dramaId()), project, false);
        });
    }
    private Object reorder(McpPrincipal caller, McpBatchInputs.Reorder input) {
        write(caller); batch(input.shotIds()); Long project = scope.project(caller, input.dramaId());
        return journal.execute(input.requestId(), "reorder_group_shots", input, () -> {
            mutex(project); scope.group(McpScope.id(input.groupId()), project);
            List<Long> ids = input.shotIds().stream().map(McpScope::id).toList();
            for (Long id : ids) if (!Objects.equals(scope.shot(id, project).getShotGroupId(), McpScope.id(input.groupId()))) throw new BizException(403, "分镜不属于指定镜头组");
            groups.reorderShotsInGroup(McpScope.id(input.groupId()), ids);
            return new McpOperationStore.Outcome(Map.of("groupId", input.groupId(), "shotIds", input.shotIds()), project, false);
        });
    }
    private Object versions(McpPrincipal caller, McpBatchInputs.VideoTakes input) {
        Long project = scope.project(caller, input.dramaId()); scope.shot(McpScope.id(input.shotId()), project);
        ShotVideoTakeQuery query = new ShotVideoTakeQuery(); query.setCurrent(McpScope.page(input.page())); query.setSize(McpScope.size(input.size()));
        var page = takes.pageByShotId(McpScope.id(input.shotId()), query);
        return McpReadTools.page(page, page.getRecords().stream().map(take -> views.project(McpBusinessView.Kind.VIDEO_TAKE, take)).toList());
    }
    private Object selectVersion(McpPrincipal caller, McpBatchInputs.SelectVideo input) {
        write(caller); Long project = scope.project(caller, input.dramaId());
        return journal.execute(input.requestId(), "select_shot_video_version", input, () -> {
            mutex(project);
            fingerprint(records.lockScoped(caller, McpRecordType.SHOT, input.shotId(), input.dramaId()), input.expectedFingerprint());
            var result = takes.selectTake(McpScope.id(input.shotId()), McpScope.id(input.takeId()));
            if (Boolean.TRUE.equals(result.getChanged())) records.clearVideoTail(McpScope.id(input.shotId()));
            Map<String, Object> selected = new LinkedHashMap<>();
            selected.put("shotId", result.getShotId()); selected.put("previousTakeId", result.getPreviousTakeId());
            selected.put("currentTakeId", result.getCurrentTakeId()); selected.put("videoUrl", result.getVideoUrl()); selected.put("changed", result.getChanged());
            return new McpOperationStore.Outcome(selected, project, false);
        });
    }
}
