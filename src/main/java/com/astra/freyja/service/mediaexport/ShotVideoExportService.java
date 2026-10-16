package com.astra.freyja.service.mediaexport;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.VideoExportProperties;
import com.astra.freyja.dto.task.*;
import com.astra.freyja.event.TaskStatusChangedEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ShotVideoExportService {
    private final ShotVideoExportSelection selection;
    private final ShotVideoExportStore store;
    private final ShotVideoExportArchiveService archive;
    private final VideoExportProperties properties;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private record Execution(AtomicBoolean active,Thread thread) {}
    private final ConcurrentMap<Long,Execution> running=new ConcurrentHashMap<>();

    public List<Node> tree(Long dramaId){return selection.tree(dramaId);}
    public Preview preview(Request request){return selection.preview(request);}
    public Task create(Request request){
        if(request==null)throw new BizException(400,"导出请求不能为空");
        enabled();validRequestId(request.getClientRequestId());
        String hash=ShotVideoExportSelection.hash(selection.write(Arrays.asList(request.getDramaId(),request.getName(),request.getMissingVideoPolicy(),request.getShotIds()==null?null:request.getShotIds().stream().sorted().toList(),request.getSelection())));
        Task previous=store.byRequest(request.getClientRequestId());if(previous!=null)return same(previous,hash);
        Preview checked=selection.preview(request);
        if(!Objects.equals(checked.getPreviewHash(),request.getExpectedPreviewHash()))throw new BizException(409,"预检结果已变化，请重新检查");
        if(checked.getBlocked()>0)throw new BizException(400,"存在不可导出的镜头，请取消勾选后重新检查");
        if(checked.getAccepted()==0||"FAIL".equals(request.getMissingVideoPolicy())&&checked.getMissing()>0)throw new BizException(400,"没有可导出视频，或所选镜头存在缺失视频");
        if(checked.getKnownBytes()+1024L*1024>properties.getMaxArchiveBytes())throw new BizException(400,"所选视频超过整包大小限制");
        var snapshots=checked.getItems().stream().collect(Collectors.toMap(Item::getShotId,Item::getSnapshot));
        Long id=tx.execute(status->{store.lock();Task old=store.byRequest(request.getClientRequestId());if(old!=null)return same(old,hash).getId();
            selection.lock(request,checked.getShotIds());Preview current=selection.prepare(request,snapshots);
            if(!Objects.equals(current.getPreviewHash(),request.getExpectedPreviewHash()))throw new BizException(409,"镜头或绑定视频已变化，请重新检查");
            Task task=newTask(request.getClientRequestId(),hash,current.getItems(),request.getName(),null);store.insert(task,current.getItems());return task.getId();});
        publish(id);events.publishEvent(new QueueChanged());return detail(id);
    }
    private Task newTask(String request,String hash,List<Item> items,String name,Long retryOf){
        Task task=new Task();task.setId(IdWorker.getId());task.setDramaId(items.getFirst().getSnapshot().getDramaId());task.setDramaTitle(items.getFirst().getSnapshot().getDramaTitle());task.setName(StringUtils.abbreviate(StringUtils.defaultIfBlank(name,task.getDramaTitle()+" · 视频打包下载"),200));task.setRequestId(request);task.setRequestHash(hash);task.setRetryOfTaskId(retryOf);task.setCreatedAt(LocalDateTime.now());task.setFilename(ShotVideoExportSelection.safe(task.getDramaTitle(),80)+"_"+task.getId()+".zip");
        items.forEach(i->{i.setId(IdWorker.getId());i.setTaskId(task.getId());i.setStatus(i.getSnapshot().getStatus());});return task;
    }
    public Task retry(Long id,String requestId){
        enabled();validRequestId(requestId);String hash=ShotVideoExportSelection.hash("retry:"+id);
        Long created=tx.execute(status->{store.lock();Task old=store.byRequest(requestId);if(old!=null)return same(old,hash).getId();Task source=detail(id);
            if(!Set.of("FAILED","PARTIAL_SUCCESS").contains(source.getStatus()))throw new BizException(409,"仅失败或部分成功的任务支持按原快照重试");
            List<Item> items=store.items(id);Task task=newTask(requestId,hash,items,source.getName()+" · 重试",id);store.insert(task,items);return task.getId();});
        publish(created);events.publishEvent(new QueueChanged());return detail(created);
    }
    private Task same(Task task,String hash){if(!Objects.equals(task.getRequestHash(),hash))throw new BizException(409,"同一请求标识的导出内容不一致");return decorate(task);}
    public Task detail(Long id){Task task=store.task(id);if(task==null)throw new BizException(404,"导出任务不存在");return decorate(task);}
    private Task decorate(Task task){task.setDownloadAvailable(Set.of("SUCCESS","PARTIAL_SUCCESS").contains(task.getStatus())&&task.getExpiresAt()!=null&&task.getExpiresAt().isAfter(LocalDateTime.now()));return task;}
    public Page<Task> page(Long drama,int current,int size){var page=store.page(drama,Math.max(1,current),Math.max(1,Math.min(100,size)));page.records().forEach(this::decorate);return page;}
    public Page<Item> items(Long id,int current,int size){detail(id);return store.items(id,Math.max(1,current),Math.max(1,Math.min(200,size)));}
    public List<Task> list(boolean active,int limit){return store.list(active,Math.max(1,Math.min(200,limit)));}
    public DownloadLink download(Long id){Task task=detail(id);if(!task.isDownloadAvailable())throw new BizException(409,"导出尚未完成或产物已过期");return archive.link(task);}
    public TaskCancelResultVO cancel(Long id){
        boolean cancelled=tx.execute(status->{store.lock();detail(id);return store.cancel(id);});
        if(cancelled){afterCommit(this::signalCancellation);publish(id);events.publishEvent(new QueueChanged());}
        return TaskCancelResultVO.builder().cancelled(cancelled).status(cancelled?"CANCELLED":detail(id).getStatus()).upstreamStatus("CONFIRMED").message(cancelled?"已取消视频打包，关闭窗口不影响其他任务":"任务已结束，无法取消").build();
    }
    /** Invoked on real queue notifications, including notifications from another instance. */
    public void signalCancellation(){running.forEach((id,execution)->{Task task=store.task(id);if(task==null||"CANCELLED".equals(task.getStatus())){execution.active().set(false);execution.thread().interrupt();archive.interruptRead(id);}});}
    public void recover(){
        List<Task> stale=tx.execute(status->{store.lock();return store.recover();});
        archive.cleanupStale();if(stale!=null)for(Task task:stale){archive.remove(task.getObjectKey());publish(task.getId());}
    }
    public boolean runNext(){
        Task task=tx.execute(status->{store.lock();return store.claim();});if(task==null)return false;
        AtomicBoolean active=new AtomicBoolean(true);running.put(task.getId(),new Execution(active,Thread.currentThread()));publish(task.getId());
        ShotVideoExportArchiveService.Artifact artifact=null;String key="shot-video-exports/"+task.getId()+"/"+task.getAttemptToken()+"/package.zip";
        AtomicLong lastPush=new AtomicLong(),bytes=new AtomicLong();AtomicInteger processed=new AtomicInteger(task.getProcessed());
        try{
            // Cancellation may have committed between claiming and local registration.
            if(!store.active(task.getId(),task.getAttemptToken()))active.set(false);
            artifact=archive.build(task,store.items(task.getId()),active::get,(read,name)->{
                bytes.set(read);long now=System.nanoTime();if(now-lastPush.get()>1_000_000_000L){lastPush.set(now);if(!store.progress(task,"已处理 "+processed.get()+"/"+task.getTotal()+" · 当前 "+name,read,Math.min(90,processed.get()*90/Math.max(1,task.getTotal()))))active.set(false);publish(task.getId());}
            },item->{store.result(task,item,item.getStatus(),item.getSize(),item.getChecksum(),item.getErrorMessage());processed.incrementAndGet();store.progress(task,"已处理 "+processed.get()+"/"+task.getTotal()+" 个视频",bytes.get(),Math.min(90,processed.get()*90/Math.max(1,task.getTotal())));publish(task.getId());});
            if(!active.get()||!store.uploading(task,key))throw new CancellationException();publish(task.getId());
            archive.upload(key,artifact.zip());
            if(!store.finish(task,Files.size(artifact.zip()),LocalDateTime.now().plusHours(properties.getRetentionHours())))archive.remove(key);
        }catch(CancellationException e){archive.remove(key);}
        catch(Exception e){archive.remove(key);store.fail(task,"视频打包失败："+StringUtils.defaultIfBlank(e.getMessage(),"请查看服务日志"));log.warn("视频打包失败 task={}",task.getId(),e);}
        finally{running.remove(task.getId());if(artifact!=null)archive.cleanup(artifact.directory());tx.executeWithoutResult(status->{store.lock();store.release(task);});Thread.interrupted();publish(task.getId());}
        return true;
    }
    public TaskCenterItemVO summary(Long id){Task task=detail(id);return TaskCenterItemVO.builder().sourceType("SHOT_VIDEO_EXPORT").taskId(String.valueOf(id)).category("FILE_EXPORT").taskType("SHOT_VIDEO_EXPORT").title(task.getName()).status(Set.of("PACKAGING","UPLOADING").contains(task.getStatus())?"RUNNING":task.getStatus()).progress(task.getProgress()).revision(task.getRevision()).currentStage(task.getCurrentStage()).dramaId(String.valueOf(task.getDramaId())).dramaTitle(task.getDramaTitle()).resumeAction("SHOT_VIDEO_EXPORT").createdAt(task.getCreatedAt()).startedAt(task.getStartedAt()).finishedAt(task.getFinishedAt()).errorMessage(task.getErrorMessage()).build();}
    private void publish(Long id){afterCommit(()->events.publishEvent(new TaskStatusChangedEvent("SHOT_VIDEO_EXPORT",String.valueOf(id),"UPDATED")));}
    private void afterCommit(Runnable action){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){action.run();}});
        else action.run();
    }
    private void enabled(){if(!properties.isEnabled())throw new BizException(503,"视频打包队列当前已停用");}
    private void validRequestId(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{8,80}"))throw new BizException(400,"缺少有效的幂等请求标识");}
}
