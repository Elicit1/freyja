package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class ShotContinuationRenderer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiImageApiService api;
    private final ShotContinuationMedia media;
    private final ReferenceVideoMediaService referenceMedia;
    private final AiTaskEventStore events;
    public String activeExecution(String task){
        var rows=jdbc.query("SELECT execution_id FROM render_task_step WHERE task_id=? AND status='SUBMITTING' ORDER BY updated_at DESC LIMIT 1",(rs,n)->rs.getString(1),task);return rows.isEmpty()?task:rows.getFirst();
    }
    public VideoGenerationResultVO render(VideoBatchModels.Snapshot snapshot)throws Exception{
        var parent=snapshot.getRequest();String task=parent.getTaskId();var urls=new ArrayList<String>();
        if(VideoExecutionState.current()!=null)VideoExecutionState.current().continuationChild=true;
        try{
            for(int i=0;i<parent.getContinuationSegments().size();i++){
                VideoExecutionState.progress("RENDERING","接续片段 "+(i+1)+" / "+parent.getContinuationSegments().size());
                var child=parent.getContinuationSegments().get(i);var range=parent.getContinuationPlan().getRanges().get(i);
                var generation=new LinkedHashMap<String,Object>();var options=new LinkedHashMap<>(child.getRequest().getH3Options());options.remove("continuation");
                generation.put("options",options);generation.put("provider",parent.getProviderId());generation.put("model",parent.getWorkflowTemplateId());generation.put("modelFingerprint",snapshot.getModelFingerprint());generation.put("size",parent.getSize());generation.put("prompt",child.getRequest().getPrompt());generation.put("seed",child.getRequest().getSeed());generation.put("images",child.getShot().getRefImagesJson());generation.put("firstFrame",child.getShot().getPreviewImageUrl());generation.put("lastFrame",child.getShot().getEndFrameImageUrl());generation.put("overlap",range.getOverlapFrames());
                generation.put("videos",child.getRequest().getResolvedRefVideos()==null?List.of():child.getRequest().getResolvedRefVideos().stream().map(v->Arrays.asList(v.getId(),v.getContentSha256(),v.getIncludeAudio())).toList());
                String key="segment-"+i,hash=VideoBatchPreparation.hash(json.writeValueAsString(generation)+"|"+(urls.isEmpty()?"":urls.getLast()));
                String execution=task+"-segment-"+i;
                var rows=jdbc.queryForList("SELECT * FROM render_task_step WHERE task_id=? AND step_key=?",task,key);
                String output=null;
                if(!rows.isEmpty()){
                    var row=rows.getFirst();if(!hash.equals(row.get("input_hash")))throw new BizException(409,"接续执行输入与持久快照不一致");
                    execution=row.get("execution_id").toString();
                    if("SUCCESS".equals(row.get("status"))){output=Objects.toString(row.get("output_url"),null);if(output==null||output.isBlank())throw new BizException(500,"已成功的接续片段缺少归档地址，停止恢复");}
                    else if(Set.of("PREPARING","FAILED").contains(Objects.toString(row.get("status"),""))){execution=task+"-segment-"+i+"-"+UUID.randomUUID();}
                    else{
                        String persistedPrompt=Objects.toString(row.get("prompt_id"),null),persistedNode=Objects.toString(row.get("node_id"),null);
                        var remote=persistedPrompt!=null&&persistedNode!=null?api.queryVideoTask(parent.getProviderId(),execution,persistedPrompt,persistedNode):api.queryVideoTask(parent.getProviderId(),execution);
                        if("SUCCESS".equals(remote.status())){if(remote.videoUrl()==null||remote.videoUrl().isBlank())throw new BizException(502,"上游已完成片段缺少视频地址");output=api.archiveExistingVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),remote.videoUrl()).getVideoUrl();}
                        else if(Set.of("FAILED","CANCELLED").contains(remote.status())){execution=task+"-segment-"+i+"-"+UUID.randomUUID();jdbc.update("UPDATE render_task_step SET execution_id=?,status='SUBMITTING',updated_at=? WHERE task_id=? AND step_key=?",execution,LocalDateTime.now(),task,key);}
                        else throw new BizException(409,"片段上游状态为 "+remote.status()+"，暂不重复提交，请稍后恢复");
                    }
                }else if(jdbc.update("INSERT INTO render_task_step(task_id,step_key,input_hash,execution_id,status,updated_at) VALUES (?,?,?,?,'PREPARING',?)",task,key,hash,execution,LocalDateTime.now())!=1)throw new BizException(500,"接续步骤持久化失败");
                if(rows.isEmpty()&&parent.getContinuationPlan().getDraft().getReuseFromTaskId()!=null){
                    String sourceTask=parent.getContinuationPlan().getDraft().getReuseFromTaskId();
                    if(jdbc.queryForObject("SELECT COUNT(*) FROM render_task WHERE task_id=? AND shot_id=?",Integer.class,sourceTask,snapshot.getShot().getId())!=1)throw new BizException(400,"只能复用当前镜头的历史任务");
                    var reusable=jdbc.query("SELECT output_url FROM render_task_step WHERE task_id=? AND step_key=? AND input_hash=? AND status='SUCCESS'",(rs,n)->rs.getString(1),sourceTask,key,hash);
                    if(!reusable.isEmpty()){output=reusable.getFirst();if(output==null||output.isBlank())throw new BizException(500,"历史接续片段缺少归档地址");jdbc.update("UPDATE render_task_step SET source_task_id=? WHERE task_id=? AND step_key=?",sourceTask,task,key);}
                }
                if(output==null){
                    if(i>0){
                        var previous=parent.getContinuationPlan().getRanges().get(i-1);
                        var context=media.tail(urls.getLast(),previous.getEndFrame()-previous.getStartFrame(),range.getOverlapFrames());
                        child.getRequest().getH3Options().put("continuation",Map.of("video_url",referenceMedia.transportUrl(context.objectPath()),"content_sha256",context.sha256(),"frames",range.getOverlapFrames()));
                    }
                    child.getRequest().setTaskId(execution);
                    if(jdbc.update("UPDATE render_task_step SET execution_id=?,status='SUBMITTING',updated_at=? WHERE task_id=? AND step_key=?",execution,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"接续执行身份保存失败");
                    if(VideoExecutionState.current()!=null)VideoExecutionState.current().upstream=(prompt,node)->{if(jdbc.update("UPDATE render_task_step SET prompt_id=?,node_id=?,updated_at=? WHERE task_id=? AND step_key=?",prompt,node,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"上游执行身份持久化失败");};
                    try{output=api.generateAndArchiveVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),child.getShot(),child.getRequest()).getVideoUrl();}
                    catch(Exception error){var state=VideoExecutionState.current();if(state!=null&&Set.of("PREPARATION","REJECTED").contains(state.phase))jdbc.update("UPDATE render_task_step SET status='FAILED',error_message=?,updated_at=? WHERE task_id=? AND step_key=?",error.getMessage(),LocalDateTime.now(),task,key);throw error;}
                }
                if(output==null||output.isBlank())throw new BizException(502,"片段未返回归档地址");
                if(jdbc.update("UPDATE render_task_step SET status='SUCCESS',output_url=?,updated_at=? WHERE task_id=? AND step_key=?",output,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"片段归档结果持久化失败");
                urls.add(output);events.append(task,"CONTINUATION_SEGMENT_COMPLETE",json.writeValueAsString(Map.of("segmentId",range.getSegmentId(),"index",i,"videoUrl",output)));
                // A child result must never be mistaken for a recoverable complete parent video.
                if(VideoExecutionState.current()!=null)VideoExecutionState.current().recoveryUrl=null;
                VideoExecutionState.phase("CONTINUATION");
            }
            VideoExecutionState.progress("ASSEMBLING","正在裁去接续重叠并合成完整视频");
            String url=media.assemble(urls,parent.getContinuationPlan());
            if(VideoExecutionState.current()!=null)VideoExecutionState.current().continuationChild=false;
            return api.archiveExistingVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),url);
        }catch(Exception e){if(VideoExecutionState.current()!=null&&VideoExecutionState.current().continuationChild)VideoExecutionState.current().recoveryUrl=null;throw e;}
        finally{if(VideoExecutionState.current()!=null){VideoExecutionState.current().continuationChild=false;VideoExecutionState.current().upstream=null;}for(var child:parent.getContinuationSegments())if(child.getRequest().getH3Options()!=null)child.getRequest().getH3Options().remove("continuation");}
    }
}
