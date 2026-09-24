package com.astra.freyja.service;

import com.astra.freyja.dto.drama.DramaDTO;
import com.astra.freyja.dto.drama.DramaOptionVO;
import com.astra.freyja.dto.drama.DramaQuery;
import com.astra.freyja.dto.drama.DramaStatsVO;
import com.astra.freyja.dto.drama.DramaTreeVO;
import com.astra.freyja.dto.drama.DramaVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 短剧项目管理服务接口。
 */
public interface DramaService {

    /**
     * 分页查询短剧列表
     */
    Page<DramaVO> page(DramaQuery query);

    /**
     * 获取短剧详情 (含统计数据)
     */
    DramaVO getById(Long id);

    /**
     * 创建短剧
     */
    Long create(DramaDTO dto);

    /**
     * 更新短剧
     */
    void update(DramaDTO dto);

    /**
     * 删除短剧
     */
    void delete(Long id);

    /**
     * 获取短剧下拉选项列表
     */
    List<DramaOptionVO> options();

    /**
     * 获取短剧 -> 剧集 -> 场次 -> 分镜 四层完整大纲树
     */
    DramaTreeVO getDramaTree(Long dramaId);

    /**
     * 获取短剧全局生产统计数据
     */
    DramaStatsVO getStats(Long dramaId);
}
