package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.ReferenceVideoProperties;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Video bodies stream to bounded temporary disk files, never into MCP JSON or heap buffers. */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpVideoTransferStorage {
    private final MinioClient minio;
    private final ReferenceVideoProperties limits;

    void download(McpPresignedVideoUploads.Pending upload, Path target) {
        try {
            var stat = minio.statObject(StatObjectArgs.builder().bucket(upload.bucket()).object(upload.objectKey()).build());
            if (stat.size() != upload.size()) throw new BizException(400, "VIDEO_SIZE_MISMATCH: 文件大小与申请不一致");
            if (!upload.contentType().equals(stat.contentType())) throw new BizException(400, "VIDEO_TYPE_MISMATCH: Content-Type 与申请不一致");
            var digest = MessageDigest.getInstance("SHA-256");
            long count = 0, deadline = System.nanoTime() + java.time.Duration.ofSeconds(limits.getTimeoutSeconds()).toNanos();
            try (var input = minio.getObject(GetObjectArgs.builder().bucket(upload.bucket()).object(upload.objectKey()).matchETag(stat.etag()).build());
                 var output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > upload.size() || count > limits.getMaxSourceBytes()) throw new BizException(413, "VIDEO_SIZE_MISMATCH: 视频超过申请大小或服务限制");
                    if (System.nanoTime() > deadline) throw new BizException(408, "VIDEO_UPLOAD_READ_TIMEOUT: 读取视频超时，请重试确认");
                    digest.update(buffer, 0, read); output.write(buffer, 0, read);
                }
            }
            if (count != upload.size()) throw new BizException(400, "VIDEO_SIZE_MISMATCH: 视频不完整");
            if (!HexFormat.of().formatHex(digest.digest()).equals(upload.sha256()))
                throw new BizException(400, "VIDEO_HASH_MISMATCH: 文件 SHA-256 与申请不一致");
        } catch (BizException e) { throw e; }
        catch (ErrorResponseException e) {
            if (Set.of("NoSuchKey", "NoSuchObject", "NotFound").contains(e.errorResponse().code()))
                throw new BizException(404, "VIDEO_UPLOAD_NOT_FOUND: 请先完成视频文件直传，再确认");
            throw new BizException(502, "VIDEO_UPLOAD_READ_FAILED: 读取视频失败，请使用原请求重试");
        } catch (Exception e) { throw new BizException(502, "VIDEO_UPLOAD_READ_FAILED: 读取视频失败，请使用原请求重试"); }
    }

    void discard(McpPresignedVideoUploads.Pending upload) {
        try { minio.removeObject(RemoveObjectArgs.builder().bucket(upload.bucket()).object(upload.objectKey()).build()); }
        catch (Exception e) { log.warn("MCP 暂存视频等待生命周期回收: uploadId={}", upload.uploadId()); }
    }
}
