package com.astra.freyja.controller;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.DramaShotFirstFrameDTO;
import com.astra.freyja.service.AiEventTaskService;
import com.astra.freyja.service.DramaShotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AiEventTaskControllerTest {
    @Mock private AiEventTaskService tasks;
    @Mock private DramaShotService shots;
    @InjectMocks private AiEventTaskController controller;

    @Test
    void obsoleteFrameTypeIsRejectedBeforeCreatingBackgroundTask() {
        DramaShotFirstFrameDTO request = new DramaShotFirstFrameDTO();
        request.setFrameType("END_FRAME");
        assertEquals(400, assertThrows(BizException.class,
                () -> controller.shotFrame(1L, request)).getCode());
        verifyNoInteractions(tasks, shots);
    }
}
