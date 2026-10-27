package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.util.ShotAudioReferenceValidator;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResAudioServiceImpl implements ResAudioService {
    private final ResAudioMapper mapper;
    private final DramaMapper dramas;
    private final DramaShotMapper shots;
    private final MediaAssetService media;
    private final VideoFrameExtractService probe;
    public static final long MAX_BYTES = 30L * 1024 * 1024;
    private final AssetMediaCleanupService cleanup;

    @Override public Page<ResAudioVO> page(ResAudioQuery query) {
        var filter = new LambdaQueryWrapper<ResAudio>()
                .like(StringUtils.isNotBlank(query.getName()), ResAudio::getName, query.getName())
                .eq(query.getDramaId() != null, ResAudio::getDramaId, query.getDramaId())
                .eq(query.getShotId() != null, ResAudio::getShotId, query.getShotId())
                .eq(query.getStatus() != null, ResAudio::getStatus, query.getStatus())
                .eq(StringUtils.isNotBlank(query.getSourceType()), ResAudio::getSourceType, query.getSourceType())
                .eq(StringUtils.isNotBlank(query.getAudioKind()), ResAudio::getAudioKind, query.getAudioKind());
        if (query.getAvailableDramaId() != null) {
            filter.and(w -> w.eq(ResAudio::getDramaId, 0L).or().eq(ResAudio::getDramaId, query.getAvailableDramaId()));
            filter.eq(ResAudio::getStatus, 1);
        }
        filter.orderByAsc(ResAudio::getSortOrder).orderByDesc(ResAudio::getId);
        var result = mapper.selectPage(new Page<>(Math.max(1, query.getCurrent() == null ? 1 : query.getCurrent()),
                Math.clamp(query.getSize() == null ? 12 : query.getSize(), 1, 100)), filter);
        var dramaIds = result.getRecords().stream().map(ResAudio::getDramaId).filter(id -> id != null && id > 0).collect(Collectors.toSet());
        var shotIds = result.getRecords().stream().map(ResAudio::getShotId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> titles = dramaIds.isEmpty() ? Map.of() : dramas.selectBatchIds(dramaIds).stream().collect(Collectors.toMap(Drama::getId, Drama::getTitle));
        Map<Long, String> names = shotIds.isEmpty() ? Map.of() : shots.selectBatchIds(shotIds).stream().collect(Collectors.toMap(DramaShot::getId, s -> StringUtils.defaultString(s.getShotName())));
        var page = new Page<ResAudioVO>(result.getCurrent(), result.getSize(), result.getTotal());
        page.setRecords(result.getRecords().stream().map(row -> {
            var vo = toVO(row); vo.setDramaTitle(row.getDramaId() == null || row.getDramaId() == 0 ? "全局公共库" : titles.getOrDefault(row.getDramaId(), "未知短剧"));
            vo.setShotName(names.get(row.getShotId())); return vo;
        }).toList());
        return page;
    }

    @Override public ResAudioVO getById(Long id) {
        ResAudio row = require(id);
        var vo = toVO(row);
        Drama drama = row.getDramaId() != null && row.getDramaId() > 0 ? dramas.selectById(row.getDramaId()) : null;
        vo.setDramaTitle(row.getDramaId() == null || row.getDramaId() == 0 ? "全局公共库" : drama == null ? "未知短剧" : drama.getTitle());
        DramaShot shot = row.getShotId() == null ? null : shots.selectById(row.getShotId());
        vo.setShotName(shot == null ? null : shot.getShotName());
        return vo;
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public ResAudioVO upload(MultipartFile file, String name, Long dramaId, Long shotId, String sourceType, String audioKind, String text, String language) {
        if (file == null || file.isEmpty()) throw new BizException(400, "请选择音频文件");
        if (sourceType == null || !Set.of("MANUAL_UPLOAD", "SHOT_UPLOAD").contains(sourceType)) throw new BizException(400, "上传来源无效");
        if (file.getSize() > MAX_BYTES) throw new BizException(400, "音频文件超过 30 MiB 限制");
        validateAudioMetadata(audioKind, text, language);
        var row = new ResAudio();
        row.setDramaId(dramaId == null ? 0L : dramaId); row.setShotId(shotId);
        validateScope(row);
        String filename = StringUtils.defaultIfBlank(file.getOriginalFilename(), "audio.wav");
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("mp3", "wav", "flac", "m4a", "ogg").contains(extension)) throw new BizException(400, "支持 MP3、WAV、FLAC、M4A、OGG 音频");
        Path temporary = null;
        try {
            temporary = Files.createTempFile("freyja_audio_asset_", "." + extension);
            try (var input = file.getInputStream()) { Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING); }
            var info = probe.probeVideoFile(temporary, false);
            Set<String> formats = info.getContainerFormat() == null ? Set.of() : new HashSet<>(Arrays.asList(info.getContainerFormat().split(",")));
            String expectedFormat = switch (extension) {
                case "mp3" -> "mp3";
                case "wav" -> "wav";
                case "flac" -> "flac";
                case "m4a" -> "mov";
                default -> "ogg";
            };
            if (!formats.contains(expectedFormat)) throw new BizException(400, "音频真实容器格式与文件类型不匹配");
            if (!Boolean.TRUE.equals(info.getHasAudio()) || info.getWidth() != null || info.getDuration() == null
                    || info.getDuration().signum() <= 0 || info.getDuration().doubleValue() > 600)
                throw new BizException(400, "请上传有音轨的纯音频文件，时长须在 0～600 秒之间");
            row.setName(StringUtils.defaultIfBlank(name, filename).trim());
            validateMetadata(row.getName(), null, 0, 1);
            row.setOriginalFilename(filename); row.setFileSize(file.getSize());
            row.setDuration(info.getDuration()); row.setAudioKind(audioKind);
            row.setText(text); row.setLanguage(language);
            row.setSourceType(sourceType); row.setSortOrder(0); row.setStatus(1);
            row.setAudioUrl(media.upload(file, "audio-material").getUrl());
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) cleanup.deleteUnreferencedNow(row.getAudioUrl());
                    }
                });
            }
            mapper.insert(row);
            return getById(row.getId());
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "音频上传或探测失败，请检查文件与媒体工具配置"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public void update(ResAudioDTO dto) {
        ResAudio row = require(dto.getId());
        validateMetadata(dto.getName(), dto.getDescription(), dto.getSortOrder(), dto.getStatus());
        validateAudioMetadata(dto.getAudioKind(), dto.getText(), dto.getLanguage());
        row.setAudioKind(dto.getAudioKind()); row.setText(dto.getText()); row.setLanguage(dto.getLanguage());
        row.setName(dto.getName().trim()); row.setDescription(dto.getDescription()); row.setRemark(dto.getRemark());
        if (dto.getDramaId() != null) row.setDramaId(dto.getDramaId());
        row.setShotId(dto.getShotId()); validateScope(row);
        if (dto.getSortOrder() != null) row.setSortOrder(dto.getSortOrder());
        if (dto.getStatus() != null) row.setStatus(dto.getStatus());
        mapper.updateById(row);
        // MyBatis ignores null updates: explicitly clear optional associations and text.
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ResAudio>()
                .eq(ResAudio::getId, row.getId()).set(ResAudio::getShotId, row.getShotId())
                .set(ResAudio::getDescription, row.getDescription()).set(ResAudio::getRemark, row.getRemark())
                .set(ResAudio::getText, row.getText()).set(ResAudio::getLanguage, row.getLanguage()));
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ResAudio row = require(id); mapper.deleteById(id); cleanup.deleteAfterCommit(row.getAudioUrl());
    }

    /** Resolve library identities on save; shot/take URLs remain immutable snapshots afterwards. */
    @Override public void validateReferences(Long dramaId, List<ShotRefAudioDTO> references) {
        if (references == null) return;
        for (var reference : references) {
            if (reference.getResAudioId() == null) continue;
            if (reference.getAudioId() != null) throw new BizException(400, "音频素材与配音中心来源不能同时指定");
            ResAudio row = require(reference.getResAudioId());
            if (!Objects.equals(row.getStatus(), 1)) throw new BizException(400, "音频素材已停用");
            if (!Objects.equals(row.getDramaId(), 0L) && !Objects.equals(row.getDramaId(), dramaId))
                throw new BizException(400, "音频素材不属于当前短剧或公共库");
            if (!Objects.equals(row.getAudioUrl(), reference.getAudioUrl())) throw new BizException(400, "音频素材 URL 与归档记录不一致");
            reference.setDuration(row.getDuration());
            reference.setAudioKind(row.getAudioKind());
        }
        ShotAudioReferenceValidator.validateForSave(references);
    }

    private void validateAudioMetadata(String kind, String text, String language) {
        if (kind == null || !ShotAudioReferenceValidator.AUDIO_KINDS.contains(kind)) throw new BizException(400, "请选择有效的音频素材种类");
        if (text != null && text.length() > 10000) throw new BizException(400, "音频文本不能超过 10000 字符");
        if (language != null && !ShotAudioReferenceValidator.LANGUAGES.contains(language)) throw new BizException(400, "音频语言无效");
    }

    private ResAudio require(Long id) {
        ResAudio row = id == null ? null : mapper.selectById(id);
        if (row == null) throw new BizException(404, "音频素材不存在");
        return row;
    }
    private void validateScope(ResAudio row) {
        if (row.getShotId() != null) {
            DramaShot shot = shots.selectById(row.getShotId());
            if (shot == null) throw new BizException(400, "关联分镜不存在");
            if (row.getDramaId() != null && row.getDramaId() > 0 && !row.getDramaId().equals(shot.getDramaId()))
                throw new BizException(400, "分镜不属于所选短剧");
            row.setDramaId(shot.getDramaId());
        }
        if (row.getDramaId() == null || row.getDramaId() < 0) throw new BizException(400, "归属短剧无效");
        if (row.getDramaId() > 0 && dramas.selectById(row.getDramaId()) == null) throw new BizException(400, "归属短剧不存在");
    }
    private void validateMetadata(String name, String description, Integer order, Integer status) {
        if (StringUtils.isBlank(name) || name.length() > 200) throw new BizException(400, "音频素材名称需为 1～200 字符");
        if (description != null && description.length() > 4000) throw new BizException(400, "音频描述不能超过 4000 字符");
        if (order != null && order < 0 || status != null && status != 0 && status != 1) throw new BizException(400, "排序或状态无效");
    }
    private ResAudioVO toVO(ResAudio row) { var vo = new ResAudioVO(); BeanUtils.copyProperties(row, vo); return vo; }
}
