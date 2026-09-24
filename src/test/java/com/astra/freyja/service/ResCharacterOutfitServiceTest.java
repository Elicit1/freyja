package com.astra.freyja.service;

import com.astra.freyja.dto.res.ResCharacterOutfitDTO;
import com.astra.freyja.service.impl.ResCharacterOutfitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResCharacterOutfitServiceTest {

    @Mock
    private ResCharacterLookService lookService;

    private ResCharacterOutfitService outfitService;

    @BeforeEach
    void setUp() {
        outfitService = new ResCharacterOutfitServiceImpl(lookService);
    }

    @Test
    @DisplayName("测试门面服务透明委托至 lookService")
    void testFacadeDelegatesToLookService() {
        ResCharacterOutfitDTO dto = new ResCharacterOutfitDTO();
        dto.setCharacterId(100L);
        dto.setOutfitName("日常便服");

        outfitService.create(dto);
        verify(lookService).create(dto);

        outfitService.setDefault(10L);
        verify(lookService).setDefault(10L);

        outfitService.delete(10L);
        verify(lookService).delete(10L);
    }
}
