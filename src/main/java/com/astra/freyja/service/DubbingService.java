package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.voice.DubbingInputs.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.util.AfterCommitTask;
import com.astra.freyja.util.VoiceModelRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.Executor;

@Service
@Slf4j
@RequiredArgsConstructor
public class DubbingService {
    public record AudioView(CharacterVoiceAudio audio, List<VoiceEffectService.Snapshot> effects, String playbackUrl, Double duration, long references) {}
    public record CharacterPage(Page<Map<String,Object>> page, Long selectedId) {}
    private final CharacterVoiceAudioMapper audios;
    private final ResCharacterMapper characters;
    private final VoiceEffectService effects;
    private final VoiceEffectEngine engine;
    private final DubbingAudioStorage storage;
    private final AiAudioApiService tts;
    private final AiTaskService tasks;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    @Autowired @Qualifier("renderAsyncExecutor") private Executor executor;

    public CharacterPage characters(int current,int size,String name,String drama,Long locateId) {
        size=Math.clamp(size,1,50);
        if(locateId!=null) {
            Long position=audios.position(locateId);
            if(position==null) throw new BizException(404,"角色不存在或已合并");
            current=(int)((position-1)/size+1); name=null; drama=null;
        }
        Page<Map<String,Object>> page=audios.characters(new Page<>(Math.max(1,current),size),name,drama);
        // Map result columns do not get the entity Long serializer reliably; normalize IDs explicitly.
        for(var row:page.getRecords()) {
            for(String key:List.of("id","drama_id")) if(row.get(key)!=null) row.put(key,row.get(key).toString());
            if(row.get("has_base") instanceof Number count) row.put("has_base",count.intValue());
        }
        return new CharacterPage(page,locateId);
    }
    public CharacterVoiceAudio require(Long id) {
        CharacterVoiceAudio audio=audios.selectById(id);
        if(audio==null) throw new BizException(404,"配音不存在");
        return audio;
    }
    public Page<Map<String,Object>> pickerCharacters(Long dramaId,List<Long> characterIds,int current,int size,String name,Long preferredId) {
        validatePickerScope(dramaId,characterIds);
        Page<Map<String,Object>> page=audios.pickerCharacters(new Page<>(Math.max(1,current),Math.clamp(size,1,50)),dramaId,characterIds,name,preferredId);
        for(var row:page.getRecords()) {
            for(String key:List.of("id","drama_id")) if(row.get(key)!=null) row.put(key,row.get(key).toString());
            if(row.get("has_base") instanceof Number count) row.put("has_base",count.intValue());
        }
        return page;
    }
    private void validatePickerScope(Long dramaId,List<Long> characterIds) {
        if(dramaId==null || dramaId<=0 || characterIds==null || characterIds.isEmpty() || characterIds.size()>500
            || characterIds.stream().anyMatch(id -> id==null || id<=0)) throw new BizException(400,"请提供当前短剧和有效的角色集合");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM drama WHERE id=? AND deleted=0",Long.class,dramaId);
        if(count==null || count==0) throw new BizException(404,"短剧不存在");
    }
    public void requirePickerCharacter(Long dramaId,List<Long> characterIds,Long characterId) {
        validatePickerScope(dramaId,characterIds);
        ResCharacter character=characters.selectById(characterId);
        if(!characterIds.contains(characterId) || character==null
            || !Objects.equals(character.getDramaId(),dramaId) && !Long.valueOf(0).equals(character.getDramaId()))
            throw new BizException(403,"角色不属于当前短剧的可选范围");
    }
    public void validateReferences(Long dramaId,List<com.astra.freyja.dto.drama.ShotRefAudioDTO> references) {
        if(references==null) return;
        references.stream().filter(a -> a.getAudioId()!=null).map(com.astra.freyja.dto.drama.ShotRefAudioDTO::getCharacterId).distinct().sorted().forEach(this::lockCharacter);
        for(var reference:references) {
            if(reference.getAudioId()==null) continue;
            var audio=require(reference.getAudioId());
            var character=characters.selectById(audio.getCharacterId());
            if(!Objects.equals(reference.getCharacterId(),audio.getCharacterId()) || character==null
                || !Objects.equals(character.getDramaId(),dramaId) && !Long.valueOf(0).equals(character.getDramaId()))
                throw new BizException(403,"参考音频不属于当前短剧角色");
            if(!"READY".equals(audio.getStatus()) || !Objects.equals(view(audio).playbackUrl(),reference.getAudioUrl()))
                throw new BizException(409,"参考音频已变化或尚未就绪，请重新选择");
        }
    }
    public CharacterVoiceAudio base(Long characterId) {
        return audios.selectOne(new LambdaQueryWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getCharacterId,characterId).eq(CharacterVoiceAudio::getKind,"BASE"));
    }
    public AudioView baseView(Long characterId) {
        var base=base(characterId);
        if(base!=null && base.getRawAudioUrl()!=null && base.getRawDuration()==null) {
            try {
                Double duration=engine.measureDuration(storage.read(base.getRawAudioUrl()));
                if(duration!=null) {
                    audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,base.getId())
                        .eq(CharacterVoiceAudio::getRawAudioUrl,base.getRawAudioUrl()).set(CharacterVoiceAudio::getRawDuration,duration));
                    base.setRawDuration(duration);
                }
            } catch(BizException e) { log.debug("旧基础声音时长暂无法检测: audioId={}",base.getId()); }
        }
        return base==null?null:view(base);
    }
    public List<AudioView> replacements(Long characterId) {
        return audios.selectList(new LambdaQueryWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getCharacterId,characterId)
            .eq(CharacterVoiceAudio::getKind,"BASE_CANDIDATE").orderByDesc(CharacterVoiceAudio::getCreateTime)).stream().map(this::view).toList();
    }
    public Page<AudioView> page(Long characterId,int current,int size,List<String> emotions,List<String> effectIds,boolean includeBase) {
        var query=new LambdaQueryWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getCharacterId,characterId)
            .in(CharacterVoiceAudio::getKind,includeBase?List.of("BASE","CLONE"):List.of("CLONE"))
            .in(emotions!=null && !emotions.isEmpty(),CharacterVoiceAudio::getEmotion,emotions)
            .orderByDesc(CharacterVoiceAudio::getCreateTime).orderByDesc(CharacterVoiceAudio::getId);
        if(effectIds!=null && !effectIds.isEmpty()) {
            if(effectIds.size()>20) throw new BizException(400,"筛选特效数量过多");
            query.and(w -> {
                for(int i=0;i<effectIds.size();i++) {
                    String id=effectIds.get(i);
                    if(id==null || !id.matches("[1-9][0-9]{0,18}")) throw new BizException(400,"特效ID无效");
                    if(i>0) w.or();
                    w.apply("JSON_CONTAINS(effects_json, JSON_OBJECT('effectId', {0}))",id);
                }
            });
        }
        var page=audios.selectPage(new Page<>(Math.max(1,current),Math.clamp(size,1,50)),query);
        Page<AudioView> result=new Page<>(page.getCurrent(),page.getSize(),page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::view).toList()); return result;
    }
    public AudioView view(CharacterVoiceAudio audio) {
        if(Set.of("GENERATING","PROCESSING").contains(audio.getStatus()) && audio.getTaskId()!=null && tasks.isCancelled(audio.getTaskId())) {
            audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,audio.getId())
                .eq(CharacterVoiceAudio::getRevision,audio.getRevision()).set(CharacterVoiceAudio::getStatus,"CANCELLED"));
            audio.setStatus("CANCELLED");
        }
        List<VoiceEffectService.Snapshot> snapshots=effects.readSnapshots(audio.getEffectsJson());
        boolean processed=!snapshots.isEmpty() && "READY".equals(audio.getStatus()) && audio.getProcessedAudioUrl()!=null;
        return new AudioView(audio,snapshots,processed?audio.getProcessedAudioUrl():audio.getRawAudioUrl(),processed?audio.getProcessedDuration():audio.getRawDuration(),references(audio));
    }
    public long references(CharacterVoiceAudio audio) {
        if(audio.getRawAudioUrl()==null && audio.getProcessedAudioUrl()==null) return 0;
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM drama_shot WHERE deleted=0 AND (JSON_CONTAINS(JSON_EXTRACT(IF(JSON_VALID(ref_audios_json),ref_audios_json,'[]'),'$[*].audioUrl'),JSON_QUOTE(?)) OR JSON_CONTAINS(JSON_EXTRACT(IF(JSON_VALID(ref_audios_json),ref_audios_json,'[]'),'$[*].audioUrl'),JSON_QUOTE(?)))",Long.class,audio.getRawAudioUrl(),audio.getProcessedAudioUrl());
        return count==null?0:count;
    }
    private ResCharacter lockCharacter(Long characterId) {
        if(audios.lockCharacter(characterId)==null) throw new BizException(404,"角色不存在");
        return characters.selectById(characterId);
    }
    private CharacterVoiceAudio locked(Long id,Integer revision) {
        CharacterVoiceAudio initial=require(id); lockCharacter(initial.getCharacterId());
        CharacterVoiceAudio current=audios.lock(id);
        if(current==null) throw new BizException(404,"配音已删除");
        if(revision!=null && !revision.equals(current.getRevision())) throw new BizException(409,"配音已修改，请刷新后重试");
        return current;
    }
    private void validateCreate(Create input) {
        if(input==null || input.name()==null || input.name().isBlank() || input.name().length()>128)
            throw new BizException(400,"配音名称无效");
        if(input.providerId()==null || input.modelCode()==null) throw new BizException(400,"请选择提供商和模型");
        boolean design="DESIGN".equals(input.method());
        if(!design && !"CLONE".equals(input.method())) throw new BizException(400,"创建方式必须为声音设计或声音克隆");
        if(!(design?"mimo-v2.5-tts-voicedesign":"mimo-v2.5-tts-voiceclone").equals(input.modelCode())) throw new BizException(400,"模型与创建方式不匹配");
        if(design && input.effects()!=null && !input.effects().isEmpty()) throw new BizException(400,"基础参考声音禁止携带特效");
        VoiceModelRequest.snapshot(input.modelRequest());
        if(input.modelRequest().containsKey("model") && !input.modelCode().equals(input.modelRequest().get("model")))
            throw new BizException(400,"JSON 中的 model 与所选模型不一致");
    }
    public AudioView create(Long characterId,Create input) {
        validateCreate(input); var snapshots=effects.snapshots(input.effects());
        String requestJson=VoiceModelRequest.snapshot(input.modelRequest());
        return transactions.execute(tx -> {
            ResCharacter character=lockCharacter(characterId); CharacterVoiceAudio base=base(characterId);
            boolean design="DESIGN".equals(input.method());
            if(design && base!=null && !Objects.equals(input.replaceBaseId(),base.getId())) throw new BizException(409,"角色已有基础声音，重新设计需明确指定要替换的基础声音");
            if(design && base==null && input.replaceBaseId()!=null) throw new BizException(409,"基础声音已变化，请刷新");
            if(design && base!=null) assertUnreferencedFamily(base);
            if(design && audios.selectCount(new LambdaQueryWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getCharacterId,characterId)
                .eq(CharacterVoiceAudio::getKind,"BASE_CANDIDATE").eq(CharacterVoiceAudio::getStatus,"GENERATING"))>0)
                throw new BizException(409,"基础声音重新设计任务正在执行，请等待完成");
            if(!design && (base==null || !"READY".equals(base.getStatus()))) throw new BizException(400,"请先创建可用的无特效基础参考声音");
            CharacterVoiceAudio audio=new CharacterVoiceAudio(); audio.setCharacterId(characterId);
            audio.setKind(design?(base==null?"BASE":"BASE_CANDIDATE"):"CLONE");
            audio.setBaseAudioId(base==null?null:base.getId()); audio.setName(input.name().trim());
            audio.setText(VoiceModelRequest.text(input.modelRequest())); audio.setModelRequestJson(requestJson);
            audio.setEffectsJson(effects.write(snapshots));
            audio.setRevision(1); audio.setStatus("GENERATING"); audio.setProviderId(input.providerId()); audio.setModelCode(input.modelCode());
            audios.insert(audio);
            start(audio,character,design?AiTaskType.CHARACTER_VOICE_DESIGN:AiTaskType.CHARACTER_VOICE_CLONE,true);
            return view(audio);
        });
    }
    public AudioView updateEffects(Long id,Effects input) {
        if(input.effects()==null || input.revision()==null) throw new BizException(400,"请提交特效数组与当前版本号，空数组表示清空");
        return transactions.execute(tx -> {
            CharacterVoiceAudio audio=locked(id,input.revision());
            var snapshots=effects.snapshots(input.effects(),effects.readSnapshots(audio.getEffectsJson()));
            if(!"CLONE".equals(audio.getKind())) throw new BizException(400,"基础参考音频不能设置特效");
            if(audio.getRawAudioUrl()==null) throw new BizException(409,"克隆原始音频尚未生成");
            if(references(audio)>0) throw new BizException(409,"配音已被分镜引用，请先解除引用再修改特效");
            String old=audio.getProcessedAudioUrl(); Long oldTask=audio.getTaskId();
            audio.setRevision(audio.getRevision()+1); audio.setEffectsJson(effects.write(snapshots));
            audio.setStatus(snapshots.isEmpty()?"READY":"PROCESSING");
            audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,id)
                .set(CharacterVoiceAudio::getRevision,audio.getRevision()).set(CharacterVoiceAudio::getEffectsJson,audio.getEffectsJson())
                .set(CharacterVoiceAudio::getStatus,audio.getStatus()).set(CharacterVoiceAudio::getErrorMessage,null)
                .set(CharacterVoiceAudio::getProcessedAudioUrl,null).set(CharacterVoiceAudio::getProcessedDuration,null));
            audio.setProcessedAudioUrl(null); audio.setProcessedDuration(null); audio.setErrorMessage(null);
            AfterCommitTask.start(() -> { if(oldTask!=null && AiTaskExecutionRegistry.isActive(oldTask)) tasks.markCancelled(oldTask,false); storage.deleteOwned(old); });
            if(!snapshots.isEmpty()) start(audio,characters.selectById(audio.getCharacterId()),AiTaskType.VOICE_EFFECT_PROCESS,false);
            return view(audio);
        });
    }
    public AudioView rename(Long id,Metadata input) {
        return transactions.execute(tx -> {
            if(input.revision()==null) throw new BizException(400,"请提交当前配音版本");
            CharacterVoiceAudio audio=locked(id,input.revision());
            if(input.name()==null || input.name().isBlank() || input.name().length()>128) throw new BizException(400,"配音名称无效");
            audio.setName(input.name().trim());
            audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,id).set(CharacterVoiceAudio::getName,audio.getName()));
            return view(audio);
        });
    }
    public AudioView retry(Long id,Integer revision) {
        if(revision==null) throw new BizException(400,"请提交当前配音版本");
        CharacterVoiceAudio current=require(id);
        if(current.getRawAudioUrl()!=null) {
            var selections=effects.readSnapshots(current.getEffectsJson()).stream().map(s -> new EffectSelection(Long.valueOf(s.effectId()),s.steps())).toList();
            return updateEffects(id,new Effects(revision,selections));
        }
        return transactions.execute(tx -> {
            CharacterVoiceAudio audio=locked(id,revision);
            if(!Set.of("FAILED","CANCELLED").contains(audio.getStatus())) throw new BizException(409,"当前任务不可重试");
            VoiceModelRequest.snapshot(VoiceModelRequest.read(audio.getModelRequestJson()));
            audio.setRevision(audio.getRevision()+1); audio.setStatus("GENERATING"); audio.setErrorMessage(null); audios.updateById(audio);
            audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,id).set(CharacterVoiceAudio::getErrorMessage,null));
            start(audio,characters.selectById(audio.getCharacterId()),"CLONE".equals(audio.getKind())?AiTaskType.CHARACTER_VOICE_CLONE:AiTaskType.CHARACTER_VOICE_DESIGN,true);
            return view(audio);
        });
    }
    public AiAudioApiService.AudioArchiveResult adoptDesignedBase(Long characterId,String text,String voiceDesc,AiAudioApiService.AudioArchiveResult generated) {
        try {
            var view=uploadBase(characterId,"基础参考音频",text,storage.read(generated.audioUrl()),null);
            audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,view.audio().getId()).set(CharacterVoiceAudio::getVoiceDesc,voiceDesc));
            characters.update(null,new LambdaUpdateWrapper<ResCharacter>().eq(ResCharacter::getId,characterId).set(ResCharacter::getVoiceDesc,voiceDesc));
            return new AiAudioApiService.AudioArchiveResult(view.audio().getRawAudioUrl(),view.audio().getRawDuration(),generated.fileSizeBytes());
        } finally { storage.deleteOwned(generated.audioUrl()); }
    }
    public AudioView uploadBase(Long characterId,String name,String text,byte[] bytes,Long replaceBaseId) {
        if(name==null || name.isBlank() || name.length()>128 || text==null || text.isBlank() || text.length()>4000) throw new BizException(400,"请填写音频名称与实际台词");
        var normalized=engine.process(bytes,List.of());
        if(normalized.duration()>30) throw new BizException(400,"基础参考音频最长30秒，建议5至10秒");
        return transactions.execute(tx -> {
            lockCharacter(characterId); var existing=base(characterId);
            if(existing!=null && !Objects.equals(existing.getId(),replaceBaseId)) throw new BizException(409,"角色已有基础声音，请明确确认替换");
            if(existing==null && replaceBaseId!=null) throw new BizException(409,"基础声音已变化，请刷新");
            if(existing!=null) { assertUnreferencedFamily(existing); deleteFamily(existing); }
            var audio=new CharacterVoiceAudio(); audio.setCharacterId(characterId); audio.setKind("BASE");audio.setName(name.trim());audio.setText(text);
            audio.setRevision(1);audio.setEffectsJson("[]");audio.setStatus("READY");audio.setRawDuration(normalized.duration()); audios.insert(audio);
            String url=storage.put("dubbing/"+characterId+"/"+audio.getId()+"/raw.wav",normalized.bytes());
            audio.setRawAudioUrl(url);audios.updateById(audio); syncProjection(audio);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) storage.deleteOwned(url); }
            });
            return view(audio);
        });
    }
    public void delete(Long id,Integer revision) {
        if(revision==null) throw new BizException(400,"请提交当前配音版本");
        transactions.executeWithoutResult(tx -> {
            var audio=locked(id,revision);
            if("BASE".equals(audio.getKind())) { assertUnreferencedFamily(audio); deleteFamily(audio); clearProjection(audio.getCharacterId()); }
            else { if(references(audio)>0) throw new BizException(409,"配音已被分镜引用，请先解除引用"); deleteRecord(audio); }
        });
    }
    public void deleteForCharacter(Long characterId) {
        transactions.executeWithoutResult(tx -> {
            lockCharacter(characterId);
            var all=audios.selectList(new LambdaQueryWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getCharacterId,characterId));
            if(all.stream().anyMatch(a -> references(a)>0)) throw new BizException(409,"角色配音仍被分镜引用，请先解除引用");
            all.forEach(this::deleteRecord);clearProjection(characterId);
        });
    }
    public List<CharacterVoiceAudio> family(CharacterVoiceAudio base) {
        return audios.selectList(new LambdaQueryWrapper<CharacterVoiceAudio>().and(w -> w.eq(CharacterVoiceAudio::getId,base.getId()).or().eq(CharacterVoiceAudio::getBaseAudioId,base.getId())));
    }
    private void assertUnreferencedFamily(CharacterVoiceAudio base) {
        if(family(base).stream().anyMatch(a -> references(a)>0)) throw new BizException(409,"基础声音或关联克隆已被分镜引用，请先解除引用");
    }
    private void deleteFamily(CharacterVoiceAudio base) { for(var audio:family(base)) deleteRecord(audio); }
    private void deleteRecord(CharacterVoiceAudio audio) {
        audios.deleteById(audio.getId());
        AfterCommitTask.start(() -> {
            if(audio.getTaskId()!=null) tasks.markCancelled(audio.getTaskId(),false);
            storage.deleteOwned(audio.getRawAudioUrl());storage.deleteOwned(audio.getProcessedAudioUrl());
        });
    }
    private void clearProjection(Long characterId) {
        characters.update(null,new LambdaUpdateWrapper<ResCharacter>().eq(ResCharacter::getId,characterId)
            .set(ResCharacter::getVoiceSampleUrl,null).set(ResCharacter::getVoiceSampleText,null).set(ResCharacter::getVoiceDesc,null).set(ResCharacter::getVoiceId,null));
    }
    private void syncProjection(CharacterVoiceAudio audio) {
        characters.update(null,new LambdaUpdateWrapper<ResCharacter>().eq(ResCharacter::getId,audio.getCharacterId())
            .set(ResCharacter::getVoiceSampleUrl,audio.getRawAudioUrl()).set(ResCharacter::getVoiceSampleText,audio.getText()==null?null:audio.getText().substring(0,Math.min(audio.getText().length(),255)))
            .set(ResCharacter::getVoiceDesc,audio.getVoiceDesc()).set(ResCharacter::getVoiceId,null));
    }
    private void start(CharacterVoiceAudio audio,ResCharacter character,AiTaskType type,boolean synthesize) {
        var task=tasks.createTask(character.getDramaId(),null,type,audio.getCharacterId().toString(),null,effects.write(Map.of("audioId",audio.getId().toString(),"revision",audio.getRevision())),synthesize?audio.getModelCode():"ffmpeg",null);
        if(task.getId()==null || tasks.getTaskById(task.getId())==null) throw new BizException(503,"配音任务未能持久化，请检查任务中心数据库");
        audio.setTaskId(task.getId()); audios.updateById(audio);
        Long audioId=audio.getId(), taskId=task.getId(); int revision=audio.getRevision();
        AfterCommitTask.start(() -> executor.execute(() -> run(audioId,revision,taskId,synthesize)));
    }
    private boolean matches(CharacterVoiceAudio audio,int revision,Long taskId) {
        return audio!=null && audio.getRevision()==revision && Objects.equals(audio.getTaskId(),taskId) && !tasks.isCancelled(taskId);
    }
    private void run(Long id,int revision,Long taskId,boolean synthesize) {
        String generated=null,processed=null; AiTaskExecutionRegistry.register(taskId);
        try {
            if(tasks.isCancelled(taskId)) return;
            tasks.markRunning(taskId); CharacterVoiceAudio audio=require(id);
            if(!matches(audio,revision,taskId)) { tasks.markCancelled(taskId,false); return; }
            if(synthesize) {
                String path="dubbing/"+audio.getCharacterId()+"/"+id+"/r"+revision+"/raw.wav";
                AiAudioApiService.AudioArchiveResult result;
                if("CLONE".equals(audio.getKind())) {
                    CharacterVoiceAudio base=require(audio.getBaseAudioId());
                    var reference=engine.process(storage.read(base.getRawAudioUrl()),List.of());
                    result=tts.generateVoice(audio.getProviderId(),audio.getModelCode(),reference.bytes(),VoiceModelRequest.read(audio.getModelRequestJson()),path);
                } else result=tts.generateVoice(audio.getProviderId(),audio.getModelCode(),null,VoiceModelRequest.read(audio.getModelRequestJson()),path);
                generated=result.audioUrl();
                Double duration=result.durationSeconds();
                if(duration==null || !Double.isFinite(duration) || duration<=0 || duration>120 || result.fileSizeBytes()>DubbingAudioStorage.MAX_BYTES)
                    throw new BizException(400,"TTS 返回的音频无有效时长或超过120秒/30 MiB");
                if(!"CLONE".equals(audio.getKind()) && duration>30) throw new BizException(400,"基础参考声音超过30秒，请缩短试音台词");
                // Keep the exact TTS output. Effects never replace or transcode this dry file.
                String raw=generated;
                Boolean accepted=transactions.execute(tx -> {
                    lockCharacter(audio.getCharacterId()); var current=audios.lock(id);
                    if(!matches(current,revision,taskId)) return false;
                    current.setRawAudioUrl(raw);current.setRawDuration(duration);
                    current.setStatus(effects.readSnapshots(current.getEffectsJson()).isEmpty()?"READY":"PROCESSING");
                    if("BASE_CANDIDATE".equals(current.getKind())) {
                        CharacterVoiceAudio old=base(current.getCharacterId());
                        if(old==null || !Objects.equals(old.getId(),current.getBaseAudioId())) throw new BizException(409,"基础声音已变化，请重新设计");
                        assertUnreferencedFamily(old);
                        // Do not delete the successfully generated replacement candidate with the old family.
                        for(var member:family(old)) if(!member.getId().equals(id)) deleteRecord(member);
                        current.setKind("BASE");current.setBaseAudioId(null);
                        audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,id).set(CharacterVoiceAudio::getBaseAudioId,null));
                    }
                    audios.updateById(current);
                    if("BASE".equals(current.getKind())) syncProjection(current);
                    return true;
                });
                if(!Boolean.TRUE.equals(accepted)) { storage.deleteOwned(generated);tasks.markCancelled(taskId,false);return; }
                generated=null; // The raw file is now owned by the committed audio record.
            }
            CharacterVoiceAudio current=require(id);
            var snapshots=effects.readSnapshots(current.getEffectsJson());
            if(!snapshots.isEmpty()) {
                if(!matches(current,revision,taskId)) { tasks.markCancelled(taskId,false); return; }
                var output=engine.process(storage.read(current.getRawAudioUrl()),effects.flatten(snapshots));
                processed=storage.put("dubbing/"+current.getCharacterId()+"/"+id+"/r"+revision+"/processed.wav",output.bytes());
                String finalProcessed=processed;
                Boolean accepted=transactions.execute(tx -> {
                    lockCharacter(current.getCharacterId());var latest=audios.lock(id);
                    if(!matches(latest,revision,taskId)) return false;
                    latest.setProcessedAudioUrl(finalProcessed);latest.setProcessedDuration(output.duration());latest.setStatus("READY");audios.updateById(latest);return true;
                });
                if(!Boolean.TRUE.equals(accepted)) {storage.deleteOwned(processed);tasks.markCancelled(taskId,false);return;}
                processed=null;
            }
            tasks.markSuccess(taskId,effects.write(Map.of("audioId",id.toString(),"revision",revision)),null);
        } catch(Exception failure) {
            log.warn("配音任务失败 audioId={}, taskId={}",id,taskId,failure);
            String message=failure instanceof BizException?failure.getMessage():"音频任务失败，请查看服务端日志";
            transactions.executeWithoutResult(tx -> {
                CharacterVoiceAudio current=audios.selectById(id);
                if(current==null) return;
                lockCharacter(current.getCharacterId());current=audios.lock(id);
                if(current==null || current.getRevision()!=revision || !Objects.equals(current.getTaskId(),taskId)) return;
                boolean cancelled=tasks.isCancelled(taskId);
                audios.update(null,new LambdaUpdateWrapper<CharacterVoiceAudio>().eq(CharacterVoiceAudio::getId,id)
                    .set(CharacterVoiceAudio::getStatus,cancelled?"CANCELLED":current.getRawAudioUrl()==null?"FAILED":"EFFECT_FAILED")
                    .set(CharacterVoiceAudio::getErrorMessage,message.substring(0,Math.min(message.length(),1000))));
            });
            if(!tasks.isCancelled(taskId)) tasks.markFailed(taskId,message);
        } finally {
            storage.deleteOwned(generated); storage.deleteOwned(processed);AiTaskExecutionRegistry.unregister(taskId);Thread.interrupted();
        }
    }
}
