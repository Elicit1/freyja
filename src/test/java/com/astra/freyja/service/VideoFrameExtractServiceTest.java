package com.astra.freyja.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 视频末帧抽取服务测试。
 * 覆盖异常入参与真实 FFmpeg 的末帧提取，包含短视频和末帧颜色变化。
 */
@DisplayName("视频末帧抽取服务测试")
class VideoFrameExtractServiceTest {

    private final VideoFrameExtractService service = new VideoFrameExtractService();

    @Test
    @DisplayName("空字节/空扩展名返回 null 不抛错")
    void testNullOrEmptyInputReturnsNull() {
        assertNull(service.extractLastFrame(null, "mp4"));
        assertNull(service.extractLastFrame(new byte[0], "mp4"));
    }

    @Test
    @DisplayName("无效视频字节安全降级返回 null")
    void testGarbageBytesGracefullyReturnsNull() {
        byte[] garbage = "definitely-not-a-real-video".getBytes(StandardCharsets.UTF_8);
        assertNull(service.extractLastFrame(garbage, "mp4"));
        assertNull(service.extractLastFrame(garbage, ""));
        assertNull(service.extractLastFrame(garbage, "webm"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 25})
    @DisplayName("实际末帧与之前画面不同时，提取末帧；不足旧偏移时长的短视频也有效")
    void extractsFinalDecodedFrame(int frames, @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        String ffmpeg = System.getProperty("freyja.test.ffmpeg", "ffmpeg");
        var executor = new NativeProcessExecutor();
        try {
            var version = executor.execute(java.util.List.of(ffmpeg, "-version"), java.time.Duration.ofSeconds(10));
            org.junit.jupiter.api.Assumptions.assumeTrue(version.exitCode() == 0, "FFmpeg is required for native extraction tests");
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("FFmpeg is unavailable; set -Dfreyja.test.ffmpeg to its path");
        }
        java.nio.file.Path video = temp.resolve("final-frame.mp4");
        var encoded = executor.execute(java.util.List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=64x64:r=25",
                "-vf", "drawbox=c=blue:t=fill:enable='eq(n," + (frames - 1) + ")'",
                "-frames:v", String.valueOf(frames), "-c:v", "libx264", "-bf", "2", "-pix_fmt", "yuv420p", video.toString()),
                java.time.Duration.ofSeconds(30));
        org.junit.jupiter.api.Assertions.assertEquals(0, encoded.exitCode(), encoded.output());
        var properties = new com.astra.freyja.config.MediaToolProperties();
        properties.setFfmpegPath(ffmpeg);
        var extractor = new VideoFrameExtractService(properties, executor);
        byte[] frame = extractor.extractLastFrame(java.nio.file.Files.readAllBytes(video), "mp4");
        org.junit.jupiter.api.Assertions.assertNotNull(frame);
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(frame));
        org.junit.jupiter.api.Assertions.assertNotNull(image);
        var color = new java.awt.Color(image.getRGB(32, 32));
        org.junit.jupiter.api.Assertions.assertTrue(color.getBlue() > 200 && color.getRed() < 50,
                "Expected the final blue frame rather than an earlier red frame, got " + color);
    }
}