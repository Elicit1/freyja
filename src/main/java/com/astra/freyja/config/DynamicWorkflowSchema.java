package com.astra.freyja.config;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class DynamicWorkflowSchema implements SmartInitializingSingleton {
    private final DataSource dataSource;
    @Override public void afterSingletonsInstantiated() {
        try(var c=dataSource.getConnection();var sql=c.createStatement()) {
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS dynamic_workflow_preset (id BIGINT PRIMARY KEY,name VARCHAR(120) NOT NULL,provider_id BIGINT NOT NULL,model_id BIGINT NOT NULL,version INT NOT NULL,status INT NOT NULL,options_json JSON NOT NULL,create_by BIGINT NULL,create_time DATETIME NULL,update_by BIGINT NULL,update_time DATETIME NULL,deleted INT NOT NULL DEFAULT 0,remark VARCHAR(500) NULL)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS dynamic_workflow_version (preset_id BIGINT NOT NULL,version INT NOT NULL,snapshot_json JSON NOT NULL,created_at DATETIME NOT NULL,PRIMARY KEY(preset_id,version))");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS dynamic_workflow_step (task_id VARCHAR(100) NOT NULL,step_key VARCHAR(100) NOT NULL,input_hash CHAR(64) NOT NULL,execution_id VARCHAR(180) NOT NULL,stage VARCHAR(20) NOT NULL,status VARCHAR(24) NOT NULL,output_url TEXT NULL,prompt_id VARCHAR(128) NULL,node_id VARCHAR(128) NULL,error_message TEXT NULL,updated_at DATETIME NOT NULL,PRIMARY KEY(task_id,step_key))");
            try(var columns=c.getMetaData().getColumns(c.getCatalog(),null,"dynamic_workflow_step","source_task_id")){
                if(!columns.next())sql.executeUpdate("ALTER TABLE dynamic_workflow_step ADD COLUMN source_task_id VARCHAR(100) NULL");
            }
        } catch(java.sql.SQLException e) { throw new IllegalStateException("动态工作流结构升级失败",e); }
    }
}
