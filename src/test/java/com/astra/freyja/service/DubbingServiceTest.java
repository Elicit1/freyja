package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.voice.DubbingInputs.*;
import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.entity.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DubbingServiceTest {
    CharacterVoiceAudioMapper audios;ResCharacterMapper characters;VoiceEffectMapper presets;VoiceEffectEngine engine;
    DubbingAudioStorage storage;AiAudioApiService tts;AiTaskService tasks;JdbcTemplate jdbc;
    DubbingService service;VoiceEffectService effects;CharacterVoiceAudio base;Map<Long,CharacterVoiceAudio> state;
    BlockingQueue<Runnable> jobs;
    @BeforeAll static void tables() {
        for(Class<?> entity:List.of(CharacterVoiceAudio.class,VoiceEffect.class,ResCharacter.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),entity);
    }
    @SuppressWarnings("unchecked") @BeforeEach void setup() {
        audios=mock(CharacterVoiceAudioMapper.class);characters=mock(ResCharacterMapper.class);presets=mock(VoiceEffectMapper.class);
        engine=mock(VoiceEffectEngine.class);storage=mock(DubbingAudioStorage.class);tts=mock(AiAudioApiService.class);tasks=mock(AiTaskService.class);jdbc=mock(JdbcTemplate.class);
        effects=new VoiceEffectService(presets,engine,new ObjectMapper());
        TransactionTemplate transactions=mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>)inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        doAnswer(inv -> {((java.util.function.Consumer<TransactionStatus>)inv.getArgument(0)).accept(mock(TransactionStatus.class));return null;}).when(transactions).executeWithoutResult(any());
        service=new DubbingService(audios,characters,effects,engine,storage,tts,tasks,transactions,jdbc);
        jobs=new LinkedBlockingQueue<>();ReflectionTestUtils.setField(service,"executor",(Executor)jobs::add);
        state=new ConcurrentHashMap<>();base=audio(100L,"BASE","[]");base.setRawAudioUrl("base.wav");state.put(100L,base);
        ResCharacter character=new ResCharacter();character.setId(10L);character.setDramaId(20L);
        when(characters.selectById(10L)).thenReturn(character);when(audios.lockCharacter(10L)).thenReturn(10L);
        when(audios.selectById(anyLong())).thenAnswer(inv -> state.get(inv.getArgument(0)));
        when(audios.lock(anyLong())).thenAnswer(inv -> state.get(inv.getArgument(0)));
        when(audios.selectOne(any(Wrapper.class))).thenReturn(base);
        when(audios.insert(any(CharacterVoiceAudio.class))).thenAnswer(inv -> {CharacterVoiceAudio a=inv.getArgument(0);a.setId(200L);state.put(200L,a);return 1;});
        AiTask task=new AiTask();task.setId(300L);
        when(tasks.createTask(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(task);
        when(tasks.getTaskById(300L)).thenReturn(new AiTaskDTO());
        when(storage.read(anyString())).thenReturn(new byte[]{1,2});
        when(engine.process(any(),any())).thenReturn(new VoiceEffectEngine.Audio(new byte[]{1,2},5));
        when(storage.put(anyString(),any())).thenAnswer(inv -> inv.getArgument(0));
    }
    CharacterVoiceAudio audio(long id,String kind,String config) {
        var audio=new CharacterVoiceAudio();audio.setId(id);audio.setCharacterId(10L);audio.setName("配音");audio.setKind(kind);audio.setRevision(1);audio.setStatus("READY");audio.setEffectsJson(config);return audio;
    }
    void runJob() throws Exception {Runnable job=jobs.poll(3,TimeUnit.SECONDS);assertNotNull(job,"异步任务没有提交");job.run();}
    Create create(String method,List<EffectSelection> effects) {return new Create("配音","你好",1L,"CLONE".equals(method)?"mimo-v2.5-tts-voiceclone":"mimo-v2.5-tts-voicedesign","年轻男声",null,effects,method,null);}
    void preset() {
        VoiceEffect preset=new VoiceEffect();preset.setId(1L);preset.setEnabled(1);preset.setName("无线电");preset.setVersion(1);preset.setStepsJson("[{\"type\":\"highpass\",\"params\":{\"frequency\":300}}]");
        when(presets.selectById(1L)).thenReturn(preset);
    }
    @Test void baseCannotCarryEffectsAndExistingBaseCannotBeSilentlyReplaced() {
        assertThrows(BizException.class,()->service.create(10L,create("DESIGN",List.of(new EffectSelection(1L,null)))));
        assertThrows(BizException.class,()->service.create(10L,create("DESIGN",List.of())));
        verifyNoInteractions(tts);verify(audios,never()).insert(any(CharacterVoiceAudio.class));
    }
    @Test void cloneSavesDryAudioBeforeFfmpegAndAlwaysUsesTheBaseVoice() throws Exception {
        preset();when(tts.cloneVoice(any(),any(),any(),any(),any(),any())).thenReturn(new AiAudioApiService.AudioArchiveResult("tts.wav",5d,2));
        service.create(10L,create("CLONE",List.of(new EffectSelection(1L,null))));runJob();
        var clone=state.get(200L);assertEquals(100L,clone.getBaseAudioId());assertNotNull(clone.getRawAudioUrl());assertNotNull(clone.getProcessedAudioUrl());
        assertNotEquals(clone.getRawAudioUrl(),clone.getProcessedAudioUrl());assertEquals("READY",clone.getStatus());
        var order=inOrder(storage,tts,engine,audios);
        order.verify(storage).read("base.wav");order.verify(engine).process(any(),eq(List.of()));order.verify(tts).cloneVoice(eq(1L),eq("mimo-v2.5-tts-voiceclone"),any(),isNull(),eq("你好"),any());
        order.verify(audios).updateById(argThat((CharacterVoiceAudio a) -> "tts.wav".equals(a.getRawAudioUrl())));
        order.verify(storage).read("tts.wav");order.verify(engine).process(any(),argThat(steps -> !steps.isEmpty()));
        assertEquals("tts.wav",clone.getRawAudioUrl());verify(storage,never()).put(contains("raw.wav"),any());
    }
    @Test void clearingEffectsRetainsDryAudioAndNeverCallsTtsOrFfmpeg() {
        var clone=audio(200,"CLONE","[{\"effectId\":\"1\",\"name\":\"无线电\",\"version\":1,\"steps\":[]}]");clone.setRawAudioUrl("dry.wav");clone.setProcessedAudioUrl("wet.wav");state.put(200L,clone);
        var result=service.updateEffects(200L,new Effects(1,List.of()));
        assertEquals("dry.wav",result.playbackUrl());assertEquals("dry.wav",result.audio().getRawAudioUrl());assertNull(result.audio().getProcessedAudioUrl());assertEquals(2,result.audio().getRevision());
        verifyNoInteractions(tts);verify(engine,never()).process(any(),any());assertTrue(jobs.isEmpty());
    }
    @Test void changingEffectsProcessesDryAudioWithoutTts() throws Exception {
        preset();var clone=audio(200,"CLONE","[]");clone.setRawAudioUrl("dry.wav");state.put(200L,clone);
        service.updateEffects(200L,new Effects(1,List.of(new EffectSelection(1L,null))));runJob();
        verify(storage).read("dry.wav");verifyNoInteractions(tts);assertEquals("dry.wav",clone.getRawAudioUrl());assertNotNull(clone.getProcessedAudioUrl());
    }
    @Test void oldTaskCannotPublishAfterEffectsWereCleared() throws Exception {
        preset();var clone=audio(200,"CLONE","[]");clone.setRawAudioUrl("dry.wav");state.put(200L,clone);
        service.updateEffects(200L,new Effects(1,List.of(new EffectSelection(1L,null))));
        service.updateEffects(200L,new Effects(2,List.of()));runJob();
        assertEquals("READY",clone.getStatus());assertNull(clone.getProcessedAudioUrl());verifyNoInteractions(tts);verify(engine,never()).process(any(),any());
    }
    @Test void staleEditAndBaseEffectsAreRejected() {
        assertThrows(BizException.class,()->service.updateEffects(100L,new Effects(1,List.of())));
        var clone=audio(200,"CLONE","[]");clone.setRawAudioUrl("dry.wav");clone.setRevision(2);state.put(200L,clone);
        assertThrows(BizException.class,()->service.updateEffects(200L,new Effects(1,List.of())));verifyNoInteractions(tts);
    }
    @Test void retainsExistingEffectSnapshotWhenPresetChangesOrIsDisabled() {
        var prior=new VoiceEffectService.Snapshot("1","旧名称",3,List.of(new Step("echo",Map.of("delay",200d,"decay",0.3))));
        var result=effects.snapshots(List.of(new EffectSelection(1L,prior.steps())),List.of(prior));
        assertEquals(List.of(prior),result);verifyNoInteractions(presets);
    }
    @Test void deletingBaseCascadesClonesAndBothStoredVersions() {
        var clone=audio(200,"CLONE","[]");clone.setBaseAudioId(100L);clone.setRawAudioUrl("dry.wav");clone.setProcessedAudioUrl("wet.wav");state.put(200L,clone);
        when(audios.selectList(any(Wrapper.class))).thenReturn(List.of(base,clone));
        service.delete(100L,1);
        verify(audios).deleteById(100L);verify(audios).deleteById(200L);
        verify(storage,timeout(2000)).deleteOwned("dry.wav");verify(storage,timeout(2000)).deleteOwned("wet.wav");
    }
    @Test void failedEffectRetryKeepsTheDryCloneAndDoesNotRepeatTts() throws Exception {
        preset();when(tts.cloneVoice(any(),any(),any(),any(),any(),any())).thenReturn(new AiAudioApiService.AudioArchiveResult("tts.wav",5d,2));
        when(engine.process(any(),any())).thenAnswer(inv -> {
            List<Step> steps=inv.getArgument(1);
            if(!steps.isEmpty()) throw new BizException(502,"FFmpeg unavailable");
            return new VoiceEffectEngine.Audio(new byte[]{1,2},5);
        });
        service.create(10L,create("CLONE",List.of(new EffectSelection(1L,null))));runJob();
        var clone=state.get(200L);String dry=clone.getRawAudioUrl();assertNotNull(dry);assertNull(clone.getProcessedAudioUrl());
        service.retry(200L,clone.getRevision());runJob();
        assertEquals(dry,clone.getRawAudioUrl());verify(tts,times(1)).cloneVoice(any(),any(),any(),any(),any(),any());verify(tasks,times(2)).markFailed(300L,"FFmpeg unavailable");
    }
    @Test void failedBaseReplacementPreservesTheExistingBaseAndItsClones() throws Exception {
        when(tts.designVoice(any(),any(),any(),any(),any())).thenThrow(new BizException(502,"provider unavailable"));
        var input=new Create("新声线","你好",1L,"mimo-v2.5-tts-voicedesign","年轻男声",null,List.of(),"DESIGN",100L);
        when(audios.selectList(any(Wrapper.class))).thenReturn(List.of(base));
        service.create(10L,input);runJob();
        assertEquals("BASE",base.getKind());assertEquals("base.wav",base.getRawAudioUrl());assertEquals("READY",base.getStatus());
        verify(audios,never()).deleteById(100L);verify(tasks).markFailed(300L,"provider unavailable");
    }
}
