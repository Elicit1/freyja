package com.astra.freyja.service.impl;

import com.astra.freyja.dto.script.CharacterRegistryItemVO;
import com.astra.freyja.dto.script.DecomposedCharacterVO;
import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.StorySegment;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterPromptBoundaryTest {

    @Test
    void plannerRegistryKeepsIdentityWithoutMixedAppearanceOrClothing() {
        CharacterRegistryItemVO character = CharacterRegistryItemVO.builder()
                .id(101L)
                .canonicalName("林岚")
                .displayName("林岚")
                .gender("FEMALE")
                .ageGroup("YOUTH")
                .appearanceDesc("短发，穿红色大衣")
                .appearancePrompt("short hair, wearing a red coat")
                .build();
        String prompt = CharacterRegistryServiceImpl.formatRegistryItemsForPrompt(List.of(character));

        assertTrue(prompt.contains("林岚"));
        assertTrue(prompt.contains("YOUTH"));
        assertFalse(prompt.contains("红色大衣"));
        assertFalse(prompt.contains("red coat"));
    }

    @Test
    void workerCharacterInputsExcludePlannerAppearanceAndOutfit() {
        DecomposedCharacterVO character = DecomposedCharacterVO.builder()
                .name("林岚")
                .roleType("PROTAGONIST")
                .gender("FEMALE")
                .personality("沉稳")
                .appearanceDesc("短发，穿红色大衣")
                .appearancePrompt("wearing a red coat")
                .outfitPrompt("red coat")
                .build();
        String registry = ScriptDecomposeServiceImpl.buildWorkerCharacterRegistry("【无已有角色】", List.of(character));
        assertTrue(registry.contains("林岚"));
        assertFalse(registry.contains("红色大衣"));
        assertFalse(registry.contains("red coat"));

        ParallelShotGenerationServiceImpl worker = new ParallelShotGenerationServiceImpl(null, null, null, null, null);
        StorySegment segment = StorySegment.builder().characterIds(List.of("林岚")).build();
        GlobalStoryContext context = GlobalStoryContext.builder().characters(List.of(character)).build();
        String facts = ReflectionTestUtils.invokeMethod(worker, "buildBoundCharacterAssetFacts", segment, context);

        assertTrue(facts.contains("林岚"));
        assertTrue(facts.contains("沉稳"));
        assertFalse(facts.contains("红色大衣"));
        assertFalse(facts.contains("red coat"));
    }
}
