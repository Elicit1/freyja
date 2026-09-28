package com.astra.freyja.service;

import com.astra.freyja.dto.res.ResKeyframeDTO;
import com.astra.freyja.dto.res.ResKeyframeOptionVO;
import com.astra.freyja.dto.res.ResKeyframeQuery;
import com.astra.freyja.dto.res.ResKeyframeVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 关键帧资产管理服务接口。
 */
public interface ResKeyframeService {

    /**
     * 分页查询关键帧资产列表
     */
    Page<ResKeyframeVO> page(ResKeyframeQuery query);

    /**
     * 获取关键帧资产详情
     */
    ResKeyframeVO getById(Long id);

    /**
     * 新增关键帧资产
     */
    Long create(ResKeyframeDTO dto);

    /**
     * 修改关键帧资产
     */
    void update(ResKeyframeDTO dto);

    /**
     * 删除关键帧资产 (逻辑删除)
     */
    void delete(Long id);

    /**
     * 修改关键帧启用/停用状态
     */
    void changeStatus(Long id, Integer status);

    /**
     * 关键帧下拉选项列表
     */
    List<ResKeyframeOptionVO> options(Long dramaId, Long shotId);
}
