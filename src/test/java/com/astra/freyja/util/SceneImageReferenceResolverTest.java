package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.entity.ResScene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SceneImageReferenceResolverTest {
    private ResScene scene() {
        ResScene scene = new ResScene();
        scene.setId(2032095628944588801L);
        scene.setDramaId(10L);
        scene.setStatus(1);
        scene.setReferenceImageUrl("spatial-v1.png");
        scene.setCoverUrl("cover.png");
        return scene;
    }

    private ShotRefImageDTO link() {
        return ShotRefImageDTO.builder().id("stable-slot").name("办公室")
                .sourceType("SCENE").sourceId(2032095628944588801L).usageRole("SCENE")
                .bindingMode("ASSET_LINK").sourceSlot("SCENE_REFERENCE").imageUrl("old-cache.png").build();
    }

    @Test void planningAllowsMissingImageWhileRenderRequiresTheSameSlot() {
        ResScene scene = scene();
        scene.setReferenceImageUrl(null);
        var images = List.of(link());
        var planned = SceneImageReferenceResolver.resolve(images, 10L, id -> scene, false);
        assertEquals(1, planned.size());
        assertNull(planned.getFirst().getImageUrl());
        assertEquals("stable-slot", planned.getFirst().getId());
        assertTrue(assertThrows(BizException.class,
                () -> SceneImageReferenceResolver.resolve(images, 10L, id -> scene, true)).getMessage().contains("Picture 1"));
        assertEquals("old-cache.png", images.getFirst().getImageUrl());
    }

    @Test void changingAnAssetAffectsNewSubmissionsButNeverFrozenInputsOrOrder() {
        ResScene scene = scene();
        var upload = ShotRefImageDTO.builder().id("upload").sourceType("UPLOAD").usageRole("STYLE").imageUrl("upload.png").build();
        var images = List.of(upload, link());
        var frozen = SceneImageReferenceResolver.resolve(images, 10L, id -> scene, true);
        scene.setReferenceImageUrl("spatial-v2.png");
        assertEquals(List.of("upload", "stable-slot"), frozen.stream().map(ShotRefImageDTO::getId).toList());
        assertEquals("spatial-v1.png", frozen.get(1).getImageUrl());
        assertEquals("SNAPSHOT", frozen.get(1).getBindingMode());
        assertEquals("spatial-v2.png", SceneImageReferenceResolver.resolve(images, 10L, id -> scene, true).get(1).getImageUrl());
        assertEquals("spatial-v1.png", SceneImageReferenceResolver.resolve(frozen, 10L,
                id -> { throw new AssertionError("Frozen inputs must not look up mutable assets"); }, true).get(1).getImageUrl());
        assertEquals("ASSET_LINK", images.get(1).getBindingMode());
    }

    @Test void explicitSlotsNeverFallBackToAnotherImageAndLegacyUrlsRemainFixed() {
        ResScene scene = scene();
        var cover = link();
        cover.setSourceSlot("SCENE_COVER");
        assertEquals("cover.png", SceneImageReferenceResolver.resolve(List.of(cover), 10L, id -> scene, true).getFirst().getImageUrl());
        var legacy = link();
        legacy.setBindingMode(null);
        legacy.setSourceSlot(null);
        assertEquals("old-cache.png", SceneImageReferenceResolver.resolve(List.of(legacy), 10L, id -> scene, true).getFirst().getImageUrl());
        scene.setReferenceImageUrl(null);
        assertThrows(BizException.class, () -> SceneImageReferenceResolver.resolve(List.of(link()), 10L, id -> scene, true));
    }

    @Test void rejectsForeignDisabledDeletedAndAmbiguousSceneLinks() {
        ResScene scene = scene();
        assertThrows(BizException.class, () -> SceneImageReferenceResolver.resolve(List.of(link()), 20L, id -> scene, false));
        scene.setDramaId(0L);
        assertDoesNotThrow(() -> SceneImageReferenceResolver.resolve(List.of(link()), 20L, id -> scene, false));
        scene.setStatus(0);
        assertThrows(BizException.class, () -> SceneImageReferenceResolver.resolve(List.of(link()), 10L, id -> scene, true));
        assertThrows(BizException.class, () -> SceneImageReferenceResolver.resolve(List.of(link()), 10L, id -> null, false));
        var ambiguous = link();
        ambiguous.setSourceSlot(null);
        assertThrows(BizException.class, () -> SceneImageReferenceResolver.resolve(List.of(ambiguous), 10L, id -> scene, false));
        ambiguous.setSourceSlot("SCENE_REFERENCE");
        ambiguous.setSourceType("UPLOAD");
        assertThrows(BizException.class, () -> SceneImageReferenceResolver.resolve(List.of(ambiguous), 10L, id -> scene, false));
    }

    @Test void unavailableSceneRemainsEditableWithoutUsingACachedOrForeignUrl() {
        var missing = SceneImageReferenceResolver.preview(List.of(link()), 10L, id -> null);
        assertEquals("stable-slot", missing.getFirst().getId());
        assertNull(missing.getFirst().getImageUrl());
        var foreign = SceneImageReferenceResolver.preview(List.of(link()), 20L, id -> scene());
        assertNull(foreign.getFirst().getImageUrl());
        assertEquals("ASSET_LINK", foreign.getFirst().getBindingMode());
    }
}
