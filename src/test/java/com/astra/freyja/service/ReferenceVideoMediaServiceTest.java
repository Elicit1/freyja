package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.*;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.video.ResolvedVideoProcessSource;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceVideoMediaServiceTest {
    @Test void preparesMeasuredClipAndCreatesUniqueImmutableObjects(@TempDir Path folder) throws Exception {
        var processes = new NativeProcessExecutor();
        var tools = new MediaToolProperties();
        try { Assumptions.assumeTrue(processes.execute(List.of(tools.getFfmpegPath(), "-version"), Duration.ofSeconds(10)).exitCode() == 0); }
        catch (java.io.IOException e) { Assumptions.abort("Native FFmpeg required"); }
        Path fixture = folder.resolve("source.mp4");
        var encoded = processes.execute(List.of(tools.getFfmpegPath(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "3", "-c:v", "libx264", "-c:a", "aac", fixture.toString()), Duration.ofSeconds(30));
        assertEquals(0, encoded.exitCode(), encoded.output());
        byte[] bytes = Files.readAllBytes(fixture);
        {
            String base = "http://storage.test:9000";
            var storage = new MinioProperties(); storage.setEndpoint(base); storage.setBucketName("assets");
            var minio = mock(MinioClient.class);
            var stat = mock(io.minio.StatObjectResponse.class); when(stat.size()).thenReturn((long) bytes.length); when(stat.etag()).thenReturn("source-etag");
            when(minio.statObject(any())).thenReturn(stat);
            when(minio.getObject(any())).thenAnswer(call -> new io.minio.GetObjectResponse(okhttp3.Headers.of(), "assets", "us-east-1", "source.mp4", new java.io.ByteArrayInputStream(bytes)));
            var limits = new ReferenceVideoProperties(); limits.setMaxDecodeWidth(96); limits.setMaxDecodeHeight(96);
            var service = new ReferenceVideoMediaService(limits, tools, new VideoFrameExtractService(tools, processes), processes, minio, storage);
            var input = new ShotRefVideoDTO(); input.setId("video1"); input.setStartSeconds(new BigDecimal("0.5")); input.setEndSeconds(new BigDecimal("1.5"));
            var source = ResolvedVideoProcessSource.builder().sourceType("DIRECT_URL").videoUrl(base + "/assets/source.mp4").build();
            var first = service.prepare(input, source, new ReferenceVideoCapability());
            assertTrue(first.getMetadata().getDuration().subtract(BigDecimal.ONE).abs().doubleValue() < 0.09);
            assertEquals(24.0, first.getMetadata().getFps()); assertEquals(24, first.getFrameCount());
            assertFalse(first.getMetadata().getHasAudio()); assertTrue(first.getSourceMetadata().getHasAudio());
            assertTrue(first.getMetadata().getWidth() <= 96); assertEquals(64, first.getContentSha256().length());
            input.setIncludeAudio(true);
            source.setVideoUrl(base + "/assets/source.mp4?versionId=chosen-version");
            var second = service.prepare(input, source, new ReferenceVideoCapability());
            assertTrue(second.getMetadata().getHasAudio()); assertNotEquals(first.getObjectPath(), second.getObjectPath());
            var objects = ArgumentCaptor.forClass(PutObjectArgs.class); verify(minio, times(4)).putObject(objects.capture());
            assertEquals(4, objects.getAllValues().stream().map(PutObjectArgs::object).distinct().count());
            var reads = ArgumentCaptor.forClass(io.minio.GetObjectArgs.class); verify(minio, times(2)).getObject(reads.capture());
            assertEquals("chosen-version", reads.getAllValues().get(1).versionId());
            input.setEndSeconds(BigDecimal.TEN);
            assertThrows(BizException.class, () -> service.prepare(input, source, new ReferenceVideoCapability()));
            verify(minio, times(4)).putObject(any());
        }
    }
}
