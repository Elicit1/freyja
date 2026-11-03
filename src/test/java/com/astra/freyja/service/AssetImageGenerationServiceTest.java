package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.res.AssetImageGenerationRequest;
import com.astra.freyja.service.impl.AssetImageGenerationServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetImageGenerationServiceTest {
    @Test void invalidProviderCannotCreateARenderOrInvokeAnImageModel() {
        var images = mock(AiImageApiService.class); var renders = mock(RenderTaskService.class);
        var service = new AssetImageGenerationServiceImpl(images, mock(ResCharacterMapper.class),
                mock(ResCharacterLookMapper.class), mock(ResSceneMapper.class), mock(ResPropMapper.class), renders);
        var input = new AssetImageGenerationRequest(); input.setTargetId(1L); input.setTargetType("CHARACTER");
        input.setSlot("REFERENCE"); input.setPrompt("character sheet"); input.setProviderId(2L);
        when(images.resolveProvider(2L)).thenThrow(new BizException(404, "provider unavailable"));
        assertEquals(404, assertThrows(BizException.class, () -> service.submit(input)).getCode());
        verifyNoInteractions(renders); verify(images).resolveProvider(2L); verifyNoMoreInteractions(images);
    }
}
