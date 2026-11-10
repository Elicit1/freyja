package com.astra.freyja.service;

import com.astra.freyja.config.DynamicWorkflowSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DynamicWorkflowSchemaTest {
    @Test void upgradesExistingStagesWithoutRewritingResultsAndCanRunTwice() {
        var ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dynamic_workflow_step(task_id VARCHAR(100),step_key VARCHAR(100),output_url TEXT)");
        jdbc.update("INSERT INTO dynamic_workflow_step VALUES ('old','segment-000:01:UPSCALE','archived-video')");
        var schema=new DynamicWorkflowSchema(ds);
        schema.afterSingletonsInstantiated();schema.afterSingletonsInstantiated();
        assertEquals("archived-video",jdbc.queryForObject("SELECT output_url FROM dynamic_workflow_step WHERE task_id='old'",String.class));
        assertNull(jdbc.queryForObject("SELECT result_manifest_json FROM dynamic_workflow_step WHERE task_id='old'",String.class));
    }
}
