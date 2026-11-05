package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.util.ShotVideoReferenceValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic 1:1 source mapping. Materialization uses these absolute 24fps bounds. */
@Component
@RequiredArgsConstructor
public class ShotContinuationReferenceSplitter {
    private final ShotContinuationPlanner planner;
    private final ObjectMapper json;

    public ShotContinuationPlan split(ShotContinuation input, ShotRefVideoDTO source,
                                      BigDecimal measuredSeconds, List<String> targetIds) {
        if(source==null)throw new BizException(400,"请选择参考视频");
        ShotVideoReferenceValidator.validate(List.of(source));
        var draft=json.convertValue(input,ShotContinuation.class);
        var plan=planner.plan(draft,false);
        if(!draft.isEnabled())throw new BizException(400,"请先开启接续模式");
        if(measuredSeconds==null||measuredSeconds.signum()<=0)throw new BizException(400,"源视频无有效时长");
        BigDecimal sourceStart=source.getStartSeconds()==null?BigDecimal.ZERO:source.getStartSeconds();
        BigDecimal sourceEnd=source.getEndSeconds()==null?measuredSeconds:source.getEndSeconds();
        if(sourceStart.compareTo(sourceEnd)>=0||sourceEnd.compareTo(measuredSeconds)>0)throw new BizException(400,"所选源范围超过视频实际时长");
        int origin=sourceStart.multiply(BigDecimal.valueOf(24)).setScale(0,RoundingMode.HALF_UP).intValueExact();
        int availableEnd=sourceEnd.multiply(BigDecimal.valueOf(24)).setScale(0,RoundingMode.FLOOR).intValueExact();
        Set<String> selected=targetIds==null||targetIds.isEmpty()?new HashSet<>(draft.getSegments().stream().map(ShotContinuation.Segment::getId).toList()):new HashSet<>(targetIds);
        Set<String> known=new HashSet<>(draft.getSegments().stream().map(ShotContinuation.Segment::getId).toList());
        if(!known.containsAll(selected))throw new BizException(400,"目标片段已变化，请刷新分段计划");
        for(var segment:draft.getSegments())if(selected.contains(segment.getId())){
            int start=origin+segment.getStartFrame(),end=origin+Math.min(segment.getEndFrame(),plan.getOutputFrames());
            if(end>availableEnd)throw new BizException(400,"参考视频不足以覆盖片段 "+segment.getId()+"，请缩短目标时长或调整源范围；不会自动循环或拉伸");
            var binding=json.convertValue(source,ShotRefVideoDTO.class);
            binding.setId(UUID.nameUUIDFromBytes(("continuation-video|"+source.getId()+"|"+segment.getId()).getBytes(StandardCharsets.UTF_8)).toString());
            binding.setSourceStartFrame(start);binding.setSourceEndFrame(end);
            binding.setStartSeconds(seconds(start));binding.setEndSeconds(seconds(end));
            var refs=segment.getReferenceVideos();int existing=-1;
            for(int i=0;i<refs.size();i++)if(binding.getId().equals(refs.get(i).getId()))existing=i;
            if(existing<0){if(refs.size()>=3)throw new BizException(400,"目标片段参考视频已满，请移除部分视频后再切分");refs.add(binding);}else refs.set(existing,binding);
        }
        var sources=draft.getVideoSources();
        sources.removeIf(video->source.getId().equals(video.getId()));
        var stored=json.convertValue(source,ShotRefVideoDTO.class);stored.setStartSeconds(seconds(origin));stored.setEndSeconds(sourceEnd);
        sources.add(stored);
        // Revalidate all mutations before returning; the caller's original draft is never modified.
        return planner.plan(draft,false);
    }

    private BigDecimal seconds(int frame){return BigDecimal.valueOf(frame).divide(BigDecimal.valueOf(24),12,RoundingMode.HALF_UP);}
}
