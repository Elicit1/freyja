package com.astra.freyja.dao;

import com.astra.freyja.entity.DramaShotVideoTake;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DramaShotVideoTakeMapper extends BaseMapper<DramaShotVideoTake> {

    /**
     * 查询某分镜下当前最大的 take_no（包含软删除记录以避免唯一索引冲突）
     */
    @Select("SELECT COALESCE(MAX(take_no), 0) FROM drama_shot_video_take WHERE shot_id = #{shotId}")
    Integer selectMaxTakeNoByShotId(@Param("shotId") Long shotId);
}
