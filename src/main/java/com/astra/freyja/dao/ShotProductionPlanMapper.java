package com.astra.freyja.dao;
import com.astra.freyja.entity.ShotProductionPlan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper public interface ShotProductionPlanMapper extends BaseMapper<ShotProductionPlan> {
    @Select("SELECT * FROM shot_production_plan WHERE shot_id = #{shotId} AND deleted = 0 FOR UPDATE")
    ShotProductionPlan lockByShotId(@Param("shotId") Long shotId);
}
