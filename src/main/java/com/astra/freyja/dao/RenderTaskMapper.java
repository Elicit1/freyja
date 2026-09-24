package com.astra.freyja.dao;

import com.astra.freyja.entity.RenderTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 渲染任务与历史归档数据访问接口。
 */
@Mapper
public interface RenderTaskMapper extends BaseMapper<RenderTask> {
}
