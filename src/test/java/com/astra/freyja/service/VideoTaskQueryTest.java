package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.service.impl.AiImageApiServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoTaskQueryTest {
    @Test void recoveryUsesConfiguredGatewayAndChecksExactTaskIdentity() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/tasks/",exchange->{
            String task=exchange.getRequestURI().getPath().substring("/v1/tasks/".length());
            String body="{\"task_id\":\""+(task.equals("mismatch")?"another-task":task)
                    +"\",\"status\":\"SUCCESS\",\"video_url\":\"http://untrusted.invalid/result.mp4\",\"prompt_id\":\"prompt-1\",\"node_id\":\"node-1\"}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
        try {
            var service=spy(new AiImageApiServiceImpl(mock(com.astra.freyja.dao.AiProviderMapper.class),
                    mock(com.astra.freyja.dao.AiModelMapper.class),mock(com.astra.freyja.dao.ResKeyframeMapper.class),
                    mock(com.astra.freyja.util.CryptoUtil.class),mock(io.minio.MinioClient.class),
                    mock(com.astra.freyja.config.MinioProperties.class),new ObjectMapper(),
                    mock(VideoFrameExtractService.class),mock(RenderDispatchCoordinator.class),mock(ReferenceVideoMediaService.class)));
            AiProvider provider=new AiProvider();
            provider.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/");
            doReturn(provider).when(service).resolveProvider(9L);
            var result=service.queryVideoTask(9L,"RENDER_2032095628944588801");
            assertEquals("SUCCESS",result.status());assertEquals("prompt-1",result.promptId());
            assertEquals("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/tasks/RENDER_2032095628944588801/output",result.videoUrl());
            assertThrows(BizException.class,()->service.queryVideoTask(9L,"mismatch"));
        } finally {server.stop(0);}
    }
}
