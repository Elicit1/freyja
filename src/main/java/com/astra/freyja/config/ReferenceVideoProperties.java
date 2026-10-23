package com.astra.freyja.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Product preparation limits; these do not assert model support. */
@Data
@Component
@ConfigurationProperties(prefix = "freyja.reference-video")
public class ReferenceVideoProperties {
    private long maxSourceBytes = 256L * 1024 * 1024;
    private long maxClipBytes = 128L * 1024 * 1024;
    private int maxSourceSeconds = 600;
    private int maxClipSeconds = 15;
    private int decodeFps = 24;
    private int maxDecodedFrames = 360;
    private int maxDecodeWidth = 768;
    private int maxDecodeHeight = 768;
    private int timeoutSeconds = 120;
    private int preflightMinutes = 30;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (maxSourceBytes < 1 || maxClipBytes < 1 || maxSourceSeconds < 1 || maxClipSeconds < 1 || decodeFps < 1 || decodeFps > 60
                || maxDecodedFrames < 1 || maxDecodeWidth < 2 || maxDecodeHeight < 2 || timeoutSeconds < 1 || preflightMinutes < 1)
            throw new IllegalStateException("参考视频准备的数量、尺寸、帧率和超时配置无效");
    }
}
