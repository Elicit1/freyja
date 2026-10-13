package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class McpScope {
    private final DramaMapper dramas;
    private final DramaEpisodeMapper episodes;
    private final DramaSceneMapper scenes;
    private final DramaShotGroupMapper groups;
    private final DramaShotMapper shots;
    @Autowired(required = false)
    private McpBusinessOperationMapper operations;

    public static Long id(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw new BizException(400, "ID 必须是正整数的字符串");
        try { return Long.valueOf(raw); }
        catch (NumberFormatException e) { throw new BizException(400, "ID 超出 64 位整数范围"); }
    }
    public static Long optionalId(String raw) { return raw == null ? null : id(raw); }
    public Long project(McpPrincipal caller, String raw) {
        Long id = authorizeProject(caller, raw);
        if (dramas.selectById(id) == null) throw new BizException(404, "项目不存在");
        return id;
    }
    /** Permission-only check allows an authorized caller to recover a committed project deletion. */
    public Long authorizeProject(McpPrincipal caller, String raw) {
        Long id = id(raw);
        if (!caller.canRead(id) && !(caller.createProjects() && operations != null && operations.createdProjectCount(id) > 0))
            throw new BizException(403, "PROJECT_ACCESS_DENIED: 项目不在 MCP 授权范围");
        return id;
    }
    public void belongs(Long actual, Long project) {
        if (!Objects.equals(actual, project)) throw new BizException(403, "SCOPE_VIOLATION: 关联对象不属于指定项目");
    }
    public DramaEpisode episode(Long id, Long project) {
        DramaEpisode e = episodes.selectById(id);
        if (e == null) throw new BizException(404, "剧集不存在");
        belongs(e.getDramaId(), project); return e;
    }
    public DramaScene scene(Long id, Long project) {
        DramaScene s = scenes.selectById(id);
        if (s == null) throw new BizException(404, "场次不存在");
        belongs(s.getDramaId(), project); episode(s.getEpisodeId(), project); return s;
    }
    public DramaShotGroup group(Long id, Long project) {
        DramaShotGroup g = groups.selectById(id);
        if (g == null) throw new BizException(404, "镜头组不存在");
        belongs(g.getDramaId(), project);
        DramaScene scene = scene(g.getSceneId(), project);
        if (!Objects.equals(scene.getEpisodeId(), g.getEpisodeId())) throw new BizException(409, "镜头组层级关联不一致");
        return g;
    }
    public DramaShot shot(Long id, Long project) {
        DramaShot s = shots.selectById(id);
        if (s == null) throw new BizException(404, "分镜不存在");
        belongs(s.getDramaId(), project);
        DramaScene scene = scene(s.getSceneId(), project);
        if (!Objects.equals(scene.getEpisodeId(), s.getEpisodeId())) throw new BizException(409, "分镜层级关联不一致");
        if (s.getShotGroupId() != null && !Objects.equals(group(s.getShotGroupId(), project).getSceneId(), s.getSceneId()))
            throw new BizException(409, "分镜与镜头组场次不一致");
        return s;
    }
    public static int page(Integer value) {
        if (value != null && (value < 1 || value > 100000)) throw new BizException(400, "page 必须在 1–100000 之间");
        return value == null ? 1 : value;
    }
    public static int size(Integer value) {
        if (value != null && (value < 1 || value > 50)) throw new BizException(400, "size 必须在 1–50 之间");
        return value == null ? 20 : value;
    }
}
