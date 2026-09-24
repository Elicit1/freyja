package com.astra.freyja.service;

import com.astra.freyja.dto.drama.DramaShotGroupDTO;
import com.astra.freyja.dto.drama.DramaShotGroupMergeDTO;
import com.astra.freyja.dto.drama.DramaShotGroupReorderDTO;
import com.astra.freyja.dto.drama.DramaShotGroupSplitDTO;
import com.astra.freyja.dto.drama.DramaShotGroupVO;

import java.util.List;

/**
 * 镜头组管理服务接口。
 */
public interface DramaShotGroupService {

    /**
     * 查询指定场次下的所有镜头组 (含嵌套分镜列表)。
     */
    List<DramaShotGroupVO> listBySceneId(Long sceneId);

    /**
     * 查询指定剧集下的所有镜头组。
     */
    List<DramaShotGroupVO> listByEpisodeId(Long episodeId);

    /**
     * 获取镜头组详情。
     */
    DramaShotGroupVO getById(Long id);

    /**
     * 创建新镜头组。
     */
    Long create(DramaShotGroupDTO dto);

    /**
     * 修改镜头组。
     */
    void update(DramaShotGroupDTO dto);

    /**
     * 删除镜头组。
     */
    void delete(Long id);

    /**
     * 拆分镜头组：从指定分镜开始拆分为新镜头组。
     */
    Long splitGroup(DramaShotGroupSplitDTO dto);

    /**
     * 合并多个镜头组：合并为一个统一的动作单元。
     */
    Long mergeGroups(DramaShotGroupMergeDTO dto);

    /**
     * 组内分镜拖拽重排。
     */
    void reorderShotsInGroup(Long groupId, List<Long> shotIds);

    /**
     * 场次内镜头组重排。
     */
    void reorderGroups(DramaShotGroupReorderDTO dto);
}
