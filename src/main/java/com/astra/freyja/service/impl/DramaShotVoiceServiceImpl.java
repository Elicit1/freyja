package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dto.voice.BatchVoiceResultVO;
import com.astra.freyja.dto.voice.ShotVoiceGenerateDTO;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.service.AiAudioApiService;
import com.astra.freyja.service.DramaShotVoiceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 分镜台词克隆配音服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaShotVoiceServiceImpl implements DramaShotVoiceService {

    private final DramaShotMapper shotMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final ResCharacterMapper characterMapper;
    private final AiAudioApiService aiAudioApiService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchVoiceResultVO.ShotVoiceItemResult generateShotVoice(Long shotId, ShotVoiceGenerateDTO dto) {
        if (shotId == null || shotId <= 0) {
            throw new BizException(400, "分镜 ID 不能为空");
        }
        DramaShot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + shotId);
        }

        // 1. 确定台词文本
        String targetDialogue = dto != null && StringUtils.isNotBlank(dto.getCustomDialogue())
                ? dto.getCustomDialogue().trim()
                : shot.getDialogue();

        if (StringUtils.isBlank(targetDialogue)) {
            throw new BizException(400, "分镜未包含对白台词，无法生成配音");
        }

        // 2. 解析说话人与基准参考样音
        ResCharacter speakerCharacter = resolveSpeakerCharacter(shot, dto != null ? dto.getCharacterId() : null);
        String referenceAudioUrl = dto != null && StringUtils.isNotBlank(dto.getReferenceAudioUrl())
                ? dto.getReferenceAudioUrl().trim()
                : (speakerCharacter != null ? speakerCharacter.getVoiceSampleUrl() : null);

        if (StringUtils.isBlank(referenceAudioUrl)) {
            String speakerName = StringUtils.defaultIfBlank(shot.getDialogueSpeaker(), "当前说话人");
            throw new BizException(400, String.format("未找到角色 [%s] 的专属参考声音母音。请先在角色资产库中为其设计声音，或指定参考音源。", speakerName));
        }

        // 3. 构造归档路径
        long dramaId = shot.getDramaId() != null ? shot.getDramaId() : 0L;
        long episodeId = shot.getEpisodeId() != null ? shot.getEpisodeId() : 0L;
        String archivePath = String.format("dramas/%d/episodes/%d/shots/%d/voice_%d.mp3",
                dramaId, episodeId, shotId, System.currentTimeMillis());

        // 4. 调用声音克隆生成
        String emotion = dto != null ? dto.getEmotion() : null;
        Long providerId = dto != null ? dto.getProviderId() : null;
        String modelCode = dto != null ? dto.getModelCode() : null;

        AiAudioApiService.AudioArchiveResult archiveResult = aiAudioApiService.cloneVoice(
                providerId,
                modelCode,
                referenceAudioUrl,
                targetDialogue,
                emotion,
                archivePath
        );

        // 5. 回填持久化分镜
        boolean autoSave = dto == null || dto.getAutoSave() == null || dto.getAutoSave();
        if (autoSave) {
            shot.setAudioUrl(archiveResult.audioUrl());
            if (archiveResult.durationSeconds() != null && archiveResult.durationSeconds() > 0) {
                BigDecimal audioSec = BigDecimal.valueOf(archiveResult.durationSeconds()).setScale(1, RoundingMode.CEILING);
                // 若分镜原始时长为空或小于音频时长，自动延展校准分镜时长
                if (shot.getDuration() == null || shot.getDuration().compareTo(audioSec) < 0) {
                    shot.setDuration(audioSec);
                }
            }
            shotMapper.updateById(shot);
            log.info("[DramaVoice] 分镜配音生成并持久化成功: shotId={}, shotName={}, audioUrl={}, duration={}s",
                    shotId, shot.getShotName(), archiveResult.audioUrl(), archiveResult.durationSeconds());
        }

        BatchVoiceResultVO.ShotVoiceItemResult item = new BatchVoiceResultVO.ShotVoiceItemResult();
        item.setShotId(shot.getId());
        item.setShotNo(shot.getShotNo());
        item.setShotName(shot.getShotName());
        item.setSpeakerName(speakerCharacter != null ? speakerCharacter.getName() : shot.getDialogueSpeaker());
        item.setDialogue(targetDialogue);
        item.setAudioUrl(archiveResult.audioUrl());
        item.setDuration(archiveResult.durationSeconds());
        item.setSuccess(true);
        item.setMessage("配音生成成功");
        return item;
    }

    @Override
    public BatchVoiceResultVO batchGenerateEpisodeVoice(Long episodeId) {
        if (episodeId == null || episodeId <= 0) {
            throw new BizException(400, "剧集 ID 不能为空");
        }
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BizException(404, "剧集不存在: " + episodeId);
        }

        List<DramaShot> shotList = shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getEpisodeId, episodeId)
                .orderByAsc(DramaShot::getShotNo));

        BatchVoiceResultVO result = new BatchVoiceResultVO();
        result.setEpisodeId(episodeId);
        result.setTotalShots(shotList.size());

        List<BatchVoiceResultVO.ShotVoiceItemResult> items = new ArrayList<>();
        int matched = 0;
        int success = 0;
        int failure = 0;
        int skipped = 0;

        for (DramaShot shot : shotList) {
            if (StringUtils.isBlank(shot.getDialogue())) {
                skipped++;
                continue;
            }
            matched++;

            try {
                BatchVoiceResultVO.ShotVoiceItemResult item = generateShotVoice(shot.getId(), null);
                items.add(item);
                success++;
            } catch (Exception e) {
                log.error("[DramaVoice] 批量生成分镜配音异常: shotId={}, err={}", shot.getId(), e.getMessage());
                failure++;
                BatchVoiceResultVO.ShotVoiceItemResult errItem = new BatchVoiceResultVO.ShotVoiceItemResult();
                errItem.setShotId(shot.getId());
                errItem.setShotNo(shot.getShotNo());
                errItem.setShotName(shot.getShotName());
                errItem.setSpeakerName(shot.getDialogueSpeaker());
                errItem.setDialogue(shot.getDialogue());
                errItem.setSuccess(false);
                errItem.setMessage(e.getMessage());
                items.add(errItem);
            }
        }

        result.setMatchedShots(matched);
        result.setSuccessCount(success);
        result.setFailureCount(failure);
        result.setSkippedCount(skipped);
        result.setItems(items);

        log.info("[DramaVoice] 剧集批量配音完成: episodeId={}, total={}, matched={}, success={}, failed={}",
                episodeId, shotList.size(), matched, success, failure);
        return result;
    }

    private ResCharacter resolveSpeakerCharacter(DramaShot shot, Long specifiedCharId) {
        if (specifiedCharId != null && specifiedCharId > 0) {
            return characterMapper.selectById(specifiedCharId);
        }

        Long dramaId = shot.getDramaId() != null ? shot.getDramaId() : 0L;

        // 1. 根据 dialogueSpeaker 说话人名称匹配
        String speakerName = shot.getDialogueSpeaker();
        if (StringUtils.isNotBlank(speakerName)) {
            String name = speakerName.trim();
            ResCharacter character = characterMapper.selectOne(new LambdaQueryWrapper<ResCharacter>()
                    .and(w -> w.eq(ResCharacter::getDramaId, dramaId).or().eq(ResCharacter::getDramaId, 0L))
                    .and(w -> w.eq(ResCharacter::getName, name)
                            .or().eq(ResCharacter::getCanonicalName, name)
                            .or().eq(ResCharacter::getDisplayName, name))
                    .orderByDesc(ResCharacter::getDramaId)
                    .last("LIMIT 1"));
            if (character != null) {
                return character;
            }
        }

        // 2. 从 characterRefsJson 中提取关联角色
        String refsJson = shot.getCharacterRefsJson();
        if (StringUtils.isNotBlank(refsJson)) {
            try {
                JsonNode array = objectMapper.readTree(refsJson);
                if (array.isArray() && !array.isEmpty()) {
                    JsonNode firstRef = array.get(0);
                    if (firstRef.hasNonNull("characterId")) {
                        Long charId = firstRef.get("characterId").asLong();
                        return characterMapper.selectById(charId);
                    }
                }
            } catch (Exception ignored) {}
        }

        return null;
    }
}
