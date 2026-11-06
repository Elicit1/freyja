package com.astra.freyja.service.video;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShotContinuationPreparationTest {
    final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:continuation_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
    final JdbcTransactionManager transactions=new JdbcTransactionManager(jdbc.getDataSource());
    final ShotContinuationService service=mock(ShotContinuationService.class);
    final DramaShot shot=new DramaShot(); final AiModel model=new AiModel(); final AiProvider provider=new AiProvider();
    final List<Runnable> pending=new ArrayList<>();
    @BeforeEach void setup(){
        jdbc.execute("CREATE TABLE shot_continuation_preparation(id VARCHAR(36) PRIMARY KEY,shot_id BIGINT,plan_hash CHAR(64),provider_id BIGINT,model_code VARCHAR(200),size VARCHAR(40),status VARCHAR(24),snapshot_json CLOB,error_message CLOB,updated_at TIMESTAMP)");
        shot.setId(1L);provider.setId(2L);model.setModelCode("h3");
        var draft=new ShotContinuation();when(service.read(shot)).thenReturn(draft);
        var plan=new ShotContinuationPlan();plan.setRenderable(true);plan.setPlanHash("a".repeat(64));
        when(service.preview(draft,false,model,provider)).thenReturn(plan);
    }
    ShotContinuationPreparation preparation(Executor executor){return new ShotContinuationPreparation(service,jdbc,new ObjectMapper(),executor,transactions);}
    ShotContinuationPreparation.State start(ShotContinuationPreparation preparation){return preparation.start(shot,model,provider,"960x544","a".repeat(64));}
    @Test void rollbackNeverDispatchesAndCommitDispatchesExactlyOnce(){
        var preparation=preparation(pending::add);var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{start(preparation);assertTrue(pending.isEmpty());status.setRollbackOnly();});
        assertTrue(pending.isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM shot_continuation_preparation",Integer.class));
        tx.executeWithoutResult(status->{assertEquals("PREPARING",start(preparation).status());assertTrue(pending.isEmpty());});
        assertEquals(1,pending.size());
        start(preparation);assertEquals(1,pending.size());
    }
    @Test void queueRejectionCommitsFailedStateInSeparateTransaction(){
        var preparation=preparation(work->{throw new java.util.concurrent.RejectedExecutionException();});
        new TransactionTemplate(transactions).executeWithoutResult(status->assertEquals("PREPARING",start(preparation).status()));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM shot_continuation_preparation",String.class));
        assertEquals("准备队列繁忙",jdbc.queryForObject("SELECT error_message FROM shot_continuation_preparation",String.class));
    }
}
