package com.astra.freyja.agent.production;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.ProductionPreparationDTO.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.drama.manifest.KeyImageRole;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.BeanUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Applies only production fields, never narrative, voice, video or DirectorPlan JSON. */
@Service @RequiredArgsConstructor
public class ProductionPlans {
    public static final Set<String> LOCKABLE = Set.of("generationMode", "characterBindings", "visualAnchor", "propBindings", "referenceSlots", "firstFrameRequirement", "lastFrameRequirement", "prompts");
    private final DramaShotMapper shots;
    private final DramaMapper dramas;
    private final ShotProductionPlanMapper plans;
    private final AgentMaterialRequirementMapper requirements;
    private final AgentPreparationJobMapper jobMapper;
    private final ProductionJobs jobs;
    private final ProductionAssets assets;
    private final AgentJson json;
    private final AiTaskService tasks;
    private final ShotAiVisualPlanService prompts;
    private final DramaShotService shotService;

    public ShotProductionPlan current(Long shotId) {
        return plans.selectOne(new LambdaQueryWrapper<ShotProductionPlan>().eq(ShotProductionPlan::getShotId, shotId));
    }
    public DramaShot scopedShot(Long id, Long drama, boolean lock) {
        DramaShot s = id == null ? null : lock ? shots.lockById(id) : shots.selectById(id);
        if (s == null || !Objects.equals(s.getDramaId(), drama)) throw new BizException(400, "SCOPE_VIOLATION: 分镜不属于当前项目");
        // Ignore historical orphaned shots whose episode/scene has been removed.
        if (shots.selectActiveByDramaId(drama).stream().noneMatch(x -> Objects.equals(x.getId(), id))) throw new BizException("分镜所在场次或剧集已删除");
        return s;
    }
    public Object list(ShotQuery input, ToolContext ctx) {
        if (ctx.dramaId() == null) throw new BizException("请先完成拆解入库");
        AgentPreparationJob job = jobs.find(ctx.runId());
        Set<Long> target = job == null ? null : new HashSet<>(jobs.targets(job));
        int limit = input.limit() == null ? 8 : Math.clamp(input.limit(), 1, 12);
        var all = shots.selectActiveByDramaId(ctx.dramaId()).stream()
                .filter(s -> target == null || target.contains(s.getId()))
                .filter(s -> input.shotGroupId() == null || Objects.equals(input.shotGroupId(), s.getShotGroupId()))
                .sorted(Comparator.comparing(DramaShot::getId)).toList();
        var page = all.stream().filter(s -> input.afterShotId() == null || s.getId() > input.afterShotId()).limit(limit).toList();
        return Map.of("shots", page.stream().map(this::view).toList(), "total", all.size(), "hasMore", !page.isEmpty() && all.stream().anyMatch(s -> s.getId() > page.getLast().getId()),
                "nextAfterShotId", page.isEmpty() ? "" : page.getLast().getId().toString());
    }
    private Map<String, Object> view(DramaShot shot) {
        ShotProductionPlan p = current(shot.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shotId", shot.getId()); out.put("shotGroupId", shot.getShotGroupId()); out.put("shotNo", shot.getShotNo()); out.put("shotName", shot.getShotName());
        out.put("scriptContent", shot.getScriptContent()); out.put("actionDescription", shot.getActionDescription()); out.put("dialogue", shot.getDialogue());
        out.put("voiceover", shot.getVoiceover()); out.put("duration", shot.getDuration()); out.put("characterRefs", readMaps(shot.getCharacterRefsJson()));
        out.put("propRefs", readMaps(shot.getPropRefsJson())); out.put("resSceneId", shot.getResSceneId()); out.put("resKeyframeId", shot.getResKeyframeId());
        out.put("generationMode", shot.getGenerationMode()); out.put("expectedRevision", p == null ? 0 : p.getRevision());
        out.put("expectedFingerprint", fingerprint(shot, p == null ? null : read(p))); out.put("plan", p == null ? null : read(p));
        out.put("lockedFields", p == null ? List.of() : locks(p)); out.put("preparationStatus", liveStatus(shot, p)); return out;
    }
    public Plan read(ShotProductionPlan p) { return json.read(p.getPlanJson(), Plan.class); }
    private List<String> locks(ShotProductionPlan p) { return p.getLockedFieldsJson() == null ? List.of() : Arrays.asList(json.read(p.getLockedFieldsJson(), String[].class)); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> readMaps(String s) { return !ProductionAssets.text(s) ? List.of() : Arrays.asList(json.read(s, Map[].class)); }
    private Long id(Object x) { return x == null ? null : Long.valueOf(x.toString()); }

    public String fingerprint(DramaShot s, Plan p) {
        Map<String, Object> basis = new TreeMap<>();
        for (String key : List.of("id", "dramaId", "episodeId", "sceneId", "shotGroupId", "shotNo", "shotName", "scriptContent", "actionDescription", "dialogue", "dialogueSpeaker", "voiceover", "soundEffect", "duration",
                "shotTypeLocked", "cameraMovementLocked", "resSceneId", "resKeyframeId", "customScenePrompt", "characterRefsJson", "propRefsJson", "generationMode", "refImagesJson", "refAudiosJson", "stylePreset",
                "prompt", "videoPrompt", "firstFramePrompt", "endFramePrompt", "negativePrompt", "previewImageUrl", "endFrameImageUrl", "firstFrameSourceType", "firstFrameSourceShotId")) {
            try { basis.put(key, new org.springframework.beans.BeanWrapperImpl(s).getPropertyValue(key)); }
            catch (RuntimeException e) { throw new IllegalStateException(key, e); }
        }
        if (Boolean.TRUE.equals(s.getShotTypeLocked())) basis.put("shotType", s.getShotType());
        if (Boolean.TRUE.equals(s.getCameraMovementLocked())) basis.put("cameraMovement", s.getCameraMovement());
        basis.put("directorPlanMode", s.getDirectorPlanMode());
        if ("MANUAL".equals(s.getDirectorPlanMode())) basis.put("manualDirectorPlan", Arrays.asList(s.getDirectorPlanRevision(), s.getDirectorPlanJson()));
        Drama d = dramas.selectById(s.getDramaId());
        if (d != null) basis.put("style", Arrays.asList(d.getAspectRatio(), d.getStylePreset(), d.getStyleTone()));
        Map<String, Object> materials = new TreeMap<>();
        if (s.getResSceneId() != null) addBasis(materials, "SCENE", s.getResSceneId(), s.getDramaId());
        if (s.getResKeyframeId() != null) addBasis(materials, "KEYFRAME", s.getResKeyframeId(), s.getDramaId());
        for (var r : readMaps(s.getCharacterRefsJson())) {
            addBasis(materials, "CHARACTER", id(r.get("characterId")), s.getDramaId());
            if (r.get("lookId") != null) addBasis(materials, "CHARACTER_LOOK", id(r.get("lookId")), s.getDramaId());
        }
        for (var r : readMaps(s.getPropRefsJson())) addBasis(materials, "PROP", id(r.get("propId")), s.getDramaId());
        if (p != null) {
            Plan normalized = json.copy(p, Plan.class); normalized.setExpectedFingerprint(null); normalized.setExpectedRevision(null); basis.put("plan", normalized);
            for (var r : p.getReferenceSlots()) addBasis(materials, r.getSourceType(), r.getSourceId(), s.getDramaId());
            for (var c : p.getCharacterBindings()) { addBasis(materials, "CHARACTER", c.getCharacterId(), s.getDramaId()); addBasis(materials, "CHARACTER_LOOK", c.getLookId(), s.getDramaId()); }
            if (p.getFirstFrameRequirement() != null && p.getFirstFrameRequirement().getKeyframeId() != null) addBasis(materials, "KEYFRAME", p.getFirstFrameRequirement().getKeyframeId(), s.getDramaId());
            if (p.getLastFrameRequirement() != null && p.getLastFrameRequirement().getKeyframeId() != null) addBasis(materials, "KEYFRAME", p.getLastFrameRequirement().getKeyframeId(), s.getDramaId());
        }
        basis.put("materials", materials);
        return digest(json.write(basis));
    }
    private void addBasis(Map<String, Object> out, String type, Long id, Long drama) {
        if (id == null) return;
        try {
            var m = assets.resolve(type, id, drama);
            // Image status and description/prompt changes invalidate the plan. Avoid unrelated audit timestamps.
            out.put(type + ":" + id, assets.candidate(m));
        } catch (BizException invalid) {
            // Existing deleted/invalid references must be repairable through a valid new plan.
            // Never read or accept the unavailable asset; include a stable invalid marker in the basis.
            out.put(type + ":" + id, Map.of("unavailable", true, "reason", invalid.getMessage()));
        }
    }
    public static String digest(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public void validate(Plan p, Long drama) {
        if (p == null || p.getShotId() == null || !Set.of("FIRST_LAST_FRAME", "REFERENCE_MODE").contains(Objects.toString(p.getGenerationMode(), ""))) throw new BizException("分镜 ID 与生成模式必填");
        if (!ProductionAssets.text(p.getDecisionReason())) throw new BizException("需要保存模式选择依据");
        if (p.getCharacterBindings() == null || p.getPropBindings() == null || p.getReferenceSlots() == null || p.getReferenceSlots().size() > 9) throw new BizException("关联数组不能为 null，参考图最多 9 个");
        Set<Long> cids = new HashSet<>();
        for (var c : p.getCharacterBindings()) {
            if (c == null || !cids.add(c.getCharacterId()) || !Set.of("SUBJECT", "NON_SUBJECT").contains(Objects.toString(c.getUsageRole(), ""))) throw new BizException("人物绑定重复或用途不合法");
            var l = assets.resolve("CHARACTER_LOOK", c.getLookId(), drama);
            if (!Objects.equals(l.characterId(), c.getCharacterId())) throw new BizException("人物与造型不匹配");
        }
        Set<Long> pids = new HashSet<>();
        for (var b : p.getPropBindings()) { if (b == null || !pids.add(b.getPropId()) || !ProductionAssets.text(b.getPurpose())) throw new BizException("道具 ID 唯一且用途必填"); assets.resolve("PROP", b.getPropId(), drama); }
        if (p.getVisualAnchor() != null) {
            if (!Set.of("SCENE", "KEYFRAME").contains(Objects.toString(p.getVisualAnchor().getSourceType(), ""))) throw new BizException("主视觉基准只能选一个场景或关键图");
            var anchor = assets.resolve(p.getVisualAnchor().getSourceType(), p.getVisualAnchor().getSourceId(), drama);
            keyframeScope(anchor, p.getShotId());
        }
        Set<String> keys = new HashSet<>(), materialRoles = new HashSet<>();
        for (var r : p.getReferenceSlots()) {
            if (r == null || !ProductionAssets.text(r.getSlotKey()) || !keys.add(r.getSlotKey()) || !ProductionAssets.text(r.getDescription())) throw new BizException("参考槽位需要唯一 slotKey 和具体用途 description");
            if (!Set.of("CHARACTER_LOOK", "SCENE", "PROP", "KEYFRAME").contains(Objects.toString(r.getSourceType(), ""))) throw new BizException("参考图来源不合法");
            var m = assets.resolve(r.getSourceType(), r.getSourceId(), drama); keyframeScope(m, p.getShotId());
            String roleKey = r.getSourceType() + ":" + r.getSourceId() + ":" + r.getUsageRole() + ":" + r.getReferenceRole();
            if (!materialRoles.add(roleKey)) throw new BizException("不能重复同一素材的同一用途");
            if ("CHARACTER_LOOK".equals(r.getSourceType())) {
                if (!Objects.equals(m.characterId(), r.getCharacterId()) || !Objects.equals(m.id(), r.getLookId())) throw new BizException("人物参考需要正确 characterId 和 lookId（sourceId=lookId）");
                var binding = p.getCharacterBindings().stream().filter(c -> Objects.equals(c.getCharacterId(), r.getCharacterId()) && Objects.equals(c.getLookId(), r.getLookId())).findFirst().orElseThrow(() -> new BizException("人物参考必须有业务绑定"));
                if (!Objects.equals(binding.getUsageRole(), r.getUsageRole()) || !Set.of("IDENTITY", "LOOK", "COMBINED", "POSE", "STYLE").contains(Objects.toString(r.getReferenceRole(), "")))
                    throw new BizException("人物槽位usageRole必须与绑定一致（SUBJECT/NON_SUBJECT）；referenceRole只允许IDENTITY、LOOK、COMBINED、POSE、STYLE");
            } else if ("KEYFRAME".equals(r.getSourceType())) KeyImageRole.requireCode(r.getUsageRole());
            else if ("SCENE".equals(r.getSourceType()) && !Set.of("SCENE_LAYOUT", "STYLE").contains(Objects.toString(r.getUsageRole(), ""))) throw new BizException("场景参考用途不合法");
            else if ("PROP".equals(r.getSourceType()) && (!pids.contains(r.getSourceId()) || !Set.of("PROP_APPEARANCE", "STYLE").contains(Objects.toString(r.getUsageRole(), "")))) throw new BizException("道具参考需要业务绑定与外观用途");
        }
        for (var c : p.getCharacterBindings()) {
            if ("SUBJECT".equals(c.getUsageRole()) && p.getReferenceSlots().stream().noneMatch(r -> r.isRequired() && "CHARACTER_LOOK".equals(r.getSourceType()) && Objects.equals(r.getLookId(), c.getLookId())))
                throw new BizException("可见人物需要必需造型参考槽位，以保持人物一致性");
        }
        validateFrame(p.getFirstFrameRequirement(), "FIRST_FRAME", p.getShotId(), drama);
        validateFrame(p.getLastFrameRequirement(), "LAST_FRAME", p.getShotId(), drama);
        if ("FIRST_LAST_FRAME".equals(p.getGenerationMode()) && (p.getFirstFrameRequirement() == null || !p.getFirstFrameRequirement().isRequired())) throw new BizException("首尾帧模式需要明确的必需首帧关键图");
        if ("REFERENCE_MODE".equals(p.getGenerationMode()) && p.getReferenceSlots().isEmpty()) throw new BizException("参考图模式需要至少一个真实资产槽位");
    }
    private void keyframeScope(ProductionAssets.Material m, Long shot) {
        if (m.basis() instanceof ResKeyframe k && k.getShotId() != null && !Objects.equals(k.getShotId(), shot)) throw new BizException("关键图已绑定另一分镜");
    }
    private void validateFrame(FrameRequirement r, String role, Long shot, Long drama) {
        if (r == null || !r.isRequired()) return;
        if (r.getKeyframeId() == null || !ProductionAssets.text(r.getDescription())) throw new BizException("必需首尾帧需要真实关键图 ID 与完整画面描述");
        var m = assets.resolve("KEYFRAME", r.getKeyframeId(), drama); keyframeScope(m, shot);
        if (!role.equals(((ResKeyframe)m.basis()).getFrameType())) throw new BizException("首尾帧关键图用途必须分别为 FIRST_FRAME / LAST_FRAME");
    }

    @Transactional public ToolResult save(Plans input, ToolContext ctx) {
        if (input.plans() == null || input.plans().isEmpty() || input.plans().size() > 10) throw new BizException("每次提交 1–10 个分镜规划");
        List<Plan> batch = new ArrayList<>(input.plans());
        if (batch.stream().anyMatch(Objects::isNull) || batch.stream().map(Plan::getShotId).distinct().count() != batch.size()) throw new BizException("不能重复提交分镜");
        batch.sort(Comparator.comparing(Plan::getShotId));
        var job = jobs.editJob(ctx, batch.stream().map(Plan::getShotId).toList());
        List<Object> result = new ArrayList<>();
        for (var p : batch) {
            if (!jobs.targets(job).contains(p.getShotId())) throw new BizException("分镜不属于本次交付范围");
            DramaShot s = scopedShot(p.getShotId(), job.getDramaId(), true);
            ShotProductionPlan old = plans.lockByShotId(s.getId());
            if (p.getExpectedRevision() == null || p.getExpectedRevision() != (old == null ? 0 : old.getRevision()) || !Objects.equals(p.getExpectedFingerprint(), fingerprint(s, old == null ? null : read(old))))
                throw new BizException(409, "PLAN_VERSION_CONFLICT: 分镜或资产已修改，请重新 list_preparation_shots 后规划");
            validate(p, job.getDramaId()); checkLocks(p, old);
            ShotProductionPlan saved = old == null ? new ShotProductionPlan() : old;
            saved.setDramaId(job.getDramaId()); saved.setShotId(s.getId()); saved.setRunId(ctx.runId());
            saved.setRevision(old == null ? 1 : old.getRevision() + 1); saved.setInputFingerprint(p.getExpectedFingerprint()); saved.setPlanJson(json.write(p));
            if (old == null) saved.setLockedFieldsJson("[]");
            if (locks(saved).contains("prompts")) throw new BizException("提示词已被用户锁定，修改制作规划前请解除锁定");
            apply(s, p, true);
            s = shots.selectById(s.getId()); saved.setAppliedFingerprint(fingerprint(s, p));
            saved.setShotStateFingerprint(shotStateFingerprint(s));
            saved.setPreparationStatus(missing(p, s.getDramaId()).isEmpty() ? "NEEDS_REFRESH" : "WAITING_ASSET_IMAGES");
            if (old == null) plans.insert(saved); else plans.updateById(saved);
            clearPromptState(saved);
            result.add(view(s));
        }
        invalidateReport(job); return ToolResult.success(result);
    }
    private void checkLocks(Plan next, ShotProductionPlan old) {
        if (old == null) return;
        var before = new org.springframework.beans.BeanWrapperImpl(read(old)); var after = new org.springframework.beans.BeanWrapperImpl(next);
        for (String field : locks(old)) if (!"prompts".equals(field) && !Objects.equals(json.write(before.getPropertyValue(field)), json.write(after.getPropertyValue(field)))) throw new BizException("LOCKED_FIELD: " + field + " 已被用户锁定");
    }
    private void clearPromptState(ShotProductionPlan p) {
        plans.update(null, new LambdaUpdateWrapper<ShotProductionPlan>().eq(ShotProductionPlan::getId, p.getId())
                .set(ShotProductionPlan::getPromptFingerprint, null).set(ShotProductionPlan::getPromptTaskId, null)
                .set(ShotProductionPlan::getPromptTaskFingerprint, null).set(ShotProductionPlan::getPromptTaskRevision, null));
    }
    private void invalidateReport(AgentPreparationJob job) {
        jobMapper.update(null, new LambdaUpdateWrapper<AgentPreparationJob>().eq(AgentPreparationJob::getId, job.getId()).set(AgentPreparationJob::getReportJson, null).set(AgentPreparationJob::getStatus, "COMMITTED"));
    }
    private void apply(DramaShot s, Plan p, boolean clearPrompts) {
        List<CharacterShotRefInfoVO> cs = new ArrayList<>();
        for (var b : p.getCharacterBindings()) if ("SUBJECT".equals(b.getUsageRole())) {
            var m = assets.resolve("CHARACTER_LOOK", b.getLookId(), s.getDramaId());
            var c = new CharacterShotRefInfoVO(); BeanUtils.copyProperties(b, c); c.setCharacterName(m.name()); cs.add(c);
        }
        List<PropShotRefInfoVO> ps = p.getPropBindings().stream().map(b -> {
            var m = assets.resolve("PROP", b.getPropId(), s.getDramaId()); var prop = (ResProp)m.basis();
            return PropShotRefInfoVO.builder().propId(prop.getId()).propName(prop.getName()).propPrompt(prop.getPropPrompt()).propType(prop.getPropType()).coverUrl(prop.getCoverUrl()).build();
        }).toList();
        List<ShotRefImageDTO> refs = p.getReferenceSlots().stream().map(r -> assets.reference(r, s.getDramaId())).filter(Objects::nonNull).toList();
        var u = new LambdaUpdateWrapper<DramaShot>().eq(DramaShot::getId, s.getId())
                .set(DramaShot::getGenerationMode, p.getGenerationMode()).set(DramaShot::getCharacterRefsJson, json.write(cs)).set(DramaShot::getPropRefsJson, json.write(ps))
                .set(DramaShot::getResSceneId, p.getVisualAnchor() != null && "SCENE".equals(p.getVisualAnchor().getSourceType()) ? p.getVisualAnchor().getSourceId() : null)
                .set(DramaShot::getResKeyframeId, p.getVisualAnchor() != null && "KEYFRAME".equals(p.getVisualAnchor().getSourceType()) ? p.getVisualAnchor().getSourceId() : null)
                .set(DramaShot::getRefImagesJson, json.write(refs));
        if ("FIRST_LAST_FRAME".equals(p.getGenerationMode())) {
            String first = frameUrl(p.getFirstFrameRequirement(), s.getDramaId()), last = frameUrl(p.getLastFrameRequirement(), s.getDramaId());
            u.set(DramaShot::getPreviewImageUrl, first).set(DramaShot::getEndFrameImageUrl, last)
                    .set(DramaShot::getFirstFrameSourceType, first == null ? null : "MANUAL_UPLOAD")
                    .set(DramaShot::getFirstFrameSourceShotId, null).set(DramaShot::getFirstFrameSourceVideoUrl, null).set(DramaShot::getFirstFrameSourceVideoTakeId, null);
        }
        if (clearPrompts) u.set(DramaShot::getPrompt, null).set(DramaShot::getVideoPrompt, null).set(DramaShot::getFirstFramePrompt, null).set(DramaShot::getEndFramePrompt, null).set(DramaShot::getNegativePrompt, null);
        shots.update(null, u);
    }
    private String frameUrl(FrameRequirement r, Long drama) {
        if (r == null || !r.isRequired()) return null;
        var m = assets.resolve("KEYFRAME", r.getKeyframeId(), drama); return "SYNCED".equals(m.imageStatus()) ? m.imageUrl() : null;
    }
    public record Missing(String materialKey, String sourceType, Long sourceId, String name, String description, String prompt, String status, List<String> purposes) {}
    public List<Missing> missing(Plan p, Long drama) {
        Map<String, Missing> out = new LinkedHashMap<>();
        for (var r : p.getReferenceSlots()) if (r.isRequired()) collectMissing(out, assets.resolve(r.getSourceType(), r.getSourceId(), drama), r.getDescription());
        if (p.getFirstFrameRequirement() != null && p.getFirstFrameRequirement().isRequired()) collectMissing(out, assets.resolve("KEYFRAME", p.getFirstFrameRequirement().getKeyframeId(), drama), p.getFirstFrameRequirement().getDescription());
        if (p.getLastFrameRequirement() != null && p.getLastFrameRequirement().isRequired()) collectMissing(out, assets.resolve("KEYFRAME", p.getLastFrameRequirement().getKeyframeId(), drama), p.getLastFrameRequirement().getDescription());
        return new ArrayList<>(out.values());
    }
    private void collectMissing(Map<String, Missing> out, ProductionAssets.Material m, String purpose) {
        if ("SYNCED".equals(m.imageStatus()) && ProductionAssets.text(m.imageUrl())) return;
        String key = m.type() + ":" + m.id();
        Missing old = out.get(key); List<String> purposes = new ArrayList<>(old == null ? List.of() : old.purposes());
        if (ProductionAssets.text(purpose) && !purposes.contains(purpose)) purposes.add(purpose);
        out.put(key, new Missing(key, m.type(), m.id(), m.name(), m.description(), m.prompt(), m.imageStatus(), purposes));
    }
    private boolean promptPresent(DramaShot s, Plan p) {
        return ProductionAssets.text(s.getVideoPrompt()) && (!"FIRST_LAST_FRAME".equals(p.getGenerationMode()) || ProductionAssets.text(s.getFirstFramePrompt())
                && (p.getLastFrameRequirement() == null || !p.getLastFrameRequirement().isRequired() || ProductionAssets.text(s.getEndFramePrompt())));
    }
    public String liveStatus(DramaShot s, ShotProductionPlan p) {
        if (p == null) return "PENDING";
        try {
            Plan data = read(p); validate(data, s.getDramaId());
            if (!Objects.equals(p.getAppliedFingerprint(), fingerprint(s, data))) return "NEEDS_REFRESH";
            if (!missing(data, s.getDramaId()).isEmpty()) return "WAITING_ASSET_IMAGES";
            return Objects.equals(p.getPromptFingerprint(), p.getAppliedFingerprint()) && promptPresent(s, data) ? "READY" : "NEEDS_REFRESH";
        } catch (BizException invalid) { return "ERROR"; }
    }

    @Transactional public Object refresh(Refresh input) {
        if (input.dramaId() == null || input.shotIds() == null || input.shotIds().isEmpty() || input.shotIds().size() > 100) throw new BizException("请指定项目与 1–100 个分镜");
        List<Object> result = new ArrayList<>();
        for (Long id : input.shotIds().stream().distinct().sorted().toList()) {
            DramaShot s = scopedShot(id, input.dramaId(), true); ShotProductionPlan p = plans.lockByShotId(id);
            if (p == null) { result.add(view(s)); continue; }
            String currentStatus = liveStatus(s, p);
            if ("READY".equals(currentStatus) || "WAITING_ASSET_IMAGES".equals(currentStatus)) { result.add(view(s)); continue; }
            // Only asset image changes can be refreshed automatically. Explicit shot edits require replanning.
            if (!projectionMatches(s, read(p)) || !Objects.equals(p.getShotStateFingerprint(), shotStateFingerprint(s))) { p.setPreparationStatus("NEEDS_REFRESH"); plans.updateById(p); result.add(view(s)); continue; }
            if (locks(p).contains("referenceSlots") || locks(p).contains("prompts")) { result.add(view(s)); continue; }
            validate(read(p), input.dramaId()); apply(s, read(p), true); s = shots.selectById(id);
            p.setAppliedFingerprint(fingerprint(s, read(p))); p.setPreparationStatus(missing(read(p), input.dramaId()).isEmpty() ? "NEEDS_REFRESH" : "WAITING_ASSET_IMAGES");
            p.setShotStateFingerprint(shotStateFingerprint(s));
            plans.updateById(p); clearPromptState(p); result.add(view(s));
        }
        return result;
    }
    private boolean projectionMatches(DramaShot s, Plan p) {
        // Excludes mutable images; never restore a manually changed association or mode.
        return Objects.equals(s.getGenerationMode(), p.getGenerationMode())
                && Objects.equals(s.getResSceneId(), p.getVisualAnchor() != null && "SCENE".equals(p.getVisualAnchor().getSourceType()) ? p.getVisualAnchor().getSourceId() : null)
                && Objects.equals(s.getResKeyframeId(), p.getVisualAnchor() != null && "KEYFRAME".equals(p.getVisualAnchor().getSourceType()) ? p.getVisualAnchor().getSourceId() : null)
                && readMaps(s.getCharacterRefsJson()).stream().map(m -> id(m.get("characterId"))).toList().equals(p.getCharacterBindings().stream().filter(c -> "SUBJECT".equals(c.getUsageRole())).map(CharacterBinding::getCharacterId).toList())
                && readMaps(s.getPropRefsJson()).stream().map(m -> id(m.get("propId"))).toList().equals(p.getPropBindings().stream().map(PropBinding::getPropId).toList());
    }
    private String shotStateFingerprint(DramaShot s) {
        // Resource image updates are absent here; every manually editable shot input remains protected.
        Map<String, Object> state = new TreeMap<>();
        var bean = new org.springframework.beans.BeanWrapperImpl(s);
        for (String key : List.of("scriptContent", "actionDescription", "dialogue", "dialogueSpeaker", "voiceover", "soundEffect", "duration", "generationMode", "resSceneId", "resKeyframeId", "customScenePrompt",
                "characterRefsJson", "propRefsJson", "refImagesJson", "refAudiosJson", "stylePreset", "prompt", "videoPrompt", "firstFramePrompt", "endFramePrompt", "negativePrompt", "previewImageUrl", "endFrameImageUrl", "shotTypeLocked", "cameraMovementLocked")) state.put(key, bean.getPropertyValue(key));
        return digest(json.write(state));
    }
    @Transactional public Object lock(Long shotId, Locks input) {
        DramaShot s = shots.lockById(shotId); if (s == null) throw new BizException("分镜不存在");
        ShotProductionPlan p = plans.lockByShotId(shotId); if (p == null) throw new BizException("分镜尚无制作规划");
        if (input.expectedRevision() != p.getRevision()) throw new BizException(409, "规划版本已改变");
        if (input.fields() == null || !LOCKABLE.containsAll(input.fields())) throw new BizException("锁定字段不合法");
        p.setLockedFieldsJson(json.write(input.fields().stream().distinct().toList())); p.setRevision(p.getRevision() + 1); plans.updateById(p); return view(s);
    }
    public Object detail(Long shotId) {
        DramaShot s = shots.selectById(shotId); if (s == null) throw new BizException("分镜不存在"); return view(s);
    }

    public ToolResult prompt(ShotInput input, ToolContext ctx, WaitHandle handle) {
        var job = jobs.editJob(ctx, List.of(input.shotId()));
        if (!jobs.targets(job).contains(input.shotId())) throw new BizException("分镜不属于当前任务");
        DramaShot s = scopedShot(input.shotId(), job.getDramaId(), true); ShotProductionPlan p = plans.lockByShotId(s.getId());
        if (p == null) throw new BizException("请先保存制作规划");
        Plan data = read(p); validate(data, s.getDramaId()); String basis = fingerprint(s, data);
        if (!Objects.equals(basis, p.getAppliedFingerprint())) return ToolResult.error("PLAN_NEEDS_REFRESH", "素材或分镜已改变，请显式刷新准备状态或重新规划");
        if (!missing(data, s.getDramaId()).isEmpty()) return ToolResult.success(Map.of("shotId", s.getId(), "status", "WAITING_ASSET_IMAGES", "message", "已保存文字方案，补图后再生成最终提示词"));
        if ("READY".equals(liveStatus(s, p))) return ToolResult.success(Map.of("shotId", s.getId(), "status", "READY"));
        if (locks(p).contains("prompts")) throw new BizException("提示词已锁定，请用户解除锁定后刷新");
        if (p.getPromptTaskId() == null) {
            if (!Objects.equals(input.expectedFingerprint(), basis)) throw new BizException(409, "分镜指纹已变化，请重新查询");
            var detail = shotService.getById(s.getId()); var dto = new ShotPromptDeriveDTO(); BeanUtils.copyProperties(detail, dto);
            dto.setShotId(s.getId()); dto.setDuration(s.getDuration() == null ? null : s.getDuration().doubleValue());
            dto.setProviderId(ctx.snapshot().config().getModel().getProviderId()); dto.setModelCode(ctx.snapshot().config().getModel().getModelCode());
            dto.setRefImages(Arrays.asList(json.read(s.getRefImagesJson(), ShotRefImageDTO[].class))); dto.setReplanOnInputChange(true);
            dto.setDirectorPlanInput(com.astra.freyja.director.model.DirectorPlanInput.fromShot(s));
            dto.setUserInstruction("严格遵循已保存制作规划：" + json.write(data));
            dto.setRequiredSkillNames(ctx.snapshot().config().getSkills().getNames());
            dto.setIncludeBgm(Boolean.TRUE.equals(data.getIncludeBgm()));
            p.setPromptTaskId(Long.valueOf(prompts.startPromptTask(dto))); p.setPromptTaskFingerprint(basis); p.setPromptTaskRevision(p.getRevision()); plans.updateById(p);
        }
        var task = tasks.getTaskById(p.getPromptTaskId());
        if (task == null) throw new BizException("提示词子任务不存在");
        if ("FAILED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
            clearPromptState(p);
            return ToolResult.error("PROMPT_FAILED", Objects.toString(task.getErrorMessage(), task.getStatus()) + "；可基于当前指纹重新提交提示词任务");
        }
        if (!"SUCCESS".equals(task.getStatus())) {
            if (handle != null && !AiTaskExecutionRegistry.isActive(task.getId())) prompts.resumePromptTask(task.getId());
            return ToolResult.deferred(ProductionJobs.waitFor(ctx, "production-prompts", task.getId(), handle));
        }
        if (!Objects.equals(p.getPromptTaskFingerprint(), fingerprint(s, data)) || !Objects.equals(p.getPromptTaskRevision(), p.getRevision()))
            return ToolResult.error("STALE_PROMPT_RESULT", "提示词生成期间分镜或资产已修改，保留结果但不覆盖，请重新规划");
        ShotPromptDeriveVO result = json.read(task.getOutputPayload(), ShotPromptDeriveVO.class);
        if (result.getDirectorPlanRevision() != null) {
            int expectedDirectorRevision = result.getDirectorPlanRevision(); // Existing service returns the revision after persistence.
            if (!Objects.equals(s.getDirectorPlanRevision(), expectedDirectorRevision) || !Objects.equals(s.getDirectorPlanMode(), result.getDirectorPlanMode()))
                return ToolResult.error("DIRECTOR_PLAN_CHANGED", "导演计划在生成后已修改，请重新查询并规划");
        }
        if (!ProductionAssets.text(result.getVideoPrompt()) || "FIRST_LAST_FRAME".equals(data.getGenerationMode()) && !ProductionAssets.text(result.getFirstFramePrompt())) throw new BizException("提示词子任务未产出所需内容");
        shots.update(null, new LambdaUpdateWrapper<DramaShot>().eq(DramaShot::getId, s.getId())
                .set(DramaShot::getPrompt, result.getPrompt()).set(DramaShot::getVideoPrompt, result.getVideoPrompt())
                .set(DramaShot::getFirstFramePrompt, result.getFirstFramePrompt()).set(DramaShot::getEndFramePrompt, result.getEndFramePrompt()).set(DramaShot::getNegativePrompt, result.getNegativePrompt()));
        s = shots.selectById(s.getId()); p.setAppliedFingerprint(fingerprint(s, data)); p.setPromptFingerprint(p.getAppliedFingerprint());
        p.setShotStateFingerprint(shotStateFingerprint(s));
        p.setPreparationStatus(promptPresent(s, data) ? "READY" : "NEEDS_REFRESH"); plans.updateById(p); invalidateReport(job);
        return ToolResult.success(view(s));
    }

    /** Recheck current state before the final reply; a saved snapshot alone cannot certify a later edit. */
    public boolean isDeliveryCurrent(AgentPreparationJob job) {
        if (!"PREPARED".equals(job.getStatus()) || job.getReportJson() == null) return false;
        try {
            @SuppressWarnings("unchecked") var report = json.read(job.getReportJson(), Map.class);
            if (!Boolean.TRUE.equals(report.get("complete")) || !(report.get("shots") instanceof List<?> rows)) return false;
            var target = new HashSet<>(jobs.targets(job));
            if (target.isEmpty() || target.size() != rows.size()) return false;
            for (Object raw : rows) {
                if (!(raw instanceof Map<?, ?> row)) return false;
                Long shotId = id(row.get("shotId"));
                if (!target.remove(shotId)) return false;
                DramaShot shot = scopedShot(shotId, job.getDramaId(), false); ShotProductionPlan plan = current(shotId);
                if (plan == null || !Objects.equals(row.get("planRevision").toString(), plan.getRevision().toString())) return false;
                String status = liveStatus(shot, plan);
                if (!Set.of("READY", "WAITING_ASSET_IMAGES").contains(status) || !Objects.equals(status, row.get("status"))) return false;
            }
            return target.isEmpty();
        } catch (BizException invalid) { return false; }
    }

    public ToolResult inspect(ToolContext ctx) {
        var job = jobs.require(ctx, true); List<Long> ids = jobs.targets(job);
        if (ids.isEmpty()) throw new BizException("没有已入库的目标分镜");
        List<Map<String, Object>> shotRows = new ArrayList<>(); Map<String, Map<String, Object>> needs = new LinkedHashMap<>();
        List<Long> incomplete = new ArrayList<>(); int ready = 0, waiting = 0;
        Map<String, Object> incompleteText = new LinkedHashMap<>();
        for (Long id : ids.stream().sorted().toList()) {
            DramaShot s = scopedShot(id, job.getDramaId(), true); ShotProductionPlan p = plans.lockByShotId(id);
            String status = liveStatus(s, p); if (p != null) { p.setPreparationStatus(status); plans.updateById(p); }
            if ("READY".equals(status)) ready++; else if ("WAITING_ASSET_IMAGES".equals(status)) waiting++; else incomplete.add(id);
            Map<String, Object> row = new LinkedHashMap<>(); row.put("shotId", id); row.put("shotName", s.getShotName()); row.put("shotNo", s.getShotNo()); row.put("generationMode", s.getGenerationMode()); row.put("status", status);
            row.put("decisionReason", p == null ? null : read(p).getDecisionReason()); row.put("planRevision", p == null ? 0 : p.getRevision()); shotRows.add(row);
            if (p != null) for (Missing m : missing(read(p), job.getDramaId())) addRequirement(needs, m, id, job.getDramaId());
            if (p != null) for (Missing m : missing(read(p), job.getDramaId())) if (!ProductionAssets.text(m.description()))
                incompleteText.put(m.materialKey(), assets.candidate(assets.resolve(m.sourceType(), m.sourceId(), job.getDramaId())));
        }
        List<Map<String, Object>> registry = jobs.registry(job); Map<String, Map<String, Object>> assetRows = new LinkedHashMap<>();
        for (Map<String, Object> entry : registry) {
            var m = assets.resolve(Objects.toString(entry.get("type")), id(entry.get("id")), job.getDramaId());
            String identity = m.type() + ":" + m.id();
            var row = new LinkedHashMap<>(assets.candidate(m));
            row.put("created", Boolean.TRUE.equals(entry.get("created")) || assetRows.containsKey(identity) && Boolean.TRUE.equals(assetRows.get(identity).get("created")));
            assetRows.put(identity, row);
            if (Boolean.TRUE.equals(entry.get("created")) && !ProductionAssets.text(m.description()))
                incompleteText.put(m.type() + ":" + m.id(), assets.candidate(m));
            if (Boolean.TRUE.equals(entry.get("created")) && !"CHARACTER".equals(m.type()) && !"SYNCED".equals(m.imageStatus())) {
                Map<String, Missing> one = new LinkedHashMap<>(); collectMissing(one, m, "新建文字资产需要生成或上传图片");
                one.values().forEach(x -> addRequirement(needs, x, null, job.getDramaId()));
            }
        }
        // Resolved material records remain durable; historical delivery snapshots are unchanged by later refreshes.
        for (var req : requirements.selectList(new LambdaQueryWrapper<AgentMaterialRequirement>().eq(AgentMaterialRequirement::getDramaId, job.getDramaId()))) {
            try { var m = assets.resolve(req.getSourceType(), req.getSourceId(), job.getDramaId()); req.setStatus(m.imageStatus()); requirements.updateById(req); }
            catch (BizException removed) { req.setStatus("MISSING"); requirements.updateById(req); }
        }
        Map<String, Object> report = new LinkedHashMap<>(); report.put("runId", ctx.runId()); report.put("dramaId", job.getDramaId());
        report.put("dramaTitle", dramas.selectById(job.getDramaId()).getTitle()); report.put("shotCount", ids.size()); report.put("plannedCount", ready + waiting);
        report.put("readyCount", ready); report.put("waitingImageCount", waiting); report.put("shots", shotRows); report.put("assets", new ArrayList<>(assetRows.values()));
        boolean complete = incomplete.isEmpty() && incompleteText.isEmpty();
        report.put("missingMaterials", new ArrayList<>(needs.values())); report.put("incompleteShotIds", incomplete); report.put("incompleteTextAssets", new ArrayList<>(incompleteText.values())); report.put("complete", complete);
        report.put("message", complete ? needs.isEmpty() ? "制作准备已完成，参考图片与最终提示词已保存。" : "制作规划已完成；请按清单生成或上传缺失图片，再刷新准备状态。"
                : !incompleteText.isEmpty() ? "资产文字设定不完整，请用 ensure_text_assets 的 existingId+updateText 补齐，再重新查询指纹并规划。" : "仍有分镜未完成规划或最终提示词，需要继续处理。");
        if (complete) { job.setReportJson(json.write(report)); job.setStatus("PREPARED"); jobMapper.updateById(job); }
        return ToolResult.success(report);
    }
    @SuppressWarnings("unchecked") private void addRequirement(Map<String, Map<String, Object>> out, Missing m, Long shot, Long drama) {
        var req = requirements.selectOne(new LambdaQueryWrapper<AgentMaterialRequirement>().eq(AgentMaterialRequirement::getDramaId, drama).eq(AgentMaterialRequirement::getMaterialKey, m.materialKey()));
        if (req == null) {
            req = new AgentMaterialRequirement(); req.setDramaId(drama); req.setMaterialKey(m.materialKey()); req.setSourceType(m.sourceType()); req.setSourceId(m.sourceId());
            req.setName(m.name()); req.setDescription(m.description()); req.setStatus(m.status()); requirements.insert(req);
        } else { req.setName(m.name()); req.setDescription(m.description()); req.setStatus(m.status()); requirements.updateById(req); }
        Map<String, Object> row = out.get(m.materialKey());
        if (row == null) {
            row = new LinkedHashMap<>(); row.put("requirementId", req.getId()); row.put("materialKey", m.materialKey()); row.put("sourceType", m.sourceType()); row.put("sourceId", m.sourceId());
            row.put("name", m.name()); row.put("description", m.description()); row.put("prompt", m.prompt()); row.put("status", m.status()); row.put("shotIds", new ArrayList<Long>()); row.put("purposes", new ArrayList<String>()); out.put(m.materialKey(), row);
            row.put("characterId", assets.resolve(m.sourceType(), m.sourceId(), drama).characterId());
        }
        var ids = (List<Long>)row.get("shotIds"); if (shot != null && !ids.contains(shot)) ids.add(shot);
        var purposes = (List<String>)row.get("purposes"); for (String x : m.purposes()) if (!purposes.contains(x)) purposes.add(x);
    }
}
