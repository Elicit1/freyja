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
    private final DynamicWorkflowExecutor dynamicWorkflows;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiImageApiService api;
    private final ShotContinuationMedia media;
    private final ReferenceVideoMediaService referenceMedia;
    private final AiTaskEventStore events;
    private final ContinuationArtifactService artifacts;
    public String activeExecution(String task){
        var rows=jdbc.query("SELECT execution_id FROM render_task_step WHERE task_id=? AND status IN ('SUBMITTING','ARCHIVING') ORDER BY updated_at DESC LIMIT 1",(rs,n)->rs.getString(1),task);return rows.isEmpty()?task:rows.getFirst();
    }
    public VideoGenerationResultVO render(VideoBatchModels.Snapshot snapshot)throws Exception{
        var replacement=snapshot.getSegmentReplacement();
        if(replacement!=null&&"UPSCALE_ONLY".equals(replacement.getRerunMode()))return replaceSegment(snapshot);
        var parent=snapshot.getRequest();String task=parent.getTaskId();var urls=new ArrayList<String>();var enhanced=new ArrayList<String>();var handoffs=new LinkedHashMap<String,Map<String,Object>>();
        Map<String,Object> previousUpscale=null;
        if(VideoExecutionState.current()!=null)VideoExecutionState.current().continuationChild=true;
        try{
            for(int i=0;i<parent.getContinuationSegments().size();i++){
                VideoExecutionState.segment(i);
                VideoExecutionState.progress("RENDERING","接续片段 "+(i+1)+" / "+parent.getContinuationSegments().size());
                var child=parent.getContinuationSegments().get(i);var range=parent.getContinuationPlan().getRanges().get(i);
                if(replacement!=null&&!replacement.indices().contains(i)){
                    var saved=replacement.getOutputs().get(i);urls.add(saved.getBaseVideoUrl());enhanced.add(saved.getVideoUrl());
                    if(saved.getLatent()!=null)handoffs.put(range.getSegmentId(),saved.getLatent());
                    previousUpscale=saved.getUpscaleLatent();continue;
                }
                var generation=new LinkedHashMap<String,Object>();var options=new LinkedHashMap<>(child.getRequest().getH3Options());options.remove("continuation");options.remove("latent_context");
                generation.put("options",options);generation.put("provider",parent.getProviderId());generation.put("model",parent.getWorkflowTemplateId());generation.put("modelFingerprint",snapshot.getModelFingerprint());generation.put("size",parent.getSize());generation.put("prompt",child.getRequest().getPrompt());generation.put("seed",child.getRequest().getSeed());generation.put("images",child.getShot().getRefImagesJson());generation.put("firstFrame",child.getShot().getPreviewImageUrl());generation.put("lastFrame",child.getShot().getEndFrameImageUrl());generation.put("overlap",range.getOverlapFrames());
                if(child.getRequest().getDynamicWorkflowStage()!=null)generation.put("dynamicBase",child.getRequest().getDynamicWorkflowStage());
                generation.put("videos",child.getRequest().getResolvedRefVideos()==null?List.of():child.getRequest().getResolvedRefVideos().stream().map(v->Arrays.asList(v.getId(),v.getContentSha256(),v.getIncludeAudio())).toList());
                // V2 explicitly fingerprints standalone audio as well as visual handoff settings.
                // Keep immutable V1 execution hashes readable during recovery.
                if(Objects.equals(options.get("schema_version"),2))generation.put("audios",child.getShot().getRefAudiosJson());
                if(!handoffs.isEmpty())generation.put("predecessorLatent",handoffs.values().stream().reduce((a,b)->b).orElseThrow().get("sha256"));
                if(range.getAnchorSourceSegmentId()!=null)generation.put("anchorLatent",handoffs.get(range.getAnchorSourceSegmentId()).get("sha256"));
                String key="segment-"+i,hash=VideoBatchPreparation.hash(json.writeValueAsString(generation)+"|"+(urls.isEmpty()?"":urls.getLast()));
                String execution=task+"-segment-"+i;
                var rows=jdbc.queryForList("SELECT * FROM render_task_step WHERE task_id=? AND step_key=?",task,key);
                String output=null;String manifest=null;
                if(!rows.isEmpty()){
                    var row=rows.getFirst();if(!hash.equals(row.get("input_hash")))throw new BizException(409,"接续执行输入与持久快照不一致");
                    execution=row.get("execution_id").toString();
                    if("SUCCESS".equals(row.get("status"))){output=Objects.toString(row.get("output_url"),null);manifest=manifestText(row.get("result_manifest_json"));if(output==null||output.isBlank())throw new BizException(500,"已成功的接续片段缺少归档地址，停止恢复");}
                    else if("ARCHIVING".equals(row.get("status"))){output=Objects.toString(row.get("output_url"),null);if(output==null)throw new BizException(500,"归档阶段缺少已生成视频");}
                    else if(Set.of("PREPARING","FAILED").contains(Objects.toString(row.get("status"),""))){execution=task+"-segment-"+i+"-"+UUID.randomUUID();}
                    else{
                        String persistedPrompt=Objects.toString(row.get("prompt_id"),null),persistedNode=Objects.toString(row.get("node_id"),null);
                        var remote=persistedPrompt!=null&&persistedNode!=null?api.queryVideoTask(parent.getProviderId(),execution,persistedPrompt,persistedNode):api.queryVideoTask(parent.getProviderId(),execution);
                        if("SUCCESS".equals(remote.status())){if(remote.videoUrl()==null||remote.videoUrl().isBlank())throw new BizException(502,"上游已完成片段缺少视频地址");output=api.archiveExistingVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),remote.videoUrl(),snapshot.getRequest().getProviderId()).getVideoUrl();}
                        else if(Set.of("FAILED","CANCELLED").contains(remote.status())){execution=task+"-segment-"+i+"-"+UUID.randomUUID();jdbc.update("UPDATE render_task_step SET execution_id=?,status='SUBMITTING',updated_at=? WHERE task_id=? AND step_key=?",execution,LocalDateTime.now(),task,key);}
                        else throw new BizException(409,"片段上游状态为 "+remote.status()+"，暂不重复提交，请稍后恢复");
                    }
                }else if(jdbc.update("INSERT INTO render_task_step(task_id,step_key,input_hash,execution_id,status,updated_at) VALUES (?,?,?,?,'PREPARING',?)",task,key,hash,execution,LocalDateTime.now())!=1)throw new BizException(500,"接续步骤持久化失败");
                if(rows.isEmpty()&&parent.getContinuationPlan().getDraft().getReuseFromTaskId()!=null){
                    String sourceTask=parent.getContinuationPlan().getDraft().getReuseFromTaskId();
                    if(jdbc.queryForObject("SELECT COUNT(*) FROM render_task WHERE task_id=? AND shot_id=?",Integer.class,sourceTask,snapshot.getShot().getId())!=1)throw new BizException(400,"只能复用当前镜头的历史任务");
                    var reusable=jdbc.queryForList("SELECT output_url,result_manifest_json FROM render_task_step WHERE task_id=? AND step_key=? AND input_hash=? AND status='SUCCESS'",sourceTask,key,hash);
                    if(!reusable.isEmpty()&&(!range.isSaveLatent()||reusable.getFirst().get("result_manifest_json")!=null)){output=Objects.toString(reusable.getFirst().get("output_url"),null);manifest=manifestText(reusable.getFirst().get("result_manifest_json"));if(output==null||output.isBlank())throw new BizException(500,"历史接续片段缺少归档地址");jdbc.update("UPDATE render_task_step SET source_task_id=? WHERE task_id=? AND step_key=?",sourceTask,task,key);}
                }
                if(output==null){
                    if(i>0){
                        var previous=parent.getContinuationPlan().getRanges().get(i-1);
                        if(range.getEffectiveContinuity()!=null&&range.getEffectiveContinuity().requiresLatent()){
                            var context=new LinkedHashMap<String,Object>(artifacts.transport(handoffs.get(previous.getSegmentId())));context.put("frames",range.getOverlapFrames());
                            if(range.getAnchorSourceSegmentId()!=null)context.put("anchor",artifacts.transport(handoffs.get(range.getAnchorSourceSegmentId())));
                            child.getRequest().getH3Options().put("latent_context",context);
                        }else{
                            var context=media.tail(urls.getLast(),previous.getEndFrame()-previous.getStartFrame(),range.getOverlapFrames());
                            child.getRequest().getH3Options().put("continuation",Map.of("video_url",referenceMedia.transportUrl(context.objectPath()),"content_sha256",context.sha256(),"frames",range.getOverlapFrames()));
                        }
                    }
                    child.getRequest().setTaskId(execution);
                    if(jdbc.update("UPDATE render_task_step SET execution_id=?,status='SUBMITTING',updated_at=? WHERE task_id=? AND step_key=?",execution,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"接续执行身份保存失败");
                    if(VideoExecutionState.current()!=null)VideoExecutionState.current().upstream=(prompt,node)->{if(jdbc.update("UPDATE render_task_step SET prompt_id=?,node_id=?,updated_at=? WHERE task_id=? AND step_key=?",prompt,node,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"上游执行身份持久化失败");};
                    try{output=api.generateAndArchiveVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),child.getShot(),child.getRequest()).getVideoUrl();}
                    catch(Exception error){var state=VideoExecutionState.current();if(state!=null&&Set.of("PREPARATION","REJECTED").contains(state.phase))jdbc.update("UPDATE render_task_step SET status='FAILED',error_message=?,updated_at=? WHERE task_id=? AND step_key=?",error.getMessage(),LocalDateTime.now(),task,key);throw error;}
                }
                if(output==null||output.isBlank())throw new BizException(502,"片段未返回归档地址");
                if(range.isSaveLatent()){
                    if(manifest==null){
                        if(jdbc.update("UPDATE render_task_step SET status='ARCHIVING',output_url=?,updated_at=? WHERE task_id=? AND step_key=?",output,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"latent 归档阶段保存失败");
                        var identity=jdbc.queryForMap("SELECT prompt_id,node_id FROM render_task_step WHERE task_id=? AND step_key=?",task,key);
                        manifest=json.writeValueAsString(artifacts.archive(parent.getProviderId(),execution,Objects.toString(identity.get("prompt_id"),null),Objects.toString(identity.get("node_id"),null),snapshot.getShot().getDramaId(),snapshot.getShot().getId()));
                    }
                    handoffs.put(range.getSegmentId(),artifacts.read(manifest));
                    if(jdbc.update("UPDATE render_task_step SET result_manifest_json=? WHERE task_id=? AND step_key=?",manifest,task,key)!=1)throw new BizException(500,"latent 归档引用保存失败");
                }
                if(jdbc.update("UPDATE render_task_step SET status='SUCCESS',output_url=?,updated_at=? WHERE task_id=? AND step_key=?",output,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"片段归档结果持久化失败");
                urls.add(output);events.append(task,"CONTINUATION_SEGMENT_COMPLETE",json.writeValueAsString(Map.of("segmentId",range.getSegmentId(),"index",i,"videoUrl",output)));
                var enhancement=child.getRequest().getDynamicWorkflowPlan();
                if(enhancement!=null&&enhancement.isSecondPassContinuity()&&enhancement.getOptions().isUpscale()){
                    var result=dynamicWorkflows.enhanceContinuation(child,output,task,String.format(Locale.ROOT,"segment-%03d",i),
                            enhancement.isNativeLatentInput()&&manifest!=null?artifacts.read(manifest):null,previousUpscale,range.getOverlapFrames());
                    enhanced.add(result.videoUrl());previousUpscale=result.latent();
                }else{
                    enhanced.add(enhancement==null?output:enhancement.isNativeLatentInput()&&manifest!=null
                            ?dynamicWorkflows.enhance(child,output,task,String.format(Locale.ROOT,"segment-%03d",i),artifacts.read(manifest))
                            :dynamicWorkflows.enhance(child,output,task,String.format(Locale.ROOT,"segment-%03d",i)));
                    previousUpscale=null;
                }
                // A child result must never be mistaken for a recoverable complete parent video.
                if(VideoExecutionState.current()!=null)VideoExecutionState.current().recoveryUrl=null;
                VideoExecutionState.phase("CONTINUATION");
            }
            VideoExecutionState.progress("ASSEMBLING","正在裁去接续重叠并合成完整视频");
            String url=replacement==null?media.assemble(enhanced,parent.getContinuationPlan()):media.assemble(enhanced,parent.getContinuationPlan(),parent.getDynamicWorkflowPlan()==null?parent.getSize():parent.getDynamicWorkflowPlan().getOutputSize());
            if(VideoExecutionState.current()!=null)VideoExecutionState.current().continuationChild=false;
            return api.archiveExistingVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),url,snapshot.getRequest().getProviderId());
        }catch(Exception e){if(VideoExecutionState.current()!=null&&VideoExecutionState.current().continuationChild)VideoExecutionState.current().recoveryUrl=null;throw e;}
        finally{if(VideoExecutionState.current()!=null){VideoExecutionState.current().continuationChild=false;VideoExecutionState.current().upstream=null;}for(var child:parent.getContinuationSegments())if(child.getRequest().getH3Options()!=null){child.getRequest().getH3Options().remove("continuation");child.getRequest().getH3Options().remove("latent_context");}}
    }
    private VideoGenerationResultVO replaceSegment(VideoBatchModels.Snapshot snapshot)throws Exception{
        var replacement=snapshot.getSegmentReplacement();var parent=snapshot.getRequest();String task=parent.getTaskId();
        var indices=replacement.indices();var outputs=replacement.getOutputs();
        if(outputs==null||outputs.size()!=parent.getContinuationSegments().size()||indices.isEmpty()
                ||indices.stream().anyMatch(index->index==null||index<0||index>=outputs.size())||new HashSet<>(indices).size()!=indices.size())throw new BizException(409,"片段替换快照与接续时间轴不一致");
        if(VideoExecutionState.current()!=null)VideoExecutionState.current().continuationChild=true;
        try{
            var urls=new ArrayList<String>();
            Map<String,Object> previousUpscale=null;
            for(int i=0;i<outputs.size();i++){
                var output=outputs.get(i);
                if(indices.contains(i)){
                    VideoExecutionState.segment(i);
                    var child=parent.getContinuationSegments().get(i);
                    if(child.getRequest().getDynamicWorkflowPlan()!=null&&child.getRequest().getDynamicWorkflowPlan().isSecondPassContinuity()){
                        var result=dynamicWorkflows.enhanceContinuation(child,output.getBaseVideoUrl(),task,String.format(Locale.ROOT,"segment-%03d",i),
                                output.getLatent(),previousUpscale,parent.getContinuationPlan().getRanges().get(i).getOverlapFrames());
                        urls.add(result.videoUrl());previousUpscale=result.latent();
                    }else{
                        urls.add(dynamicWorkflows.enhance(child,output.getBaseVideoUrl(),task,String.format(Locale.ROOT,"segment-%03d",i),output.getLatent()));
                        previousUpscale=null;
                    }
                }else{urls.add(output.getVideoUrl());previousUpscale=output.getUpscaleLatent();}
            }
            VideoExecutionState.progress("ASSEMBLING","正在替换片段并按原时间轴重新合成");
            boolean mixedBase=parent.getContinuationSegments().stream().anyMatch(c->c.getRequest().getDynamicWorkflowPlan()==null
                    ||c.getRequest().getDynamicWorkflowPlan().getOptions()!=null&&!c.getRequest().getDynamicWorkflowPlan().getOptions().isUpscale());
            String merged=mixedBase&&parent.getDynamicWorkflowPlan()!=null?media.assemble(urls,parent.getContinuationPlan(),parent.getDynamicWorkflowPlan().getOutputSize()):media.assemble(urls,parent.getContinuationPlan());
            if(VideoExecutionState.current()!=null)VideoExecutionState.current().continuationChild=false;
            return api.archiveExistingVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),merged,parent.getProviderId());
        }finally{if(VideoExecutionState.current()!=null){VideoExecutionState.current().continuationChild=false;VideoExecutionState.current().upstream=null;VideoExecutionState.current().recoveryUrl=null;}}
    }
    private String manifestText(Object value){return value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):Objects.toString(value,null);}
}
