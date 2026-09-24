package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dto.res.AssetUploadResultVO;
import com.astra.freyja.service.MediaAssetService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetServiceImpl implements MediaAssetService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public AssetUploadResultVO upload(MultipartFile file, String category) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }

        String safeCategory = StringUtils.defaultIfBlank(category, "general").toLowerCase();
        String originalFilename = file.getOriginalFilename();
        String ext = getFileExtension(originalFilename);
        String dateDir = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String uniqueName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String objectPath = String.format("%s/%s/%s", safeCategory, dateDir, uniqueName);

        String bucket = minioProperties.getBucketName();
        ensureBucketExists(bucket);

        try (InputStream is = file.getInputStream()) {
            String contentType = file.getContentType();
            if (StringUtils.isBlank(contentType)) {
                contentType = determineContentType(ext);
            }

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectPath)
                            .stream(is, file.getSize(), -1)
                            .contentType(contentType)
                            .build()
            );

            String accessUrl = buildAccessUrl(bucket, objectPath);
            log.info("[MediaAsset] 成功上传文件: bucket={}, path={}, url={}", bucket, objectPath, accessUrl);

            return AssetUploadResultVO.builder()
                    .bucket(bucket)
                    .objectPath(objectPath)
                    .url(accessUrl)
                    .originalFilename(originalFilename)
                    .size(file.getSize())
                    .contentType(contentType)
                    .build();
        } catch (Exception e) {
            log.error("[MediaAsset] 上传文件失败: {}", e.getMessage(), e);
            throw new BizException("上传文件到 MinIO 失败: " + e.getMessage());
        }
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MediaAsset] 自动创建 MinIO 存储桶 [{}]", bucket);
            }
        } catch (Exception e) {
            log.warn("[MediaAsset] 检查或创建存储桶异常: {}", e.getMessage());
        }
    }

    private String buildAccessUrl(String bucket, String objectPath) {
        String base = minioProperties.getExternalEndpoint();
        if (StringUtils.isBlank(base)) {
            base = minioProperties.getEndpoint();
        }
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanObject = objectPath.startsWith("/") ? objectPath.substring(1) : objectPath;
        return String.format("%s/%s/%s", cleanBase, bucket, cleanObject);
    }

    private String getFileExtension(String filename) {
        if (StringUtils.isBlank(filename) || !filename.contains(".")) {
            return "png";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private String determineContentType(String ext) {
        return switch (ext.toLowerCase()) {
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "gif" -> "image/gif";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "png" -> "image/png";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            default -> "application/octet-stream";
        };
    }
}
