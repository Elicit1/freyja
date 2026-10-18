package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.net.URI;

@Service
@Slf4j
@RequiredArgsConstructor
public class DubbingAudioStorage {
    public static final int MAX_BYTES = 30 * 1024 * 1024;
    private final MinioClient client;
    private final MinioProperties properties;

    public byte[] read(String url) {
        String key = objectKey(url);
        try (var input = client.getObject(GetObjectArgs.builder().bucket(properties.getBucketName()).object(key).build())) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new BizException(400, "音频文件超过30 MiB");
            return bytes;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(502, "无法读取已归档音频"); }
    }

    public String put(String key, byte[] bytes) {
        if (!key.startsWith("dubbing/") || bytes.length > MAX_BYTES) throw new BizException(400, "无效音频归档");
        try (var input = new ByteArrayInputStream(bytes)) {
            String bucket = properties.getBucketName();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(input, bytes.length, -1).contentType("audio/wav").build());
            String base = properties.getExternalEndpoint();
            if (base == null || base.isBlank()) base = properties.getEndpoint();
            return base.replaceAll("/+$", "") + "/" + bucket + "/" + key;
        } catch (Exception e) { throw new BizException(502, "音频归档失败"); }
    }

    public void deleteOwned(String url) {
        if (url == null || url.isBlank()) return;
        try {
            String key = objectKey(url);
            // Migrated media can be shared with historical records. Only this module's files are owned.
            if (key.startsWith("dubbing/")) client.removeObject(RemoveObjectArgs.builder().bucket(properties.getBucketName()).object(key).build());
        } catch (Exception e) { log.warn("配音文件清理失败，待存储维护清理: {}", url); }
    }

    private String objectKey(String url) {
        if (url == null) throw new BizException(400, "音频地址为空");
        URI uri;
        try { uri = URI.create(url); } catch (Exception e) { throw new BizException(400, "音频地址无效"); }
        boolean allowed = false;
        for (String endpoint : new String[]{properties.getEndpoint(), properties.getExternalEndpoint()}) {
            if (endpoint == null || endpoint.isBlank()) continue;
            URI base = URI.create(endpoint);
            if (java.util.Objects.equals(base.getScheme(), uri.getScheme()) && java.util.Objects.equals(base.getAuthority(), uri.getAuthority())) allowed = true;
        }
        String prefix = "/" + properties.getBucketName() + "/";
        if (!allowed || uri.getPath() == null || !uri.getPath().startsWith(prefix))
            throw new BizException(400, "音频必须来自平台归档存储，请先上传音频");
        return uri.getPath().substring(prefix.length());
    }
}
