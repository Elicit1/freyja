package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.AiProviderDTO;
import com.astra.freyja.dto.AiProviderVO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.impl.AiProviderServiceImpl;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiProviderServiceTest {

    @Mock
    private AiProviderMapper providerMapper;

    @Mock
    private AiModelMapper modelMapper;

    @Mock
    private CryptoUtil cryptoUtil;

    @Mock
    private AiModelFactory aiModelFactory;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private AiProviderServiceImpl providerService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testCreateSuccess() {
        AiProviderDTO dto = new AiProviderDTO();
        dto.setProviderCode("deepseek");
        dto.setProviderName("DeepSeek");
        dto.setProviderType(AiModelFactory.TYPE_OPENAI);
        dto.setApiKey("sk-test-key-12345");
        dto.setBaseUrl("https://api.deepseek.com");
        dto.setStatus(1);

        when(providerMapper.selectCount(any())).thenReturn(0L);
        when(cryptoUtil.encrypt("sk-test-key-12345")).thenReturn("encrypted-key");

        providerService.create(dto);

        verify(providerMapper, times(1)).insert(isA(AiProvider.class));
        verify(redisTemplate, times(1)).delete("freyja:ai:provider:enabled");
    }

    @Test
    void testCreateDuplicateCodeThrows() {
        AiProviderDTO dto = new AiProviderDTO();
        dto.setProviderCode("deepseek");
        dto.setProviderName("DeepSeek");
        dto.setProviderType(AiModelFactory.TYPE_OPENAI);
        dto.setApiKey("sk-test-key-12345");
        dto.setStatus(1);

        when(providerMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> providerService.create(dto));
        assertEquals("提供商编码已存在", ex.getMessage());
        verify(providerMapper, never()).insert(isA(AiProvider.class));
    }

    @Test
    void testCreateOpenAiMissingApiKeyThrows() {
        AiProviderDTO dto = new AiProviderDTO();
        dto.setProviderCode("deepseek");
        dto.setProviderName("DeepSeek");
        dto.setProviderType(AiModelFactory.TYPE_OPENAI);
        dto.setStatus(1);

        BizException ex = assertThrows(BizException.class, () -> providerService.create(dto));
        assertEquals("API Key 不能为空", ex.getMessage());
    }

    @Test
    void testGetById() {
        AiProvider provider = new AiProvider();
        provider.setId(100L);
        provider.setProviderCode("openai");
        provider.setProviderName("OpenAI");
        provider.setProviderType(AiModelFactory.TYPE_OPENAI);
        provider.setApiKey("encrypted-key");
        provider.setStatus(1);

        when(providerMapper.selectById(100L)).thenReturn(provider);
        when(cryptoUtil.decrypt("encrypted-key")).thenReturn("sk-12345678");
        when(cryptoUtil.mask("sk-12345678")).thenReturn("****5678");

        AiProviderVO vo = providerService.getById(100L);
        assertNotNull(vo);
        assertEquals("openai", vo.getProviderCode());
        assertEquals("****5678", vo.getMaskedApiKey());
        assertTrue(vo.getHasApiKey());
    }

    @Test
    void testTestConnectivitySuccess() {
        AiProvider provider = new AiProvider();
        provider.setId(100L);
        provider.setProviderType(AiModelFactory.TYPE_OPENAI);
        when(providerMapper.selectById(100L)).thenReturn(provider);

        AiModel model = new AiModel();
        model.setId(1L);
        model.setProviderId(100L);
        model.setModelCode("deepseek-chat");
        model.setStatus(1);

        when(modelMapper.selectList(any())).thenReturn(List.of(model));

        ChatModel mockChatModel = mock(ChatModel.class);
        when(mockChatModel.call("ping")).thenReturn("pong");
        when(aiModelFactory.getChatModel(100L, "deepseek-chat")).thenReturn(mockChatModel);

        assertDoesNotThrow(() -> providerService.test(100L, null));
    }

    @Test
    void testTestConnectivityNoModelsThrows() {
        AiProvider provider = new AiProvider();
        provider.setId(100L);
        provider.setProviderType(AiModelFactory.TYPE_OPENAI);
        when(providerMapper.selectById(100L)).thenReturn(provider);
        when(modelMapper.selectList(any())).thenReturn(Collections.emptyList());

        BizException ex = assertThrows(BizException.class, () -> providerService.test(100L, null));
        assertEquals("该提供商下无启用中的模型", ex.getMessage());
    }
}
