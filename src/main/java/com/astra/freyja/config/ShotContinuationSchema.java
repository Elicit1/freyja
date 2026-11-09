package com.astra.freyja.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.SmartInitializingSingleton;
import javax.sql.DataSource;

/** Additive, idempotent schema upgrade. No historical JSON is rewritten. */
@Component @RequiredArgsConstructor
public class ShotContinuationSchema implements SmartInitializingSingleton {
    private final DataSource dataSource;
    @Override public void afterSingletonsInstantiated(){
        try(var c=dataSource.getConnection()){
            boolean found;try(var columns=c.getMetaData().getColumns(c.getCatalog(),null,"drama_shot","continuation_json")){found=columns.next();}
            try(var sql=c.createStatement()){
                if(!found)sql.executeUpdate("ALTER TABLE drama_shot ADD COLUMN continuation_json JSON NULL");
                sql.executeUpdate("CREATE TABLE IF NOT EXISTS render_task_step (task_id VARCHAR(100) NOT NULL, step_key VARCHAR(100) NOT NULL, input_hash CHAR(64) NOT NULL, execution_id VARCHAR(180) NOT NULL, status VARCHAR(24) NOT NULL, output_url TEXT NULL, error_message TEXT NULL, updated_at DATETIME NOT NULL, PRIMARY KEY(task_id,step_key))");
                sql.executeUpdate("CREATE TABLE IF NOT EXISTS shot_continuation_preparation (id VARCHAR(36) PRIMARY KEY, shot_id BIGINT NOT NULL, plan_hash CHAR(64) NOT NULL, provider_id BIGINT NOT NULL, model_code VARCHAR(200) NOT NULL, size VARCHAR(40) NOT NULL, status VARCHAR(24) NOT NULL, snapshot_json JSON NULL, error_message TEXT NULL, updated_at DATETIME NOT NULL, INDEX idx_continuation_plan(shot_id,plan_hash))");
                boolean progress;try(var columns=c.getMetaData().getColumns(c.getCatalog(),null,"shot_continuation_preparation","progress_message")){progress=columns.next();}
                if(!progress)sql.executeUpdate("ALTER TABLE shot_continuation_preparation ADD COLUMN progress_message VARCHAR(200) NULL");
                for(String column:java.util.List.of("node_id","prompt_id","source_task_id")){
                    boolean exists;try(var columns=c.getMetaData().getColumns(c.getCatalog(),null,"render_task_step",column)){exists=columns.next();}
                    if(!exists)sql.executeUpdate("ALTER TABLE render_task_step ADD COLUMN "+column+" VARCHAR(128) NULL");
                }
            }
        }catch(java.sql.SQLException e){throw new IllegalStateException("接续任务结构升级失败",e);}
    }
}
