package com.astra.freyja.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 基础配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /** MinIO 服务端点 (内网/API 端点) */
    private String endpoint = "http://127.0.0.1:9000";

    /** 外部/前端可访问的端点 (例如外网域名或反代地址) */
    private String externalEndpoint = "http://127.0.0.1:9000";

    /** Access Key */
    private String accessKey = "minioadmin";

    /** Secret Key */
    private String secretKey = "minioadmin123";

    /** 默认存储桶名称 */
    private String bucketName = "video-assets";
}
