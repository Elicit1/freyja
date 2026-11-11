package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.director.model.DirectorPlanSchema;
import com.astra.freyja.util.ShotImageReferenceValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Exact selections shared by REST and MCP. No inferred assets or silently repaired choices. */
@Service @RequiredArgsConstructor
public class ShotContinuationSelections {
    private final DictService dictionaries;
    private final ResCharacterMapper characters;
    private final ResCharacterLookMapper looks;
    private final ResSceneMapper scenes;
    private final ResPropMapper props;
    private final ResKeyframeMapper keyframes;
    private final DubbingService dubbing;
    private final ResAudioService audios;
    private final ShotVideoReferenceService videos;

    public List<Map<String,String>> dictionary(String type) {
        var values=dictionaries.listByType(type);
        return values==null?List.of():values.stream().filter(Objects::nonNull).filter(v->Integer.valueOf(1).equals(v.getStatus())&&v.getDictValue()!=null&&!v.getDictValue().isBlank())
                .map(v->Map.of("value",v.getDictValue(),"label",Objects.toString(v.getDictLabel(),v.getDictValue()))).toList();
    }
    private void choice(String value,Collection<String> allowed,String path) {
        if(value!=null&&!allowed.contains(value))throw new BizException(400,path+" 选项已失效或不可用，允许值="+allowed);
    }
    public void validate(DramaShot shot,ShotContinuation draft) {
        if(draft==null||draft.getSegments()==null)throw new BizException(400,"接续草稿及片段列表不能为空");
        if(draft.getSegments().size()>100)throw new BizException(400,"接续片段最多100个");
        if(!draft.isEnabled()){ordinaryControl(shot,draft);return;}
        var sizes=dictionary("shot_type").stream().map(v->v.get("value")).toList();
        var movements=dictionary("camera_movement").stream().map(v->v.get("value")).toList();
        if(draft.getVideoSources()!=null)videos.validateSources(shot,draft.getVideoSources());
        for(var segment:draft.getSegments()) {
            if(segment==null)throw new BizException(400,"片段不能为空");
            if(segment.getCharacterRefs()!=null&&segment.getCharacterRefs().size()>30||segment.getPropRefs()!=null&&segment.getPropRefs().size()>30||segment.getReferenceImages()!=null&&segment.getReferenceImages().size()>9||segment.getReferenceVideos()!=null&&segment.getReferenceVideos().size()>3||segment.getReferenceAudios()!=null&&segment.getReferenceAudios().size()>3)throw new BizException(400,"片段素材数量超过选项上限");
            String path="片段 "+segment.getId();
            if(segment.getResSceneId()!=null&&segment.getResKeyframeId()!=null)throw new BizException(400,path+" 的环境场景与关键图参考只能选择一个");
            choice(segment.getShotType(),sizes,path+".shotType");choice(segment.getCameraMovement(),movements,path+".cameraMovement");
            segment.controlOptions().validateSources(segment.getReferenceVideos());
            if(segment.getResSceneId()!=null)asset(scenes.selectById(segment.getResSceneId()),shot);
            if(segment.getResKeyframeId()!=null)asset(keyframes.selectById(segment.getResKeyframeId()),shot);
            if(segment.getCharacterRefs()!=null)for(var ref:segment.getCharacterRefs()) {
                if(ref==null||ref.getCharacterId()==null)throw new BizException(400,"请选择有效角色");
                asset(characters.selectById(ref.getCharacterId()),shot);
                if(ref.getLookId()!=null){var look=looks.selectById(ref.getLookId());asset(look,shot);if(!ref.getCharacterId().equals(look.getCharacterId()))throw new BizException(400,"造型不属于所选角色");}
            }
            if(segment.getPropRefs()!=null)for(var ref:segment.getPropRefs()) {
                if(ref==null||ref.getPropId()==null)throw new BizException(400,"请选择有效道具");asset(props.selectById(ref.getPropId()),shot);
            }
            ShotImageReferenceValidator.validate(segment.getReferenceImages());
            if(segment.getReferenceImages()!=null)for(var image:segment.getReferenceImages()) {
                if(image.getSourceId()!=null)switch(image.getSourceType()) {
                    case "SCENE"->asset(scenes.selectById(image.getSourceId()),shot);
                    case "CHARACTER"->{asset(characters.selectById(image.getSourceId()),shot);if(image.getCharacterId()!=null&&!image.getCharacterId().equals(image.getSourceId()))throw new BizException(400,"人物参考图与所选角色不一致");}
                    case "CHARACTER_REFERENCE","CHARACTER_LOOK"->{var look=looks.selectById(image.getSourceId());asset(look,shot);if(!Objects.equals(image.getCharacterId(),look.getCharacterId())||image.getLookId()!=null&&!image.getLookId().equals(look.getId()))throw new BizException(400,"人物参考图不属于指定角色或造型");}
                    case "PROP"->asset(props.selectById(image.getSourceId()),shot);
                    case "KEYFRAME"->asset(keyframes.selectById(image.getSourceId()),shot);
                    default->throw new BizException(400,"上传图片不能声明资产来源ID");
                }
                if(image.getCharacterId()!=null)asset(characters.selectById(image.getCharacterId()),shot);
                if(image.getLookId()!=null){var look=looks.selectById(image.getLookId());asset(look,shot);if(!Objects.equals(image.getCharacterId(),look.getCharacterId()))throw new BizException(400,"图片造型不属于所选角色");}
            }
            if(segment.getReferenceVideos()!=null)videos.validateSources(shot,segment.getReferenceVideos());
            if(segment.getReferenceAudios()!=null){if(segment.getReferenceAudios().stream().anyMatch(Objects::isNull))throw new BizException(400,"参考音频不能为空");audios.validateReferences(shot.getDramaId(),segment.getReferenceAudios());dubbing.validateReferences(shot.getDramaId(),segment.getReferenceAudios());}
            var director=segment.getDirectorPlan();
            if(director!=null){
                choice(director.getShotSize(),DirectorPlanSchema.SHOT_SIZES,path+".directorPlan.shotSize");choice(director.getCameraAngle(),DirectorPlanSchema.CAMERA_ANGLES,path+".directorPlan.cameraAngle");
                if(director.getCameraBeats()!=null)for(var beat:director.getCameraBeats()) {if(beat==null)throw new BizException(400,"导演节拍不能为空");choice(beat.getMovement(),DirectorPlanSchema.MOVEMENTS,path+".movement");choice(beat.getSpeed(),DirectorPlanSchema.SPEEDS,path+".speed");}
            }
        }
    }
    private void ordinaryControl(DramaShot shot,ShotContinuation draft){
        var control=draft.getControlNet();
        if(control!=null){
            if(control.isActive()&&!"REFERENCE_MODE".equals(shot.getGenerationMode()))throw new BizException(400,"ControlNet 仅支持参考生成模式");
            control.validateSources(com.astra.freyja.util.ShotVideoReferenceValidator.parse(shot.getRefVideosJson(),new com.fasterxml.jackson.databind.ObjectMapper()));
        }
    }

    private void asset(BaseEntity entity,DramaShot shot) {
        if(entity==null)throw new BizException(400,"资产选项已删除或不存在，请重新选择");
        Long project;Integer status;
        switch(entity){
            case ResCharacter v->{project=v.getDramaId();status=v.getStatus();}
            case ResScene v->{project=v.getDramaId();status=v.getStatus();}
            case ResProp v->{project=v.getDramaId();status=v.getStatus();}
            case ResKeyframe v->{project=v.getDramaId();status=v.getStatus();if(v.getShotId()!=null&&!v.getShotId().equals(shot.getId()))throw new BizException(400,"关键帧属于其他镜头");}
            case ResCharacterLook v->{var c=characters.selectById(v.getCharacterId());asset(c,shot);project=c.getDramaId();status=v.getStatus();}
            default->throw new BizException(400,"不支持的资产选项");
        }
        if(!Integer.valueOf(1).equals(status)||project!=null&&project!=0&&!project.equals(shot.getDramaId()))throw new BizException(400,"资产已停用或不属于当前项目");
    }
}
