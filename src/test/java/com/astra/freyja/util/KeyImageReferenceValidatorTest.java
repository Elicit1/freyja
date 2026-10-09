package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.dto.drama.manifest.KeyImageRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyImageReferenceValidatorTest {
    @Test
    void rejectsInvalidKeyImageSlotEvenWhenItHasNoUrl() {
        ShotRefImageDTO slot = ShotRefImageDTO.builder().sourceType("KEYFRAME")
                .sourceId(2032095628944588801L).usageRole("MOTION_KEYFRAME").build();
        assertThrows(BizException.class,
                () -> KeyImageReferenceValidator.validate(List.of(slot), id -> "COMPOSITION_ANCHOR"));
    }

    @Test
    void inheritsAssetOnlyForMissingUsageAndKeepsOtherMediaRoles() {
        ShotRefImageDTO slot = ShotRefImageDTO.builder().sourceType("KEYFRAME").sourceId(1L).build();
        assertEquals(KeyImageRole.COMPOSITION_ANCHOR,
                KeyImageReferenceValidator.resolveRole(slot, id -> "COMPOSITION_ANCHOR"));
        slot.setUsageRole("LAST_FRAME");
        assertEquals(KeyImageRole.LAST_FRAME, KeyImageReferenceValidator.resolveRole(slot,
                id -> { fail("Explicit role must not require an asset lookup"); return null; }));
        assertDoesNotThrow(() -> KeyImageReferenceValidator.validate(List.of(
                ShotRefImageDTO.builder().sourceType("CHARACTER").usageRole("IDENTITY").build()),
                id -> null));
    }

    @Test
    void storedJsonMustBeValidAndCannotBypassSlotValidation() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(BizException.class,
                () -> KeyImageReferenceValidator.parseAndValidate("broken-json", mapper, id -> null));
        assertThrows(BizException.class, () -> KeyImageReferenceValidator.parseAndValidate(
                "[{\"sourceType\":\"KEYFRAME\",\"usageRole\":\"END_FRAME\"}]", mapper,
                id -> "LAST_FRAME"));
        List<ShotRefImageDTO> slots = KeyImageReferenceValidator.parseAndValidate(
                "[{\"sourceType\":\"KEYFRAME\",\"sourceId\":\"2032095628944588801\",\"usageRole\":\"LAST_FRAME\"}]",
                mapper, id -> null);
        assertEquals(2032095628944588801L, slots.getFirst().getSourceId());
    }
}
