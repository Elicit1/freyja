package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.script.DecomposedCharacterVO;
import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.ScriptSkillPolicy;
import com.astra.freyja.dto.script.ScriptSkillStagePolicy;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.validation.SkillPackageValidator;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.astra.freyja.skill.service.SkillContentService;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.service.ScriptSkillRuntime;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerCharacterDisambiguationTest {

    @Test
    void plannerBoundAndUnresolvedCharactersKeepAiIdentityWithoutJavaGuessing() {
        DecomposedCharacterVO namedCharacter = DecomposedCharacterVO.builder()
                .name("菜月昴")
                .aliases(List.of("昴", "486"))
                .matchedCharacterId(101L)
                .existingCharacterId(101L)
                .build();
        DecomposedCharacterVO unresolvedCharacter = DecomposedCharacterVO.builder()
                .name("那位老师")
                .identityStatus("UNRESOLVED")
                .candidateCharacterIds(List.of(101L, 103L))
                .build();
        DecomposedCharacterVO newCharacter = DecomposedCharacterVO.builder()
                .name("新登场的人物")
                .build();

        assertDoesNotThrow(() -> ScriptDecomposeServiceImpl.validatePlannerCharacterReferences(
                List.of(namedCharacter, unresolvedCharacter, newCharacter), Set.of(101L, 103L)));
        assertNull(newCharacter.getMatchedCharacterId());
        assertNull(newCharacter.getExistingCharacterId());
    }

    @Test
    void plannerCharacterIdsMustBelongToTheCurrentProject() {
        DecomposedCharacterVO foreignId = DecomposedCharacterVO.builder()
                .name("菜月昴")
                .matchedCharacterId(999L)
                .build();

        BizException error = assertThrows(BizException.class,
                () -> ScriptDecomposeServiceImpl.validatePlannerCharacterReferences(
                        List.of(foreignId), Set.of(101L, 103L)));
        assertTrue(error.getMessage().contains("999"));
    }

    @Test
    void conflictingPlannerIdCompatibilityFieldsAreRejected() {
        DecomposedCharacterVO conflicting = DecomposedCharacterVO.builder()
                .name("菜月昴")
                .matchedCharacterId(101L)
                .existingCharacterId(103L)
                .build();

        assertThrows(BizException.class,
                () -> ScriptDecomposeServiceImpl.validatePlannerCharacterReferences(
                        List.of(conflicting), Set.of(101L, 103L)));
    }

    @Test
    void unresolvedIdentityCannotAlsoBindAnExistingCharacterId() {
        DecomposedCharacterVO unresolved = DecomposedCharacterVO.builder()
                .name("那位老师")
                .identityStatus("UNRESOLVED")
                .matchedCharacterId(101L)
                .candidateCharacterIds(List.of(101L, 103L))
                .build();

        assertThrows(BizException.class,
                () -> ScriptDecomposeServiceImpl.validatePlannerCharacterReferences(
                        List.of(unresolved), Set.of(101L, 103L)));
    }

    @Test
    void plannerAlwaysRequiresCharacterSkillAndKeepsOtherStagePolicies() {
        ScriptDecomposeRequestDTO request = new ScriptDecomposeRequestDTO();
        ScriptSkillPolicy policy = new ScriptSkillPolicy();
        ScriptSkillStagePolicy planner = new ScriptSkillStagePolicy();
        planner.setAllowDynamicLoad(true);
        planner.setRequiredSkillNames(List.of("story-structure"));
        ScriptSkillStagePolicy worker = new ScriptSkillStagePolicy();
        worker.setRequiredSkillNames(List.of("camera-direction"));
        policy.setPlanner(planner);
        policy.setWorker(worker);
        request.setSkillPolicy(policy);

        ScriptDecomposeServiceImpl.requirePlannerCharacterDisambiguationSkill(request);
        ScriptDecomposeServiceImpl.requirePlannerCharacterDisambiguationSkill(request);

        assertEquals(List.of("story-structure", "character-disambiguation"), planner.getRequiredSkillNames());
        assertTrue(planner.isAllowDynamicLoad());
        assertEquals(List.of("camera-direction"), worker.getRequiredSkillNames());
    }

    @Test
    void skillDocumentsRequestedNameAliasPronounAndAmbiguityCases() throws IOException {
        String skill;
        try (var input = getClass().getResourceAsStream("/skills/character-disambiguation/SKILL.md")) {
            assertNotNull(input, "Skill source must be packaged as a resource");
            skill = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(skill.startsWith("---\nname: character-disambiguation\n"));
        assertTrue(skill.contains("菜月昴"));
        assertTrue(skill.contains("昴"));
        assertTrue(skill.contains("486"));
        assertTrue(skill.contains("雷姆看着菜月昴"));
        assertTrue(skill.contains("雷姆推开玻璃门"));
        assertTrue(skill.contains("UNRESOLVED"));
        assertTrue(skill.contains("不得编造角色 ID"));
    }

    @Test
    void distributableSkillZipPassesTheExistingPackageValidator() throws IOException {
        try (var input = getClass().getResourceAsStream("/skills/character-disambiguation.zip")) {
            assertNotNull(input, "Uploadable Skill ZIP must be packaged as a resource");
            var skillPackage = new SkillPackageValidator().validateZip(input, 20 * 1024);
            assertEquals("character-disambiguation", skillPackage.getMetadata().getName());
            assertTrue(skillPackage.getEntrypointContent().contains("# Character Disambiguation"));
        }
    }

    @Test
    void requiredSkillIsLoadedIntoPlannerSystemPromptThroughExistingRuntime() {
        SkillCatalogService catalogService = mock(SkillCatalogService.class);
        SkillContentService contentService = mock(SkillContentService.class);
        SkillPromptContextService promptContextService = mock(SkillPromptContextService.class);
        LoadSkillToolFactory toolFactory = mock(LoadSkillToolFactory.class);
        AiModelMapper modelMapper = mock(AiModelMapper.class);
        ScriptSkillRuntime runtime = new ScriptSkillRuntime(catalogService, contentService,
                promptContextService, toolFactory, modelMapper, new ObjectMapper());

        ScriptDecomposeRequestDTO request = new ScriptDecomposeRequestDTO();
        ScriptSkillPolicy policy = new ScriptSkillPolicy();
        ScriptSkillStagePolicy planner = new ScriptSkillStagePolicy();
        planner.setRequiredSkillNames(List.of("character-disambiguation"));
        policy.setPlanner(planner);
        request.setSkillPolicy(policy);
        when(catalogService.getEnabledCatalog()).thenReturn(List.of(SkillCatalogItem.builder()
                .name("character-disambiguation").version("1").versionId(7L).build()));
        when(contentService.loadSkillVersion(7L)).thenReturn(LoadedSkill.builder()
                .name("character-disambiguation").version("1").content("# character rules").build());
        when(promptContextService.loadSelected(anyString(), anyString(), anyList(), anyMap()))
                .thenReturn(new SkillPromptContext("# character rules", "sha256:test",
                        List.of("character-disambiguation@v1")));
        when(promptContextService.appendToSystemPrompt(anyString(), any(SkillPromptContext.class)))
                .thenAnswer(call -> call.getArgument(0, String.class) + "\n"
                        + call.getArgument(1, SkillPromptContext.class).prompt());

        runtime.prepareRequest(request);
        ScriptSkillRuntime.Invocation invocation = runtime.begin(request, planner,
                GlobalStoryContext.builder().taskId(22L).build(), "planner base", "PLANNER", null, 0, null);

        assertNotNull(invocation);
        assertTrue(invocation.systemPrompt().contains("planner base"));
        assertTrue(invocation.systemPrompt().contains("# character rules"));
        assertTrue(invocation.session().getLoadedSkillNames().contains("character-disambiguation"));
        verify(contentService, atLeastOnce()).loadSkillVersion(7L);
    }

    @Test
    void missingRequiredSkillFailsBeforePlannerCanRun() {
        SkillCatalogService catalogService = mock(SkillCatalogService.class);
        SkillContentService contentService = mock(SkillContentService.class);
        ScriptSkillRuntime runtime = new ScriptSkillRuntime(catalogService, contentService,
                mock(SkillPromptContextService.class), mock(LoadSkillToolFactory.class),
                mock(AiModelMapper.class), new ObjectMapper());
        when(catalogService.getEnabledCatalog()).thenReturn(List.of());

        ScriptDecomposeRequestDTO request = new ScriptDecomposeRequestDTO();
        ScriptSkillPolicy policy = new ScriptSkillPolicy();
        ScriptSkillStagePolicy planner = new ScriptSkillStagePolicy();
        planner.setRequiredSkillNames(List.of("character-disambiguation"));
        policy.setPlanner(planner);
        request.setSkillPolicy(policy);

        BizException error = assertThrows(BizException.class, () -> runtime.prepareRequest(request));
        assertTrue(error.getMessage().contains("character-disambiguation"));
        verifyNoInteractions(contentService);
    }
}
