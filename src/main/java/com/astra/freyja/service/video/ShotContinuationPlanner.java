package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Pure frame arithmetic. No prose inference or mutable asset resolution. */
@Component
public class ShotContinuationPlanner {
    public record SegmentPreset(String id, String label, int segmentFrames, int overlapFrames) {}
    public List<SegmentPreset> presets() {
        return List.of(new SegmentPreset("5s", "5 秒 · 省显存", 124, 22),
                new SegmentPreset("8s", "8 秒", 192, 39),
                new SegmentPreset("10s", "10 秒", 243, 39),
                new SegmentPreset("15s", "15 秒", 362, 39));
    }
    public ShotContinuationPlan plan(ShotContinuation draft, boolean auto) {
        if (draft == null || draft.getSchemaVersion()!=1 || !Double.isFinite(draft.getDurationSeconds())
                || draft.getDurationSeconds()<=0 || draft.getDurationSeconds()>300)
            throw new BizException(400,"接续时长应大于 0 且不超过 300 秒");
        if (!draft.isEnabled() && draft.getDurationSeconds()>15) throw new BizException(400,"超过 15 秒必须开启接续模式");
        if(draft.getVideoSources()==null||draft.getVideoSources().size()>20)throw new BizException(400,"待分配视频最多 20 个");
        com.astra.freyja.util.ShotVideoReferenceValidator.validate(draft.getVideoSources());
        int length=draft.getSegmentFrames(), overlap=draft.getOverlapFrames();
        if(length<124||length>362||(length-5)%17!=0||overlap<5||(overlap-5)%17!=0||overlap>=length)
            throw new BizException(400,"段长/重叠必须符合 H3 帧网格，段长 124～362，重叠小于段长");
        int output=(int)Math.round(draft.getDurationSeconds()*24), total=align(output);
        if(output<1)throw new BizException(400,"目标时长不足一帧");
        var old=draft.getSegments()==null?List.<ShotContinuation.Segment>of():draft.getSegments();
        if(auto||old.isEmpty()) {
            List<ShotContinuation.Segment> next=new ArrayList<>();
            for(int start=0,index=0;start<total;index++) {
                int end=Math.min(start+length,total);
                if(end-start<124 && start>0){start=Math.max(0,total-124);start-=start%17;end=total;}
                final int s=start,e=end;
                var segment=old.stream().filter(x->x.getStartFrame()==s&&x.getEndFrame()==e).findFirst().orElse(null);
                if(segment==null){segment=new ShotContinuation.Segment();segment.setId(UUID.randomUUID().toString());segment.setStartFrame(start);segment.setEndFrame(end);}
                next.add(segment);if(end==total)break;start=end-overlap;
            }
            // Never silently orphan existing material or locked edits during automatic replanning.
            if(old.stream().anyMatch(s->!next.contains(s)&&(s.isLocked()||!s.getReferenceImages().isEmpty()||!s.getReferenceVideos().isEmpty()||s.getFirstFrameImageUrl()!=null||s.getLastFrameImageUrl()!=null)))
                throw new BizException(409,"重新拆分会影响已锁定片段或素材挂载，请先手动调整或清空对应绑定");
            draft.setSegments(next);
        }
        var result=new ShotContinuationPlan();result.setDraft(draft);result.setOutputFrames(output);result.setRenderFrames(total);
        Set<String> ids=new HashSet<>();int previousEnd=0,keepCursor=0;
        for(int i=0;i<draft.getSegments().size();i++) {
            var segment=draft.getSegments().get(i);int start=segment.getStartFrame(),end=segment.getEndFrame(),frames=end-start;
            if(segment.getFirstFrameImageUrl()!=null&&segment.getFirstFrameImageUrl().isBlank())segment.setFirstFrameImageUrl(null);
            if(segment.getLastFrameImageUrl()!=null&&segment.getLastFrameImageUrl().isBlank())segment.setLastFrameImageUrl(null);
            if(segment.getId()==null||!ids.add(segment.getId())||start<0||start%17!=0||frames<22||frames>length||(frames-5)%17!=0
                    || (i==0?start!=0:start>=previousEnd||end<=previousEnd)||end>total)
                throw new BizException(400,"片段范围须连续覆盖、有真实重叠，且满足 H3 帧数与起点约束");
            int shared=i==0?0:previousEnd-start;
            if(i>0&&(shared<5||(shared-5)%17!=0))throw new BizException(400,"共享上下文必须符合 17k+5 帧约束");
            if(segment.getReferenceImages()==null||segment.getReferenceVideos()==null||segment.getReferenceImages().size()>9||segment.getReferenceVideos().size()>3)
                throw new BizException(400,"片段参考图最多 9 张、参考视频最多 3 段");
            Set<String> imageIds=new HashSet<>();for(var image:segment.getReferenceImages()) {
                if(image==null||image.getId()==null||!imageIds.add(image.getId())||image.getImageUrl()==null||image.getImageUrl().isBlank()||"ASSET_LINK".equals(image.getBindingMode()))
                    throw new BizException(400,"请给片段挂载有效且固定的图片地址");
            }
            com.astra.freyja.util.ShotVideoReferenceValidator.validate(segment.getReferenceVideos());
            if(!Double.isFinite(segment.getControlStrength())||segment.getControlStrength()<0||segment.getControlStrength()>2)
                throw new BizException(400,"ControlNet 强度应在 0～2 范围内");
            if(segment.isControlNetEnabled()) {
                if(segment.getReferenceVideos().isEmpty())throw new BizException(400,"开启 ControlNet 的片段必须挂载参考视频");
                if(segment.getReferenceVideos().size()==1&&segment.getControlVideoId()==null)segment.setControlVideoId(segment.getReferenceVideos().getFirst().getId());
                if(segment.getReferenceVideos().stream().noneMatch(v->Objects.equals(v.getId(),segment.getControlVideoId())))throw new BizException(400,"请选择本段 ControlNet 视频源");
                if(!Set.of("CANNY","POSE","DEPTH","PREPARED").contains(segment.getControlType()))throw new BizException(400,"ControlNet 控制类型无效");
            }
            var range=new ShotContinuationPlan.Range();range.setSegmentId(segment.getId());range.setStartFrame(start);range.setEndFrame(end);
            range.setOverlapFrames(shared);range.setKeepStartFrame(keepCursor);range.setKeepEndFrame(Math.min(end,output));result.getRanges().add(range);
            keepCursor=Math.min(end,output);previousEnd=end;
        }
        if(previousEnd!=total||draft.getSegments().size()>100)throw new BizException(400,"片段必须完整覆盖目标时长，且不超过 100 段");
        result.setSegmentCount(draft.getSegments().size());
        try{result.setPlanHash(VideoBatchPreparation.hash(new ObjectMapper().writeValueAsString(draft)));}catch(Exception e){throw new IllegalStateException(e);}
        return result;
    }
    public static int align(int n){return n+Math.floorMod(5-n,17);}
}
