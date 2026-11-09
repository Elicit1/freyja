package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;

/** Heavy media preparation finishes before taking the global video queue DB lock. */
@Service
public class ShotContinuationPreparation {
    private final ShotContinuationService service;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Executor executor;
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    public ShotContinuationPreparation(ShotContinuationService service,JdbcTemplate jdbc,ObjectMapper json,
            @Qualifier("referenceVideoPreparationExecutor") Executor executor, org.springframework.transaction.PlatformTransactionManager transactions){this.service=service;this.jdbc=jdbc;this.json=json;this.executor=executor;this.transactions=transactions;}
    public record State(String id,String status,String planHash,String errorMessage,String progressMessage){}
    public boolean enabled(DramaShot shot){return service.enabled(shot);}
    public ShotControlNetOptions savedControl(DramaShot shot){return service.read(shot).getControlNet();}
    public Map<String,Object> controlOptions(DramaShot shot,AiModel model,AiProvider provider,ShotControlNetOptions control){return service.controlOptions(shot,model,provider,control);}
    public State start(DramaShot shot,AiModel model,AiProvider provider,String size,String expected){
        var plan=service.preview(service.read(shot),false,model,provider);
        if(!plan.isRenderable()||!Objects.equals(plan.getPlanHash(),expected))throw new BizException(409,"请预览有效的最新接续计划");
        var existing=jdbc.query("SELECT id,status,plan_hash,error_message,progress_message FROM shot_continuation_preparation WHERE shot_id=? AND plan_hash=? AND provider_id=? AND model_code=? AND size=? AND status IN ('PREPARING','READY') AND updated_at>? ORDER BY updated_at DESC LIMIT 1",
                (rs,n)->new State(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),shot.getId(),expected,provider.getId(),model.getModelCode(),size,LocalDateTime.now().minusMinutes(30));
        if(!existing.isEmpty())return existing.getFirst();
        String id=UUID.randomUUID().toString();if(jdbc.update("INSERT INTO shot_continuation_preparation(id,shot_id,plan_hash,provider_id,model_code,size,status,updated_at) VALUES (?,?,?,?,?,?,'PREPARING',?)",id,shot.getId(),expected,provider.getId(),model.getModelCode(),size,LocalDateTime.now())!=1)throw new BizException(500,"接续素材准备任务保存失败");
        Runnable dispatch=()->{try{executor.execute(()->{
            try{
                var request=new DramaShotRenderRequestDTO();request.setExpectedRenderFingerprint(expected);
                if(shot.getSeed()==null)shot.setSeed(1L);
                var snapshot=service.prepare(shot,model,provider,size,request,(completed,total)->{
                    String message=completed==total?"素材准备完成":"正在准备片段 "+(completed+1)+" / "+total;
                    if(jdbc.update("UPDATE shot_continuation_preparation SET progress_message=?,updated_at=? WHERE id=? AND status='PREPARING'",message,LocalDateTime.now(),id)!=1)
                        throw new IllegalStateException("接续素材进度保存失败");
                });
                if(jdbc.update("UPDATE shot_continuation_preparation SET status='READY',snapshot_json=?,updated_at=? WHERE id=? AND status='PREPARING'",json.writeValueAsString(snapshot),LocalDateTime.now(),id)!=1)throw new IllegalStateException("接续素材快照写入失败");
            }catch(Exception e){jdbc.update("UPDATE shot_continuation_preparation SET status='FAILED',error_message=?,updated_at=? WHERE id=? AND status='PREPARING'",e.getMessage(),LocalDateTime.now(),id);}
        });}catch(RuntimeException e){jdbc.update("UPDATE shot_continuation_preparation SET status='FAILED',error_message='准备队列繁忙',updated_at=? WHERE id=?",LocalDateTime.now(),id);}};
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.executeWithoutResult(status->dispatch.run());}});else dispatch.run();
        return get(shot.getId(),id);
    }
    public State get(Long shot,String id){var rows=jdbc.query("SELECT id,status,plan_hash,error_message,progress_message FROM shot_continuation_preparation WHERE id=? AND shot_id=?",(rs,n)->new State(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),id,shot);if(rows.isEmpty())throw new BizException(404,"准备任务不存在");return rows.getFirst();}
    public VideoBatchModels.Snapshot prepare(DramaShot shot,AiModel model,AiProvider provider,String size,DramaShotRenderRequestDTO request){
        var plan=service.preview(service.read(shot),false,model,provider);
        if(!plan.isRenderable()||request!=null&&!Objects.equals(request.getExpectedRenderFingerprint(),plan.getPlanHash()))throw new BizException(409,"接续计划已变化，请重新预览");
        var rows=jdbc.query("SELECT snapshot_json FROM shot_continuation_preparation WHERE shot_id=? AND plan_hash=? AND provider_id=? AND model_code=? AND size=? AND status='READY' AND updated_at>? ORDER BY updated_at DESC LIMIT 1",(rs,n)->rs.getString(1),shot.getId(),plan.getPlanHash(),provider.getId(),model.getModelCode(),size,LocalDateTime.now().minusMinutes(30));
        if(rows.isEmpty())throw new BizException(409,"请先在接续编辑器完成素材准备，再提交渲染");
        try{
            var snap=json.readValue(rows.getFirst(),VideoBatchModels.Snapshot.class);
            // Normal seed admission remains authoritative; media preparation never changes a shot seed.
            snap.getShot().setSeed(shot.getSeed());snap.getRequest().setSeed(shot.getSeed());
            for(int i=0;i<snap.getRequest().getContinuationSegments().size();i++)snap.getRequest().getContinuationSegments().get(i).getRequest().setSeed(1+Math.floorMod(shot.getSeed()+i*104729L,9007199254740990L));
            return snap;
        }catch(Exception e){throw new BizException(500,"接续素材快照无法读取");}
    }
}
