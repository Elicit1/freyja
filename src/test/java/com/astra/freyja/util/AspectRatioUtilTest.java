package com.astra.freyja.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AspectRatioUtilTest {

    @Test
    @DisplayName("测试具体宽高分辨率输入解析 (1920*1080, 1080*1920, 1920x1080)")
    void testDirectResolutionInputs() {
        assertEquals("1920x1080", AspectRatioUtil.resolveSize("1920*1080"));
        assertEquals("1920x1080", AspectRatioUtil.resolveSize("1920x1080"));
        assertEquals("1920x1080", AspectRatioUtil.resolveSize("1920X1080"));
        assertEquals("1080x1920", AspectRatioUtil.resolveSize("1080*1920"));
        assertEquals("1280x720", AspectRatioUtil.resolveSize("1280*720"));
        assertEquals("720x1280", AspectRatioUtil.resolveSize("720*1280"));
    }

    @Test
    @DisplayName("测试标准画幅比例解析 (16:9, 9:16, 1:1, 4:3)")
    void testAspectRatioInputs() {
        assertEquals("1280x720", AspectRatioUtil.resolveSize("16:9"));
        assertEquals("720x1280", AspectRatioUtil.resolveSize("9:16"));
        assertEquals("1024x1024", AspectRatioUtil.resolveSize("1:1"));
        assertEquals("1024x768", AspectRatioUtil.resolveSize("4:3"));
        assertEquals("768x1024", AspectRatioUtil.resolveSize("3:4"));
    }

    @Test
    @DisplayName("测试空值与未知值兜底为短剧标准 720x1280")
    void testDefaultFallbacks() {
        assertEquals("720x1280", AspectRatioUtil.resolveSize(null));
        assertEquals("720x1280", AspectRatioUtil.resolveSize(""));
        assertEquals("720x1280", AspectRatioUtil.resolveSize("   "));
        assertEquals("720x1280", AspectRatioUtil.resolveSize("unknown"));
    }

    @Test
    @DisplayName("测试尺寸反向推导比例")
    void testResolveRatioBySize() {
        assertEquals("16:9", AspectRatioUtil.resolveRatioBySize("1920*1080"));
        assertEquals("16:9", AspectRatioUtil.resolveRatioBySize("1920x1080"));
        assertEquals("9:16", AspectRatioUtil.resolveRatioBySize("1080*1920"));
        assertEquals("9:16", AspectRatioUtil.resolveRatioBySize("720x1280"));
        assertEquals("1:1", AspectRatioUtil.resolveRatioBySize("1024x1024"));
    }
}
