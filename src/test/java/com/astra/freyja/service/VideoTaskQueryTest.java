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
    @Test void generationHandoffDownloadsOriginalResultWithStagesAndNoResubmission() throws Exception {
        var generated=new java.util.concurrent.atomic.AtomicInteger();var downloaded=new java.util.concurrent.atomic.AtomicInteger();
        var deferredHeader=new java.util.concurrent.atomic.AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/v1/videos/generations",exchange->{
            generated.incrementAndGet();deferredHeader.set(exchange.getRequestHeaders().getFirst("X-Freyja-Deferred-Video"));
            exchange.getRequestBody().readAllBytes();byte[] body=("{\"data\":[{\"url\":\""+base+"/original.mp4\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.createContext("/original.mp4",exchange->{
            int status=downloaded.incrementAndGet()==1?503:200;byte[] body="original-video".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        String task="render-original";var stages=new java.util.ArrayList<String>();
        var minio=mock(io.minio.MinioClient.class);var properties=new com.astra.freyja.config.MinioProperties();
        properties.setBucketName("video-assets");properties.setEndpoint("http://minio:9000");properties.setExternalEndpoint("http://minio:9000");
        var service=spy(new AiImageApiServiceImpl(mock(com.astra.freyja.dao.AiProviderMapper.class),mock(com.astra.freyja.dao.AiModelMapper.class),
                mock(com.astra.freyja.dao.ResKeyframeMapper.class),mock(com.astra.freyja.util.CryptoUtil.class),minio,properties,new ObjectMapper(),
                mock(VideoFrameExtractService.class),mock(RenderDispatchCoordinator.class),mock(ReferenceVideoMediaService.class)));
        AiProvider provider=new AiProvider();provider.setId(9L);provider.setBaseUrl(base+"/v1");
        doReturn(provider).when(service).resolveProvider(9L);doReturn("test-video").when(service).resolveVideoModelCode(any(),eq(9L),any());
        var shot=new com.astra.freyja.entity.DramaShot();shot.setGenerationMode("REFERENCE_MODE");shot.setPrompt("saved video prompt");
        var request=new com.astra.freyja.dto.drama.DramaShotRenderRequestDTO();request.setProviderId(9L);request.setTaskId(task);
        com.astra.freyja.service.video.VideoExecutionState.begin(task,(stage,message)->stages.add(stage));
        try{
            var result=service.generateAndArchiveVideo(1L,2L,shot,request);
            assertTrue(result.getVideoUrl().startsWith("http://minio:9000/video-assets/"));
            assertEquals("true",deferredHeader.get());assertEquals(1,generated.get());assertEquals(2,downloaded.get());
            assertEquals(java.util.List.of("DOWNLOADING","DOWNLOADING","ARCHIVING"),stages);
            verify(minio,times(1)).putObject(any(io.minio.PutObjectArgs.class));
        }finally{com.astra.freyja.service.video.VideoExecutionState.clear(task);server.stop(0);}
    }

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
