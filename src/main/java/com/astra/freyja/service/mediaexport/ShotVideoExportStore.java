package com.astra.freyja.service.mediaexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;

@Repository
@RequiredArgsConstructor
public class ShotVideoExportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Task> taskMapper = BeanPropertyRowMapper.newInstance(Task.class);
    public void lock(){jdbc.queryForObject("SELECT id FROM shot_video_export_dispatch WHERE id=1 FOR UPDATE",Integer.class);}
    public Task task(Long id){return first(jdbc.query("SELECT * FROM shot_video_export_task WHERE id=?",taskMapper,id));}
    public Task byRequest(String id){return first(jdbc.query("SELECT * FROM shot_video_export_task WHERE request_id=?",taskMapper,id));}
    public List<Task> list(boolean active,int limit){return jdbc.query("SELECT * FROM shot_video_export_task WHERE status "+(active?"IN":"NOT IN")+" ('QUEUED','PACKAGING','UPLOADING') ORDER BY id DESC LIMIT ?",taskMapper,limit);}
    public Page<Task> page(Long dramaId,int current,int size){
        String filter=dramaId==null?"":" WHERE drama_id=?";List<Object> args=new ArrayList<>();if(dramaId!=null)args.add(dramaId);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM shot_video_export_task"+filter,Long.class,args.toArray());
        args.add(size);args.add((current-1)*size);return new Page<>(jdbc.query("SELECT * FROM shot_video_export_task"+filter+" ORDER BY id DESC LIMIT ? OFFSET ?",taskMapper,args.toArray()),total,current,size);
    }
    public void insert(Task task,List<Item> items){
        jdbc.update("INSERT INTO shot_video_export_task(id,drama_id,drama_title,name,request_id,request_hash,retry_of_task_id,total,processed,skipped,created_at,current_stage,filename,concat_videos) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                task.getId(),task.getDramaId(),task.getDramaTitle(),task.getName(),task.getRequestId(),task.getRequestHash(),task.getRetryOfTaskId(),items.size(),items.stream().filter(i->"MISSING_VIDEO".equals(i.getStatus())).count(),items.stream().filter(i->"MISSING_VIDEO".equals(i.getStatus())).count(),task.getCreatedAt(),"等待文件打包队列",task.getFilename(),task.isConcatVideos());
        for(var item:items)jdbc.update("INSERT INTO shot_video_export_item(id,task_id,shot_id,execution_order,zip_entry_path,snapshot_json,status,error_message) VALUES(?,?,?,?,?,?,?,?)",item.getId(),task.getId(),item.getShotId(),item.getExecutionOrder(),item.getZipEntryPath(),write(item.getSnapshot()),"MISSING_VIDEO".equals(item.getStatus())?"SKIPPED":"QUEUED",item.getErrorMessage());
    }
    public List<Item> items(Long id){return jdbc.query("SELECT * FROM shot_video_export_item WHERE task_id=? ORDER BY execution_order,id",itemMapper(),id);}
    public Page<Item> items(Long id,int current,int size){long total=jdbc.queryForObject("SELECT COUNT(*) FROM shot_video_export_item WHERE task_id=?",Long.class,id);return new Page<>(jdbc.query("SELECT * FROM shot_video_export_item WHERE task_id=? ORDER BY execution_order,id LIMIT ? OFFSET ?",itemMapper(),id,size,(current-1)*size),total,current,size);}
    private RowMapper<Item> itemMapper(){return (rs,n)->{
        Snapshot snap;try{snap=json.readValue(rs.getString("snapshot_json"),Snapshot.class);}catch(Exception e){throw new IllegalStateException("视频导出快照损坏",e);}
        Item item=new Item();BeanUtils.copyProperties(snap,item);item.setSnapshot(snap);item.setId(rs.getLong("id"));item.setTaskId(rs.getLong("task_id"));item.setStatus(rs.getString("status"));item.setErrorMessage(rs.getString("error_message"));item.setSize(rs.getObject("size",Long.class));item.setChecksum(rs.getString("checksum"));return item;
    };}
    /** Only called after obtaining the independent global session mutex. */
    public List<Task> recover(){
        var stale=jdbc.query("SELECT * FROM shot_video_export_task WHERE status IN ('PACKAGING','UPLOADING')",taskMapper);
        for(var task:stale){jdbc.update("UPDATE shot_video_export_item SET status=CASE WHEN status='SKIPPED' THEN 'SKIPPED' ELSE 'QUEUED' END,size=NULL,checksum=NULL WHERE task_id=?",task.getId());
            jdbc.update("UPDATE shot_video_export_task SET status='QUEUED',attempt_token=NULL,object_key=NULL,size=NULL,bytes_read=0,progress=0,succeeded=0,failed=0,processed=skipped,current_stage='恢复原视频快照，重新打包',revision=revision+1 WHERE id=?",task.getId());}
        jdbc.update("UPDATE shot_video_export_dispatch SET task_id=NULL,attempt_token=NULL WHERE id=1");return stale;
    }
    public Task claim(){
        Task task=first(jdbc.query("SELECT * FROM shot_video_export_task WHERE status='QUEUED' ORDER BY id LIMIT 1",taskMapper));if(task==null)return null;
        String token=UUID.randomUUID().toString();jdbc.update("UPDATE shot_video_export_task SET status='PACKAGING',attempt_token=?,started_at=?,current_stage='准备打包',revision=revision+1 WHERE id=? AND status='QUEUED'",token,LocalDateTime.now(),task.getId());
        jdbc.update("UPDATE shot_video_export_dispatch SET task_id=?,attempt_token=? WHERE id=1",task.getId(),token);return task(task.getId());
    }
    public boolean active(Long id,String token){return jdbc.queryForObject("SELECT COUNT(*) FROM shot_video_export_task WHERE id=? AND attempt_token=? AND status IN ('PACKAGING','UPLOADING')",Integer.class,id,token)>0;}
    public boolean progress(Task task,String stage,long bytes,int progress){return jdbc.update("UPDATE shot_video_export_task SET bytes_read=?,progress=GREATEST(progress,?),current_stage=?,revision=revision+1 WHERE id=? AND attempt_token=? AND status IN ('PACKAGING','UPLOADING')",bytes,progress,stage,task.getId(),task.getAttemptToken())==1;}
    public void result(Task task,Item item,String status,Long size,String checksum,String error){
        jdbc.update("UPDATE shot_video_export_item SET status=?,size=?,checksum=?,error_message=? WHERE id=? AND task_id=? AND EXISTS (SELECT 1 FROM shot_video_export_task WHERE id=? AND attempt_token=? AND status='PACKAGING')",status,size,checksum,error,item.getId(),task.getId(),task.getId(),task.getAttemptToken());
        jdbc.update("UPDATE shot_video_export_task SET processed=(SELECT COUNT(*) FROM shot_video_export_item WHERE task_id=? AND status IN ('SUCCESS','FAILED','SKIPPED')),succeeded=(SELECT COUNT(*) FROM shot_video_export_item WHERE task_id=? AND status='SUCCESS'),failed=(SELECT COUNT(*) FROM shot_video_export_item WHERE task_id=? AND status='FAILED'),revision=revision+1 WHERE id=? AND attempt_token=? AND status='PACKAGING'",task.getId(),task.getId(),task.getId(),task.getId(),task.getAttemptToken());
    }
    public boolean uploading(Task task,String key){return jdbc.update("UPDATE shot_video_export_task SET status='UPLOADING',object_key=?,progress=95,current_stage=?,revision=revision+1 WHERE id=? AND attempt_token=? AND status='PACKAGING'",key,task.isConcatVideos()?"正在上传拼接视频":"正在上传 ZIP",task.getId(),task.getAttemptToken())==1;}
    public boolean finish(Task task,long size,LocalDateTime expiry){return jdbc.update("UPDATE shot_video_export_task SET status=CASE WHEN failed+skipped>0 THEN 'PARTIAL_SUCCESS' ELSE 'SUCCESS' END,size=?,expires_at=?,finished_at=?,progress=100,current_stage='打包完成，可下载',revision=revision+1 WHERE id=? AND attempt_token=? AND status='UPLOADING'",size,expiry,LocalDateTime.now(),task.getId(),task.getAttemptToken())==1;}
    public void fail(Task task,String message){jdbc.update("UPDATE shot_video_export_task SET status='FAILED',error_message=?,finished_at=?,current_stage='打包失败',revision=revision+1 WHERE id=? AND attempt_token=? AND status IN ('PACKAGING','UPLOADING')",message,LocalDateTime.now(),task.getId(),task.getAttemptToken());}
    public boolean cancel(Long id){
        int changed=jdbc.update("UPDATE shot_video_export_task SET status='CANCELLED',finished_at=?,current_stage='已取消',revision=revision+1 WHERE id=? AND status IN ('QUEUED','PACKAGING','UPLOADING')",LocalDateTime.now(),id);
        if(changed==1)jdbc.update("UPDATE shot_video_export_item SET status='CANCELLED',error_message='用户取消视频打包' WHERE task_id=? AND status='QUEUED'",id);
        return changed==1;
    }
    public void release(Task task){jdbc.update("UPDATE shot_video_export_dispatch SET task_id=NULL,attempt_token=NULL WHERE id=1 AND task_id=? AND attempt_token=?",task.getId(),task.getAttemptToken());}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static <T>T first(List<T> list){return list.isEmpty()?null:list.getFirst();}
}
