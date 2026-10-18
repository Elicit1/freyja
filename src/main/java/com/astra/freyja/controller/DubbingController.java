package com.astra.freyja.controller;

import com.astra.freyja.common.*;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.voice.DubbingInputs.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/dubbing")
@RequiredArgsConstructor
public class DubbingController {
    private final DubbingService dubbing;
    private final VoiceEffectService effects;
    private final VoiceEffectEngine engine;
    private final DubbingAudioStorage storage;
    private final AiProviderMapper providers;
    private final AiModelMapper models;

    @GetMapping("/characters") public R<?> characters(@RequestParam(defaultValue="1") int current,@RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) String name,@RequestParam(required=false) String dramaName,@RequestParam(required=false) Long locateId) {
        return R.ok(dubbing.characters(current,size,name,dramaName,locateId));
    }
    @GetMapping("/characters/{id}/base") public R<?> base(@PathVariable Long id) { return R.ok(dubbing.baseView(id)); }
    @GetMapping("/characters/{id}/replacements") public R<?> replacements(@PathVariable Long id) { return R.ok(dubbing.replacements(id)); }
    @GetMapping("/characters/{id}/audios") public R<?> audios(@PathVariable Long id,@RequestParam(defaultValue="1") int current,
            @RequestParam(defaultValue="9") int size,@RequestParam(required=false) List<String> emotions,@RequestParam(required=false) List<String> effectIds) {
        return R.ok(dubbing.page(id,current,size,emotions,effectIds,false));
    }
    @PostMapping("/characters/{id}/audios") public R<?> create(@PathVariable Long id,@RequestBody Create input) { return R.ok(dubbing.create(id,input)); }
    @PostMapping("/characters/{id}/upload-base") public R<?> upload(@PathVariable Long id,@RequestParam MultipartFile file,
            @RequestParam String name,@RequestParam String text,@RequestParam(required=false) Long replaceBaseId) throws java.io.IOException {
        checkFile(file); return R.ok(dubbing.uploadBase(id,name,text,file.getBytes(),replaceBaseId));
    }
    @GetMapping("/audios/{id}") public R<?> detail(@PathVariable Long id) { return R.ok(dubbing.view(dubbing.require(id))); }
    @PutMapping("/audios/{id}/effects") public R<?> updateEffects(@PathVariable Long id,@RequestBody Effects input) { return R.ok(dubbing.updateEffects(id,input)); }
    @PutMapping("/audios/{id}") public R<?> rename(@PathVariable Long id,@RequestBody Metadata input) { return R.ok(dubbing.rename(id,input)); }
    @PostMapping("/audios/{id}/retry") public R<?> retry(@PathVariable Long id,@RequestParam Integer revision) { return R.ok(dubbing.retry(id,revision)); }
    @DeleteMapping("/audios/{id}") public R<?> delete(@PathVariable Long id,@RequestParam Integer revision) { dubbing.delete(id,revision); return R.ok(); }
    @GetMapping("/effects") public R<?> effects(@RequestParam(defaultValue="1") int current,@RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) String name,@RequestParam(required=false) Boolean enabled) { return R.ok(effects.page(current,size,name,enabled)); }
    @PostMapping("/effects") public R<?> save(@RequestBody EffectEdit input) { return R.ok(effects.save(input)); }
    @GetMapping("/effects/filters") public R<?> filters() { return R.ok(engine.capabilities()); }
    @PostMapping("/effects/preview") public R<?> preview(@RequestParam(required=false) MultipartFile file,
            @RequestParam(required=false) Long audioId,@RequestParam String steps) throws java.io.IOException {
        byte[] source;
        if(audioId!=null) source=storage.read(dubbing.require(audioId).getRawAudioUrl());
        else { if(file==null) throw new BizException(400,"请选择测试音频");checkFile(file);source=file.getBytes(); }
        var result=engine.process(source,effects.readSteps(steps));
        // Preview is returned directly; no untracked preview objects accumulate in MinIO.
        return R.ok(Map.of("audioUrl","data:audio/wav;base64,"+Base64.getEncoder().encodeToString(result.bytes()),"duration",result.duration()));
    }
    @GetMapping("/models") public R<?> models() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(AiProvider provider:providers.selectList(new LambdaQueryWrapper<AiProvider>().eq(AiProvider::getStatus,1))) {
            for(AiModel model:models.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,provider.getId()).eq(AiModel::getStatus,1)
                    .in(AiModel::getModelType,List.of("TTS","AUDIO","VOICE")).in(AiModel::getModelCode,List.of("mimo-v2.5-tts-voicedesign","mimo-v2.5-tts-voiceclone"))))
                result.add(Map.of("providerId",provider.getId().toString(),"providerName",provider.getProviderName(),"modelCode",model.getModelCode(),"modelName",model.getModelName()));
        }
        return R.ok(result);
    }
    private void checkFile(MultipartFile file) {
        if(file.isEmpty() || file.getSize()>7*1024*1024) throw new BizException(400,"上传音频不能为空且不得超过7 MiB");
    }
}
