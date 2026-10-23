package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.ReferenceVideoProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.video.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.VideoBatchPreparation;
import com.astra.freyja.util.ShotVideoReferenceValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;

/** Preparation never occupies the GPU queue. Only a completed, matching snapshot can enter it. */
@Service
public class ShotVideoReferenceService {
    private final DramaShotMapper shots;
    private final AiModelMapper models;
    private final VideoProcessSourceResolver sources;
    private final ShotVideoCapabilityService capabilities;
    private final ReferenceVideoMediaService media;
    private final ReferenceVideoProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Executor executor;

    public ShotVideoReferenceService(DramaShotMapper shots, AiModelMapper models, VideoProcessSourceResolver sources,
            ShotVideoCapabilityService capabilities, ReferenceVideoMediaService media, ReferenceVideoProperties properties,
            JdbcTemplate jdbc, ObjectMapper json, @Qualifier("referenceVideoPreparationExecutor") Executor executor) {
        this.shots = shots; this.models = models; this.sources = sources; this.capabilities = capabilities;
        this.media = media; this.properties = properties; this.jdbc = jdbc; this.json = json; this.executor = executor;
    }

    public VideoProbeInfoVO probe(Long shotId, ShotRefVideoDTO reference) {
        DramaShot shot = shots.selectById(shotId);
        if (shot == null) throw new BizException(404, "分镜不存在");
        if (reference == null) throw new BizException(400, "参考视频来源不能为空");
        ShotVideoReferenceValidator.validate(List.of(reference));
        return media.probeSource(resolve(shot, List.of(reference)).getFirst().getVideoUrl());
    }

    public VideoReferencePreflightVO start(Long shotId, DramaShotRenderRequestDTO request) {
        DramaShot shot = shots.selectById(shotId);
        if (shot == null) throw new BizException(404, "分镜不存在");
        if (!"REFERENCE_MODE".equals(shot.getGenerationMode())) throw new BizException(400, "参考视频仅用于参考生成模式");
        AiModel model = model(request);
        var inputs = ShotVideoReferenceValidator.parse(shot.getRefVideosJson(), json);
        if (inputs.isEmpty()) throw new BizException(400, "请先保存参考视频选择");
        var resolved = resolve(shot, inputs);
        var option = capabilities.describe(model);
        var result = new VideoReferencePreflightVO();
        result.setPreflightId(UUID.randomUUID().toString()); result.setShotId(shotId); result.setProviderId(model.getProviderId()); result.setModelCode(model.getModelCode());
        result.setStatus("PREPARING"); result.setModel(option); result.setExpiresAt(LocalDateTime.now().plusMinutes(properties.getPreflightMinutes()));
        result.setInputFingerprint(fingerprint(shot, option, inputs, resolved));
        jdbc.update("INSERT INTO shot_video_reference_preflight(preflight_id,shot_id,provider_id,model_code,input_fingerprint,status,data_json,created_at,expires_at) VALUES (?,?,?,?,?,'PREPARING',?,?,?)",
                result.getPreflightId(), shotId, result.getProviderId(), result.getModelCode(), result.getInputFingerprint(), write(result), LocalDateTime.now(), result.getExpiresAt());
        try {
            executor.execute(() -> prepare(result, inputs, resolved));
        } catch (RuntimeException e) {
            result.setStatus("FAILED"); result.setErrorCode("REFERENCE_VIDEO_PREPARATION_BUSY"); result.setErrorMessage("素材准备队列已满，请稍后重试"); persist(result);
        }
        return get(shotId, result.getPreflightId());
    }

    private void prepare(VideoReferencePreflightVO result, List<ShotRefVideoDTO> inputs, List<ResolvedVideoProcessSource> resolved) {
        try {
            List<ResolvedShotVideoReference> prepared = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) prepared.add(media.prepare(inputs.get(index), resolved.get(index), result.getModel().getReferenceVideo()));
            result.setReferences(prepared); result.setStatus("READY");
        } catch (Exception e) {
            result.setStatus("FAILED");
            String message = e.getMessage() == null ? "参考视频准备失败" : e.getMessage();
            result.setErrorCode(message.startsWith("REFERENCE_VIDEO_") ? message.split(":", 2)[0] : "REFERENCE_VIDEO_DECODE_FAILED");
            result.setErrorMessage(message);
        }
        persist(result);
    }

    private void persist(VideoReferencePreflightVO result) {
        jdbc.update("UPDATE shot_video_reference_preflight SET status=?,data_json=? WHERE preflight_id=? AND status='PREPARING'",
                result.getStatus(), write(result), result.getPreflightId());
    }

    public VideoReferencePreflightVO get(Long shotId, String id) {
        var rows = jdbc.query("SELECT data_json FROM shot_video_reference_preflight WHERE preflight_id=? AND shot_id=?", (rs, row) -> rs.getString(1), id, shotId);
        if (rows.isEmpty()) throw new BizException(404, "参考视频预检不存在");
        VideoReferencePreflightVO result;
        try { result = json.readValue(rows.getFirst(), VideoReferencePreflightVO.class); }
        catch (Exception e) { throw new BizException(500, "参考视频预检快照无法读取"); }
        if (result.getExpiresAt().isBefore(LocalDateTime.now())) {
            result.setStatus("EXPIRED"); result.setErrorCode("REFERENCE_VIDEO_PREFLIGHT_EXPIRED"); result.setErrorMessage("预检已过期，请重新准备素材");
        }
        if (result.getReferences() != null) result.setPreviewUrls(result.getReferences().stream().map(ref -> media.previewUrl(ref.getObjectPath())).toList());
        return result;
    }

    public VideoReferencePreflightVO forSubmission(DramaShot shot, AiModel model, String preflightId) {
        var inputs = ShotVideoReferenceValidator.parse(shot.getRefVideosJson(), json);
        var option = capabilities.describe(model);
        capabilities.requireRenderable(option);
        var resolved = resolve(shot, inputs);
        String fingerprint = fingerprint(shot, option, inputs, resolved);
        if (preflightId == null || preflightId.isBlank()) {
            var rows = jdbc.query("SELECT preflight_id FROM shot_video_reference_preflight WHERE shot_id=? AND provider_id=? AND model_code=? AND input_fingerprint=? AND status='READY' AND expires_at>? ORDER BY created_at DESC LIMIT 1",
                    (rs, row) -> rs.getString(1), shot.getId(), model.getProviderId(), model.getModelCode(), fingerprint, LocalDateTime.now());
            if (rows.isEmpty()) throw new BizException(409, "REFERENCE_VIDEO_PREFLIGHT_REQUIRED: 请先在参考渲染工坊完成视频素材预检");
            preflightId = rows.getFirst();
        }
        var prepared = get(shot.getId(), preflightId);
        if (!"READY".equals(prepared.getStatus()) || !fingerprint.equals(prepared.getInputFingerprint())
                || !model.getProviderId().equals(prepared.getProviderId()) || !model.getModelCode().equals(prepared.getModelCode()))
            throw new BizException(409, "REFERENCE_VIDEO_SOURCE_CHANGED: 视频来源、素材或模型已变化，请重新预检");
        validateSnapshot(prepared.getReferences(), option, shot);
        prepared.setModel(option); prepared.setPreviewUrls(null);
        return prepared;
    }

    public void validateSnapshot(List<ResolvedShotVideoReference> references, ShotVideoModelOptionVO option, DramaShot shot) {
        capabilities.requireRenderable(option);
        var cap = option.getReferenceVideo();
        if (references == null || references.isEmpty() || references.size() > Math.min(1, cap.getMaxVideos())) throw new BizException(400, "参考视频快照数量无效");
        int mixed = references.size();
        try {
            mixed += json.readTree(shot.getRefImagesJson() == null ? "[]" : shot.getRefImagesJson()).size();
            mixed += json.readTree(shot.getRefAudiosJson() == null ? "[]" : shot.getRefAudiosJson()).size();
        } catch (Exception e) { throw new BizException(400, "参考素材配置无法读取"); }
        for (var ref : references) {
            if (Boolean.TRUE.equals(ref.getIncludeAudio())) {
                if (!Boolean.TRUE.equals(cap.getSupportsVideoAudio())) throw new BizException(400, "所选模型尚未验证视频原音参考能力");
                mixed++;
            }
            media.validatePrepared(ref.getMetadata(), cap, ref.getIncludeAudio());
        }
        if (mixed > cap.getMaxMixedAssets()) throw new BizException(400, "REFERENCE_VIDEO_LIMIT_EXCEEDED: 混合参考素材总量超过模型限制");
    }

    private List<ResolvedVideoProcessSource> resolve(DramaShot target, List<ShotRefVideoDTO> references) {
        return references.stream().map(ref -> {
            var request = new VideoProcessProbeSourceDTO(); request.setSourceType(ref.getSourceType());
            request.setSourceVideoUrl(ref.getSourceVideoUrl()); request.setSourceShotId(ref.getSourceShotId()); request.setSourceVideoTakeId(ref.getSourceVideoTakeId());
            var source = sources.resolveForProbe(request);
            if (source.getDramaId() != null && !Objects.equals(source.getDramaId(), target.getDramaId())) throw new BizException(400, "请选择当前短剧的分镜视频或上传参考视频");
            return source;
        }).toList();
    }

    private String fingerprint(DramaShot shot, ShotVideoModelOptionVO option, List<ShotRefVideoDTO> inputs, List<ResolvedVideoProcessSource> resolved) {
        var versions = resolved.stream().map(source -> Arrays.asList(source.getShotId(), source.getVideoTakeId(), source.getVideoUrl())).toList();
        return VideoBatchPreparation.hash(write(Arrays.asList(shot.getId(), shot.getGenerationMode(), inputs, versions,
                shot.getRefImagesJson(), shot.getRefAudiosJson(), option.getModelFingerprint(), option.getWorkflowRevision(), option.getNodeId())));
    }
    private AiModel model(DramaShotRenderRequestDTO request) {
        if (request == null || request.getProviderId() == null || request.getWorkflowTemplateId() == null) throw new BizException(400, "请选择参考视频模型与提供商");
        AiModel model = models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, request.getProviderId()).eq(AiModel::getModelCode, request.getWorkflowTemplateId()).eq(AiModel::getStatus, 1));
        if (model == null || !"TXT2VIDEO_REF".equals(model.getModelType())) throw new BizException(400, "所选参考模型不存在或未启用");
        return model;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new BizException(500, "参考视频快照序列化失败"); }
    }
}
