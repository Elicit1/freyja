package com.astra.freyja.service;

import com.astra.freyja.dto.drama.DramaSceneDTO;
import com.astra.freyja.dto.drama.DramaSceneVO;

import java.util.List;

/**
 * 情景场次管理服务接口。
 */
public interface DramaSceneService {

    /**
     * 查询某剧集下的所有场次列表
     */
    List<DramaSceneVO> listByEpisodeId(Long episodeId);

    /**
     * 获取场次详情
     */
    DramaSceneVO getById(Long id);

    /**
     * 创建场次
     */
    Long create(DramaSceneDTO dto);

    /**
     * 更新场次
     */
    void update(DramaSceneDTO dto);

    /**
     * 删除场次
     */
    void delete(Long id);
}
