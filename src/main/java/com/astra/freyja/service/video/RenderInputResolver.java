package com.astra.freyja.service.video;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.DramaShot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import java.util.*;
import static com.astra.freyja.service.video.RenderValidationReport.*;

/** The same ordered slots feed admission, execution and the gateway binding contract. */
public final class RenderInputResolver {
    private RenderInputResolver() {}

    public static RenderValidationReport inspect(VideoBatchModels.Snapshot snapshot, boolean dispatch,
                                                  ObjectMapper json) {
        DramaShot shot = snapshot.getShot();
        var request = snapshot.getRequest();
        List<Slot> slots = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        boolean reference = VideoBatchPreparation.REF.equals(shot.getGenerationMode());
        if (reference) {
            var pictures = read(shot.getRefImagesJson(), new TypeReference<List<ShotRefImageDTO>>() {}, json);
            for (var picture : pictures) {
                String label = "<Picture " + (slots.size() + 1) + ">";
                slots.add(new Slot(label, picture.getId(), "IMAGE", picture.getImageUrl(), "REFERENCE_IMAGE", false));
                if (StringUtils.isBlank(picture.getImageUrl()) || "ASSET_LINK".equals(picture.getBindingMode()))
                    issues.add(issue(shot, "MEDIA_SOURCE_MISSING", label, "refImages", "素材 " + label + " 缺少已冻结图片", "补齐图片后重新检查"));
            }
            int audioIndex = 0, videoIndex = 0;
            var videos = request.getResolvedRefVideos();
            if (videos != null) for (var video : videos) {
                String videoLabel="<Video " + (++videoIndex) + ">";
                slots.add(new Slot(videoLabel,
                        video.getId(), "VIDEO", video.getObjectPath(), "REFERENCE_VIDEO", false));
                if(StringUtils.isBlank(video.getObjectPath()))issues.add(issue(shot,"MEDIA_SOURCE_MISSING",videoLabel,"refVideos","素材 " + videoLabel + " 缺少已冻结视频","重新准备参考视频"));
                if (Boolean.TRUE.equals(video.getIncludeAudio()))
                    slots.add(new Slot("<Audio " + (++audioIndex) + ">", video.getId(), "AUDIO", video.getObjectPath(), "VIDEO_AUDIO", false));
            }
            if (!read(shot.getRefVideosJson(), new TypeReference<List<ShotRefVideoDTO>>() {}, json).isEmpty() && (videos == null || videos.isEmpty()))
                issues.add(issue(shot,"MEDIA_SOURCE_MISSING",null,"refVideos","参考视频尚未完成预处理","先准备参考视频后重新检查"));
            var audios = read(shot.getRefAudiosJson(), new TypeReference<List<ShotRefAudioDTO>>() {}, json);
            for (var audio : audios) {
                String label = "<Audio " + (++audioIndex) + ">";
                slots.add(new Slot(label, audio.getId(), "AUDIO", audio.getAudioUrl(), "REFERENCE_AUDIO", false));
                if (StringUtils.isBlank(audio.getAudioUrl()))
                    issues.add(issue(shot, "MEDIA_SOURCE_MISSING", label, "refAudios", "素材 " + label + " 缺少音频地址", "补齐音频后重新检查"));
            }
        } else {
            frame(slots, issues, shot, shot.getPreviewImageUrl(), snapshot.getFirstFramePlan(), true, dispatch);
            frame(slots, issues, shot, shot.getEndFrameImageUrl(), snapshot.getLastFramePlan(), false, dispatch);
            // FL2VA consumes only its frame inputs. Other media must not be silently discarded.
            if (!read(shot.getRefImagesJson(), new TypeReference<List<ShotRefImageDTO>>() {}, json).isEmpty()
                    || !read(shot.getRefAudiosJson(), new TypeReference<List<ShotRefAudioDTO>>() {}, json).isEmpty())
                issues.add(issue(shot, "MEDIA_MODE_MISMATCH", null, "references", "首尾帧模式不挂载额外参考图片或音频", "移除额外参考素材，或切换参考生成模式"));
        }
        var report = RenderInputValidator.validate(shot.getId(), request.getPrompt(), reference, slots);
        report.getIssues().addAll(issues);
        report.setSubmissionReady(report.getIssues().isEmpty());
        report.setDispatchReady(report.isSubmissionReady() && slots.stream().noneMatch(Slot::pending));
        // Deliberately no timestamps or transport credentials in the fingerprint.
        try {
            report.setInputFingerprint(VideoBatchPreparation.hash(new ObjectMapper().writeValueAsString(Arrays.asList(
                    report.getRulesVersion(), shot.getGenerationMode(), request.getPrompt(), slots,
                    shot.getDuration(), request.getSize(), shot.getRefImagesJson(), shot.getRefAudiosJson(), shot.getRefVideosJson(),
                    snapshot.getModelFingerprint(), snapshot.getFirstFramePlan(), snapshot.getLastFramePlan(),
                    request.getReferenceVideoRevision(), request.getReferenceVideoNodeId()))));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return report;
    }

    private static void frame(List<Slot> slots, List<Issue> issues, DramaShot shot, String url,
                              VideoBatchModels.FramePlan plan, boolean first, boolean dispatch) {
        String label = first ? "<Picture 1>" : "<Picture 2>";
        boolean planned = plan != null && plan.getSourceShotId()!=null &&
                ("EXISTING_VIDEO".equals(plan.getType()) && StringUtils.isNotBlank(plan.getVideoUrl())
                        || "PRIOR_BATCH_RESULT".equals(plan.getType()) && StringUtils.isNotBlank(plan.getSourceTaskId()));
        boolean pending = StringUtils.isBlank(url) && planned;
        String source = StringUtils.trimToNull(url);
        if (pending) source = plan.getType() + ":" + plan.getSourceShotId() + ":" + plan.getVideoUrl() + ":" + plan.getTakeId();
        slots.add(new Slot(label, first ? "FIRST_FRAME" : "LAST_FRAME", "IMAGE", source,
                first ? "FIRST_FRAME" : "LAST_FRAME", pending));
        if (StringUtils.isBlank(url) && (!planned || dispatch))
            issues.add(issue(shot, "MEDIA_SOURCE_MISSING", label, first ? "previewImageUrl" : "endFrameImageUrl",
                    (first ? "首帧" : "尾帧") + " " + label + " 尚未就绪", "配置图片或有效的邻镜抽帧来源"));
    }

    private static Issue issue(DramaShot shot, String code, String label, String field, String message, String suggestion) {
        return new Issue(code, shot.getId(), label, field, null, message, suggestion);
    }
    private static <T> List<T> read(String value, TypeReference<List<T>> type, ObjectMapper json) {
        try { return StringUtils.isBlank(value) ? List.of() : Objects.requireNonNull(json.readValue(value, type)); }
        catch (Exception e) { throw new com.astra.freyja.common.BizException(400, "渲染素材快照无效"); }
    }
}
