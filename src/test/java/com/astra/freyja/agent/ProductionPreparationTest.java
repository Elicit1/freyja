package com.astra.freyja.agent;

import com.astra.freyja.agent.production.*;
import com.astra.freyja.agent.profile.CoreAssistantProfile;
import com.astra.freyja.agent.model.*;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.ProductionPreparationDTO.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.service.impl.ResKeyframeServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionPreparationTest {
    private static final Long DRAMA = 2032095628944588801L, SHOT = 2032095628944588802L, CHARACTER = 2032095628944588803L, LOOK = 2032095628944588804L;
    private final AgentJson json = new AgentJson();
    private ProductionAssets assets;
    private ProductionPlans service;
    private DramaMapper dramas;
    private DramaShotMapper shots;
    private ShotProductionPlanMapper storedPlans;
    private ProductionJobs jobs;
    private AiTaskService tasks;
    private ShotAiVisualPlanService prompts;
    private DramaShotService shotService;
    @BeforeEach void setup() {
        var metadata = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (var type : List.of(Drama.class, DramaShot.class, ResKeyframe.class, ResCharacter.class, ResCharacterLook.class, ShotProductionPlan.class, AgentPreparationJob.class)) TableInfoHelper.initTableInfo(metadata, type);
        assets = mock(ProductionAssets.class); dramas = mock(DramaMapper.class); shots = mock(DramaShotMapper.class);
        storedPlans = mock(ShotProductionPlanMapper.class); jobs = mock(ProductionJobs.class);
        tasks = mock(AiTaskService.class); prompts = mock(ShotAiVisualPlanService.class); shotService = mock(DramaShotService.class);
        service = new ProductionPlans(shots, dramas, storedPlans, mock(AgentMaterialRequirementMapper.class), mock(AgentPreparationJobMapper.class), jobs, assets, json, tasks, prompts, shotService);
        var look = new ProductionAssets.Material("CHARACTER_LOOK", LOOK, CHARACTER, "小岚 · 蓝外套", "蓝色外套", "blue coat", null, "MISSING", new ResCharacterLook());
        when(assets.resolve("CHARACTER_LOOK", LOOK, DRAMA)).thenReturn(look);
        when(assets.resolve("CHARACTER", CHARACTER, DRAMA)).thenReturn(new ProductionAssets.Material("CHARACTER", CHARACTER, CHARACTER, "小岚", "人物设定", "portrait", null, "MISSING", new ResCharacter()));
        when(assets.candidate(any())).thenAnswer(inv -> {
            ProductionAssets.Material m = inv.getArgument(0);
            Map<String, Object> map = new LinkedHashMap<>(); map.put("id", m.id()); map.put("description", m.description()); map.put("imageStatus", m.imageStatus()); map.put("imageUrl", m.imageUrl()); return map;
        });
    }
    private Plan plan() {
        Plan p = new Plan(); p.setShotId(SHOT); p.setGenerationMode("REFERENCE_MODE"); p.setDecisionReason("保持人物外观一致");
        CharacterBinding c = new CharacterBinding(); c.setCharacterId(CHARACTER); c.setLookId(LOOK); p.setCharacterBindings(List.of(c));
        ReferenceSlot r = new ReferenceSlot(); r.setSlotKey("lead"); r.setSourceType("CHARACTER_LOOK"); r.setSourceId(LOOK); r.setCharacterId(CHARACTER); r.setLookId(LOOK); r.setUsageRole("SUBJECT"); r.setReferenceRole("COMBINED"); r.setDescription("保持蓝外套造型"); p.setReferenceSlots(List.of(r)); return p;
    }
    private DramaShot shot() {
        var s = new DramaShot(); s.setId(SHOT); s.setDramaId(DRAMA); s.setScriptContent("小岚抬起头。"); s.setCharacterRefsJson("[]"); s.setPropRefsJson("[]"); return s;
    }
    @Test void readyPromptRequestContainsCurrentDirectorInputSnapshot() {
        var s = shot(); s.setRefImagesJson("[]"); s.setGenerationMode("REFERENCE_MODE");
        var p = plan();
        when(assets.resolve("CHARACTER_LOOK", LOOK, DRAMA)).thenReturn(new ProductionAssets.Material("CHARACTER_LOOK", LOOK, CHARACTER, "小岚", "蓝外套", "blue coat", "https://example.test/look.png", "SYNCED", new ResCharacterLook()));
        var stored = new ShotProductionPlan(); stored.setId(91L); stored.setRevision(1); stored.setPlanJson(json.write(p)); stored.setAppliedFingerprint(service.fingerprint(s, p));
        var job = new AgentPreparationJob(); job.setDramaId(DRAMA);
        when(jobs.editJob(any(), eq(List.of(SHOT)))).thenReturn(job); when(jobs.targets(job)).thenReturn(List.of(SHOT));
        when(shots.lockById(SHOT)).thenReturn(s); when(shots.selectActiveByDramaId(DRAMA)).thenReturn(List.of(s));
        when(storedPlans.lockByShotId(SHOT)).thenReturn(stored);
        var detail = new com.astra.freyja.dto.drama.DramaShotVO(); org.springframework.beans.BeanUtils.copyProperties(s, detail); when(shotService.getById(SHOT)).thenReturn(detail);
        when(prompts.startPromptTask(any())).thenReturn("93");
        var task = new com.astra.freyja.dto.script.AiTaskDTO(); task.setId(93L); task.setStatus("PENDING"); when(tasks.getTaskById(93L)).thenReturn(task);
        var ctx = new ToolContext(1L, 2L, 3L, DRAMA, 4L, "test", AgentTestSupport.snapshot(List.of(), "NORMAL"));
        assertEquals("DEFERRED", service.prompt(new ShotInput(SHOT, stored.getAppliedFingerprint()), ctx, null).kind());
        var request = ArgumentCaptor.forClass(com.astra.freyja.dto.drama.ShotPromptDeriveDTO.class); verify(prompts).startPromptTask(request.capture());
        assertEquals(com.astra.freyja.director.model.DirectorPlanInput.fromShot(s), request.getValue().getDirectorPlanInput());
        assertEquals("小岚抬起头。", request.getValue().getDirectorPlanInput().scriptContent());
        s.setDirectorPlanMode("AUTO"); s.setDirectorPlanRevision(2);
        stored.setAppliedFingerprint(service.fingerprint(s, p)); stored.setPromptTaskFingerprint(stored.getAppliedFingerprint()); stored.setPromptTaskRevision(1);
        var result = new com.astra.freyja.dto.drama.ShotPromptDeriveVO(); result.setVideoPrompt("Calm movement.");
        result.setDirectorPlanMode("AUTO"); result.setDirectorPlanRevision(2); result.setDirectorPlan(new com.astra.freyja.director.model.DirectorPlan());
        task.setStatus("SUCCESS"); task.setOutputPayload(json.write(result));
        when(shots.selectById(SHOT)).thenReturn(s); when(storedPlans.selectOne(any())).thenReturn(stored);
        assertNull(service.prompt(new ShotInput(SHOT, stored.getAppliedFingerprint()), ctx, null).errorCode(), "returned director revision is already persisted, never add one again");
    }
    @Test void missingImagesAreSuccessfulPlanningState() {
        Plan p = plan(); DramaShot s = shot(); service.validate(p, DRAMA);
        ShotProductionPlan stored = new ShotProductionPlan(); stored.setPlanJson(json.write(p)); stored.setAppliedFingerprint(service.fingerprint(s, p));
        assertEquals("WAITING_ASSET_IMAGES", service.liveStatus(s, stored));
        assertEquals(LOOK, service.missing(p, DRAMA).getFirst().sourceId());
    }
    @Test void repeatedMissingLookHasOneMaterialWithDistinctPurposes() {
        Plan p = plan(); ReferenceSlot style = json.copy(p.getReferenceSlots().getFirst(), ReferenceSlot.class);
        style.setSlotKey("lead-style"); style.setReferenceRole("STYLE"); style.setDescription("色彩基调");
        p.setReferenceSlots(List.of(p.getReferenceSlots().getFirst(), style));
        assertEquals(1, service.missing(p, DRAMA).size()); assertEquals(2, service.missing(p, DRAMA).getFirst().purposes().size());
    }
    @Test void missingRequiredFirstFrameCannotBePlannedWithoutRealAsset() {
        Plan p = plan(); p.setGenerationMode("FIRST_LAST_FRAME"); assertThrows(BizException.class, () -> service.validate(p, DRAMA));
    }
    @Test void firstFrameAssetRoleIsValidated() {
        Plan p = plan(); p.setGenerationMode("FIRST_LAST_FRAME");
        FrameRequirement frame = new FrameRequirement(); frame.setRequired(true); frame.setKeyframeId(9L); frame.setDescription("开场构图"); p.setFirstFrameRequirement(frame);
        var key = new ResKeyframe(); key.setFrameType("COMPOSITION_ANCHOR");
        when(assets.resolve("KEYFRAME", 9L, DRAMA)).thenReturn(new ProductionAssets.Material("KEYFRAME", 9L, null, "构图", "", "", null, "MISSING", key));
        assertThrows(BizException.class, () -> service.validate(p, DRAMA));
        key.setFrameType("FIRST_FRAME"); assertDoesNotThrow(() -> service.validate(p, DRAMA));
    }
    @Test void anotherShotsKeyframeIsRejected() {
        Plan p = plan(); Anchor a = new Anchor(); a.setSourceType("KEYFRAME"); a.setSourceId(9L); p.setVisualAnchor(a);
        var key = new ResKeyframe(); key.setShotId(SHOT + 1);
        when(assets.resolve("KEYFRAME", 9L, DRAMA)).thenReturn(new ProductionAssets.Material("KEYFRAME", 9L, null, "构图", "", "", null, "MISSING", key));
        assertThrows(BizException.class, () -> service.validate(p, DRAMA));
    }
    @Test void mismatchedCharacterLookAndSubjectRoleAreRejected() {
        Plan p = plan(); p.getCharacterBindings().getFirst().setCharacterId(CHARACTER + 1); assertThrows(BizException.class, () -> service.validate(p, DRAMA));
        Plan q = plan(); q.getReferenceSlots().getFirst().setUsageRole("NON_SUBJECT"); assertThrows(BizException.class, () -> service.validate(q, DRAMA));
    }
    @Test void visibleCharacterCannotLoseRequiredLook() {
        Plan p = plan(); p.getReferenceSlots().getFirst().setRequired(false); assertThrows(BizException.class, () -> service.validate(p, DRAMA));
    }
    @Test void nonSubjectBindingIsLegalWithoutOnscreenConstraint() {
        Plan p = plan(); p.getCharacterBindings().getFirst().setUsageRole("NON_SUBJECT"); p.getReferenceSlots().getFirst().setUsageRole("NON_SUBJECT"); assertDoesNotThrow(() -> service.validate(p, DRAMA));
    }
    @Test void referenceLimitAndUniqueSlotsAreEnforced() {
        Plan p = plan(); p.setReferenceSlots(Collections.nCopies(10, p.getReferenceSlots().getFirst())); assertThrows(BizException.class, () -> service.validate(p, DRAMA));
        p.setReferenceSlots(Collections.nCopies(2, p.getReferenceSlots().getFirst())); assertThrows(BizException.class, () -> service.validate(p, DRAMA));
    }
    @Test void manualEditsInvalidatePreparationWithoutTimestampHeuristics() {
        Plan p = plan(); DramaShot s = shot(); ShotProductionPlan stored = new ShotProductionPlan(); stored.setPlanJson(json.write(p)); stored.setAppliedFingerprint(service.fingerprint(s, p));
        s.setScriptContent("小岚走出门。"); assertEquals("NEEDS_REFRESH", service.liveStatus(s, stored));
    }
    @Test void changedImageUrlInvalidatesInput() {
        Plan p = plan(); DramaShot s = shot(); String before = service.fingerprint(s, p);
        when(assets.resolve("CHARACTER_LOOK", LOOK, DRAMA)).thenReturn(new ProductionAssets.Material("CHARACTER_LOOK", LOOK, CHARACTER, "蓝外套", "蓝外套", "blue coat", "https://example.com/look.png", "SYNCED", new ResCharacterLook()));
        assertNotEquals(before, service.fingerprint(s, p));
    }
    @Test void framesAndImagesReadyStillRequireCurrentPrompts() {
        Plan p = plan(); DramaShot s = shot(); when(assets.resolve("CHARACTER_LOOK", LOOK, DRAMA)).thenReturn(new ProductionAssets.Material("CHARACTER_LOOK", LOOK, CHARACTER, "造型", "", "", "https://example.com/look.png", "SYNCED", new ResCharacterLook()));
        ShotProductionPlan stored = new ShotProductionPlan(); stored.setPlanJson(json.write(p)); stored.setAppliedFingerprint(service.fingerprint(s, p));
        assertEquals("NEEDS_REFRESH", service.liveStatus(s, stored)); s.setVideoPrompt("A subtle turn of the head.");
        stored.setAppliedFingerprint(service.fingerprint(s, p)); stored.setPromptFingerprint(stored.getAppliedFingerprint()); assertEquals("READY", service.liveStatus(s, stored));
    }
    @Test void assetScopeAcceptsPublicAndCurrentOnly() {
        assertDoesNotThrow(() -> ProductionAssets.scope(0L, DRAMA)); assertDoesNotThrow(() -> ProductionAssets.scope(null, DRAMA));
        assertThrows(BizException.class, () -> ProductionAssets.scope(DRAMA + 1, DRAMA));
        assertThrows(BizException.class, () -> ProductionAssets.scope(0L, null));
    }
    @Test void completionRequiresPersistedDeliveryReport() {
        var mapper = mock(AgentPreparationJobMapper.class);
        @SuppressWarnings("unchecked") var provider = (org.springframework.beans.factory.ObjectProvider<ProductionPlans>) mock(org.springframework.beans.factory.ObjectProvider.class);
        var live = mock(ProductionPlans.class); when(provider.getObject()).thenReturn(live);
        var profile = new CoreAssistantProfile(mapper, provider);
        var job = new AgentPreparationJob(); job.setStatus("COMMITTED"); job.setRequestJson("{}"); when(mapper.selectOne(any())).thenReturn(job);
        var reply = new ModelReply("全部完成", List.of(), Map.of(), null, null, null);
        assertFalse(profile.checkCompletion(reply, null, 1L).complete()); job.setStatus("PREPARED"); job.setReportJson("{\"complete\":true}");
        assertFalse(profile.checkCompletion(reply, null, 1L).complete(), "an old report cannot complete a changed plan");
        when(live.isDeliveryCurrent(job)).thenReturn(true); assertTrue(profile.checkCompletion(reply, null, 1L).complete());
    }
    @Test void snowflakeIdsRoundTripWithoutRounding() { assertEquals(SHOT, json.read(json.write(plan()), Plan.class).getShotId()); assertTrue(json.write(plan()).contains("\"" + SHOT + "\"")); }
    @Test void logicalLookReferenceUsesRealLookRecordIdAndNeverStaleImage() {
        var cs = mock(ResCharacterMapper.class); var ls = mock(ResCharacterLookMapper.class); var visual = mock(CharacterVisualAssetResolver.class);
        var resolver = new ProductionAssets(cs, ls, mock(ResSceneMapper.class), mock(ResPropMapper.class), mock(ResKeyframeMapper.class), mock(ResCharacterService.class), mock(ResCharacterLookService.class), mock(ResSceneService.class), mock(ResPropService.class), mock(ResKeyframeService.class), visual, json);
        var c = new ResCharacter(); c.setId(CHARACTER); c.setDramaId(DRAMA); c.setStatus(1); c.setName("小岚"); when(cs.selectById(CHARACTER)).thenReturn(c);
        var l = new ResCharacterLook(); l.setId(LOOK); l.setCharacterId(CHARACTER); l.setStatus(1); l.setLookName("蓝外套"); when(ls.selectById(LOOK)).thenReturn(l);
        var v = new ResolvedCharacterVisual(); v.setCharacter(c); v.setLook(l); v.setReferenceImageUrl("https://example.com/blue.png"); v.setImageStatus("SYNCED"); when(visual.resolve(CHARACTER, LOOK)).thenReturn(v);
        var slot = plan().getReferenceSlots().getFirst(); var ref = resolver.reference(slot, DRAMA);
        assertEquals("CHARACTER_REFERENCE", ref.getSourceType()); assertEquals(LOOK, ref.getSourceId()); assertEquals(CHARACTER, ref.getCharacterId()); assertEquals(LOOK, ref.getLookId());
        v.setImageStatus("STALE"); assertNull(resolver.reference(slot, DRAMA));
        v.setImageStatus("SYNCED"); slot.setCharacterId(CHARACTER + 1); assertThrows(BizException.class, () -> resolver.reference(slot, DRAMA));
    }
    @Test void malformedOrEmptyImageUrlCannotBecomeRealReference() {
        assertFalse(ProductionAssets.validImageUrl("")); assertFalse(ProductionAssets.validImageUrl("not-an-image")); assertFalse(ProductionAssets.validImageUrl("javascript:alert(1)"));
        assertTrue(ProductionAssets.validImageUrl("http://minio.localhost:9000/assets/example.png")); assertTrue(ProductionAssets.validImageUrl("/assets/example.png"));
    }
    @Test void invalidOldAssociationCanBeReplannedWithoutAcceptingItAsAValidAsset() {
        var s = shot(); s.setPropRefsJson("[{\"propId\":\"9\"}]");
        when(assets.resolve("PROP", 9L, DRAMA)).thenThrow(new BizException("SCOPE_VIOLATION"));
        assertDoesNotThrow(() -> service.fingerprint(s, null));
        var p = plan(); var b = new PropBinding(); b.setPropId(9L); b.setPurpose("道具"); p.setPropBindings(List.of(b));
        assertThrows(BizException.class, () -> service.validate(p, DRAMA));
    }
    @Test void enrichLookTextPreservesImageAndRejectsPublicLibraryWrites() {
        var cs = mock(ResCharacterMapper.class); var ls = mock(ResCharacterLookMapper.class); var visual = mock(CharacterVisualAssetResolver.class);
        var lookService = mock(ResCharacterLookService.class);
        var resolver = new ProductionAssets(cs, ls, mock(ResSceneMapper.class), mock(ResPropMapper.class), mock(ResKeyframeMapper.class), mock(ResCharacterService.class), lookService, mock(ResSceneService.class), mock(ResPropService.class), mock(ResKeyframeService.class), visual, json);
        var c = new ResCharacter(); c.setId(CHARACTER); c.setDramaId(DRAMA); c.setStatus(1); when(cs.selectById(CHARACTER)).thenReturn(c);
        var l = new ResCharacterLook(); l.setId(LOOK); l.setCharacterId(CHARACTER); l.setStatus(1); l.setReferenceImageUrl("https://example.test/look.png"); l.setLookName("蓝外套"); when(ls.selectById(LOOK)).thenReturn(l);
        var v = new ResolvedCharacterVisual(); v.setImageStatus("SYNCED"); v.setReferenceImageUrl(l.getReferenceImageUrl()); when(visual.resolve(CHARACTER, LOOK)).thenReturn(v);
        var a = new Asset(); a.setType("CHARACTER_LOOK"); a.setExistingId(LOOK); a.setDescription("蓝外套，维持身份特征"); a.setPrompt("blue coat");
        resolver.updateText(a, DRAMA);
        var changed = ArgumentCaptor.forClass(ResCharacterLookDTO.class); verify(lookService).update(changed.capture());
        assertEquals(LOOK, changed.getValue().getId()); assertEquals(l.getReferenceImageUrl(), changed.getValue().getReferenceImageUrl()); assertEquals(a.getDescription(), changed.getValue().getDesignDesc());
        c.setDramaId(0L); assertThrows(BizException.class, () -> resolver.updateText(a, DRAMA)); verifyNoMoreInteractions(lookService);
    }
    @Test void missingAssetDescriptionPreventsDelivery() {
        var s = shot(); var p = plan();
        when(assets.resolve("CHARACTER_LOOK", LOOK, DRAMA)).thenReturn(new ProductionAssets.Material("CHARACTER_LOOK", LOOK, CHARACTER, "基础造型", null, "", null, "MISSING", new ResCharacterLook()));
        var stored = new ShotProductionPlan(); stored.setRevision(1); stored.setPlanJson(json.write(p)); stored.setAppliedFingerprint(service.fingerprint(s, p));
        var job = new AgentPreparationJob(); job.setDramaId(DRAMA);
        when(jobs.require(any(), eq(true))).thenReturn(job); when(jobs.targets(job)).thenReturn(List.of(SHOT)); when(jobs.registry(job)).thenReturn(List.of());
        when(shots.lockById(SHOT)).thenReturn(s); when(shots.selectActiveByDramaId(DRAMA)).thenReturn(List.of(s)); when(storedPlans.lockByShotId(SHOT)).thenReturn(stored);
        var drama = new Drama(); drama.setTitle("虚构项目"); when(dramas.selectById(DRAMA)).thenReturn(drama);
        var ctx = new ToolContext(1L, 2L, 3L, DRAMA, 4L, "test", AgentTestSupport.snapshot(List.of(), "NORMAL"));
        var report = (Map<?, ?>) service.inspect(ctx).data();
        assertEquals(false, report.get("complete")); assertEquals(1, ((List<?>)report.get("incompleteTextAssets")).size());
    }
    @Test void createKeyframeWithoutImageSavesTextDraft() {
        var mapper = mock(ResKeyframeMapper.class); var keyframes = new ResKeyframeServiceImpl(mapper, dramas, shots);
        when(mapper.insert(any(ResKeyframe.class))).thenAnswer(inv -> { ((ResKeyframe)inv.getArgument(0)).setId(9L); return 1; });
        var dto = new ResKeyframeDTO(); dto.setName("开场首帧"); dto.setDescription("小岚站在门前，竖幅中景"); dto.setFrameType("FIRST_FRAME"); dto.setFrameUrl("  ");
        assertEquals(9L, keyframes.create(dto)); var saved = ArgumentCaptor.forClass(ResKeyframe.class); verify(mapper).insert(saved.capture());
        assertNull(saved.getValue().getFrameUrl()); assertEquals("TEXT_DRAFT", saved.getValue().getSourceType());
    }
    @Test void omittedKeyframeUrlKeepsImageAndExplicitClearRemovesIt() {
        var mapper = mock(ResKeyframeMapper.class); var service = new ResKeyframeServiceImpl(mapper, dramas, shots);
        var old = new ResKeyframe(); old.setId(9L); old.setName("首帧"); old.setFrameUrl("https://example.com/key.png"); old.setFrameType("FIRST_FRAME"); when(mapper.selectById(9L)).thenReturn(old);
        var dto = new ResKeyframeDTO(); dto.setId(9L); dto.setName("更新首帧文字"); dto.setDescription("改写描述"); service.update(dto); assertEquals("https://example.com/key.png", old.getFrameUrl());
        dto.setClearFrameUrl(true); service.update(dto); assertNull(old.getFrameUrl()); verify(mapper).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
