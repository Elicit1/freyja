package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.astra.freyja.mcp.McpInputs.AssetType;

@Component
@RequiredArgsConstructor
public class McpAssets {
    private final ResCharacterMapper characters;
    private final ResCharacterLookMapper looks;
    private final ResSceneMapper scenes;
    private final ResPropMapper props;
    private final ResKeyframeMapper keyframes;
    private final McpScope scope;
    private final McpJson json;
    private final McpBusinessView views;
    private final McpQueryMapper queries;

    public record Entry(AssetType type, Long id, Long dramaId, Long characterId, Long shotId) {}

    public Entry resolve(McpPrincipal caller, AssetType type, String rawId, String project, boolean write) {
        Long id = McpScope.id(rawId);
        Entry entry = switch (type) {
            case CHARACTER -> { ResCharacter e = characters.selectById(id); yield e == null ? null : new Entry(type, id, e.getDramaId(), id, null); }
            case CHARACTER_LOOK -> {
                ResCharacterLook e = looks.selectById(id);
                ResCharacter c = e == null ? null : characters.selectById(e.getCharacterId());
                yield c == null ? null : new Entry(type, id, c.getDramaId(), c.getId(), null);
            }
            case SCENE -> { ResScene e = scenes.selectById(id); yield e == null ? null : new Entry(type, id, e.getDramaId(), null, null); }
            case PROP -> { ResProp e = props.selectById(id); yield e == null ? null : new Entry(type, id, e.getDramaId(), null, null); }
            case KEYFRAME -> { ResKeyframe e = keyframes.selectById(id); yield e == null ? null : new Entry(type, id, e.getDramaId(), null, e.getShotId()); }
        };
        if (entry == null) throw new BizException(404, "资产不存在");
        if (Long.valueOf(0L).equals(entry.dramaId())) entry = new Entry(entry.type(), entry.id(), null, entry.characterId(), entry.shotId());
        if (project != null) scope.project(caller, project);
        if (entry.dramaId() == null) {
            if (write) throw new BizException(403, "公共库通过 MCP 只读");
            if (entry.shotId() != null) throw new BizException(409, "公共资产具有不一致的分镜绑定");
        } else {
            Long allowed = scope.project(caller, entry.dramaId().toString());
            if (project != null) scope.belongs(allowed, McpScope.id(project));
            if (entry.shotId() != null) scope.shot(entry.shotId(), allowed);
        }
        return entry;
    }

    public Object detail(McpPrincipal caller, McpInputs.Asset input) {
        Entry e = resolve(caller, input.type(), input.recordId(), input.dramaId(), false);
        return views.project(McpBusinessView.kind(e.type()), raw(e.type(), e.id()), input.fields());
    }

    public Object search(McpPrincipal caller, McpInputs.Assets input) {
        if (input.type() == null) throw new BizException(400, "必须指定资产类型");
        Long project = Boolean.TRUE.equals(input.publicOnly()) ? null : scope.project(caller, input.dramaId());
        if (Boolean.TRUE.equals(input.publicOnly()) && input.dramaId() != null)
            throw new BizException(400, "公共库查询请省略 dramaId");
        QueryWrapper<BaseEntity> query = new QueryWrapper<>();
        if (input.type() == AssetType.CHARACTER_LOOK) {
            Entry character = resolve(caller, AssetType.CHARACTER, input.characterId(), input.dramaId(), false);
            if (!Objects.equals(character.dramaId(), project)) throw new BizException(403, "造型查询范围与人物不一致");
            query.eq("character_id", character.id());
        } else {
            if (project == null) query.and(q -> q.isNull("drama_id").or().eq("drama_id", 0)); else query.eq("drama_id", project);
            if (input.characterId() != null) throw new BizException(400, "仅造型查询接受 characterId");
        }
        String column = input.type() == AssetType.CHARACTER_LOOK ? "look_name" : "name";
        if (input.keyword() != null && !input.keyword().isBlank()) {
            if (input.keyword().length() > 200) throw new BizException(400, "关键词过长");
            query.like(column, input.keyword());
        }
        query.orderByAsc("sort_order").orderByAsc("id");
        if (input.type() == AssetType.KEYFRAME) {
            Page<ResKeyframe> page = queries.keyframes(new Page<>(McpScope.page(input.page()), McpScope.size(input.size())), project, input.keyword());
            return McpReadTools.page(page, page.getRecords().stream().map(e -> views.project(McpBusinessView.Kind.KEYFRAME, e)).toList());
        }
        Page<BaseEntity> page = mapper(input.type()).selectPage(new Page<>(McpScope.page(input.page()), McpScope.size(input.size())), query);
        return McpReadTools.page(page, page.getRecords().stream().map(e -> views.project(McpBusinessView.kind(input.type()), e)).toList());
    }

    @SuppressWarnings("unchecked")
    private BaseMapper<BaseEntity> mapper(AssetType type) {
        BaseMapper<?> mapper = switch (type) {
            case CHARACTER -> characters; case CHARACTER_LOOK -> looks; case SCENE -> scenes; case PROP -> props; case KEYFRAME -> keyframes;
        };
        return (BaseMapper<BaseEntity>) mapper;
    }
    public BaseEntity raw(AssetType type, Long id) { return mapper(type).selectById(id); }
    public BaseMapper<BaseEntity> businessMapper(AssetType type) { return mapper(type); }
}
