package com.astra.freyja.service;

import com.astra.freyja.dto.drama.DramaEpisodeBatchDTO;
import com.astra.freyja.dto.drama.DramaEpisodeDTO;
import com.astra.freyja.dto.drama.DramaEpisodeVO;

import java.util.List;

/**
 * 剧集管理服务接口。
 */
public interface DramaEpisodeService {

    /**
     * 查询某短剧的所有剧集列表
     */
    List<DramaEpisodeVO> listByDramaId(Long dramaId);

    /**
     * 获取剧集详情
     */
    DramaEpisodeVO getById(Long id);

    /**
     * 创建单集
     */
    Long create(DramaEpisodeDTO dto);

    /**
     * 批量创建剧集 (如一次性批量创建 1~20 集)
     */
    List<Long> batchCreate(DramaEpisodeBatchDTO dto);

    /**
     * 更新剧集
     */
    void update(DramaEpisodeDTO dto);

    /**
     * 删除剧集
     */
    void delete(Long id);

    /**
     * 重新计算剧集实际累计分镜时长
     */
    void recalculateEpisodeDuration(Long episodeId);
}
