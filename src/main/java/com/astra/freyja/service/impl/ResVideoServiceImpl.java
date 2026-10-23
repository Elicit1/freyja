package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.ReferenceVideoProperties;
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
public class ResVideoServiceImpl implements ResVideoService {
    private final ResVideoMapper mapper;
    private final DramaMapper dramas;
    private final DramaShotMapper shots;
    private final MediaAssetService media;
    private final VideoFrameExtractService probe;
    private final ReferenceVideoProperties limits;
    private final AssetMediaCleanupService cleanup;

    @Override public Page<ResVideoVO> page(ResVideoQuery query) {
        var filter = new LambdaQueryWrapper<ResVideo>()
                .like(StringUtils.isNotBlank(query.getName()), ResVideo::getName, query.getName())
                .eq(query.getDramaId() != null, ResVideo::getDramaId, query.getDramaId())
                .eq(query.getShotId() != null, ResVideo::getShotId, query.getShotId())
                .eq(query.getStatus() != null, ResVideo::getStatus, query.getStatus())
                .eq(StringUtils.isNotBlank(query.getSourceType()), ResVideo::getSourceType, query.getSourceType());
        if (query.getAvailableDramaId() != null) {
            filter.and(w -> w.eq(ResVideo::getDramaId, 0L).or().eq(ResVideo::getDramaId, query.getAvailableDramaId()));
            filter.eq(ResVideo::getStatus, 1);
        }
        filter.orderByAsc(ResVideo::getSortOrder).orderByDesc(ResVideo::getId);
        var result = mapper.selectPage(new Page<>(Math.max(1, query.getCurrent() == null ? 1 : query.getCurrent()),
                Math.clamp(query.getSize() == null ? 12 : query.getSize(), 1, 100)), filter);
        var dramaIds = result.getRecords().stream().map(ResVideo::getDramaId).filter(id -> id != null && id > 0).collect(Collectors.toSet());
        var shotIds = result.getRecords().stream().map(ResVideo::getShotId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> titles = dramaIds.isEmpty() ? Map.of() : dramas.selectBatchIds(dramaIds).stream().collect(Collectors.toMap(Drama::getId, Drama::getTitle));
        Map<Long, String> names = shotIds.isEmpty() ? Map.of() : shots.selectBatchIds(shotIds).stream().collect(Collectors.toMap(DramaShot::getId, s -> StringUtils.defaultString(s.getShotName())));
        var page = new Page<ResVideoVO>(result.getCurrent(), result.getSize(), result.getTotal());
        page.setRecords(result.getRecords().stream().map(row -> {
            var vo = toVO(row); vo.setDramaTitle(row.getDramaId() == null || row.getDramaId() == 0 ? "全局公共库" : titles.getOrDefault(row.getDramaId(), "未知短剧"));
            vo.setShotName(names.get(row.getShotId())); return vo;
        }).toList());
        return page;
    }

    @Override public ResVideoVO getById(Long id) {
        ResVideo row = require(id);
        var vo = toVO(row);
        Drama drama = row.getDramaId() != null && row.getDramaId() > 0 ? dramas.selectById(row.getDramaId()) : null;
        vo.setDramaTitle(row.getDramaId() == null || row.getDramaId() == 0 ? "全局公共库" : drama == null ? "未知短剧" : drama.getTitle());
        DramaShot shot = row.getShotId() == null ? null : shots.selectById(row.getShotId());
        vo.setShotName(shot == null ? null : shot.getShotName());
        return vo;
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public ResVideoVO upload(MultipartFile file, String name, Long dramaId, Long shotId, String sourceType) {
        if (file == null || file.isEmpty()) throw new BizException(400, "请选择视频文件");
        if (sourceType == null || !Set.of("MANUAL_UPLOAD", "SHOT_UPLOAD").contains(sourceType)) throw new BizException(400, "上传来源无效");
        if (file.getSize() > limits.getMaxSourceBytes()) throw new BizException(400, "视频文件超过上传大小限制");
        var row = new ResVideo();
        row.setDramaId(dramaId == null ? 0L : dramaId); row.setShotId(shotId);
        validateScope(row);
        String filename = StringUtils.defaultIfBlank(file.getOriginalFilename(), "video.mp4");
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("mp4", "webm", "mov", "mkv").contains(extension)) throw new BizException(400, "支持 MP4、WebM、MOV、MKV 视频");
        Path temporary = null;
        try {
            temporary = Files.createTempFile("freyja_video_asset_", "." + extension);
            try (var input = file.getInputStream()) { Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING); }
            var info = probe.probeVideoFile(temporary, false);
            if (info.getWidth() == null || info.getHeight() == null || info.getWidth() <= 0 || info.getHeight() <= 0
                    || info.getDuration() == null || info.getDuration().signum() <= 0 || info.getDuration().doubleValue() > limits.getMaxSourceSeconds())
                throw new BizException(400, "视频没有有效画面、时长无效或超过素材时长限制");
            row.setName(StringUtils.defaultIfBlank(name, filename).trim());
            validateMetadata(row.getName(), null, 0, 1);
            row.setOriginalFilename(filename); row.setFileSize(file.getSize());
            row.setDuration(info.getDuration()); row.setWidth(info.getWidth()); row.setHeight(info.getHeight());
            row.setFps(info.getFps()); row.setHasAudio(Boolean.TRUE.equals(info.getHasAudio()));
            row.setSourceType(sourceType); row.setSortOrder(0); row.setStatus(1);
            row.setVideoUrl(media.upload(file, "video-material").getUrl());
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) cleanup.deleteUnreferencedNow(row.getVideoUrl());
                    }
                });
            }
            mapper.insert(row);
            return getById(row.getId());
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "视频上传或探测失败，请检查文件与媒体工具配置"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public void update(ResVideoDTO dto) {
        ResVideo row = require(dto.getId());
        validateMetadata(dto.getName(), dto.getDescription(), dto.getSortOrder(), dto.getStatus());
        row.setName(dto.getName().trim()); row.setDescription(dto.getDescription()); row.setRemark(dto.getRemark());
        if (dto.getDramaId() != null) row.setDramaId(dto.getDramaId());
        row.setShotId(dto.getShotId()); validateScope(row);
        if (dto.getSortOrder() != null) row.setSortOrder(dto.getSortOrder());
        if (dto.getStatus() != null) row.setStatus(dto.getStatus());
        mapper.updateById(row);
        // MyBatis ignores null updates: explicitly clear optional associations and text.
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ResVideo>()
                .eq(ResVideo::getId, row.getId()).set(ResVideo::getShotId, row.getShotId())
                .set(ResVideo::getDescription, row.getDescription()).set(ResVideo::getRemark, row.getRemark()));
    }

    @Override @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ResVideo row = require(id); mapper.deleteById(id); cleanup.deleteAfterCommit(row.getVideoUrl());
    }

    private ResVideo require(Long id) {
        ResVideo row = id == null ? null : mapper.selectById(id);
        if (row == null) throw new BizException(404, "视频素材不存在");
        return row;
    }
    private void validateScope(ResVideo row) {
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
        if (StringUtils.isBlank(name) || name.length() > 200) throw new BizException(400, "视频素材名称需为 1～200 字符");
        if (description != null && description.length() > 4000) throw new BizException(400, "视频描述不能超过 4000 字符");
        if (order != null && order < 0 || status != null && status != 0 && status != 1) throw new BizException(400, "排序或状态无效");
    }
    private ResVideoVO toVO(ResVideo row) { var vo = new ResVideoVO(); BeanUtils.copyProperties(row, vo); return vo; }
}
