package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.VoiceEffectMapper;
import com.astra.freyja.dto.voice.DubbingInputs.*;
import com.astra.freyja.entity.VoiceEffect;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class VoiceEffectService {
    public record Snapshot(String effectId,String name,Integer version,List<Step> steps) {}
    private final VoiceEffectMapper effects;
    private final VoiceEffectEngine engine;
    private final ObjectMapper json;

    public List<Map<String,Object>> filterCapabilities() { return engine.capabilities(); }

    public Page<VoiceEffect> page(int current,int size,String name,Boolean enabled) {
        return effects.selectPage(new Page<>(Math.max(1,current),Math.clamp(size,1,100)),new LambdaQueryWrapper<VoiceEffect>()
            .like(name!=null && !name.isBlank(),VoiceEffect::getName,name).eq(enabled!=null,VoiceEffect::getEnabled,Boolean.TRUE.equals(enabled)?1:0)
            .orderByDesc(VoiceEffect::getCreateTime).orderByDesc(VoiceEffect::getId));
    }
    public VoiceEffect save(EffectEdit input) {
        if(input.name()==null || input.name().isBlank() || input.name().length()>128 || input.enabled()==null || input.description()!=null && input.description().length()>1000) throw new BizException(400,"特效名称、描述或状态无效");
        var steps=engine.normalize(input.steps());
        if(input.steps().isEmpty() || input.steps().size()>12) throw new BizException(400,"特效模板需要1至12个处理步骤");
        VoiceEffect effect = new VoiceEffect(); effect.setName(input.name().trim()); effect.setDescription(input.description());
        effect.setStepsJson(write(steps)); effect.setEnabled(input.enabled()?1:0);
        if(input.id()==null) { effect.setVersion(1); effects.insert(effect); }
        else {
            VoiceEffect existing=effects.selectById(input.id());
            if(existing==null) throw new BizException(404,"特效不存在");
            if(!Objects.equals(existing.getVersion(),input.version())) throw new BizException(409,"特效已修改，请刷新");
            effect.setId(input.id()); effect.setVersion(existing.getVersion()+1);
            if(effects.update(effect,new LambdaUpdateWrapper<VoiceEffect>().eq(VoiceEffect::getId,input.id()).eq(VoiceEffect::getVersion,input.version()))!=1)
                throw new BizException(409,"特效已修改，请刷新");
        }
        return effects.selectById(effect.getId());
    }
    public List<Snapshot> snapshots(List<EffectSelection> selections) {
        return snapshots(selections,List.of());
    }
    public List<Snapshot> snapshots(List<EffectSelection> selections,List<Snapshot> previous) {
        if(selections==null) return List.of();
        if(selections.size()>6) throw new BizException(400,"一次最多选择6个特效");
        Set<Long> selected=new HashSet<>(); List<Snapshot> result=new ArrayList<>();
        for(EffectSelection selection:selections) {
            if(selection==null || selection.effectId()==null || !selected.add(selection.effectId())) throw new BizException(400,"特效不能为空或重复");
            Snapshot retained=previous.stream().filter(s -> s.effectId().equals(selection.effectId().toString()) && selection.steps()!=null
                && Objects.equals(engine.normalize(s.steps()),engine.normalize(selection.steps()))).findFirst().orElse(null);
            if(retained!=null) { result.add(retained); continue; }
            VoiceEffect preset=effects.selectById(selection.effectId());
            if(preset==null || !Integer.valueOf(1).equals(preset.getEnabled())) throw new BizException(400,"特效不存在或已停用");
            List<Step> steps=selection.steps()==null?readSteps(preset.getStepsJson()):selection.steps();
            if(steps.isEmpty()) throw new BizException(400,"特效不能没有处理步骤");
            if(steps.size()>12) throw new BizException(400,"每个特效最多12个处理步骤");
            steps=engine.normalize(steps);
            result.add(new Snapshot(preset.getId().toString(),preset.getName(),preset.getVersion(),steps));
        }
        engine.validate(flatten(result));
        return List.copyOf(result);
    }
    public List<Snapshot> readSnapshots(String value) {
        try { return json.readValue(value,new TypeReference<>(){}); }
        catch(Exception e) { throw new BizException(500,"配音特效配置损坏"); }
    }
    public List<Step> readSteps(String value) {
        try { return json.readValue(value,new TypeReference<>(){}); }
        catch(Exception e) { throw new BizException(400,"特效处理步骤必须为有效数组"); }
    }
    public List<Step> flatten(List<Snapshot> snapshots) { return snapshots.stream().flatMap(s -> s.steps().stream()).toList(); }
    public String write(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new BizException(400,"配音数据无法序列化"); }
    }
}
