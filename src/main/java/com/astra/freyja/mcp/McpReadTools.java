package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.astra.freyja.mcp.McpInputs.*;

@Component
@RequiredArgsConstructor
public class McpReadTools {
    public static final Set<String> CREATION_DICTS = Set.of("drama_genre", "drama_style_preset", "shot_type", "camera_movement",
            "res_scene_type", "res_time_of_day", "res_gender", "res_role_type", "res_prop_type", "res_keyframe_type",
            "drama_aspect_ratio", "drama_status", "episode_status");
    private final DramaMapper dramas;
    private final DramaEpisodeMapper episodes;
    private final DramaSceneMapper scenes;
    private final DramaShotGroupMapper groups;
    private final DictService dictService;
    private final McpQueryMapper queries;
    private final McpAssets assets;
    private final McpScope scope;
    private final McpJson json;
    private final McpBusinessView views;

    public List<McpTool<?>> tools() {
        return List.of(
                McpTool.read("search_projects", "分页查找授权项目。keyword 搜索标题，size 最多 50。业务 ID 全部为字符串。", Search.class, this::searchProjects),
                McpTool.read("get_project", "读取授权项目的业务设定与 editFingerprint。不返回内部 Agent 设置或系统提示词。", Project.class, (c, i) ->
                        views.project(McpBusinessView.Kind.PROJECT, dramas.selectById(scope.project(c, i.dramaId())))),
                McpTool.read("get_project_outline", "分页读取 EPISODE 剧集、SCENE 剧作场次或 SHOT_GROUP 镜头组。场景资产请用 search_assets。", Outline.class, this::outline),
                McpTool.read("list_shots", "分页读取指定项目的有效分镜，可按 episodeId/sceneId 筛选。详情用 get_shot。", Shots.class, this::shots),
                McpTool.read("get_shot", "读取项目内完整分镜配置：剧本、对白、提示词、导演计划、参考图音频、关联资产和 editFingerprint。不执行内部 AI。", Shot.class, (c, i) -> {
                    Long project = scope.project(c, i.dramaId());
                    return views.project(McpBusinessView.Kind.SHOT, scope.shot(McpScope.id(i.shotId()), project));
                }),
                McpTool.read("search_assets", "分页查找 CHARACTER/CHARACTER_LOOK/SCENE/PROP/KEYFRAME。项目库传 dramaId；公共库 publicOnly=true 且省略 dramaId。造型必须传 characterId。", Assets.class, assets::search),
                McpTool.read("get_asset", "按类型和字符串 assetId 读取资产详情；公共库只读，项目资产须在授权范围。", Asset.class, assets::detail),
                McpTool.read("list_character_looks", "分页读取人物造型，获取真实 lookId、默认造型、参考图和一致性状态。", CharacterLooks.class, (c, i) -> {
                    var e = assets.resolve(c, AssetType.CHARACTER, i.characterId(), i.dramaId(), false);
                    return assets.search(c, new Assets(AssetType.CHARACTER_LOOK, e.dramaId() == null ? null : e.dramaId().toString(),
                            e.dramaId() == null, i.characterId(), null, i.page(), i.size()));
                }),
                McpTool.read("get_business_options", "按 dictType 读取业务字典，如题材、画幅、景别、场景类型。AI 系统字典和模型目录不公开。", Options.class, this::options)
        );
    }

    public Object searchProjects(McpPrincipal caller, Search input) {
        int page = McpScope.page(input.page()), size = McpScope.size(input.size());
        if (!caller.allProjects() && !caller.createProjects() && caller.projects().isEmpty()) return page(new Page<>(page, size, 0), List.of());
        if (input.keyword() != null && !input.keyword().isBlank()) {
            if (input.keyword().length() > 200) throw new BizException(400, "关键词过长");
        }
        Page<Drama> result = queries.projects(new Page<>(page, size), caller.allProjects(), caller.createProjects(), caller.projects(), input.keyword());
        return page(result, result.getRecords().stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId()); row.put("title", d.getTitle()); row.put("genre", d.getGenre()); row.put("status", d.getStatus());
            row.put("synopsis", d.getSynopsis()); row.put("aspectRatio", d.getAspectRatio()); return row;
        }).toList());
    }

    public Object outline(McpPrincipal caller, Outline input) {
        Long project = scope.project(caller, input.dramaId());
        Long episode = McpScope.optionalId(input.episodeId()), scene = McpScope.optionalId(input.sceneId());
        if (episode != null) scope.episode(episode, project);
        if (scene != null) {
            DramaScene s = scope.scene(scene, project);
            if (episode != null && !episode.equals(s.getEpisodeId())) throw new BizException(400, "sceneId 与 episodeId 不匹配");
        }
        if (input.level() == null) throw new BizException(400, "必须指定 level");
        int current = McpScope.page(input.page()), size = McpScope.size(input.size());
        return switch (input.level()) {
            case EPISODE -> {
                if (scene != null) throw new BizException(400, "剧集查询不接受 sceneId");
                Page<DramaEpisode> p = episodes.selectPage(new Page<>(current, size), new LambdaQueryWrapper<DramaEpisode>()
                        .eq(DramaEpisode::getDramaId, project).eq(episode != null, DramaEpisode::getId, episode)
                        .orderByAsc(DramaEpisode::getEpisodeNo).orderByAsc(DramaEpisode::getId));
                yield page(p, p.getRecords().stream().map(this::outlineSummary).toList());
            }
            case SCENE -> {
                Page<DramaScene> p = queries.scenes(new Page<>(current, size), project, episode, scene);
                yield page(p, p.getRecords().stream().map(this::outlineSummary).toList());
            }
            case SHOT_GROUP -> {
                Page<DramaShotGroup> p = queries.groups(new Page<>(current, size), project, episode, scene);
                yield page(p, p.getRecords().stream().map(this::outlineSummary).toList());
            }
        };
    }

    public Object shots(McpPrincipal caller, Shots input) {
        Long project = scope.project(caller, input.dramaId());
        Long episode = McpScope.optionalId(input.episodeId()), scene = McpScope.optionalId(input.sceneId());
        if (episode != null) scope.episode(episode, project);
        if (scene != null) {
            var s = scope.scene(scene, project);
            if (episode != null && !Objects.equals(episode, s.getEpisodeId())) throw new BizException(400, "场次与剧集不匹配");
        }
        Page<DramaShot> p = queries.shots(new Page<>(McpScope.page(input.page()), McpScope.size(input.size())), project, episode, scene);
        return page(p, p.getRecords().stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId()); row.put("episodeId", s.getEpisodeId()); row.put("sceneId", s.getSceneId()); row.put("shotGroupId", s.getShotGroupId());
            row.put("shotNo", s.getShotNo()); row.put("shotName", s.getShotName()); row.put("duration", s.getDuration());
            row.put("scriptContent", s.getScriptContent()); row.put("actionDescription", s.getActionDescription());
            row.put("dialogue", s.getDialogue()); return row;
        }).toList());
    }

    public Object options(McpPrincipal caller, Options input) {
        if (!CREATION_DICTS.contains(input.dictType())) throw new BizException(403, "仅允许读取业务字典");
        return dictService.listByType(input.dictType()).stream().filter(d -> Integer.valueOf(1).equals(d.getStatus())).map(d ->
                Map.of("label", d.getDictLabel(), "value", d.getDictValue(), "order", d.getSortOrder() == null ? 0 : d.getSortOrder())).toList();
    }

    public static Map<String, Object> page(Page<?> page, List<?> records) {
        return Map.of("records", records, "page", page.getCurrent(), "size", page.getSize(), "total", page.getTotal(),
                "hasMore", page.getCurrent() < page.getPages());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outlineSummary(BaseEntity entity) {
        Map<String, Object> source = (Map<String, Object>) json.value(entity);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "dramaId", "episodeId", "sceneId", "episodeNo", "sceneNo", "groupNo", "title", "name",
                "summary", "purpose", "resSceneId", "sceneType", "timeOfDay", "locationName", "targetDuration", "actualDuration", "status", "sortOrder"))
            if (source.containsKey(field)) result.put(field, source.get(field));
        return result;
    }
}
