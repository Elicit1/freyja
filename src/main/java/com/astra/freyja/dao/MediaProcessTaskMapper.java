package com.astra.freyja.dao;

import com.astra.freyja.entity.MediaProcessTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 视频后处理任务持久化 Mapper。
 */
@Mapper
public interface MediaProcessTaskMapper extends BaseMapper<MediaProcessTask> {
}
