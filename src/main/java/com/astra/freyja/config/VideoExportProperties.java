package com.astra.freyja.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "freyja.video-export")
public class VideoExportProperties {
    private boolean enabled = true;
    private String bucket = "freyja-video-exports";
    private String tempDirectory = System.getProperty("java.io.tmpdir") + "/freyja-video-exports";
    private int maxShots = 1000;
    private long maxFileBytes = 4L * 1024 * 1024 * 1024;
    private long maxArchiveBytes = 20L * 1024 * 1024 * 1024;
    private long minFreeBytes = 512L * 1024 * 1024;
    private int readTimeoutSeconds = 120;
    private int retentionHours = 24;
    private int concatTimeoutSeconds = 3600;
    @jakarta.annotation.PostConstruct
    public void validate() {
        if(maxShots<1||maxFileBytes<1||maxArchiveBytes<1||minFreeBytes<0||readTimeoutSeconds<1||retentionHours<1||concatTimeoutSeconds<1)
            throw new IllegalStateException("视频导出容量、数量、超时和保留时间配置无效");
        if(bucket==null||!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))
            throw new IllegalStateException("视频导出私有桶名称无效");
    }
}
