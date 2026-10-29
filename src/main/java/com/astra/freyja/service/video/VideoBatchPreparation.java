package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.VideoBatchModels.*;
import com.astra.freyja.util.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VideoBatchPreparation {
    private final com.astra.freyja.service.ShotVideoReferenceService videoReferences;
    private final com.astra.freyja.service.ShotVideoCapabilityService videoCapabilities;
    private final DramaShotMapper shots;
    private final DramaMapper dramas;
    private final DramaEpisodeMapper episodes;
    private final DramaSceneMapper scenes;
    private final DramaShotGroupMapper groups;
    private final AiModelMapper models;
    private final AiProviderMapper providers;
    private final ResKeyframeMapper keyframes;
    private final RenderTaskMapper renders;
    private final VideoBatchStore store;
    private final ObjectMapper json;
    public static final String REF="REFERENCE_MODE", FL="FIRST_LAST_FRAME";
    public static final Map<String,String> SIZES=Map.of("16:9","960x544","9:16","544x960","1:1","768x768","4:3","1024x768","3:4","768x1024","3:2","1152x768","2:3","768x1152");

    public List<DramaShot> orderedShots(Long dramaId) {
        if(dramaId==null || dramas.selectById(dramaId)==null) throw new BizException(404,"短剧不存在");
        var ep=episodes.selectList(new LambdaQueryWrapper<DramaEpisode>().eq(DramaEpisode::getDramaId,dramaId)).stream().collect(Collectors.toMap(DramaEpisode::getId,Function.identity()));
        var sc=scenes.selectList(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getDramaId,dramaId)).stream().collect(Collectors.toMap(DramaScene::getId,Function.identity()));
        var gr=groups.selectList(new LambdaQueryWrapper<DramaShotGroup>().eq(DramaShotGroup::getDramaId,dramaId)).stream().collect(Collectors.toMap(DramaShotGroup::getId,Function.identity()));
        List<DramaShot> result=new ArrayList<>(shots.selectActiveByDramaId(dramaId));
        result.sort(Comparator.comparingInt((DramaShot s)->ep.containsKey(s.getEpisodeId())?zero(ep.get(s.getEpisodeId()).getSortOrder()):0)
                .thenComparing(DramaShot::getEpisodeId).thenComparingInt(s->sc.containsKey(s.getSceneId())?zero(sc.get(s.getSceneId()).getSortOrder()):0)
                .thenComparing(DramaShot::getSceneId).thenComparingInt(s->gr.containsKey(s.getShotGroupId())?zero(gr.get(s.getShotGroupId()).getSortOrder()):0)
                .thenComparing(s->s.getShotGroupId(),Comparator.nullsFirst(Long::compareTo)).thenComparing(shotOrder()));
        return result;
    }
    public static Comparator<DramaShot> shotOrder() { return Comparator.comparingInt((DramaShot s)->zero(s.getSortOrder())).thenComparingInt(s->zero(s.getShotNo())).thenComparing(DramaShot::getId); }
    private static int zero(Integer v) { return v==null?0:v; }

    public List<Node> tree(Long dramaId) {
        var all=orderedShots(dramaId);
        Map<String,Node> nodes=new LinkedHashMap<>(); List<Node> roots=new ArrayList<>();
        var ep=episodes.selectList(new LambdaQueryWrapper<DramaEpisode>().eq(DramaEpisode::getDramaId,dramaId)).stream().collect(Collectors.toMap(DramaEpisode::getId,DramaEpisode::getTitle));
        var sc=scenes.selectList(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getDramaId,dramaId)).stream().collect(Collectors.toMap(DramaScene::getId,DramaScene::getName));
        var gr=groups.selectList(new LambdaQueryWrapper<DramaShotGroup>().eq(DramaShotGroup::getDramaId,dramaId)).stream().collect(Collectors.toMap(DramaShotGroup::getId,DramaShotGroup::getName));
        for(var s:all) {
            Node e=node(nodes,"EPISODE",s.getEpisodeId(),ep.get(s.getEpisodeId()),roots);
            Node c=node(nodes,"SCENE",s.getSceneId(),sc.get(s.getSceneId()),e.getChildren());
            Node g=s.getShotGroupId()==null?c:node(nodes,"SHOT_GROUP",s.getShotGroupId(),gr.get(s.getShotGroupId()),c.getChildren());
            node(nodes,"SHOT",s.getId(),"S"+s.getShotNo()+" · "+StringUtils.defaultString(s.getShotName(),"分镜"),g.getChildren());
        }
        return roots;
    }
    private Node node(Map<String,Node> nodes,String type,Long id,String label,List<Node> parent) {
        return nodes.computeIfAbsent(type+":"+id,key->{Node n=new Node();n.setKey(key);n.setType(type);n.setId(id);n.setLabel(StringUtils.defaultIfBlank(label,type));parent.add(n);return n;});
    }

    public Preview preview(VideoRenderBatchRequest req, boolean lock) {
        if(req==null || !SIZES.containsKey(StringUtils.defaultString(req.getAspectRatio()))) throw new BizException(400,"请选择有效的统一画面比例");
        String batchSize=req.getSize()==null?SIZES.get(req.getAspectRatio()):normalizeSize(req.getSize());
        if(!Set.of("SKIP","CREATE_NEW_TAKE").contains(req.getExistingVideoPolicy())) throw new BizException(400,"生成对象选项无效");
        List<DramaShot> all=orderedShots(req.getDramaId()); Set<Long> selected=new HashSet<>();
        if(req.getShotIds()!=null) selected.addAll(req.getShotIds());
        else if(req.getSelection()!=null && req.getSelection().getInclude()!=null) {
            for(var scope:req.getSelection().getInclude()) {
                if(scope==null || scope.getType()==null) throw new BizException(400,"选择范围无效");
                List<DramaShot> matches=all.stream().filter(s->switch(scope.getType()) {
                    case "ALL"->true;case "EPISODE"->Objects.equals(s.getEpisodeId(),scope.getId());case "SCENE"->Objects.equals(s.getSceneId(),scope.getId());
                    case "SHOT_GROUP"->scope.getId()!=null&&Objects.equals(s.getShotGroupId(),scope.getId());case "SHOT"->Objects.equals(s.getId(),scope.getId());
                    default->throw new BizException(400,"选择范围类型无效");}).toList();
                if(matches.isEmpty()&&!"ALL".equals(scope.getType())) throw new BizException(400,"选择范围不存在或不属于当前短剧");
                matches.forEach(s->selected.add(s.getId()));
            }
            if(req.getSelection().getExcludeShotIds()!=null) selected.removeAll(req.getSelection().getExcludeShotIds());
        }
        if(selected.isEmpty()||selected.size()>200) throw new BizException(400,"请选择 1～200 个分镜");
        Set<Long> existing=all.stream().map(DramaShot::getId).collect(Collectors.toSet());
        if(!existing.containsAll(selected)) throw new BizException(400,"分镜不存在或不属于当前短剧");
        if(lock) {
            // Also lock neighboring source records before freezing their video versions.
            for(Long id:all.stream().map(DramaShot::getId).sorted().toList()) shots.lockById(id);
            all=orderedShots(req.getDramaId());
        }
        List<Candidate> candidates=new ArrayList<>();
        for(var s:all) if(selected.contains(s.getId())) {
            Candidate c=new Candidate(); c.setShotId(s.getId()); c.setName("S"+s.getShotNo()+" · "+StringUtils.defaultString(s.getShotName(),"分镜"));
            c.setContext("剧集 "+s.getEpisodeId()+" / 场景 "+s.getSceneId());c.setGenerationMode(s.getGenerationMode());c.setStatus("READY");
            try {
                if(store.occupied(s.getId()) || renders.selectCount(new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getShotId,s.getId()).eq(RenderTask::getTaskType,"SHOT_VIDEO").in(RenderTask::getStatus,"QUEUED","RENDERING"))>0) {
                    c.setStatus("SKIPPED");c.setReason("已有排队或渲染中任务");
                } else if("SKIP".equals(req.getExistingVideoPolicy())&&StringUtils.isNotBlank(s.getVideoUrl())) {
                    c.setStatus("SKIPPED");c.setReason("已有当前视频");
                } else {
                    Snapshot snap=prepare(s,req.getModeConfigs()==null?null:req.getModeConfigs().get(s.getGenerationMode()),batchSize,req.getAspectRatio(),null);
                    c.setSnapshot(snap);c.setSize(snap.getRequest().getSize());
                }
            } catch(BizException e) {c.setStatus("BLOCKED");c.setReason(e.getMessage());}
            candidates.add(c);
        }
        // Stable partition: narrative order remains unchanged within each phase.
        candidates.sort(Comparator.comparingInt(c->REF.equals(c.getGenerationMode())?0:1));
        Map<Long,Candidate> accepted=candidates.stream().filter(c->"READY".equals(c.getStatus())).collect(Collectors.toMap(Candidate::getShotId,Function.identity()));
        int order=0; double duration=0;
        for(var c:candidates) c.setExecutionOrder(++order);
        for(var c:candidates) if(c.getSnapshot()!=null) {
            var s=c.getSnapshot().getShot();var cfg=req.getModeConfigs().get(s.getGenerationMode());
            if(FL.equals(s.getGenerationMode())) {
                List<DramaShot> siblings=all.stream().filter(x->s.getShotGroupId()!=null&&Objects.equals(x.getShotGroupId(),s.getShotGroupId())).sorted(shotOrder()).toList();
                int index=-1;for(int i=0;i<siblings.size();i++) if(siblings.get(i).getId().equals(s.getId())) index=i;
                DramaShot prev=index>0?siblings.get(index-1):null,next=index>=0&&index+1<siblings.size()?siblings.get(index+1):null;
                c.getSnapshot().setFirstFramePlan(plan(s.getPreviewImageUrl(),cfg.isAutoUsePreviousVideoTailAsFirstFrame(),prev,accepted,c.getExecutionOrder(),"LAST"));
                c.getSnapshot().setLastFramePlan(plan(s.getEndFrameImageUrl(),cfg.isAutoUseNextVideoHeadAsLastFrame(),next,accepted,c.getExecutionOrder(),"FIRST"));
            }
            c.setFirstFrameSource(label(c.getSnapshot().getFirstFramePlan()));c.setLastFrameSource(label(c.getSnapshot().getLastFramePlan()));
            duration+=s.getDuration()==null?0:s.getDuration().doubleValue();
        }
        Preview p=new Preview();p.setShotIds(all.stream().map(DramaShot::getId).filter(selected::contains).toList());p.setItems(candidates);
        p.setAccepted(accepted.size());p.setSkipped((int)candidates.stream().filter(c->"SKIPPED".equals(c.getStatus())).count());p.setBlocked(candidates.size()-p.getAccepted()-p.getSkipped());p.setDuration(duration);
        // Hash full internal snapshots too; @JsonIgnore keeps them out of the public preview.
        p.setPreviewHash(hash(write(List.of(req.getAspectRatio(),batchSize,req.getExistingVideoPolicy(),req.getModeConfigs()==null?Map.of():new TreeMap<>(req.getModeConfigs()),candidates,candidates.stream().map(c->c.getSnapshot()==null?c.getReason():write(c.getSnapshot())).toList()))));
        return p;
    }

    public static String normalizeSize(String value) {
        String normalized=StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT).replace('*','x').replace('×','x');
        if(!normalized.matches("[0-9]{1,9}x[0-9]{1,9}"))throw new BizException(400,"视频尺寸格式应为宽x高，例如 1344x768");
        String[] wh=normalized.split("x");int width=Integer.parseInt(wh[0]),height=Integer.parseInt(wh[1]);
        if(width<32||height<32||width%32!=0||height%32!=0)throw new BizException(400,"视频宽高必须至少为 32，且为 32 像素的整数倍");
        return width+"x"+height;
    }

    public Snapshot prepare(DramaShot shot,VideoRenderBatchRequest.ModeConfig cfg,String size,String ratio,DramaShotRenderRequestDTO supplied) {
        if(!Set.of(REF,FL).contains(StringUtils.defaultString(shot.getGenerationMode()))) throw new BizException(400,"请明确选择分镜生成模式");
        AiModel model=cfg==null?null:models.selectById(cfg.getModelId());
        if(cfg==null||model==null||!Objects.equals(model.getProviderId(),cfg.getProviderId())||!Objects.equals(model.getStatus(),1)||!Objects.equals(model.getModelType(),REF.equals(shot.getGenerationMode())?"TXT2VIDEO_REF":"TXT2VIDEO_FIRST_LAST")) throw new BizException(400,"请选择此模式下已启用的提供商和视频模型");
        AiProvider provider=providers.selectById(cfg.getProviderId());
        if(provider==null||!Objects.equals(provider.getStatus(),1)||StringUtils.isBlank(provider.getBaseUrl())) throw new BizException(400,"提供商未启用或缺少服务地址");
        String prompt=ShotRenderPromptUtil.resolve(shot.getGenerationMode(),shot.getPrompt(),shot.getVideoPrompt(),supplied==null?null:supplied.getPrompt());
        if(StringUtils.isBlank(prompt)) throw new BizException(400,"请先保存视频提示词");
        if(shot.getSeed()==null||shot.getSeed()<=0||shot.getSeed()>ShotSeedUtil.MAX_SEED) throw new BizException(400,"请先配置有效的镜头种子");
        if(shot.getDuration()==null||shot.getDuration().signum()<=0||shot.getDuration().doubleValue()>15) throw new BizException(400,"镜头时长应在 0～15 秒内");
        var images=KeyImageReferenceValidator.parseAndValidate(shot.getRefImagesJson(),json,id->{var k=keyframes.selectById(id);return k==null?null:k.getFrameType();});
        List<ShotRefAudioDTO> audios=readAudios(shot.getRefAudiosJson());ShotAudioReferenceValidator.validate(audios);
        var videos=com.astra.freyja.util.ShotVideoReferenceValidator.parse(shot.getRefVideosJson(),json);
        if(!videos.isEmpty()&&!REF.equals(shot.getGenerationMode()))throw new BizException(400,"参考视频只适用于参考生成模式，请切换模式或清空视频草稿");
        if(model.getMaxImages()!=null&&images.size()>model.getMaxImages()) throw new BizException(400,"参考图数量超过模型上限");
        if(model.getMaxAudios()!=null&&audios.size()>model.getMaxAudios()) throw new BizException(400,"参考音频数量超过模型上限");
        if(REF.equals(shot.getGenerationMode())&&!audios.isEmpty()&&images.isEmpty()&&videos.isEmpty()) throw new BizException(400,"参考音频不能作为唯一参考模态");
        if(size==null||!size.matches("[0-9]+x[0-9]+")) throw new BizException(400,"视频尺寸无效");
        String[] wh=size.split("x");if(Integer.parseInt(wh[0])%32!=0||Integer.parseInt(wh[1])%32!=0||Integer.parseInt(wh[0])<32||Integer.parseInt(wh[1])<32) throw new BizException(400,"视频尺寸需按 32 像素对齐");
        if(StringUtils.isNotBlank(model.getParamsJson())){
            try{
                var capabilities=json.readTree(model.getParamsJson()).path("videoGeneration");
                var ratios=capabilities.path("supportedAspectRatios");
                if(ratio!=null&&ratios.isArray()&&java.util.stream.StreamSupport.stream(ratios.spliterator(),false).noneMatch(n->ratio.equals(n.asText())))throw new BizException(400,"所选模型不支持当前画面比例");
                var sizes=capabilities.path("supportedSizes");
                if(sizes.isArray()&&java.util.stream.StreamSupport.stream(sizes.spliterator(),false).noneMatch(n->size.equals(n.asText())))throw new BizException(400,"所选模型不支持当前实际输出尺寸");
                if(capabilities.has("minDuration")&&shot.getDuration().doubleValue()<capabilities.path("minDuration").asDouble())throw new BizException(400,"镜头时长小于模型最小时长");
                if(capabilities.has("maxDuration")&&shot.getDuration().doubleValue()>capabilities.path("maxDuration").asDouble())throw new BizException(400,"镜头时长超过模型上限");
            }catch(BizException e){throw e;}catch(Exception e){throw new BizException(400,"模型视频能力配置无效");}
        }
        Snapshot snap=new Snapshot();snap.setShot(copy(shot));snap.setAspectRatio(ratio);snap.setModelFingerprint(fingerprint(model,provider));
        DramaShotRenderRequestDTO dto=new DramaShotRenderRequestDTO();dto.setProviderId(provider.getId());dto.setWorkflowTemplateId(model.getModelCode());dto.setSize(size);dto.setPrompt(prompt);dto.setSeed(shot.getSeed());
        if(!videos.isEmpty()) {
            var prepared=videoReferences.forSubmission(shot,model,supplied==null?null:supplied.getPreflightId());
            dto.setPreflightId(prepared.getPreflightId());dto.setResolvedRefVideos(prepared.getReferences());
            dto.setReferenceVideoRevision(prepared.getModel().getWorkflowRevision());dto.setReferenceVideoNodeId(prepared.getModel().getNodeId());
        }
        snap.setRequest(dto);return snap;
    }
    public void validateRoute(Snapshot snap) {
        var p=providers.selectById(snap.getRequest().getProviderId());
        var m=models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,snap.getRequest().getProviderId()).eq(AiModel::getModelCode,snap.getRequest().getWorkflowTemplateId()).last("LIMIT 1"));
        boolean hasVideos=snap.getRequest().getResolvedRefVideos()!=null&&!snap.getRequest().getResolvedRefVideos().isEmpty();
        boolean matches=m!=null&&p!=null&&(fingerprint(m,p).equals(snap.getModelFingerprint())||!hasVideos&&legacyFingerprint(m,p).equals(snap.getModelFingerprint()));
        if(m==null||p==null||!Objects.equals(m.getStatus(),1)||!Objects.equals(p.getStatus(),1)||!matches) throw new BizException(409,"提供商或模型配置已变更，请重新预检");
        if(snap.getRequest().getResolvedRefVideos()!=null&&!snap.getRequest().getResolvedRefVideos().isEmpty()) {
            var option=videoCapabilities.describe(m,p);
            videoReferences.validateSnapshot(snap.getRequest().getResolvedRefVideos(),option,snap.getShot());
            if(!Objects.equals(option.getNodeId(),snap.getRequest().getReferenceVideoNodeId())||!Objects.equals(option.getWorkflowRevision(),snap.getRequest().getReferenceVideoRevision()))throw new BizException(409,"REFERENCE_VIDEO_WORKFLOW_MISMATCH: 视频工作流版本已变化");
        }
    }
    private String fingerprint(AiModel m,AiProvider p) { return hash(write(Arrays.asList(m.getId(),m.getModelCode(),m.getModelType(),m.getParamsJson(),m.getMaxImages(),m.getMaxAudios(),m.getMaxVideos(),p.getId(),p.getBaseUrl()))); }
    private String legacyFingerprint(AiModel m,AiProvider p) { return hash(write(Arrays.asList(m.getId(),m.getModelCode(),m.getModelType(),m.getParamsJson(),m.getMaxImages(),m.getMaxAudios(),p.getId(),p.getBaseUrl()))); }
    private FramePlan plan(String configured,boolean enabled,DramaShot source,Map<Long,Candidate> accepted,int order,String frameType) {
        FramePlan p=new FramePlan();p.setFrameType(frameType);
        if(StringUtils.isNotBlank(configured)){p.setType("CONFIGURED");return p;}
        if(!enabled){p.setReason("自动引用已关闭");return p;}
        if(source==null){p.setReason("无相邻镜头");return p;}
        p.setSourceShotId(source.getId());p.setVideoUrl(source.getVideoUrl());p.setTakeId(source.getCurrentVideoTakeId());
        Candidate c=accepted.get(source.getId());
        if(c!=null&&c.getExecutionOrder()<order){p.setType("PRIOR_BATCH_RESULT");p.setSourceTaskId(String.valueOf(source.getId()));}
        else if(StringUtils.isNotBlank(source.getVideoUrl())) p.setType("EXISTING_VIDEO");
        else p.setReason(c!=null?"邻镜尚未渲染，不使用此帧":"邻镜没有视频");
        return p;
    }
    public static String label(FramePlan p) {return p==null?"不适用":switch(p.getType()){case "CONFIGURED"->"已配置";case "PRIOR_BATCH_RESULT"->"等待本批前序视频";case "EXISTING_VIDEO"->"邻镜已有视频";default->StringUtils.defaultIfBlank(p.getReason(),"不使用");};}
    private List<ShotRefAudioDTO> readAudios(String value) {try{return StringUtils.isBlank(value)?List.of():json.readValue(value,new TypeReference<List<ShotRefAudioDTO>>(){});}catch(Exception e){throw new BizException(400,"参考音频配置无效");}}
    public DramaShot copy(DramaShot s) {try{return json.readValue(json.writeValueAsString(s),DramaShot.class);}catch(Exception e){throw new IllegalStateException(e);}}
    public String write(Object o) {try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    public static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
