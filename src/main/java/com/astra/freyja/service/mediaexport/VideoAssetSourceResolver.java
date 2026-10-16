package com.astra.freyja.service.mediaexport;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;

@Service
@RequiredArgsConstructor
public class VideoAssetSourceResolver {
    private final MinioClient minio;
    private final MinioProperties properties;
    private final com.astra.freyja.config.VideoExportProperties exportProperties;
    private volatile MinioClient reader;

    public void resolve(Snapshot snapshot) {
        URI video;
        try { video = URI.create(snapshot.getVideoUrl()); }
        catch (Exception e) { throw new BizException(400, "当前视频地址无效"); }
        if (video.getUserInfo() != null || video.getFragment() != null) throw new BizException(400, "当前视频地址格式不受支持");
        for (String endpoint : Arrays.asList(properties.getEndpoint(), properties.getExternalEndpoint())) {
            if (endpoint == null || endpoint.isBlank()) continue;
            URI base = URI.create(endpoint);
            if (!Objects.equals(video.getScheme(), base.getScheme()) || !Objects.equals(video.getHost(), base.getHost()) || port(video) != port(base)) continue;
            String prefix = base.getPath().replaceAll("/+$", "") + "/" + properties.getBucketName() + "/";
            String path = video.getPath();
            if (path != null && path.startsWith(prefix) && path.length() > prefix.length()) {
                String key = path.substring(prefix.length());
                if (key.indexOf('\0') >= 0 || Arrays.asList(key.split("/", -1)).contains("..")) break;
                snapshot.setBucket(properties.getBucketName()); snapshot.setObjectKey(key);
                if(video.getRawQuery()!=null)for(String part:video.getRawQuery().split("&")){
                    String[] parameter=part.split("=",2);
                    if("versionId".equals(java.net.URLDecoder.decode(parameter[0],java.nio.charset.StandardCharsets.UTF_8))){
                        if(parameter.length<2||parameter[1].isBlank()||snapshot.getVersionId()!=null)throw new BizException(400,"视频对象版本参数无效");
                        snapshot.setVersionId(java.net.URLDecoder.decode(parameter[1],java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                return;
            }
        }
        throw new BizException(400, "当前视频不属于已配置的媒体存储，请先归档到资源库");
    }

    public void describe(Snapshot snapshot) {
        try {
            var args = StatObjectArgs.builder().bucket(snapshot.getBucket()).object(snapshot.getObjectKey());
            if(snapshot.getVersionId()!=null)args.versionId(snapshot.getVersionId());
            var stat = minio.statObject(args.build());
            snapshot.setSourceSize(stat.size()); snapshot.setEtag(stat.etag()); snapshot.setVersionId(stat.versionId());
            String type = Optional.ofNullable(stat.contentType()).orElse("").split(";", 2)[0].toLowerCase(Locale.ROOT);
            String ext = switch (type) { case "video/mp4" -> "mp4"; case "video/webm" -> "webm"; case "video/quicktime" -> "mov"; case "video/x-matroska" -> "mkv"; default -> null; };
            if (ext == null && (type.equals("application/octet-stream") || type.isBlank())) {
                String key = snapshot.getObjectKey();
                String suffix = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (Set.of("mp4", "webm", "mov", "mkv").contains(suffix)) ext = suffix;
            }
            if (ext == null) throw new BizException(400, "当前对象不是支持的视频格式");
            snapshot.setExtension(ext);
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "当前视频不可读取，请确认媒体文件仍存在"); }
    }

    public java.io.InputStream open(Snapshot snapshot) throws Exception {
        var builder = GetObjectArgs.builder().bucket(snapshot.getBucket()).object(snapshot.getObjectKey());
        if (snapshot.getVersionId() != null && !snapshot.getVersionId().isBlank()) builder.versionId(snapshot.getVersionId());
        if (snapshot.getEtag() != null) builder.extraHeaders(Map.of("If-Match", "\"" + snapshot.getEtag() + "\""));
        return reader().getObject(builder.build());
    }
    private synchronized MinioClient reader() {
        if(reader==null) reader=MinioClient.builder().endpoint(properties.getEndpoint()).credentials(properties.getAccessKey(),properties.getSecretKey())
                .httpClient(new okhttp3.OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(15)).readTimeout(java.time.Duration.ofSeconds(exportProperties.getReadTimeoutSeconds())).callTimeout(java.time.Duration.ofSeconds(exportProperties.getReadTimeoutSeconds())).build()).build();
        return reader;
    }
    private static int port(URI uri) { return uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
}
