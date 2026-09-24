package com.astra.freyja.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Native media tool configuration. The tools are executed directly on the host OS. */
@Data
@Component
@ConfigurationProperties(prefix = "freyja.media-tools")
public class MediaToolProperties {

    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";
    private Duration timeout = Duration.ofSeconds(30);
    private int tailOffsetMs = 100;
}
