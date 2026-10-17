package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.comfy.ComfyRenderTaskVO;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.dto.task.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.entity.enums.ComfyTaskStatus;
import com.astra.freyja.event.TaskStatusChangedEvent;
import com.astra.freyja.service.*;
import com.astra.freyja.service.video.VideoBatchModels.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoRenderBatchService {
    private final VideoBatchStore store;
    private final VideoBatchPreparation preparation;
    private final VideoBatchFrameService frames;
    private final DramaShotMapper shots;
    private final RenderTaskMapper renders;
    private final AiModelMapper models;
    private final AiImageApiService api;
    @Lazy private final RenderTaskService renderTasks;
    private final ShotVideoTakeService takes;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private volatile String localTask;

    public List<Node> tree(Long dramaId){return preparation.tree(dramaId);}
    public Preview preview(VideoRenderBatchRequest req){return preparePreview(req,false);}
    private Preview preparePreview(VideoRenderBatchRequest req,boolean lock) {
        if(req.getRetryOfBatchId()==null)return preparation.preview(req,lock);
        Batch source=detail(req.getRetryOfBatchId());
        if(!Objects.equals(source.getDramaId(),req.getDramaId())||req.getShotIds()==null||req.getShotIds().isEmpty())throw new BizException(400,"重试来源无效");
        Set<Long> wanted=new HashSet<>(req.getShotIds());
        List<Item> original=source.getItems().stream().filter(i->wanted.contains(i.getShotId())&&"FAILED".equals(i.getStatus())&&i.getRecoveryUrl()==null).toList();
        if(original.size()!=wanted.size())throw new BizException(400,"只能重试已确认失败的来源项目");
        if("CURRENT_SHOT".equals(req.getInputSource()))return preparation.preview(req,lock);
        if(!"ORIGINAL_SNAPSHOT".equals(req.getInputSource()))throw new BizException(400,"请选择重试输入来源");
        VideoRenderBatchRequest options;
        try{options=json.readValue(source.getOptionsJson(),VideoRenderBatchRequest.class);}catch(Exception e){throw new IllegalStateException(e);}
        if(!Objects.equals(options.getAspectRatio(),req.getAspectRatio())||!preparation.write(options.getModeConfigs()).equals(preparation.write(req.getModeConfigs())))throw new BizException(400,"原始快照重试必须保留比例和模型配置");
        String requestedSize=req.getSize()==null?null:VideoBatchPreparation.normalizeSize(req.getSize());
        List<Candidate> candidates=new ArrayList<>();int order=0;
        for(Item i:original){Candidate c=new Candidate();c.setShotId(i.getShotId());c.setName(i.getName());c.setExecutionOrder(++order);c.setGenerationMode("REF2VA".equals(i.getPhase())?VideoBatchPreparation.REF:VideoBatchPreparation.FL);c.setStatus("READY");
            try{
                if((lock?shots.lockById(i.getShotId()):shots.selectById(i.getShotId()))==null)throw new BizException(404,"分镜已删除");
                if(store.occupied(i.getShotId()))throw new BizException(409,"分镜已有活动或待核实任务");
                Snapshot snap=json.readValue(StringUtils.defaultIfBlank(i.getResolvedJson(),i.getSnapshotJson()),Snapshot.class);preparation.validateRoute(snap);
                if(requestedSize!=null&&!requestedSize.equals(snap.getRequest().getSize()))throw new BizException(400,"原始快照重试必须保留输出尺寸");
                // Resolved first/last images are inputs in their own right on original-snapshot retry.
                if(StringUtils.isNotBlank(i.getResolvedJson())){snap.setFirstFramePlan(null);snap.setLastFramePlan(null);}
                c.setSnapshot(snap);c.setSize(snap.getRequest().getSize());c.setFirstFrameSource("原始执行快照");c.setLastFrameSource("原始执行快照");
            }catch(Exception e){c.setStatus("BLOCKED");c.setReason(e.getMessage());}
            candidates.add(c);
        }
        Preview p=new Preview();p.setShotIds(original.stream().map(Item::getShotId).toList());p.setItems(candidates);p.setAccepted((int)candidates.stream().filter(c->"READY".equals(c.getStatus())).count());p.setBlocked(candidates.size()-p.getAccepted());
        p.setPreviewHash(VideoBatchPreparation.hash(requestHash(req)+preparation.write(candidates)+preparation.write(candidates.stream().map(c->c.getSnapshot()==null?"":preparation.write(c.getSnapshot())).toList())));return p;
    }
    public Batch create(VideoRenderBatchRequest req) {
        if(req.getClientRequestId()==null||!req.getClientRequestId().matches("[A-Za-z0-9_-]{8,80}")) throw new BizException(400,"缺少有效的幂等请求标识");
        String hash=requestHash(req);
        Long id=tx.execute(s->{
            store.lockQueue(); Batch old=store.byRequest(req.getClientRequestId());
            if(old!=null){if(!old.getRequestHash().equals(hash))throw new BizException(409,"同一请求标识的内容不一致");return old.getId();}
            Preview p=preparePreview(req,true);
            if(!Objects.equals(req.getExpectedPreviewHash(),p.getPreviewHash()))throw new BizException(409,"预检结果已变化，请重新检查后提交");
            if(p.getAccepted()==0)throw new BizException(400,"没有可提交的分镜");
            Batch b=new Batch();b.setId(IdWorker.getId());b.setDramaId(req.getDramaId());b.setName(StringUtils.abbreviate(StringUtils.defaultIfBlank(req.getName(),"批量视频渲染"),200));
            b.setRequestId(req.getClientRequestId());b.setRequestHash(hash);b.setOptionsJson(preparation.write(req));b.setCreatedAt(LocalDateTime.now());store.insert(b);
            Map<Long,String> tasks=new HashMap<>();for(var c:p.getItems()) if("READY".equals(c.getStatus()))tasks.put(c.getShotId(),"RENDER_"+IdWorker.getId());
            for(var c:p.getItems()) {
                Item i=new Item();i.setId(IdWorker.getId());i.setBatchId(b.getId());i.setQueueOrder(b.getId());i.setShotId(c.getShotId());i.setName(c.getName());i.setPhase(VideoBatchPreparation.REF.equals(c.getGenerationMode())?"REF2VA":"FL2VA");
                i.setExecutionOrder(c.getExecutionOrder());i.setCreatedAt(b.getCreatedAt());i.setTaskId(tasks.getOrDefault(c.getShotId(),"SKIP_"+i.getId()));
                i.setStatus("READY".equals(c.getStatus())?"QUEUED":"SKIPPED");i.setErrorMessage(c.getReason());
                if(c.getSnapshot()!=null){
                    if(req.getRetryOfBatchId()==null||"CURRENT_SHOT".equals(req.getInputSource()))for(FramePlan plan:Arrays.asList(c.getSnapshot().getFirstFramePlan(),c.getSnapshot().getLastFramePlan())) if(plan!=null&&"PRIOR_BATCH_RESULT".equals(plan.getType()))plan.setSourceTaskId(tasks.get(plan.getSourceShotId()));
                    c.getSnapshot().getRequest().setTaskId(i.getTaskId());i.setSnapshotJson(preparation.write(c.getSnapshot()));register(i,c.getSnapshot());
                }else i.setSnapshotJson("{}");
                store.insert(i);
            }
            return b.getId();
        });
        publish(id);queueChanged();return detail(id);
    }
    private String requestHash(VideoRenderBatchRequest r) {
        return VideoBatchPreparation.hash(preparation.write(Arrays.asList(r.getDramaId(),r.getName(),r.getShotIds()==null?null:r.getShotIds().stream().sorted().toList(),r.getSelection(),r.getAspectRatio(),r.getSize(),r.getExistingVideoPolicy(),r.getModeConfigs()==null?Map.of():new TreeMap<>(r.getModeConfigs()),r.getRetryOfBatchId(),r.getInputSource())));
    }
    public ComfyRenderTaskVO enqueueSingle(Long id,DramaShotRenderRequestDTO request) {
        String task=tx.execute(s->{
            store.lockQueue();DramaShot shot=shots.lockById(id);if(shot==null)throw new BizException(404,"分镜不存在");
            if(store.occupied(id)||renders.selectCount(new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getShotId,id).eq(RenderTask::getTaskType,"SHOT_VIDEO").in(RenderTask::getStatus,"QUEUED","RENDERING"))>0)throw new BizException(409,"分镜已有排队、渲染中或待核实任务");
            DramaShotRenderRequestDTO req=request==null?new DramaShotRenderRequestDTO():request;
            if(shot.getSeed()==null||shot.getSeed()<=0||shot.getSeed()>com.astra.freyja.util.ShotSeedUtil.MAX_SEED){shot.setSeed(com.astra.freyja.util.ShotSeedUtil.next());shots.update(null,new UpdateWrapper<DramaShot>().eq("id",id).set("seed",shot.getSeed()));}
            AiProvider provider=api.resolveProvider(req.getProviderId());String code=api.resolveVideoModelCode(req,provider.getId(),shot.getGenerationMode());
            AiModel model=models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,provider.getId()).eq(AiModel::getModelCode,code).last("LIMIT 1"));
            if(model==null)throw new BizException(400,"视频模型不存在");
            var cfg=new VideoRenderBatchRequest.ModeConfig();cfg.setProviderId(provider.getId());cfg.setModelId(model.getId());
            Snapshot snap=preparation.prepare(shot,cfg,StringUtils.defaultIfBlank(req.getSize(),"544x960"),null,req);
            if(StringUtils.isNotBlank(req.getPrompt()))shots.update(null,new UpdateWrapper<DramaShot>().eq("id",id).set(VideoBatchPreparation.REF.equals(shot.getGenerationMode())?"prompt":"video_prompt",snap.getRequest().getPrompt()));
            // Single renders use the same fallback without creating implicit generation dependencies.
            if(VideoBatchPreparation.FL.equals(shot.getGenerationMode())&&StringUtils.isBlank(shot.getPreviewImageUrl())&&shot.getShotGroupId()!=null){
                var siblings=shots.selectList(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getShotGroupId,shot.getShotGroupId())).stream().sorted(VideoBatchPreparation.shotOrder()).toList();
                for(int n=1;n<siblings.size();n++)if(siblings.get(n).getId().equals(id)&&StringUtils.isNotBlank(siblings.get(n-1).getVideoUrl())){
                    var prev=siblings.get(n-1);FramePlan plan=new FramePlan();plan.setType("EXISTING_VIDEO");plan.setFrameType("LAST");plan.setSourceShotId(prev.getId());plan.setVideoUrl(prev.getVideoUrl());plan.setTakeId(prev.getCurrentVideoTakeId());snap.setFirstFramePlan(plan);
                }
            }
            Batch b=new Batch();b.setId(IdWorker.getId());b.setDramaId(shot.getDramaId());b.setName("单镜视频 · S"+shot.getShotNo());b.setRequestId("single_"+b.getId());b.setRequestHash(VideoBatchPreparation.hash(preparation.write(snap)));b.setCreatedAt(LocalDateTime.now());
            var options=new VideoRenderBatchRequest();options.setDramaId(shot.getDramaId());options.setShotIds(List.of(id));options.setExistingVideoPolicy("CREATE_NEW_TAKE");options.setModeConfigs(Map.of(shot.getGenerationMode(),cfg));
            options.setSize(snap.getRequest().getSize());options.setAspectRatio(VideoBatchPreparation.SIZES.entrySet().stream().filter(e->e.getValue().equals(snap.getRequest().getSize())).map(Map.Entry::getKey).findFirst().orElse(null));b.setOptionsJson(preparation.write(options));store.insert(b);
            Item i=new Item();i.setId(IdWorker.getId());i.setBatchId(b.getId());i.setQueueOrder(b.getId());i.setShotId(id);i.setTaskId("RENDER_"+IdWorker.getId());i.setName("S"+shot.getShotNo()+" · "+StringUtils.defaultString(shot.getShotName(),"分镜"));
            i.setPhase(VideoBatchPreparation.REF.equals(shot.getGenerationMode())?"REF2VA":"FL2VA");i.setStatus("QUEUED");i.setCreatedAt(LocalDateTime.now());snap.getRequest().setTaskId(i.getTaskId());i.setSnapshotJson(preparation.write(snap));register(i,snap);store.insert(i);return i.getTaskId();
        });
        Item queued=store.task(task);publish(queued.getBatchId());queueChanged();var vo=new ComfyRenderTaskVO();vo.setTaskId(task);vo.setShotId(id);vo.setProjectId(detail(queued.getBatchId()).getDramaId());vo.setStatus(ComfyTaskStatus.PENDING);vo.setProgress(0);return vo;
    }
    private void register(Item i,Snapshot snap) {
        store.holdShot(i.getShotId(),i.getTaskId());
        shots.update(null,new UpdateWrapper<DramaShot>().eq("id",i.getShotId()).set("latest_task_id",i.getTaskId()).set("render_status","QUEUED"));
        renderTasks.createTask(RenderTaskVO.builder().taskId(i.getTaskId()).taskType("SHOT_VIDEO").taskName(i.getName()+" 视频渲染")
                .dramaId(snap.getShot().getDramaId()).episodeId(snap.getShot().getEpisodeId()).sceneId(snap.getShot().getSceneId()).shotGroupId(snap.getShot().getShotGroupId()).shotId(i.getShotId())
                .providerId(snap.getRequest().getProviderId()).modelCode(snap.getRequest().getWorkflowTemplateId()).prompt(snap.getRequest().getPrompt()).negativePrompt(snap.getShot().getNegativePrompt()).status("QUEUED").progress(0).currentNode("等待全局串行渲染队列").build());
    }
    public boolean owns(String taskId){return store.task(taskId)!=null;}
    public Long batchForTask(String taskId){Item i=store.task(taskId);return i==null?null:i.getBatchId();}
    public List<Batch> list(boolean active,int limit){return store.batches(active,Math.max(1,Math.min(200,limit))).stream().map(b->detail(b.getId())).toList();}
    public Batch detail(Long id) {
        Batch b=store.batch(id);if(b==null)throw new BizException(404,"批次不存在");List<Item> items=store.items(id);b.setItems(items);
        try{b.setOptions(json.readValue(b.getOptionsJson(),VideoRenderBatchRequest.class));}catch(Exception e){throw new IllegalStateException("批次配置快照不可读",e);}
        for(Item i:items){
            if("SKIPPED".equals(i.getStatus())){b.setSkipped(b.getSkipped()+1);continue;}
            b.setTotal(b.getTotal()+1);boolean done=VideoBatchStore.terminal(i.getStatus());if(done)b.setProcessed(b.getProcessed()+1);
            if("REF2VA".equals(i.getPhase())){b.setRefTotal(b.getRefTotal()+1);if(done)b.setRefProcessed(b.getRefProcessed()+1);}else{b.setFlTotal(b.getFlTotal()+1);if(done)b.setFlProcessed(b.getFlProcessed()+1);}
            switch(i.getStatus()){case "SUCCESS"->b.setSucceeded(b.getSucceeded()+1);case "FAILED"->b.setFailed(b.getFailed()+1);case "CANCELLED"->b.setCancelled(b.getCancelled()+1);default->{}}
            if("UNCONFIRMED".equals(i.getUpstreamStatus())||"FAILED".equals(i.getUpstreamStatus()))b.setUpstreamStatus(i.getUpstreamStatus());
            if(!done&&b.getCurrentShot()==null){b.setCurrentShot(i.getName());b.setCurrentPhase(i.getPhase());}
        }
        if(b.getCurrentPhase()==null)b.setCurrentPhase("COMPLETED");b.setProgress(b.getTotal()==0?100:(int)(100L*b.getProcessed()/b.getTotal()));return b;
    }
    public TaskCenterItemVO summary(Long id){Batch b=detail(id);return TaskCenterItemVO.builder().sourceType("VIDEO_RENDER_BATCH").taskId(String.valueOf(id)).category("RENDER").taskType("VIDEO_RENDER_BATCH").title(b.getName()).status(b.getStatus()).progress(b.getProgress()).revision(b.getRevision())
            .currentStage(b.getCurrentPhase()+" · 已处理 "+b.getProcessed()+"/"+b.getTotal()+" · 成功 "+b.getSucceeded()+" / 失败 "+b.getFailed()+" / 取消 "+b.getCancelled()+(b.getCurrentShot()==null?"":" · "+b.getCurrentShot()))
            .dramaId(String.valueOf(b.getDramaId())).resumeAction("VIDEO_RENDER_BATCH").createdAt(b.getCreatedAt()).finishedAt(b.getFinishedAt()).errorMessage(b.getUpstreamStatus()==null?null:"上游停止待核实").build();}

    /** Called only by the dedicated single consumer; database slot covers all application instances. */
    public boolean runNext() {
        java.util.concurrent.atomic.AtomicReference<Long> recoveredBatch=new java.util.concurrent.atomic.AtomicReference<>();
        Item item=tx.execute(s->{store.lockQueue();String current=store.currentTask();
            if(current!=null){if(!current.equals(localTask)){Item orphan=store.task(current);if(orphan!=null&&!VideoBatchStore.terminal(orphan.getStatus())&&!"NEEDS_REVIEW".equals(orphan.getStatus())){store.status(current,"NEEDS_REVIEW","工作实例中断，上游结果待核实",null,"UNCONFIRMED");renderTasks.failTask(current,"工作实例中断，上游结果待核实",null);recompute(orphan.getBatchId());recoveredBatch.set(orphan.getBatchId());}}return null;}
            Item next=store.next();if(next==null)return null;store.claim(next.getTaskId());store.status(next.getTaskId(),"DISPATCHING",null,null,null);localTask=next.getTaskId();return next;});
        if(item==null){publish(recoveredBatch.get());return false;}boolean submitted=false;
        RenderTaskThreadRegistry.register(item.getTaskId(),Thread.currentThread());
        VideoExecutionState.begin(item.getTaskId());
        try {
            if(cancelled(item))return true;
            Snapshot snap=json.readValue(StringUtils.defaultIfBlank(item.getResolvedJson(),item.getSnapshotJson()),Snapshot.class);
            if(item.getRecoveryUrl()==null)preparation.validateRoute(snap);
            if(item.getRecoveryUrl()==null&&StringUtils.isBlank(item.getResolvedJson())){
                applyFrame(snap,item,true);applyFrame(snap,item,false);
                tx.executeWithoutResult(s->{store.lockQueue();if(!cancelled(item))store.resolved(item.getTaskId(),preparation.write(snap));});
            }
            Boolean maySubmit=tx.execute(s->{store.lockQueue();if(cancelled(item))return false;store.status(item.getTaskId(),"RENDERING",null,null,null);if(item.getBatchId()!=null)store.batchStatus(item.getBatchId(),"RUNNING",false);return true;});
            if(!Boolean.TRUE.equals(maySubmit))return true;
            renderTasks.updateProgress(item.getTaskId(),0,"串行渲染中，等待视频生成与归档");shots.markRenderingIfLatest(item.getShotId(),item.getTaskId());publish(item.getBatchId());
            if(cancelled(item)||Thread.currentThread().isInterrupted())return true;
            submitted=true;
            VideoGenerationResultVO result;
            if(item.getRecoveryUrl()!=null){
                VideoExecutionState.completed(item.getRecoveryUrl());
                result="RESULT_COMMIT".equals(item.getFailurePhase())?VideoGenerationResultVO.builder().videoUrl(item.getRecoveryUrl()).build():api.archiveExistingVideo(item.getShotId(),snap.getShot().getDramaId(),item.getRecoveryUrl());
            }else result=api.generateAndArchiveVideo(item.getShotId(),snap.getShot().getDramaId(),snap.getShot(),snap.getRequest());
            VideoExecutionState.completed(result.getVideoUrl());VideoExecutionState.phase("RESULT_COMMIT");
            tx.executeWithoutResult(s->{store.lockQueue();if(cancelled(item))return;
                RenderTask render=renders.selectOne(new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId,item.getTaskId()));
                takes.recordGeneratedTake(snap.getShot(),render,snap.getRequest(),result);renderTasks.finishTask(item.getTaskId(),result.getVideoUrl(),result.getLastFrameUrl());
                store.status(item.getTaskId(),"SUCCESS",null,result.getVideoUrl(),"CONFIRMED");store.releaseShot(item.getTaskId());store.release(item.getTaskId());recompute(item.getBatchId());});
        }catch(Exception e){
            boolean uncertain=submitted&&VideoExecutionState.current().uncertain();
            String failurePhase=VideoExecutionState.current().phase;
            String recoveryUrl=VideoExecutionState.current().recoveryUrl;
            tx.executeWithoutResult(s->{store.lockQueue();if(cancelled(item))return;
                String error=uncertain?"上游结果待核实，队列暂停："+e.getMessage():e.getMessage();
                store.status(item.getTaskId(),uncertain?"NEEDS_REVIEW":"FAILED",error,null,uncertain?"UNCONFIRMED":"CONFIRMED");renderTasks.failTask(item.getTaskId(),error,null);
                store.recovery(item.getTaskId(),failurePhase,recoveryUrl);
                shots.update(null,new UpdateWrapper<DramaShot>().eq("id",item.getShotId()).eq("latest_task_id",item.getTaskId()).set("render_status","FAILED"));
                if(!uncertain){store.release(item.getTaskId());store.releaseShot(item.getTaskId());}recompute(item.getBatchId());});
            log.warn("视频串行任务结束: task={}, uncertain={}, error={}",item.getTaskId(),uncertain,e.getMessage());
        }finally{
            RenderTaskThreadRegistry.unregister(item.getTaskId());VideoExecutionState.clear(item.getTaskId());localTask=null;Thread.interrupted();publish(item.getBatchId());
        }
        return true;
    }
    private boolean cancelled(Item item){Item fresh=store.task(item.getTaskId());return fresh==null||"CANCELLED".equals(fresh.getStatus())||(item.getBatchId()!=null&&store.batch(item.getBatchId()).isCancelRequested());}
    private void applyFrame(Snapshot snap,Item item,boolean first){
        FramePlan plan=first?snap.getFirstFramePlan():snap.getLastFramePlan();if(plan==null||"CONFIGURED".equals(plan.getType()))return;
        String url=plan.getVideoUrl();Long takeId=plan.getTakeId();
        if("PRIOR_BATCH_RESULT".equals(plan.getType())){Item source=store.task(plan.getSourceTaskId());if(source==null||!VideoBatchStore.terminal(source.getStatus()))throw new BizException(409,"来源视频尚未结束");if("SUCCESS".equals(source.getStatus())){url=source.getOutputUrl();takeId=null;}}
        String image=null;if(!"NONE".equals(plan.getType())&&StringUtils.isNotBlank(url))image=frames.extract(plan.getSourceShotId(),url,takeId,plan.getFrameType());
        plan.setVideoUrl(url);plan.setTakeId(takeId);if(first)snap.getShot().setPreviewImageUrl(image);else snap.getShot().setEndFrameImageUrl(image);
    }
    private void recompute(Long id){if(id==null)return;Batch b=detail(id);String status;
        if(b.isCancelRequested())status="CANCELLED";
        else if(b.getItems().stream().anyMatch(i->"NEEDS_REVIEW".equals(i.getStatus())))status="NEEDS_REVIEW";
        else if(b.getProcessed()==b.getTotal())status=b.getFailed()==0?"SUCCESS":b.getSucceeded()>0?"PARTIAL_FAILED":"FAILED";
        else status="RUNNING";
        store.batchStatus(id,status,Set.of("SUCCESS","PARTIAL_FAILED","FAILED","CANCELLED").contains(status));
    }
    public TaskCancelResultVO cancel(Long batchId){
        List<Item> affected=tx.execute(s->{store.lockQueue();Batch b=detail(batchId);if(b.isCancelRequested())return cancelLocal(b.getItems());if(b.getProcessed()==b.getTotal())return List.<Item>of();store.requestCancel(batchId);return cancelLocal(b.getItems());});
        return finishCancellation(affected,batchId);
    }
    public TaskCancelResultVO cancelByTask(String taskId){Item i=store.task(taskId);if(i==null)throw new BizException(404,"视频任务不存在");if(i.getBatchId()!=null)return cancel(i.getBatchId());
        List<Item> affected=tx.execute(s->{store.lockQueue();return cancelLocal(List.of(store.task(taskId)));});return finishCancellation(affected,null);}
    private List<Item> cancelLocal(List<Item> items){List<Item> affected=new ArrayList<>();for(Item i:items)if(!VideoBatchStore.terminal(i.getStatus())){
        String upstream=Set.of("RENDERING","NEEDS_REVIEW").contains(i.getStatus())?"UNCONFIRMED":"CONFIRMED";
        var execution=VideoExecutionState.task(i.getTaskId());if(execution!=null&&Set.of("ARCHIVE","RESULT_COMMIT").contains(execution.phase))upstream="CONFIRMED";
        store.status(i.getTaskId(),"CANCELLED","用户取消整个任务",null,upstream);
        renders.update(null,new UpdateWrapper<RenderTask>().eq("task_id",i.getTaskId()).in("status","QUEUED","RENDERING","FAILED").set("status","CANCELLED").set("finish_time",LocalDateTime.now()).set("cancel_upstream_status",upstream));
        shots.update(null,new UpdateWrapper<DramaShot>().eq("id",i.getShotId()).eq("latest_task_id",i.getTaskId()).set("render_status","INIT"));
        i.setUpstreamStatus(upstream);affected.add(i);if("CONFIRMED".equals(upstream)){store.release(i.getTaskId());store.releaseShot(i.getTaskId());}
    }else if("CANCELLED".equals(i.getStatus())&&"UNCONFIRMED".equals(i.getUpstreamStatus()))affected.add(i);return affected;}
    private TaskCancelResultVO finishCancellation(List<Item> affected,Long batchId){String upstream="CONFIRMED";
        for(Item i:affected){
            if("UNCONFIRMED".equals(i.getUpstreamStatus())){
                String state="UNCONFIRMED";
                try{var snap=json.readValue(i.getSnapshotJson(),Snapshot.class);var remote=api.cancelRemoteTask(snap.getRequest().getProviderId(),i.getTaskId(),i.getShotId());if(remote!=null&&remote.isSuccess())state="CONFIRMED";}catch(Exception e){log.warn("取消上游视频失败: {}",i.getTaskId());}
                String finalState=state;tx.executeWithoutResult(s->{store.lockQueue();renders.update(null,new UpdateWrapper<RenderTask>().eq("task_id",i.getTaskId()).set("cancel_upstream_status",finalState));
                    // Item is already terminal; update cancellation evidence separately.
                    store.cancelEvidence(i.getTaskId(),finalState);if("CONFIRMED".equals(finalState)){store.release(i.getTaskId());store.releaseShot(i.getTaskId());}});
                if(!"CONFIRMED".equals(state))upstream="UNCONFIRMED";
            }
            RenderTaskThreadRegistry.interrupt(i.getTaskId());renderTasks.refreshTaskFromDatabase(i.getTaskId());
        }
        tx.executeWithoutResult(s->{store.lockQueue();recompute(batchId);});publish(batchId);queueChanged();
        return TaskCancelResultVO.builder().cancelled(true).status("CANCELLED").upstreamStatus(upstream).message("CONFIRMED".equals(upstream)?"已取消；成功视频保留，未执行项不再渲染":"本地已中断；上游停止待核实，后续视频派发已暂停").build();
    }
    private void publish(Long id){if(id!=null)events.publishEvent(new TaskStatusChangedEvent("VIDEO_RENDER_BATCH",String.valueOf(id),"UPDATED"));}
    private void queueChanged(){events.publishEvent(new com.astra.freyja.event.RenderQueueChangedEvent());}

    /** Explicit manual recovery; evidence is retained, never inferred from an expired local timeout. */
    public void reconcile(Long batchId,String taskId,String evidence) {
        if(StringUtils.isBlank(evidence)||evidence.length()>2000)throw new BizException(400,"请提供不超过 2000 字的上游停止核实依据");
        tx.executeWithoutResult(s->{store.lockQueue();Item i=store.task(taskId);
            if(i==null||!Objects.equals(i.getBatchId(),batchId)||!Set.of("NEEDS_REVIEW","CANCELLED").contains(i.getStatus())||!"UNCONFIRMED".equals(i.getUpstreamStatus()))throw new BizException(409,"项目不是待核实状态");
            if(taskId.equals(localTask))throw new BizException(409,"本地任务尚未退出，请稍后核实");
            store.status(taskId,"FAILED","已确认上游停止："+evidence,null,"CONFIRMED");store.cancelEvidence(taskId,"CONFIRMED");
            store.recordEvidence(taskId,evidence);store.release(taskId);store.releaseShot(taskId);renderTasks.failTask(taskId,"人工核实上游已停止："+evidence,null);recompute(batchId);
        });publish(batchId);queueChanged();
    }

    public void retryArchive(Long batchId,String taskId){
        tx.executeWithoutResult(s->{store.lockQueue();Batch b=detail(batchId);Item i=store.task(taskId);
            if(b.isCancelRequested()||i==null||!Objects.equals(i.getBatchId(),batchId)||!"FAILED".equals(i.getStatus())||i.getRecoveryUrl()==null)throw new BizException(409,"该项目不可恢复归档");
            if(store.occupied(i.getShotId()))throw new BizException(409,"分镜已有其他活动任务");
            store.holdShot(i.getShotId(),taskId);store.retryArchive(taskId);store.batchStatus(batchId,"QUEUED",false);
            renders.update(null,new UpdateWrapper<RenderTask>().eq("task_id",taskId).eq("status","FAILED").set("status","QUEUED").set("finish_time",null));
        });renderTasks.refreshTaskFromDatabase(taskId);publish(batchId);queueChanged();
    }

    public record RecoveryResult(String status, String message) {}

    /** Query outside the DB lock; atomically recheck cancellation/ownership before enqueuing archive. */
    public RecoveryResult recoverResult(Long batchId, String taskId) {
        Item original = store.task(taskId);
        checkRecoveryTarget(batchId, original);
        if ("SUCCESS".equals(original.getStatus())) return new RecoveryResult("SUCCESS", "视频已取回，无需重复操作");
        if (Set.of("QUEUED","DISPATCHING","RENDERING").contains(original.getStatus()) && original.getRecoveryUrl()!=null)
            return new RecoveryResult("QUEUED", "已加入结果归档队列");
        if (!"NEEDS_REVIEW".equals(original.getStatus())) throw new BizException(409,"该任务不处于待核实状态");
        Snapshot snapshot;
        try { snapshot=json.readValue(StringUtils.defaultIfBlank(original.getResolvedJson(),original.getSnapshotJson()),Snapshot.class); }
        catch(Exception e){throw new BizException(500,"无法读取原任务快照");}
        var remote=api.queryVideoTask(snapshot.getRequest().getProviderId(),taskId);
        if(remote==null)throw new BizException(502,"未获取到上游任务状态");
        if(!Set.of("SUCCESS","FAILED","CANCELLED").contains(remote.status()))
            return new RecoveryResult(remote.status(),Set.of("RUNNING","QUEUED").contains(remote.status())
                    ?"原任务仍在上游执行或排队，请稍后再次查询":"上游状态仍不明确，队列继续暂停");
        if("SUCCESS".equals(remote.status())&&StringUtils.isBlank(remote.videoUrl()))
            throw new BizException(502,"上游未返回已完成的视频地址");
        RecoveryResult result=tx.execute(s->{
            store.lockQueue();Item current=store.task(taskId);checkRecoveryTarget(batchId,current);
            if("SUCCESS".equals(current.getStatus()))return new RecoveryResult("SUCCESS","视频已取回");
            if(Set.of("QUEUED","DISPATCHING","RENDERING").contains(current.getStatus())&&current.getRecoveryUrl()!=null)return new RecoveryResult("QUEUED","已加入结果归档队列");
            if(!"NEEDS_REVIEW".equals(current.getStatus())||!taskId.equals(store.currentTask())||taskId.equals(localTask))
                throw new BizException(409,"任务执行状态已变化，请刷新后重试");
            if("SUCCESS".equals(remote.status())){
                store.recoverCompleted(taskId,remote.videoUrl());
                renders.update(null,new UpdateWrapper<RenderTask>().eq("task_id",taskId).eq("status","FAILED")
                        .set("status","QUEUED").set("error_message",null).set("error_detail",null).set("finish_time",null));
                store.release(taskId);store.batchStatus(batchId,"QUEUED",false);
                return new RecoveryResult("QUEUED","已找到完成的视频，正在取回归档；完成后继续批次");
            }
            store.status(taskId,"FAILED","上游已明确结束："+remote.status(),null,"CONFIRMED");
            renders.update(null,new UpdateWrapper<RenderTask>().eq("task_id",taskId).eq("status","FAILED")
                    .set("error_message","上游已明确结束："+remote.status()));
            store.release(taskId);store.releaseShot(taskId);recompute(batchId);
            return new RecoveryResult("FAILED","已确认原任务失败或中断，后续分镜继续执行");
        });
        renderTasks.refreshTaskFromDatabase(taskId);publish(batchId);queueChanged();return result;
    }

    private void checkRecoveryTarget(Long batchId, Item item) {
        if(item==null||!Objects.equals(item.getBatchId(),batchId))throw new BizException(404,"批次任务不存在");
        if(detail(batchId).isCancelRequested()||"CANCELLED".equals(item.getStatus()))
            throw new BizException(409,"已取消的批次不能恢复执行");
    }
}
