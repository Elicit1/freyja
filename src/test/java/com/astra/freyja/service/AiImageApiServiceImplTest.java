package com.astra.freyja.service;

import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
