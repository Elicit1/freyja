package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResAudioMapper;
import com.astra.freyja.dto.res.ResAudioDTO;
import com.astra.freyja.entity.ResAudio;
import com.astra.freyja.service.ResAudioService;
import com.astra.freyja.util.ShotAudioReferenceValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/** Audio materials share workbench persistence, scoped access and MCP write recovery. */
@Component
@RequiredArgsConstructor
public class McpAudioAssets {
    public static final Set<String> TOOL_NAMES = Set.of("search_audio_assets", "get_audio_asset", "update_audio_asset", "delete_audio_asset");
    private static final McpBusinessView.Kind KIND = McpBusinessView.Kind.AUDIO_ASSET;
    private static final Set<String> PATCH_FIELDS = Set.of("name", "description", "audioKind", "text", "language", "shotId", "sortOrder", "status", "remark");
    private final ResAudioMapper mapper;
    private final ResAudioService service;
    private final McpScope scope;
    private final McpBusinessView views;
    private final McpOperationStore journal;

    public record Search(String dramaId, Boolean publicOnly, String keyword, String audioKind, String shotId,
                         Integer status, Integer page, Integer size) {}

    public List<McpTool<?>> tools() {
        return List.of(
                new McpTool<>("search_audio_assets", "分页搜索音频素材。传授权dramaId时包含当前项目与公共库，默认只查启用素材；publicOnly=true时省略dramaId，只读公共库。可按keyword/audioKind/shotId/status筛选，size最多50。返回真实audioUrl、duration、audioKind和字符串ID；不生成声音。", Search.class, McpTool.Effect.READ, searchSchema(), this::search),
                new McpTool<>("get_audio_asset", "读取音频素材详情与editFingerprint，支持fields精简返回。公共素材只读；项目素材按归属授权。将id作为resAudioId、audioUrl作为参考URL，明确选择H3四种referenceType之一后写入镜头refAudios；音乐、音效无需characterId。", McpRecordInputs.Get.class, McpTool.Effect.READ, recordSchema(false, false), (c, i) -> views.project(KIND, require(c, i.recordId(), i.dramaId(), false, false), i.fields())),
                new McpTool<>("update_audio_asset", "更新项目音频素材元数据。先读取editFingerprint作为expectedFingerprint，提供稳定requestId与data稀疏补丁；省略保留，可选文本/语言/shotId传null清空。只能修改名称、种类、描述、原文、语言、同项目分镜关联、排序、状态与备注；不能更换文件、URL或项目。公共库只读。", McpRecordInputs.Update.class, McpTool.Effect.WRITE, recordSchema(true, true), this::update),
                new McpTool<>("delete_audio_asset", "删除项目音频素材，需创作者明确要求、最新expectedFingerprint和稳定requestId。逻辑删除素材记录，保留被镜头/Take引用的文件和URL快照；未引用文件在提交后清理。公共库只读。", McpRecordInputs.Delete.class, McpTool.Effect.WRITE, recordSchema(true, false), this::delete)
        );
    }

    Object search(McpPrincipal caller, Search input) {
        if (input == null) throw new BizException(400, "必须提供音频查询参数");
        boolean publicOnly = Boolean.TRUE.equals(input.publicOnly());
        if (publicOnly && input.dramaId() != null) throw new BizException(400, "公共库查询请省略dramaId");
        Long project = publicOnly ? null : scope.project(caller, input.dramaId());
        if (input.shotId() != null) {
            if (project == null) throw new BizException(400, "公共库查询不接受shotId");
            scope.shot(McpScope.id(input.shotId()), project);
        }
        if (input.keyword() != null && input.keyword().length() > 200) throw new BizException(400, "关键词不能超过200字符");
        if (input.audioKind() != null && !ShotAudioReferenceValidator.AUDIO_KINDS.contains(input.audioKind())) throw new BizException(400, "audioKind无效");
        if (input.status() != null && input.status() != 0 && input.status() != 1) throw new BizException(400, "status只能为0或1");
        var query = new LambdaQueryWrapper<ResAudio>();
        if (project == null) query.eq(ResAudio::getDramaId, 0L);
        else query.and(q -> q.eq(ResAudio::getDramaId, 0L).or().eq(ResAudio::getDramaId, project));
        query.eq(ResAudio::getStatus, input.status() == null ? 1 : input.status())
                .like(input.keyword() != null && !input.keyword().isBlank(), ResAudio::getName, input.keyword())
                .eq(input.audioKind() != null, ResAudio::getAudioKind, input.audioKind())
                .eq(input.shotId() != null, ResAudio::getShotId, McpScope.optionalId(input.shotId()))
                .orderByAsc(ResAudio::getSortOrder).orderByDesc(ResAudio::getId);
        var page = mapper.selectPage(new Page<>(McpScope.page(input.page()), McpScope.size(input.size())), query);
        return McpReadTools.page(page, page.getRecords().stream().map(row -> views.project(KIND, row)).toList());
    }

    private ResAudio require(McpPrincipal caller, String recordId, String dramaId, boolean write, boolean lock) {
        Long id = McpScope.id(recordId);
        ResAudio row = lock ? mapper.selectOne(new LambdaQueryWrapper<ResAudio>().eq(ResAudio::getId, id).last("FOR UPDATE")) : mapper.selectById(id);
        if (row == null) throw new BizException(404, "音频素材不存在");
        Long explicit = dramaId == null ? null : scope.project(caller, dramaId);
        if (Objects.equals(row.getDramaId(), 0L)) {
            if (write) throw new BizException(403, "公共库通过MCP只读");
            if (row.getShotId() != null) throw new BizException(409, "公共素材具有不一致的分镜绑定");
        } else {
            Long owner = scope.project(caller, row.getDramaId().toString());
            if (explicit != null) scope.belongs(owner, explicit);
            if (row.getShotId() != null) scope.shot(row.getShotId(), owner);
        }
        return row;
    }

    private Object update(McpPrincipal caller, McpRecordInputs.Update input) {
        requireWrite(caller);
        if (input == null || input.data() == null || input.data().isEmpty() || !PATCH_FIELDS.containsAll(input.data().keySet())) throw new BizException(400, "音频素材补丁为空或包含不可修改字段");
        views.validateFields(KIND, input.fields());
        if (input.dramaId() != null) scope.project(caller, input.dramaId());
        return journal.execute(input.requestId(), "update_audio_asset", input, caller, () -> {
            var row = require(caller, input.recordId(), input.dramaId(), true, true);
            views.checkFingerprint(KIND, row, input.expectedFingerprint(), input.data().keySet());
            var dto = new ResAudioDTO(); BeanUtils.copyProperties(row, dto);
            input.data().forEach((name, value) -> patch(dto, name, value));
            if (dto.getShotId() != null) scope.shot(dto.getShotId(), row.getDramaId());
            service.update(dto);
            return new McpOperationStore.Outcome(views.project(KIND, mapper.selectById(row.getId()), input.fields()), row.getDramaId(), false);
        });
    }

    private Object delete(McpPrincipal caller, McpRecordInputs.Delete input) {
        requireWrite(caller);
        if (input == null) throw new BizException(400, "必须提供删除参数");
        if (input.dramaId() != null) scope.authorizeProject(caller, input.dramaId());
        return journal.execute(input.requestId(), "delete_audio_asset", input, caller, () -> {
            var row = require(caller, input.recordId(), input.dramaId(), true, true);
            views.checkFingerprint(KIND, row, input.expectedFingerprint(), Set.of());
            service.delete(row.getId());
            return new McpOperationStore.Outcome(Map.of("recordId", row.getId().toString(), "deleted", true), row.getDramaId(), false);
        });
    }

    private static void patch(ResAudioDTO dto, String name, Object value) {
        if (Set.of("sortOrder", "status").contains(name)) {
            int number;
            try { if (!(value instanceof Number)) throw new IllegalArgumentException(); number = new java.math.BigDecimal(value.toString()).intValueExact(); }
            catch (RuntimeException e) { throw new BizException(400, name + "必须为整数"); }
            if (name.equals("status")) dto.setStatus(number); else dto.setSortOrder(number);
            return;
        }
        if (value != null && !(value instanceof String)) throw new BizException(400, name + "必须为字符串或null");
        String text = (String) value;
        switch (name) {
            case "name" -> dto.setName(text);
            case "audioKind" -> dto.setAudioKind(text);
            case "description" -> dto.setDescription(text);
            case "text" -> dto.setText(text);
            case "language" -> dto.setLanguage(text);
            case "shotId" -> dto.setShotId(McpScope.optionalId(text));
            case "remark" -> { if (text != null && text.length() > 1000) throw new BizException(400, "remark不能超过1000字符"); dto.setRemark(text); }
            default -> throw new BizException(400, "不支持的音频元数据字段");
        }
    }

    private static void requireWrite(McpPrincipal caller) { if (!caller.write()) throw new BizException(403, "MCP_WRITE_DISABLED"); }
    private static Map<String, Object> idSchema() { return Map.of("type", "string", "pattern", "^[1-9][0-9]{0,18}$"); }
    private static Map<String, Object> stringSchema(int max, boolean nullable) { return Map.of("type", nullable ? List.of("string", "null") : "string", "maxLength", max); }
    private static Map<String, Object> enumSchema(Set<String> values) { return Map.of("type", "string", "enum", values.stream().sorted().toList()); }

    private Map<String, Object> searchSchema() {
        var fields = new LinkedHashMap<String, Object>(); fields.put("dramaId", idSchema()); fields.put("publicOnly", Map.of("type", "boolean"));
        fields.put("keyword", stringSchema(200, false)); fields.put("audioKind", enumSchema(ShotAudioReferenceValidator.AUDIO_KINDS)); fields.put("shotId", idSchema());
        fields.put("status", Map.of("type", "integer", "enum", List.of(0, 1))); fields.put("page", Map.of("type", "integer", "minimum", 1, "maximum", 100000));
        fields.put("size", Map.of("type", "integer", "minimum", 1, "maximum", 50));
        return Map.of("type", "object", "additionalProperties", false, "properties", fields);
    }

    private Map<String, Object> recordSchema(boolean write, boolean update) {
        var fields = new LinkedHashMap<String, Object>(); fields.put("recordId", idSchema()); fields.put("dramaId", idSchema());
        if (!write || update) fields.put("fields", Map.of("type", "array", "items", enumSchema(McpBusinessView.publicFields(KIND))));
        if (write) {
            fields.put("requestId", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{8,80}$"));
            fields.put("expectedFingerprint", Map.of("type", "string", "pattern", "^[a-f0-9]{64}$"));
        }
        if (update) {
            var patch = new LinkedHashMap<String, Object>(); patch.put("name", Map.of("type", "string", "minLength", 1, "maxLength", 200));
            patch.put("audioKind", enumSchema(ShotAudioReferenceValidator.AUDIO_KINDS)); patch.put("description", stringSchema(4000, true));
            patch.put("text", stringSchema(10000, true)); patch.put("remark", stringSchema(1000, true));
            patch.put("shotId", Map.of("type", List.of("string", "null"), "pattern", "^[1-9][0-9]{0,18}$"));
            var languages = new ArrayList<Object>(ShotAudioReferenceValidator.LANGUAGES.stream().sorted().toList()); languages.add(null);
            patch.put("language", Map.of("type", List.of("string", "null"), "enum", languages));
            patch.put("sortOrder", Map.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE)); patch.put("status", Map.of("type", "integer", "enum", List.of(0, 1)));
            fields.put("data", Map.of("type", "object", "additionalProperties", false, "minProperties", 1, "properties", patch));
        }
        return Map.of("type", "object", "additionalProperties", false, "required", write ? (update ? List.of("requestId", "recordId", "expectedFingerprint", "data") : List.of("requestId", "recordId", "expectedFingerprint")) : List.of("recordId"), "properties", fields);
    }
}
