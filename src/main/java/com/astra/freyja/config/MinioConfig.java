package com.astra.freyja.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端注入与存储桶初始化配置。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final MinioProperties minioProperties;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    @Bean
    public CommandLineRunner initMinioBucket(MinioClient minioClient) {
        return args -> {
            try {
                String bucket = minioProperties.getBucketName();
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build()
                );
                if (!exists) {
                    minioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(bucket).build()
                    );
                    log.info("[MinIO] 默认存储桶 [{}] 初始化创建成功", bucket);
                } else {
                    log.info("[MinIO] 默认存储桶 [{}] 已存在", bucket);
                }
                // 确保存储桶具有公开只读权限，以便浏览器能直接匿名预览图片
                setBucketPublicPolicy(minioClient, bucket);
            } catch (Exception e) {
                log.warn("[MinIO] 初始化检查存储桶 [{}] 失败 (如 MinIO 未启动可忽略): {}",
                        minioProperties.getBucketName(), e.getMessage());
            }
        };
    }

    private void setBucketPublicPolicy(MinioClient minioClient, String bucket) {
        try {
            String policy = String.format("""
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": "*",
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """, bucket);
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build()
            );
            log.info("[MinIO] 存储桶 [{}] 已成功配置匿名只读公开策略 (Public Read-Only)", bucket);
        } catch (Exception e) {
            log.warn("[MinIO] 配置存储桶公开只读策略失败: {}", e.getMessage());
        }
    }
}
