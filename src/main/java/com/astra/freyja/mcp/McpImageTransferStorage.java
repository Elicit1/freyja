package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

/** Binary transfers go directly to a private staging bucket; only validated bytes reach the asset bucket. */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpImageTransferStorage {
    private final MinioClient minio;
    private final MinioProperties properties;
    private String initializedBucket;

    String bucket() {
        String bucket = properties.getMcpUploadBucket();
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.equals(properties.getBucketName()))
            throw new BizException(500, "MCP_UPLOAD_CONFIG_INVALID: 请配置独立的 MinIO 私有暂存桶");
        return bucket;
    }

    Map<String, Object> authorize(McpPresignedImageUploads.Pending upload) {
        // Sign and access storage over the internal endpoint, but send clients their own reachable address.
        String endpoint = properties.getMcpUploadEndpoint();
        if (endpoint == null || endpoint.isBlank()) endpoint = properties.getExternalEndpoint();
        endpoint = endpoint == null ? "" : endpoint.trim();
        try {
            URI uri = URI.create(endpoint);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new BizException(500, "MCP_UPLOAD_CONFIG_INVALID: 请配置客户端可访问的 MINIO_MCP_UPLOAD_ENDPOINT（未配置时使用 MINIO_EXTERNAL_ENDPOINT）");
        }
        try {
            ensureBucket(upload.bucket());
            PostPolicy policy = new PostPolicy(upload.bucket(), ZonedDateTime.parse(upload.expiresAt()));
            policy.addEqualsCondition("key", upload.objectKey());
            policy.addEqualsCondition("Content-Type", upload.contentType());
            // S3 enforces the actual file size, excluding multipart envelope bytes, before accepting the object.
            policy.addContentLengthRangeCondition(upload.size(), upload.size());
            Map<String, String> fields = new LinkedHashMap<>(minio.getPresignedPostFormData(policy));
            fields.put("key", upload.objectKey());
            fields.put("Content-Type", upload.contentType());
            return Map.of("method", "POST", "uploadUrl", endpoint.replaceAll("/+$", "") + "/" + upload.bucket(),
                    "fields", fields, "fileField", "file");
        } catch (Exception e) {
            throw new BizException(502, "IMAGE_UPLOAD_AUTHORIZATION_FAILED: 无法签发上传凭证，请使用原 requestId 重试");
        }
    }

    private synchronized void ensureBucket(String bucket) throws Exception {
        if (bucket.equals(initializedBucket)) return;
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            try { minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
            catch (ErrorResponseException e) {
                if (!"BucketAlreadyOwnedByYou".equals(e.errorResponse().code())) throw e;
            }
        }
        // This dedicated bucket is never assigned the public-read policy of the normal asset bucket.
        var rule = new LifecycleRule(Status.ENABLED, null,
                new Expiration((ZonedDateTime) null, 1, null), new RuleFilter("mcp/"),
                "expire-mcp-image-uploads", null, null, null);
        minio.setBucketLifecycle(SetBucketLifecycleArgs.builder().bucket(bucket)
                .config(new LifecycleConfiguration(List.of(rule))).build());
        initializedBucket = bucket;
    }

    byte[] read(McpPresignedImageUploads.Pending upload) {
        try {
            var stat = minio.statObject(StatObjectArgs.builder().bucket(upload.bucket()).object(upload.objectKey()).build());
            if (stat.size() != upload.size()) throw new BizException(400, "IMAGE_SIZE_MISMATCH: 上传文件大小与申请不一致");
            if (!upload.contentType().equals(stat.contentType()))
                throw new BizException(400, "IMAGE_TYPE_MISMATCH: 上传 Content-Type 与申请不一致");
            try (var stream = minio.getObject(GetObjectArgs.builder().bucket(upload.bucket()).object(upload.objectKey())
                    .matchETag(stat.etag()).build())) {
                byte[] bytes = stream.readNBytes(upload.size() + 1);
                if (bytes.length != upload.size()) throw new BizException(400, "IMAGE_SIZE_MISMATCH: 上传文件不完整或大小已改变");
                return bytes;
            }
        } catch (BizException e) { throw e; }
        catch (ErrorResponseException e) {
            if (Set.of("NoSuchKey", "NoSuchObject", "NotFound").contains(e.errorResponse().code()))
                throw new BizException(404, "IMAGE_UPLOAD_NOT_FOUND: 文件尚未上传，请完成二进制直传后再确认");
            throw new BizException(502, "IMAGE_UPLOAD_READ_FAILED: 读取暂存图片失败，请重试确认");
        } catch (Exception e) {
            throw new BizException(502, "IMAGE_UPLOAD_READ_FAILED: 读取暂存图片失败，请重试确认");
        }
    }

    void discard(McpPresignedImageUploads.Pending upload) {
        try { minio.removeObject(RemoveObjectArgs.builder().bucket(upload.bucket()).object(upload.objectKey()).build()); }
        catch (Exception e) { log.warn("[MCP] 暂存图片清理失败，等待存储生命周期回收: uploadId={}", upload.uploadId()); }
    }
}
