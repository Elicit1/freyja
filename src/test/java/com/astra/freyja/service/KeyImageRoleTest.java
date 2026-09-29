package com.astra.freyja.service;

import com.astra.freyja.dto.drama.manifest.KeyImageRole;
import com.astra.freyja.dto.drama.manifest.ReferenceManifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyImageRoleTest {

    @Test
    void eachPictureRoleKeepsItsOwnTaskTypeAndTimingContract() {
        List<ReferenceManifest.PictureManifestItem> pictures = new ArrayList<>();
        KeyImageRole[] roles = KeyImageRole.values();
        for (int i = 0; i < roles.length; i++) {
            pictures.add(ReferenceManifest.PictureManifestItem.builder()
                    .pictureIndex(i + 1)
                    .sourceType("KEYFRAME")
                    .usageRole(roles[i].name())
                    .build());
        }

        String rules = ReferenceManifest.builder().pictures(pictures).build().toSystemRoleGuidance();
        assertTrue(rules.contains("FIRST_FRAME / Concrete Frame / [keyframe completion]"));
        assertTrue(rules.contains("the shot begins from <Picture 1>"));
        assertTrue(rules.contains("the shot's keyframe corresponds to <Picture 2>"));
        assertTrue(rules.contains("the shot ends on <Picture 3>"));
        assertTrue(rules.contains("EDITED_KEYFRAME / Concrete Frame / [keyframe completion]"));
        assertTrue(rules.contains("COMPOSITION_ANCHOR / Planning / Reference / [reference generation]"));
        assertTrue(rules.contains("STORYBOARD_REFERENCE / Planning / Reference / [reference generation]"));
        assertTrue(rules.contains("SHOT_PLANNING_REFERENCE / Planning / Reference / [reference generation]"));
        assertFalse(rules.contains("the shot begins from <Picture 5>"));
        assertFalse(rules.contains("the shot's keyframe corresponds to <Picture 6>"));
        assertFalse(rules.contains("the shot ends on <Picture 7>"));
    }

    @Test
    void savedGenericKeyframeSlotUsesAssetRoleWhileExplicitSlotCanOverrideIt() {
        assertEquals(KeyImageRole.COMPOSITION_ANCHOR,
                KeyImageRole.resolve("MOTION_KEYFRAME", "COMPOSITION_ANCHOR"));
        assertEquals(KeyImageRole.LAST_FRAME,
                KeyImageRole.resolve("LAST_FRAME", "COMPOSITION_ANCHOR"));
        assertEquals(KeyImageRole.LAST_FRAME, KeyImageRole.fromCode("END_FRAME"));
        assertEquals(KeyImageRole.KEYFRAME, KeyImageRole.fromCode("ACTION_BEAT"));
    }
}
