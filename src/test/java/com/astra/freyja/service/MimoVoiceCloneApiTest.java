package com.astra.freyja.service;

import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.impl.AiAudioApiServiceImpl;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.minio.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MimoVoiceCloneApiTest {
    @SuppressWarnings("unchecked") @ParameterizedTest
    @ValueSource(strings={"(愤怒 兴奋)马上[提高音量]离开。", "（悲伤）请你[哽咽]留下。", "[平静]你好。"})
    void forwardsCallerMessagesAndOptionsWithoutRewritingTags(String spokenText) throws Exception {
        for(Class<?> type:List.of(AiModel.class,AiProvider.class)) TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),type);
        ObjectMapper json=new ObjectMapper();byte[] wav=VoiceEffectEngineTest.sine();AtomicReference<JsonNode> received=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            received.set(json.readTree(exchange.getRequestBody()));
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("audio",Map.of("data",Base64.getEncoder().encodeToString(wav)))))));
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var providers=mock(AiProviderMapper.class);var models=mock(AiModelMapper.class);var minio=mock(MinioClient.class);var crypto=mock(CryptoUtil.class);
            AiProvider provider=new AiProvider();provider.setId(1L);provider.setStatus(1);provider.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");provider.setApiKey("encrypted");provider.setTimeout(10);
            when(providers.selectById(1L)).thenReturn(provider);when(models.selectOne(any(Wrapper.class))).thenReturn(new AiModel());when(crypto.decrypt("encrypted")).thenReturn("test-key");
            when(minio.bucketExists(any())).thenReturn(true);
            var api=new AiAudioApiServiceImpl(providers,models,minio,new MinioProperties(),crypto,json,mock(VideoFrameExtractService.class));
            var input=Map.<String,Object>of("messages",List.of(Map.of("role","user","content","在句尾停顿。"),Map.of("role","assistant","content",spokenText)),
                    "audio",Map.of("optimize_text_preview",false),"temperature",0.7);
            var snapshot=json.writeValueAsString(input);
            var output=api.generateVoice(1L,"mimo-v2.5-tts-voiceclone",wav,input,"dubbing/test.wav");
            assertEquals(snapshot,json.writeValueAsString(input),"调用不能修改原始 JSON");
            JsonNode body=received.get();assertNotNull(body);assertEquals("mimo-v2.5-tts-voiceclone",body.path("model").asText());
            assertEquals("data:audio/wav;base64,"+Base64.getEncoder().encodeToString(wav),body.path("audio").path("voice").asText());
            assertEquals("assistant",body.path("messages").get(1).path("role").asText());assertEquals(spokenText,body.path("messages").get(1).path("content").asText());
            assertEquals(json.valueToTree(input.get("messages")),body.path("messages"));assertEquals(0.7,body.path("temperature").asDouble());assertFalse(body.path("audio").path("optimize_text_preview").asBoolean());assertFalse(body.path("stream").asBoolean());
            assertEquals(1.5,output.durationSeconds(),0.05);verify(minio).putObject(any(PutObjectArgs.class));
        } finally {server.stop(0);}
    }
}
