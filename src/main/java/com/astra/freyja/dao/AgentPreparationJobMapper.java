package com.astra.freyja.dao;
import com.astra.freyja.entity.AgentPreparationJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper public interface AgentPreparationJobMapper extends BaseMapper<AgentPreparationJob> {
    @Select("SELECT * FROM agent_preparation_job WHERE run_id = #{runId} AND deleted = 0 FOR UPDATE")
    AgentPreparationJob lockByRunId(@Param("runId") Long runId);
}
