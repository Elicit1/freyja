package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor
public class ShotContinuationService {
    private final ShotContinuationPlanner planner;
    private final ShotVideoReferenceService videoReferences;
    private final ObjectMapper json;
    private final CryptoUtil crypto;
    private final com.astra.freyja.config.ReferenceVideoProperties referenceLimits;
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ShotContinuation read(DramaShot shot){
        if(shot.getContinuationJson()==null){var draft=new ShotContinuation();if(shot.getDuration()!=null)draft.setDurationSeconds(shot.getDuration().doubleValue());return draft;}
        try{var draft=json.readValue(shot.getContinuationJson(),ShotContinuation.class);if(draft.isEnabled()&&shot.getDuration()!=null)draft.setDurationSeconds(shot.getDuration().doubleValue());return draft;}catch(Exception e){throw new BizException(400,"接续配置无法读取");}
    }
    public boolean enabled(DramaShot shot){return shot.getContinuationJson()!=null&&read(shot).isEnabled();}
    public JsonNode capabilities(AiModel model,AiProvider provider){
        try{
            String url=ShotVideoCapabilityService.apiRoot(provider.getBaseUrl())+"/models/"+URLEncoder.encode(model.getModelCode(),StandardCharsets.UTF_8)+"/capabilities";
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+Objects.toString(crypto.decrypt(provider.getApiKey()),"")).GET().build();
            var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new BizException(502,"读取 H3 网关能力失败");
            return json.readTree(response.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new BizException(409,"读取能力已取消");}
        catch(BizException e){throw e;}catch(Exception e){throw new BizException(502,"无法读取 H3 网关能力，请检查提供商连接");}
    }
    public ShotContinuationPlan preview(ShotContinuation draft,boolean auto,AiModel model,AiProvider provider){
        var result=planner.plan(draft,auto);JsonNode cap;
        try{cap=capabilities(model,provider);}catch(BizException e){result.getIssues().add(e.getMessage());return result;}
        var h3=cap.path("h3");
        result.setCapabilityRevision(h3.path("revision").asText());result.setNodeId(cap.path("nodeId").asText());
        if(!cap.path("healthy").asBoolean())result.getIssues().add("算力节点离线");
        if(!h3.path("continuation").asBoolean())result.getIssues().add("此工作流未启用接续适配");
        if(draft.isContextWindows()&&!h3.path("contextWindows").asBoolean())result.getIssues().add("此工作流未验证上下文窗口");
        var videoCap=cap.path("referenceVideo");
        int gatewayVideoLimit=videoCap.path("maxVideos").asInt(3);
        int videoLimit=model.getMaxVideos()==null?gatewayVideoLimit:Math.min(gatewayVideoLimit,model.getMaxVideos());
        boolean hasVideos=draft.getSegments().stream().anyMatch(s->!s.getReferenceVideos().isEmpty());
        if(hasVideos&&!"MINIMAX_H3_REF_VIDEO_V1".equals(videoCap.path("adapter").asText()))result.getIssues().add("此工作流不支持参考视频输入");
        List<String> excessiveVideoSegments=new ArrayList<>();
        int segmentNumber=0;
        for(var segment:draft.getSegments()) {
            segmentNumber++;
            if(segment.getDirectorPlan()!=null)try{
                new com.astra.freyja.director.service.DirectorPlanValidator().validateManual(segment.getDirectorPlan(),BigDecimal.valueOf((segment.getEndFrame()-segment.getStartFrame())/24.0).setScale(3,java.math.RoundingMode.HALF_UP),segment.getShotType(),segment.getCameraMovement(),segment.isShotTypeLocked(),segment.isCameraMovementLocked());
            }catch(BizException e){result.getIssues().add("片段 "+segment.getId()+" · 导演计划："+e.getMessage());}
            if(model.getMaxImages()!=null&&segment.getReferenceImages().size()>model.getMaxImages())result.getIssues().add("片段参考图超过所选模型上限");
            if(!segment.getReferenceVideos().isEmpty()){
                if(segment.getReferenceVideos().size()>videoLimit)excessiveVideoSegments.add(String.valueOf(segmentNumber));
                double maxSeconds=Math.min(referenceLimits.getMaxClipSeconds(),videoCap.path("maxClipSeconds").asDouble(referenceLimits.getMaxClipSeconds()));
                int maxFrames=Math.min(referenceLimits.getMaxDecodedFrames(),videoCap.path("maxDecodedFrames").asInt(referenceLimits.getMaxDecodedFrames()));
                double totalSeconds=0;
                for(var video:segment.getReferenceVideos())if(video.getEndSeconds()!=null){
                    double seconds=video.getEndSeconds().subtract(video.getStartSeconds()==null?BigDecimal.ZERO:video.getStartSeconds()).doubleValue();totalSeconds+=seconds;
                    if(seconds>maxSeconds+1e-6||seconds*24>maxFrames+1e-6)result.getIssues().add("片段视频超出已配置参考预算，请缩短源范围或将段长改为 345 帧等较小值");
                    int required=Math.min(segment.getEndFrame(),result.getOutputFrames())-segment.getStartFrame();
                    if(segment.isControlNetEnabled()&&Objects.equals(video.getId(),segment.getControlVideoId())&&seconds*24+1e-6<required)result.getIssues().add("ControlNet 视频未完整覆盖本段真实输出范围，请缩短段长或选择足够长的源范围");
                }
                if(totalSeconds>Math.min(referenceLimits.getMaxClipSeconds(),videoCap.path("maxTotalClipSeconds").asDouble(referenceLimits.getMaxClipSeconds()))+1e-6)result.getIssues().add("片段参考视频总时长超过预算");
            }
            if("TXT2VIDEO_FIRST_LAST".equals(model.getModelType())&&(!segment.getReferenceImages().isEmpty()||!segment.getReferenceVideos().isEmpty()||!segment.getReferenceAudios().isEmpty()))result.getIssues().add("首尾帧工作流请明确设置片段首尾帧；普通图片/视频参考需选择参考生成模式");
            if("TXT2VIDEO_REF".equals(model.getModelType())&&(segment.getFirstFrameImageUrl()!=null||segment.getLastFrameImageUrl()!=null))result.getIssues().add("参考生成工作流请挂载普通参考图，不使用首尾帧锚点");
            if(segment.getLastFrameImageUrl()!=null&&segment.getEndFrame()>result.getOutputFrames())result.getIssues().add("末段尾帧位于网格补齐区，请将时长调整为 "+(result.getRenderFrames()/24.0)+" 秒，或移除该尾帧，避免锚点被裁掉");
            if(segment.getPrompt()==null||segment.getPrompt().isBlank())result.getIssues().add("片段 "+segment.getId()+" 需要填写本段提示词");
            if(segment.isControlNetEnabled()&&!h3.path("controlNet").asBoolean())result.getIssues().add("所选工作流未验证 ControlNet");
            if(segment.isControlNetEnabled()&&h3.path("controlTypes").isArray()&&java.util.stream.StreamSupport.stream(h3.path("controlTypes").spliterator(),false).noneMatch(type->segment.getControlType().equals(type.asText())))result.getIssues().add("算力节点未安装 "+segment.getControlType()+" 控制预处理器");
            if(segment.isControlNetEnabled()&&draft.isContextWindows()&&!h3.path("contextWindowsWithControlNet").asBoolean())result.getIssues().add("官方上下文窗口与 ControlNet 联用尚未验收，请关闭片段内窗口或 ControlNet");
        }
        if(!excessiveVideoSegments.isEmpty())result.getIssues().add("片段 "+String.join("、",excessiveVideoSegments)+" 的参考视频数量超过上限 "+videoLimit
                +"（模型中心："+Objects.toString(model.getMaxVideos(),"未设置")+"，网关："+gatewayVideoLimit+"）"
                +(videoLimit==0?"，请检查模型中心的参考视频数量配置":""));
        result.setIssues(new ArrayList<>(new LinkedHashSet<>(result.getIssues())));
        result.setRenderable(result.getIssues().isEmpty());
        // Reference adapters have a separate revision from H3 continuation options.
        // Include both so media prepared before an adapter update cannot be reused.
        String referenceRevision=hasVideos?cap.path("workflowRevision").asText(""):"";
        result.setPlanHash(VideoBatchPreparation.hash(result.getPlanHash()+"|"+result.getCapabilityRevision()+"|"+model.getId()+"|"+provider.getBaseUrl()+"|"+result.getNodeId()+"|"+referenceRevision));return result;
    }
    public VideoBatchModels.Snapshot prepare(DramaShot shot,AiModel model,AiProvider provider,String size,DramaShotRenderRequestDTO supplied){
        var draft=read(shot);var plan=preview(draft,false,model,provider);
        if(!plan.isRenderable())throw new BizException(409,String.join("；",plan.getIssues()));
        if(supplied!=null&&!Objects.equals(supplied.getExpectedRenderFingerprint(),plan.getPlanHash()))throw new BizException(409,"接续计划已变化，请先预览最新分段计划");
        if(!Objects.equals(model.getModelType(),"REFERENCE_MODE".equals(shot.getGenerationMode())?"TXT2VIDEO_REF":"TXT2VIDEO_FIRST_LAST"))throw new BizException(400,"模型类型与镜头生成模式不一致");
        var parent=new VideoBatchModels.Snapshot();parent.setShot(copy(shot));parent.setRequest(new DramaShotRenderRequestDTO());
        var req=parent.getRequest();req.setProviderId(provider.getId());req.setWorkflowTemplateId(model.getModelCode());req.setSize(size);req.setPrompt(draft.getSegments().getFirst().getPrompt());req.setSeed(shot.getSeed());req.setContinuationPlan(plan);
        req.setContinuationSegments(new ArrayList<>());
        for(int i=0;i<draft.getSegments().size();i++){
            var segment=draft.getSegments().get(i);var child=copy(shot);child.setContinuationJson(null);child.setDuration(BigDecimal.valueOf((segment.getEndFrame()-segment.getStartFrame())/24.0));
            child.setRefImagesJson(write(segment.getReferenceImages()));child.setRefVideosJson(write(segment.getReferenceVideos()));child.setRefAudiosJson(write(segment.getReferenceAudios()));
            child.setShotName(segment.getName());child.setScriptContent(segment.getScriptContent());child.setActionDescription(segment.getActionDescription());child.setSoundEffect(segment.getSoundEffect());
            child.setShotType(segment.getShotType());child.setCameraMovement(segment.getCameraMovement());child.setShotTypeLocked(segment.isShotTypeLocked());child.setCameraMovementLocked(segment.isCameraMovementLocked());
            child.setResSceneId(segment.getResSceneId());child.setResKeyframeId(segment.getResKeyframeId());child.setCustomScenePrompt(segment.getCustomScenePrompt());
            child.setCharacterRefsJson(write(segment.getCharacterRefs()));child.setPropRefsJson(write(segment.getPropRefs()));
            child.setFirstFramePrompt(segment.getFirstFramePrompt());child.setEndFramePrompt(segment.getEndFramePrompt());child.setNegativePrompt(segment.getNegativePrompt());
            child.setDirectorPlanJson(segment.getDirectorPlan()==null?null:write(segment.getDirectorPlan()));child.setDirectorPlanMode(segment.getDirectorPlan()==null?"AUTO":"MANUAL");child.setDirectorPlanRevision(0);child.setDirectorPlanInputHash(null);
            child.setPrompt(segment.getPrompt());child.setVideoPrompt(segment.getPrompt());
            // Endpoint anchors must also be explicitly attached to this segment.
            child.setPreviewImageUrl(segment.getFirstFrameImageUrl());child.setEndFrameImageUrl(segment.getLastFrameImageUrl());
            var childReq=new DramaShotRenderRequestDTO();childReq.setProviderId(provider.getId());childReq.setWorkflowTemplateId(model.getModelCode());childReq.setSize(size);childReq.setPrompt(segment.getPrompt());childReq.setSeed(1+Math.floorMod(shot.getSeed()+i*104729L,9007199254740990L));
            var options=new LinkedHashMap<String,Object>();options.put("schema_version",1);options.put("continuation_enabled",true);options.put("capability_revision",plan.getCapabilityRevision());options.put("length",segment.getEndFrame()-segment.getStartFrame());
            options.put("control_required_frames",Math.min(segment.getEndFrame(),plan.getOutputFrames())-segment.getStartFrame());
            options.put("context_windows",Map.of("enabled",draft.isContextWindows()));
            var control=new LinkedHashMap<String,Object>();control.put("enabled",segment.isControlNetEnabled());control.put("reference_id",segment.getControlVideoId());control.put("control_type",segment.getControlType());control.put("strength",segment.getControlStrength());options.put("control_net",control);childReq.setH3Options(options);
            if(!segment.getReferenceVideos().isEmpty()){
                var prepared=videoReferences.prepareSegment(child,model,segment.getReferenceVideos());childReq.setResolvedRefVideos(prepared.getReferences());childReq.setReferenceVideoRevision(prepared.getModel().getWorkflowRevision());childReq.setReferenceVideoNodeId(prepared.getModel().getNodeId());
                if(!Objects.equals(plan.getNodeId(),prepared.getModel().getNodeId()))throw new BizException(409,"片段素材与接续计划的算力节点不一致");
            }
            options.put("node_id",plan.getNodeId()); // Removed from transport; retained here to pin execution routing.
            var snap=new VideoBatchModels.Snapshot();snap.setShot(child);snap.setRequest(childReq);snap.setModelFingerprint(parent.getModelFingerprint());req.getContinuationSegments().add(snap);
        }
        return parent;
    }
    public Map<String,Object> controlOptions(DramaShot shot,AiModel model,AiProvider provider,ShotControlNetOptions control){
        var cap=capabilities(model,provider);var h3=cap.path("h3");
        if(!h3.path("controlNet").asBoolean()||!cap.path("healthy").asBoolean())throw new BizException(409,"ControlNet 所需节点或权重不可用，或算力节点离线");
        if(control.getControlType()==null||!ShotControlNetOptions.CONTROL_TYPES.contains(control.getControlType())||!Double.isFinite(control.getStrength())||control.getStrength()<0||control.getStrength()>2)throw new BizException(400,"ControlNet 类型或强度无效");
        if(h3.path("controlTypes").isArray()&&java.util.stream.StreamSupport.stream(h3.path("controlTypes").spliterator(),false).noneMatch(type->control.getControlType().equals(type.asText())))throw new BizException(409,"算力节点未安装 "+control.getControlType()+" 控制预处理器");
        var options=new LinkedHashMap<String,Object>();options.put("schema_version",1);options.put("continuation_enabled",false);options.put("capability_revision",h3.path("revision").asText());options.put("length",ShotContinuationPlanner.align((int)Math.round(shot.getDuration().doubleValue()*24)));options.put("node_id",cap.path("nodeId").asText());
        options.put("control_required_frames",(int)Math.round(shot.getDuration().doubleValue()*24));
        options.put("control_net",Map.of("enabled",true,"reference_id",control.getReferenceId(),"control_type",control.getControlType(),"strength",control.getStrength()));return options;
    }
    private DramaShot copy(DramaShot shot){return json.convertValue(shot,DramaShot.class);}
    private String write(Object value){return com.astra.freyja.util.VideoReferenceSnapshotJson.write(value,json);}
}
