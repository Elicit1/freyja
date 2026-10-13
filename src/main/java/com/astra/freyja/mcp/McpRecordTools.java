package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.McpQueryMapper;
import com.astra.freyja.entity.BaseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/** Independently published tools with object-specific schemas and explicitly bound business updates. */
@Component
@RequiredArgsConstructor
public class McpRecordTools {
    private final McpBusinessRecords records;
    private final McpBusinessSchemas schemas;
    private final McpOperationStore journal;
    private final McpScope scope;
    private final McpBusinessView views;
    private final McpQueryMapper queries;

    @FunctionalInterface
    interface UpdateAction {
        void apply(McpPrincipal caller, BaseEntity current, Long project, Map<String, Object> data);
    }
    record Binding(McpRecordType type, String singular, String plural, String label, UpdateAction update) {
        String tool(String action) { return action + "_" + singular; }
        String batchTool() { return "batch_update_" + plural; }
        Map<String, Object> capability() {
            return Map.of("type", type.name(), "label", label, "read", tool("get"), "create", tool("create"),
                    "update", tool("update"), "delete", tool("delete"), "batchUpdate", batchTool());
        }
    }
    private List<Binding> bindings() {
        return List.of(
                new Binding(McpRecordType.PROJECT, "project", "projects", "项目", records::updateProject),
                new Binding(McpRecordType.EPISODE, "episode", "episodes", "剧集", records::updateEpisode),
                new Binding(McpRecordType.SCENE, "scene", "scenes", "剧情场次", records::updateScene),
                new Binding(McpRecordType.SHOT_GROUP, "shot_group", "shot_groups", "镜头组", records::updateShotGroup),
                new Binding(McpRecordType.SHOT, "shot", "shots", "分镜", records::updateShot),
                new Binding(McpRecordType.CHARACTER, "character", "characters", "人物", records::updateCharacter),
                new Binding(McpRecordType.CHARACTER_LOOK, "character_look", "character_looks", "人物造型", records::updateCharacterLook),
                new Binding(McpRecordType.RESOURCE_SCENE, "resource_scene", "resource_scenes", "场景资产", records::updateResourceScene),
                new Binding(McpRecordType.PROP, "prop", "props", "道具", records::updateProp),
                new Binding(McpRecordType.KEYFRAME, "keyframe", "keyframes", "关键图", records::updateKeyframe));
    }
    public List<Map<String, Object>> capabilities() { return bindings().stream().map(Binding::capability).toList(); }

    public List<McpTool<?>> tools() {
        List<McpTool<?>> result = new ArrayList<>();
        for (Binding binding : bindings()) {
            // Existing project/shot detail tools keep their established dramaId/shotId arguments.
            if (binding.type() != McpRecordType.PROJECT && binding.type() != McpRecordType.SHOT)
                result.add(new McpTool<>(binding.tool("get"), "读取" + binding.label() + "完整业务字段及 editFingerprint；公共资产只读。",
                        McpRecordInputs.Get.class, McpTool.Effect.READ, schemas.singleGet(binding.type()),
                        (c, i) -> records.read(c, binding.type(), i.recordId(), i.dramaId())));
            result.add(new McpTool<>(binding.tool("create"), "单条创建" + binding.label() + "；仅接受该对象业务字段，requestId 幂等，不调用内部 AI。",
                    McpRecordInputs.Create.class, McpTool.Effect.WRITE, schemas.singleCreate(binding.type()), (c, i) -> create(binding, c, i)));
            result.add(new McpTool<>(binding.tool("update"), "单条修改" + binding.label() + "；省略字段保留，需当前 expectedFingerprint 和稳定 requestId。提示词由外部直接填写。",
                    McpRecordInputs.Update.class, McpTool.Effect.WRITE, schemas.singleUpdate(binding.type()), (c, i) -> update(binding, c, i)));
            result.add(new McpTool<>(binding.tool("delete"), "单条逻辑删除" + binding.label() + "；需 expectedFingerprint，先删除子记录并解除引用，不删除媒体文件，requestId 幂等。",
                    McpRecordInputs.Delete.class, McpTool.Effect.WRITE, schemas.singleDelete(), (c, i) -> delete(binding, c, i)));
            result.add(new McpTool<>(binding.batchTool(), "事务批量修改" + binding.label() + "，每次 1–100 项；只接受该对象的字段，无 type 参数。每项需 expectedFingerprint，任一失败全批回滚，requestId 幂等。",
                    McpRecordInputs.BatchUpdate.class, McpTool.Effect.WRITE, schemas.batchUpdate(binding.type()), (c, i) -> batchUpdate(binding, c, i)));
        }
        return result;
    }
    private Object create(Binding binding, McpPrincipal caller, McpRecordInputs.Create input) {
        write(caller);
        Long project;
        if (binding.type() == McpRecordType.PROJECT) {
            if (input.dramaId() != null) throw new BizException(400, "新建项目请省略 dramaId");
            if (!caller.createProjects()) throw new BizException(403, "MCP_CREATE_PROJECT_DISABLED");
            project = null;
        } else project = scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.tool("create"), input, () -> {
            if (project != null) mutex(project);
            Long id = records.create(caller, binding.type(), project, input.data());
            Long owner = project == null ? id : project;
            McpPrincipal effective = caller;
            if (project == null) {
                Set<Long> access = new HashSet<>(caller.projects()); access.add(id);
                effective = new McpPrincipal(caller.write(), caller.allProjects(), caller.createProjects(), access);
            }
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", owner.toString(),
                    "recordId", id.toString(), "data", records.read(effective, binding.type(), id.toString(), owner.toString())), owner, project == null);
        });
    }
    private Object update(Binding binding, McpPrincipal caller, McpRecordInputs.Update input) {
        write(caller); Long project = scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.tool("update"), input, () -> {
            mutex(project);
            BaseEntity current = records.lockScoped(caller, binding.type(), input.recordId(), input.dramaId());
            fingerprint(current, input.expectedFingerprint());
            binding.update().apply(caller, current, project, input.data());
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", input.dramaId(),
                    "recordId", input.recordId(), "data", records.read(caller, binding.type(), input.recordId(), input.dramaId())), project, false);
        });
    }
    private Object delete(Binding binding, McpPrincipal caller, McpRecordInputs.Delete input) {
        write(caller); Long project = scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.tool("delete"), input, () -> {
            mutex(project);
            BaseEntity current = records.lockScoped(caller, binding.type(), input.recordId(), input.dramaId());
            fingerprint(current, input.expectedFingerprint());
            records.delete(binding.type(), current);
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", input.dramaId(),
                    "recordId", input.recordId(), "deleted", true), project, false);
        });
    }
    private Object batchUpdate(Binding binding, McpPrincipal caller, McpRecordInputs.BatchUpdate input) {
        write(caller);
        if (input.items() == null || input.items().isEmpty() || input.items().size() > 100) throw new BizException(400, "每批必须包含 1–100 项");
        Long project = scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.batchTool(), input, () -> {
            mutex(project);
            Set<String> seen = new HashSet<>(); List<BaseEntity> locked = new ArrayList<>();
            // Compare all original versions before one update changes siblings (e.g. default looks).
            for (int index = 0; index < input.items().size(); index++) {
                var item = input.items().get(index);
                try {
                    if (item == null || !seen.add(item.recordId())) throw new BizException(400, "更新项无效或重复");
                    BaseEntity current = records.lockScoped(caller, binding.type(), item.recordId(), input.dramaId());
                    fingerprint(current, item.expectedFingerprint()); locked.add(current);
                } catch (BizException e) { throw itemError(index, e); }
            }
            for (int index = 0; index < input.items().size(); index++) {
                try { binding.update().apply(caller, records.lock(binding.type(), locked.get(index).getId()), project, input.items().get(index).data()); }
                catch (BizException e) { throw itemError(index, e); }
            }
            List<Object> result = new ArrayList<>();
            for (var item : input.items()) result.add(Map.of("recordId", item.recordId(), "data", records.read(caller, binding.type(), item.recordId(), input.dramaId())));
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", input.dramaId(), "records", result), project, false);
        });
    }
    private void mutex(Long project) { if (queries.lockProject(project) == null) throw new BizException(404, "项目不存在"); }
    private void fingerprint(BaseEntity entity, String expected) {
        if (expected == null || !expected.equals(views.fingerprint(entity))) throw new BizException(409, "EDIT_CONFLICT: 内容已变动，请重新读取后提交");
    }
    private void write(McpPrincipal caller) { if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED"); }
    private BizException itemError(int index, BizException error) { return new BizException(error.getCode(), "BATCH_ROLLED_BACK: items[" + index + "]: " + error.getMessage()); }
}
