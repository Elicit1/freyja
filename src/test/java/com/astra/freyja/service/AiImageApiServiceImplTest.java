package com.astra.freyja.service;

import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.service.impl.AiImageApiServiceImpl;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @InjectMocks
    private AiImageApiServiceImpl aiImageApiService;

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
