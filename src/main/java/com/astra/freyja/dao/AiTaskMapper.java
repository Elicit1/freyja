package com.astra.freyja.dao;

import com.astra.freyja.entity.AiTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 流水线任务数据访问接口。
 */
@Mapper
public interface AiTaskMapper extends BaseMapper<AiTask> {
}
