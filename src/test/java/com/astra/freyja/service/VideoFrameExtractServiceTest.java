package com.astra.freyja.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 视频末帧抽取服务测试。
 * 注: 非 Spring 环境时 @Value 字段为空，extractLastFrame 会安全降级返回 null；这里验证各种异常入参不抛错。
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
}