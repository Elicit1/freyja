package com.astra.freyja.skill.repository;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** MinIO 中的标准 Skill 附属文件存储实现。 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SkillRepositoryImpl implements SkillRepository {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public void saveFile(String objectKey, byte[] data, String contentType) {
        if (StringUtils.isBlank(objectKey)) {
            throw new BizException("Skill 文件对象键不能为空");
        }
        ensureBucketExists();
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(input, data.length, -1)
                    .contentType(StringUtils.defaultIfBlank(contentType, "application/octet-stream"))
                    .build());
        } catch (Exception e) {
            throw new BizException("保存 Skill 附属文件失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] loadFile(String objectKey, String expectedHash, Long expectedSize) {
        if (StringUtils.isBlank(objectKey)) {
            throw new BizException("Skill 文件对象键不能为空");
        }
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioProperties.getBucketName())
                .object(objectKey)
                .build())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            byte[] result = output.toByteArray();
            if (expectedSize != null && expectedSize >= 0 && result.length != expectedSize) {
                throw new BizException("Skill 附属文件大小校验失败: " + objectKey);
            }
            String actualHash = "sha256:" + toHex(MessageDigest.getInstance("SHA-256").digest(result));
            if (StringUtils.isNotBlank(expectedHash) && !expectedHash.equalsIgnoreCase(actualHash)) {
                throw new BizException("Skill 附属文件哈希校验失败: " + objectKey);
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("读取 Skill 附属文件失败: " + e.getMessage());
        }
    }

    @Override
    public String loadTextFile(String objectKey, String expectedHash, Long expectedSize) {
        return new String(loadFile(objectKey, expectedHash, expectedSize), StandardCharsets.UTF_8);
    }

    @Override
    public void removeFile(String objectKey) {
        if (StringUtils.isBlank(objectKey)) return;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("[SkillRepository] 删除 Skill 附属文件失败: objectKey={}, error={}", objectKey, e.getMessage());
        }
    }

    @Override
    public void removeFiles(List<String> objectKeys) {
        if (objectKeys == null) return;
        objectKeys.forEach(this::removeFile);
    }

    private void ensureBucketExists() {
        try {
            String bucket = minioProperties.getBucketName();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new BizException("检查 Skill 存储桶失败: " + e.getMessage());
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
