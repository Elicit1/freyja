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
    private final McpBusinessInputValidator inputValidator;
    private final McpOperationStore journal;
    private final McpScope scope;
    private final McpBusinessView views;
    private final McpQueryMapper queries;

    @FunctionalInterface
    interface UpdateAction {
        void apply(McpPrincipal caller, BaseEntity current, Long project, Map<String, Object> data);
    }
    enum Operation { GET, CREATE, UPDATE, DELETE, BATCH_UPDATE }
    record Binding(McpRecordType type, String label, Map<Operation, String> names, UpdateAction update) {
        String tool(Operation operation) { return names.get(operation); }
        String batchTool() { return tool(Operation.BATCH_UPDATE); }
        Map<String, Object> capability() {
            return Map.of("type", type.name(), "label", label, "read", tool(Operation.GET), "create", tool(Operation.CREATE),
                    "update", tool(Operation.UPDATE), "delete", tool(Operation.DELETE), "batchUpdate", batchTool());
        }
    }
    private Binding binding(McpRecordType type, String label, String get, String create, String update, String delete, String batch, UpdateAction action) {
        return new Binding(type, label, Map.of(Operation.GET, get, Operation.CREATE, create, Operation.UPDATE, update,
                Operation.DELETE, delete, Operation.BATCH_UPDATE, batch), action);
    }
    private List<Binding> bindings() {
        return List.of(
                binding(McpRecordType.PROJECT, "项目", "get_project", "create_project", "update_project", "delete_project", "batch_update_projects", records::updateProject),
                binding(McpRecordType.EPISODE, "剧集", "get_episode", "create_episode", "update_episode", "delete_episode", "batch_update_episodes", records::updateEpisode),
                binding(McpRecordType.SCENE, "剧情场次", "get_scene", "create_scene", "update_scene", "delete_scene", "batch_update_scenes", records::updateScene),
                binding(McpRecordType.SHOT_GROUP, "镜头组", "get_shot_group", "create_shot_group", "update_shot_group", "delete_shot_group", "batch_update_shot_groups", records::updateShotGroup),
                binding(McpRecordType.SHOT, "分镜", "get_shot", "create_shot", "update_shot", "delete_shot", "batch_update_shots", records::updateShot),
                binding(McpRecordType.CHARACTER, "人物", "get_character", "create_character", "update_character", "delete_character", "batch_update_characters", records::updateCharacter),
                binding(McpRecordType.CHARACTER_LOOK, "人物造型", "get_character_look", "create_character_look", "update_character_look", "delete_character_look", "batch_update_character_looks", records::updateCharacterLook),
                binding(McpRecordType.RESOURCE_SCENE, "场景资产", "get_resource_scene", "create_resource_scene", "update_resource_scene", "delete_resource_scene", "batch_update_resource_scenes", records::updateResourceScene),
                binding(McpRecordType.PROP, "道具", "get_prop", "create_prop", "update_prop", "delete_prop", "batch_update_props", records::updateProp),
                binding(McpRecordType.KEYFRAME, "关键图", "get_keyframe", "create_keyframe", "update_keyframe", "delete_keyframe", "batch_update_keyframes", records::updateKeyframe));
    }
    public List<Map<String, Object>> capabilities() { return bindings().stream().map(Binding::capability).toList(); }

    public List<McpTool<?>> tools() {
        List<McpTool<?>> result = new ArrayList<>();
        for (Binding binding : bindings()) {
            // Project and shot details are registered by McpReadTools with the same Get input.
            if (binding.type() != McpRecordType.PROJECT && binding.type() != McpRecordType.SHOT)
                result.add(new McpTool<>(binding.tool(Operation.GET), "读取" + binding.label() + "业务字段及 editFingerprint；dramaId 可省略，fields 可选公开顶层字段，始终返回 id/editFingerprint；公共资产只读。",
                        McpRecordInputs.Get.class, McpTool.Effect.READ, schemas.singleGet(binding.type()),
                        (c, i) -> records.read(c, binding.type(), i.recordId(), i.dramaId(), i.fields())));
            result.add(new McpTool<>(binding.tool(Operation.CREATE), "单条创建" + binding.label() + "；仅接受该对象业务字段，requestId 幂等，不调用内部 AI。",
                    McpRecordInputs.Create.class, McpTool.Effect.WRITE, schemas.singleCreate(binding.type()), (c, i) -> create(binding, c, i)).validated(args -> validate(binding.type(), args.get("data"), true)));
            result.add(new McpTool<>(binding.tool(Operation.UPDATE), "单条补丁修改" + binding.label() + "；data 仅传变更字段，省略字段保留，需当前 expectedFingerprint 和稳定 requestId。dramaId 可省略。"
                            + (binding.type() == McpRecordType.SHOT ? "不需重传 Prompt、引用或导演计划；只改负向词示例 data={\"negativePrompt\":\"blur\"}。fields 仅控制返回字段。" : ""),
                    McpRecordInputs.Update.class, McpTool.Effect.WRITE, schemas.singleUpdate(binding.type()), (c, i) -> update(binding, c, i)).validated(args -> validate(binding.type(), args.get("data"), false)));
            result.add(new McpTool<>(binding.tool(Operation.DELETE), "单条逻辑删除" + binding.label() + "；需 expectedFingerprint，先删除子记录并解除引用，不删除媒体文件，requestId 幂等。",
                    McpRecordInputs.Delete.class, McpTool.Effect.WRITE, schemas.singleDelete(), (c, i) -> delete(binding, c, i)));
            result.add(new McpTool<>(binding.batchTool(), "事务批量修改" + binding.label() + "，每次 1–100 项；只接受该对象的字段，无 type 参数。每项 data 仅传变更字段，省略保留；需 expectedFingerprint，任一失败全批回滚，requestId 幂等。dramaId 可省略；仅分镜支持回执 fields。",
                    McpRecordInputs.BatchUpdate.class, McpTool.Effect.WRITE, schemas.batchUpdate(binding.type()), (c, i) -> batchUpdate(binding, c, i)).validated(args -> validateBatch(binding.type(), args)));
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private void validate(McpRecordType type, Object data, boolean create) {
        if (data instanceof Map<?, ?> map) inputValidator.validate(type, (Map<String, Object>) map, false, create);
    }
    private void validateBatch(McpRecordType type, Map<String, Object> arguments) {
        if (arguments.get("items") instanceof List<?> items) for (Object item : items)
            if (item instanceof Map<?, ?> map) validate(type, map.get("data"), false);
    }
    private Object create(Binding binding, McpPrincipal caller, McpRecordInputs.Create input) {
        write(caller);
        Long project;
        if (binding.type() == McpRecordType.PROJECT) {
            if (input.dramaId() != null) throw new BizException(400, "新建项目请省略 dramaId");
            if (!caller.createProjects()) throw new BizException(403, "MCP_CREATE_PROJECT_DISABLED");
            project = null;
        } else project = scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.tool(Operation.CREATE), input, () -> {
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
        write(caller);
        if (input.dramaId() != null) scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.tool(Operation.UPDATE), input, caller, () -> {
            Long project = records.resolveProject(caller, binding.type(), input.recordId(), input.dramaId(), true);
            views.validateFields(binding.type().viewKind(), input.fields());
            mutex(project);
            BaseEntity current = records.lockScoped(caller, binding.type(), input.recordId(), project.toString());
            views.checkFingerprint(binding.type().viewKind(), current, input.expectedFingerprint(), input.data().keySet());
            binding.update().apply(caller, current, project, input.data());
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", project.toString(),
                    "recordId", input.recordId(), "data", records.read(caller, binding.type(), input.recordId(), project.toString(), input.fields())), project, false);
        });
    }
    private Object delete(Binding binding, McpPrincipal caller, McpRecordInputs.Delete input) {
        write(caller);
        if (input.dramaId() != null) scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.tool(Operation.DELETE), input, caller, () -> {
            Long project = records.resolveProject(caller, binding.type(), input.recordId(), input.dramaId(), true);
            mutex(project);
            BaseEntity current = records.lockScoped(caller, binding.type(), input.recordId(), project.toString());
            views.checkFingerprint(binding.type().viewKind(), current, input.expectedFingerprint(), Set.of());
            records.delete(binding.type(), current);
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", project.toString(),
                    "recordId", input.recordId(), "deleted", true), project, false);
        });
    }
    private Object batchUpdate(Binding binding, McpPrincipal caller, McpRecordInputs.BatchUpdate input) {
        write(caller);
        if (input.items() == null || input.items().isEmpty() || input.items().size() > 100) throw new BizException(400, "每批必须包含 1–100 项");
        if (input.dramaId() != null) scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), binding.batchTool(), input, caller, () -> {
            var first = input.items().getFirst();
            if (first == null) throw itemError(0, null, new BizException(400, "更新项不能为空"));
            Long project;
            try { project = records.resolveProject(caller, binding.type(), first.recordId(), input.dramaId(), true); }
            catch (BizException e) { throw itemError(0, first.recordId(), e); }
            views.validateFields(binding.type().viewKind(), input.fields());
            mutex(project);
            Set<String> seen = new HashSet<>(); List<BaseEntity> locked = new ArrayList<>();
            // Compare all original versions before one update changes siblings (e.g. default looks).
            for (int index = 0; index < input.items().size(); index++) {
                var item = input.items().get(index);
                try {
                    if (item == null || !seen.add(item.recordId())) throw new BizException(400, "更新项无效或重复");
                    BaseEntity current = records.lockScoped(caller, binding.type(), item.recordId(), project.toString());
                    views.checkFingerprint(binding.type().viewKind(), current, item.expectedFingerprint(), item.data().keySet()); locked.add(current);
                } catch (BizException e) { throw itemError(index, input.items().get(index) == null ? null : input.items().get(index).recordId(), e); }
            }
            for (int index = 0; index < input.items().size(); index++) {
                try { binding.update().apply(caller, records.lock(binding.type(), locked.get(index).getId()), project, input.items().get(index).data()); }
                catch (BizException e) { throw itemError(index, input.items().get(index) == null ? null : input.items().get(index).recordId(), e); }
            }
            List<Object> result = new ArrayList<>();
            for (var item : input.items()) result.add(Map.of("recordId", item.recordId(), "data", records.read(caller, binding.type(), item.recordId(), project.toString(), input.fields())));
            return new McpOperationStore.Outcome(Map.of("requestId", input.requestId(), "dramaId", project.toString(), "records", result), project, false);
        });
    }
    private void mutex(Long project) { if (queries.lockProject(project) == null) throw new BizException(404, "项目不存在"); }
    private void write(McpPrincipal caller) { if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED"); }
    private BizException itemError(int index, String recordId, BizException error) { return McpBusinessException.batch(index, recordId, error); }
}
