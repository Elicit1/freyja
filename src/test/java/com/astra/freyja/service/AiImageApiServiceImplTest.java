package com.astra.freyja.service;

import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.impl.AiImageApiServiceImpl;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiImageApiServiceImplTest {

    @Mock
    private AiProviderMapper providerMapper;

    @Mock
    private AiModelMapper modelMapper;

    @Mock
    private com.astra.freyja.dao.ResKeyframeMapper resKeyframeMapper;

    @Mock
    private CryptoUtil cryptoUtil;

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private VideoFrameExtractService videoFrameExtractService;

    @Spy
    @InjectMocks
    private AiImageApiServiceImpl aiImageApiService;

    @Test
    void invalidStoredKeyImageIsRejectedBeforeGatewayOrProviderAccess() throws Exception {
        DramaShot shot = new DramaShot();
        shot.setRefImagesJson("[{\"sourceType\":\"KEYFRAME\",\"usageRole\":\"END_FRAME\"}]");
        when(objectMapper.readValue(eq(shot.getRefImagesJson()),
                org.mockito.ArgumentMatchers.<TypeReference<List<ShotRefImageDTO>>>any()))
                .thenReturn(List.of(ShotRefImageDTO.builder().sourceType("KEYFRAME")
                        .usageRole("END_FRAME").build()));
        assertEquals(400, assertThrows(BizException.class,
                () -> aiImageApiService.generateAndArchiveVideo(1L, 2L, shot, null)).getCode());
        org.mockito.Mockito.verifyNoInteractions(providerMapper, modelMapper, cryptoUtil, minioClient);
    }

    @Test
    @DisplayName("参考模式拒绝聊天模型并只查询参考视频模型")
    void testReferenceModeRejectsChatModel() {
        assertVideoModeRejectsUnmatchedModel("REFERENCE_MODE", "TXT2VIDEO_REF");
    }

    @Test
    @DisplayName("首尾帧模式拒绝聊天模型并只查询首尾帧视频模型")
    void testFirstLastModeRejectsChatModel() {
        assertVideoModeRejectsUnmatchedModel("FIRST_LAST_FRAME", "TXT2VIDEO_FIRST_LAST");
    }

    private void assertVideoModeRejectsUnmatchedModel(String mode, String requiredType) {
        DramaShotRenderRequestDTO request = new DramaShotRenderRequestDTO();
        request.setWorkflowTemplateId("deepseek-flash");
        doReturn(null).when(modelMapper).selectOne(any());

        BizException error = assertThrows(BizException.class,
                () -> aiImageApiService.resolveVideoModelCode(request, 5L, mode));
        assertTrue(error.getMessage().contains(requiredType));
    }

    @Test
    @DisplayName("视频网关请求使用提交时固定的镜头种子")
    void testVideoPayloadUsesSeedSnapshot() throws Exception {
        AiProvider provider = new AiProvider();
        provider.setId(5L);
        provider.setStatus(1);
        provider.setBaseUrl("https://gateway.example.test/v1");
        when(providerMapper.selectById(5L)).thenReturn(provider);

        DramaShot shot = new DramaShot();
        shot.setPrompt("cinematic shot");
        shot.setSeed(1234L);
        DramaShotRenderRequestDTO request = new DramaShotRenderRequestDTO();
        request.setProviderId(5L);
        request.setWorkflowTemplateId("minimax-h3-fl2va");
        request.setSeed(5678L);

        doReturn("minimax-h3-fl2va").when(aiImageApiService)
                .resolveVideoModelCode(eq(request), eq(5L), nullable(String.class));
        when(objectMapper.writeValueAsString(any())).thenThrow(new BizException(500, "capture payload"));
        assertThrows(BizException.class, () -> aiImageApiService.generateAndArchiveVideo(1L, 2L, shot, request));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(payload.capture());
        assertEquals(5678L, ((Map<?, ?>) payload.getValue()).get("seed"));
    }

    @Test
    @DisplayName("非主体人物参考图和造型关系仍传给视频网关")
    void nonSubjectCharacterImageIsNotDroppedFromVideoPayload() throws Exception {
        AiProvider provider = new AiProvider(); provider.setId(5L); provider.setStatus(1);
        provider.setBaseUrl("https://gateway.example.test/v1");
        when(providerMapper.selectById(5L)).thenReturn(provider);
        String dataUri = "data:image/png;base64,aW1hZ2U=";
        ShotRefImageDTO reference = ShotRefImageDTO.builder().id("pov-look")
                .sourceType("CHARACTER_REFERENCE").sourceId(301L).characterId(201L).lookId(301L)
                .name("林默造型").usageRole("NON_SUBJECT").referenceRole("COMBINED").imageUrl(dataUri).build();
        DramaShot shot = new DramaShot(); shot.setGenerationMode("REFERENCE_MODE");
        shot.setPrompt("First-person view from Lin Mo, looking toward the doorway.");
        shot.setRefImagesJson("stored-reference-json");
        when(objectMapper.readValue(eq(shot.getRefImagesJson()),
                org.mockito.ArgumentMatchers.<TypeReference<List<ShotRefImageDTO>>>any())).thenReturn(List.of(reference));
        DramaShotRenderRequestDTO request = new DramaShotRenderRequestDTO(); request.setProviderId(5L);
        doReturn("minimax-h3-ref2va").when(aiImageApiService)
                .resolveVideoModelCode(eq(request), eq(5L), eq("REFERENCE_MODE"));
        when(objectMapper.writeValueAsString(any())).thenThrow(new BizException(500, "capture payload"));
        assertThrows(BizException.class, () -> aiImageApiService.generateAndArchiveVideo(1L, 2L, shot, request));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(payload.capture());
        List<?> references = (List<?>) ((Map<?, ?>) payload.getValue()).get("references");
        Map<?, ?> binding = (Map<?, ?>) references.getFirst();
        assertEquals("NON_SUBJECT", binding.get("usageRole"));
        assertEquals("COMBINED", binding.get("referenceRole"));
        assertEquals("201", binding.get("characterId"));
        assertEquals("301", binding.get("lookId"));
        assertEquals(List.of(dataUri), binding.get("referenceImages"));
    }

    @Test
    @DisplayName("测试当生图网关返回127.0.0.1时，自动校正为提供商配置的真实远程IP")
    void testResolveRemoteImageUrl_loopbackRewrite() throws Exception {
        Method method = AiImageApiServiceImpl.class.getDeclaredMethod("resolveRemoteImageUrl", String.class, String.class);
        method.setAccessible(true);

        String remoteUrl = "http://127.0.0.1:8000/outputs/d8747c6a9da0_FLUX2_Klein_00007_.png";
        String providerBaseUrl = "http://192.168.1.200:8000/v1";

        String result = (String) method.invoke(aiImageApiService, remoteUrl, providerBaseUrl);

        assertEquals("http://192.168.1.200:8000/outputs/d8747c6a9da0_FLUX2_Klein_00007_.png", result);
    }

    @Test
    @DisplayName("测试当生图网关返回公网或合法非回环地址时，保持原地址不篡改")
    void testResolveRemoteImageUrl_noRewriteForExternal() throws Exception {
        Method method = AiImageApiServiceImpl.class.getDeclaredMethod("resolveRemoteImageUrl", String.class, String.class);
        method.setAccessible(true);

        String remoteUrl = "https://cdn.example.com/images/generated.png";
        String providerBaseUrl = "https://api.openai.com/v1";

        String result = (String) method.invoke(aiImageApiService, remoteUrl, providerBaseUrl);

        assertEquals("https://cdn.example.com/images/generated.png", result);
    }
}
