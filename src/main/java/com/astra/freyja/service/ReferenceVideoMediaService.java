package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.*;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.video.ResolvedVideoProcessSource;
import com.astra.freyja.dto.video.VideoProbeInfoVO;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReferenceVideoMediaService {
    private final ReferenceVideoProperties properties;
    private final MediaToolProperties tools;
    private final VideoFrameExtractService probe;
    private final NativeProcessExecutor processes;
    private final MinioClient minio;
    private final MinioProperties storage;
    private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(2);

    public ResolvedShotVideoReference prepare(ShotRefVideoDTO input, ResolvedVideoProcessSource source, ReferenceVideoCapability capability) {
        if (!permits.tryAcquire()) throw new BizException(429, "参考视频准备繁忙，请稍后重试");
        Path folder = null;
        try {
            folder = Files.createTempDirectory("freyja_video_reference_");
            Path original = folder.resolve("source.media"), clip = folder.resolve("clip.mp4");
            download(source.getVideoUrl(), original);
            VideoProbeInfoVO measured = probe.probeVideoFile(original, false);
            if (!validVideo(measured) || measured.getDuration().compareTo(BigDecimal.valueOf(properties.getMaxSourceSeconds())) > 0)
                throw new BizException(400, "REFERENCE_VIDEO_LIMIT_EXCEEDED: 源视频无有效视频轨或时长超限");
            BigDecimal start = input.getStartSeconds() == null ? BigDecimal.ZERO : input.getStartSeconds();
            BigDecimal end = input.getEndSeconds() == null ? measured.getDuration() : input.getEndSeconds();
            BigDecimal duration = end.subtract(start);
            var policy = preparationPolicy(capability);
            if (start.signum() < 0 || end.compareTo(measured.getDuration()) > 0 || duration.signum() <= 0
                    || duration.compareTo(policy.getMinClipSeconds()) < 0 || duration.compareTo(policy.getMaxClipSeconds()) > 0
                    || duration.compareTo(policy.getMaxTotalClipSeconds()) > 0 || duration.doubleValue() * policy.getDecodeFps() > policy.getMaxDecodedFrames())
                throw new BizException(400, "REFERENCE_VIDEO_LIMIT_EXCEEDED: 片段范围或解码帧数超限，请缩短参考片段");
            if (Boolean.TRUE.equals(input.getIncludeAudio()) && !Boolean.TRUE.equals(measured.getHasAudio()))
                throw new BizException(400, "REFERENCE_VIDEO_DECODE_FAILED: 视频没有可供参考的音轨");
            var command = new ArrayList<>(List.of(tools.getFfmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-i", original.toString(), "-ss", start.toPlainString(), "-t", duration.toPlainString(), "-map", "0:v:0"));
            if (Boolean.TRUE.equals(input.getIncludeAudio())) command.addAll(List.of("-map", "0:a:0", "-c:a", "aac"));
            else command.add("-an");
            command.addAll(List.of("-vf", "scale=" + policy.getMaxDecodeWidth() + ":" + policy.getMaxDecodeHeight()
                    + ":force_original_aspect_ratio=decrease:force_divisible_by=2,fps=" + policy.getDecodeFps(),
                    "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", clip.toString()));
            var execution = processes.execute(command, Duration.ofSeconds(properties.getTimeoutSeconds()), () -> {
                if (Files.exists(clip) && Files.size(clip) > clipLimit(policy)) throw new IOException("参考视频片段文件超限");
            });
            if (execution.timedOut() || execution.exitCode() != 0 || !Files.exists(clip)) throw new BizException(400, "REFERENCE_VIDEO_DECODE_FAILED: 视频片段处理失败，请检查 FFmpeg 与输入格式");
            VideoProbeInfoVO output = probe.probeVideoFile(clip, true);
            validatePrepared(output, policy, input.getIncludeAudio());
            if (output.getDuration().subtract(duration).abs().doubleValue() > 2.0 / policy.getDecodeFps()) throw new BizException(400, "REFERENCE_VIDEO_DECODE_FAILED: 片段实际时长不符合选择范围");
            String prefix = "reference-video/" + UUID.randomUUID();
            var result = new ResolvedShotVideoReference();
            result.setId(input.getId()); result.setName(input.getName()); result.setOrdinal(1); result.setSourceType(input.getSourceType());
            result.setSourceShotId(source.getShotId()); result.setSourceVideoTakeId(source.getVideoTakeId());
            result.setSourceUrl(withoutQuery(source.getVideoUrl())); result.setSourceMetadata(measured);
            result.setSourceSha256(digest(original)); result.setSourceObjectPath(prefix + "/source.media");
            result.setStartSeconds(start); result.setEndSeconds(end); result.setIncludeAudio(Boolean.TRUE.equals(input.getIncludeAudio()));
            result.setReferenceDescription(input.getReferenceDescription()); result.setMetadata(output); result.setFrameCount(output.getFrameCount().intValue());
            result.setObjectPath(prefix + "/clip.mp4"); result.setContentSha256(digest(clip));
            archive(original, result.getSourceObjectPath(), "application/octet-stream");
            archive(clip, result.getObjectPath(), "video/mp4");
            return result;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "REFERENCE_VIDEO_DECODE_FAILED: 参考视频无法读取、归档或处理：" + e.getClass().getSimpleName()); }
        finally {
            if (folder != null) try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
            catch (IOException ignored) { }
            if (folder != null) try { Files.deleteIfExists(folder); } catch (IOException ignored) { }
            permits.release();
        }
    }

    public VideoProbeInfoVO probeSource(String url) {
        if (!permits.tryAcquire()) throw new BizException(429, "参考视频探测繁忙，请稍后重试");
        Path path = null;
        try {
            path = Files.createTempFile("freyja_reference_probe_", ".media");
            download(url, path);
            var result = probe.probeVideoFile(path, false);
            if (!validVideo(result) || result.getDuration().doubleValue() > properties.getMaxSourceSeconds()) throw new BizException(400, "源视频无有效视频轨或时长超限");
            result.setSourceUrl(url);
            return result;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "参考视频探测失败，请检查源视频与 FFprobe"); }
        finally { if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) {} permits.release(); }
    }

    public ReferenceVideoCapability preparationPolicy(ReferenceVideoCapability configured) {
        var cap = new ReferenceVideoCapability(); cap.setDecodeFps(properties.getDecodeFps());
        cap.setMinClipSeconds(new BigDecimal("0.1")); cap.setMaxClipSeconds(BigDecimal.valueOf(properties.getMaxClipSeconds()));
        cap.setMaxTotalClipSeconds(cap.getMaxClipSeconds()); cap.setMaxDecodedFrames(properties.getMaxDecodedFrames());
        cap.setMaxDecodeWidth(properties.getMaxDecodeWidth()); cap.setMaxDecodeHeight(properties.getMaxDecodeHeight()); cap.setMaxDownloadBytes(properties.getMaxClipBytes());
        if (configured != null) {
            if (configured.getDecodeFps() != null && configured.getDecodeFps() > 0) cap.setDecodeFps(configured.getDecodeFps());
            if (configured.getMinClipSeconds() != null) cap.setMinClipSeconds(cap.getMinClipSeconds().max(configured.getMinClipSeconds()));
            if (configured.getMaxClipSeconds() != null) cap.setMaxClipSeconds(cap.getMaxClipSeconds().min(configured.getMaxClipSeconds()));
            if (configured.getMaxTotalClipSeconds() != null) cap.setMaxTotalClipSeconds(cap.getMaxTotalClipSeconds().min(configured.getMaxTotalClipSeconds()));
            if (configured.getMaxDecodedFrames() != null) cap.setMaxDecodedFrames(Math.min(cap.getMaxDecodedFrames(), configured.getMaxDecodedFrames()));
            if (configured.getMaxDecodeWidth() != null) cap.setMaxDecodeWidth(Math.min(cap.getMaxDecodeWidth(), configured.getMaxDecodeWidth()));
            if (configured.getMaxDecodeHeight() != null) cap.setMaxDecodeHeight(Math.min(cap.getMaxDecodeHeight(), configured.getMaxDecodeHeight()));
            if (configured.getMaxDownloadBytes() != null) cap.setMaxDownloadBytes(Math.min(cap.getMaxDownloadBytes(), configured.getMaxDownloadBytes()));
        }
        return cap;
    }

    public void validatePrepared(VideoProbeInfoVO video, ReferenceVideoCapability cap, Boolean includeAudio) {
        if (!validVideo(video) || video.getFrameCount() == null || video.getFrameCount() < 1 || video.getFrameCount() > cap.getMaxDecodedFrames()
                || video.getWidth() > cap.getMaxDecodeWidth() || video.getHeight() > cap.getMaxDecodeHeight()
                || video.getFps() == null || Math.abs(video.getFps() - cap.getDecodeFps()) > 0.01
                || video.getDuration().doubleValue() < cap.getMinClipSeconds().doubleValue() - 1.0 / cap.getDecodeFps()
                || video.getDuration().doubleValue() > Math.min(cap.getMaxClipSeconds().doubleValue(), cap.getMaxTotalClipSeconds().doubleValue()) + 1.0 / cap.getDecodeFps()
                || Boolean.TRUE.equals(includeAudio) && !Boolean.TRUE.equals(video.getHasAudio()))
            throw new BizException(400, "REFERENCE_VIDEO_LIMIT_EXCEEDED: 片段实测参数不满足当前模型，请重新预检");
    }

    public String transportUrl(String objectPath) {
        if (objectPath == null || !objectPath.startsWith("reference-video/") || objectPath.contains("..")) throw new BizException(400, "参考视频归档对象无效");
        try { return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(io.minio.http.Method.GET)
                .bucket(storage.getBucketName()).object(objectPath).expiry(900).build()); }
        catch (Exception e) { throw new BizException(400, "参考视频读取授权失败"); }
    }

    public String previewUrl(String objectPath) {
        String base = storage.getExternalEndpoint();
        if (base == null || base.isBlank()) return transportUrl(objectPath);
        return base.replaceAll("/+$", "") + "/" + storage.getBucketName() + "/" + objectPath;
    }

    private long clipLimit(ReferenceVideoCapability cap) { return Math.min(properties.getMaxClipBytes(), cap.getMaxDownloadBytes() == null ? Long.MAX_VALUE : cap.getMaxDownloadBytes()); }
    private static boolean validVideo(VideoProbeInfoVO info) { return info != null && info.getWidth() != null && info.getWidth() > 0 && info.getHeight() != null && info.getHeight() > 0 && info.getDuration() != null && info.getDuration().signum() > 0; }
    public static String withoutQuery(String url) { int index = url.indexOf('?'); return index < 0 ? url : url.substring(0, index); }

    private void archive(Path file, String object, String contentType) throws Exception {
        try (InputStream input = Files.newInputStream(file)) {
            minio.putObject(PutObjectArgs.builder().bucket(storage.getBucketName()).object(object).stream(input, Files.size(file), -1).contentType(contentType).build());
        }
    }
    private static String digest(Path file) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count); }
        return HexFormat.of().formatHex(hash.digest());
    }
    private void download(String source, Path target) throws Exception {
        String key = storageKey(source);
        if (key != null) {
            String version = storageVersion(source);
            var description = StatObjectArgs.builder().bucket(storage.getBucketName()).object(key);
            if (version != null) description.versionId(version);
            var stat = minio.statObject(description.build());
            if (stat.size() > properties.getMaxSourceBytes()) throw new IOException("源视频文件超限");
            var args = GetObjectArgs.builder().bucket(storage.getBucketName()).object(key);
            if (version != null || stat.versionId() != null) args.versionId(version == null ? stat.versionId() : version);
            if (stat.etag() != null) args.extraHeaders(Map.of("If-Match", "\"" + stat.etag() + "\""));
            try (var input = minio.getObject(args.build())) { copyBounded(input, target); }
            if (Files.size(target) != stat.size()) throw new IOException("源视频传输不完整，请重新预检");
            return;
        }
        var http = new okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(30)).callTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build();
        String url = source;
        for (int hop = 0; hop < 4; hop++) {
            validateUrl(url);
            try (var response = http.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
                if (Set.of(301, 302, 303, 307, 308).contains(response.code())) {
                    String location = response.header("Location");
                    if (location == null) throw new IOException("Missing redirect location");
                    url = URI.create(url).resolve(location).toString(); continue;
                }
                if (!response.isSuccessful() || response.body() == null || response.body().contentLength() > properties.getMaxSourceBytes()) throw new IOException("源视频下载失败或文件超限");
                try (var input = response.body().byteStream()) { copyBounded(input, target); }
                return;
            }
        }
        throw new IOException("源视频重定向次数超限");
    }
    private void copyBounded(InputStream input, Path target) throws IOException {
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65536]; long total = 0; int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > properties.getMaxSourceBytes()) throw new IOException("源视频文件超限");
                output.write(buffer, 0, count);
            }
        }
    }
    private String storageKey(String source) throws Exception {
        URI uri = URI.create(source);
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null) throw new IOException("视频地址无效");
        for (String endpoint : Arrays.asList(storage.getEndpoint(), storage.getExternalEndpoint())) {
            if (endpoint == null || endpoint.isBlank()) continue;
            URI base = URI.create(endpoint);
            String prefix = base.getPath().replaceAll("/+$", "") + "/" + storage.getBucketName() + "/";
            if (Objects.equals(base.getScheme(), uri.getScheme()) && Objects.equals(base.getHost(), uri.getHost()) && port(base) == port(uri)
                    && uri.getPath() != null && uri.getPath().startsWith(prefix)) {
                String key = uri.getPath().substring(prefix.length());
                if (key.isBlank() || key.indexOf('\0') >= 0 || Arrays.stream(key.split("/")).anyMatch(part -> part.equals("..") || part.equals("."))) throw new IOException("无效的视频对象路径");
                return key;
            }
        }
        return null;
    }
    private String storageVersion(String source) throws IOException {
        String query = URI.create(source).getRawQuery(), version = null;
        if (query != null) for (String part : query.split("&")) {
            String[] value = part.split("=", 2);
            if ("versionId".equals(URLDecoder.decode(value[0], java.nio.charset.StandardCharsets.UTF_8))) {
                if (version != null || value.length != 2 || value[1].isBlank()) throw new IOException("视频存储版本参数无效");
                version = URLDecoder.decode(value[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return version;
    }
    private void validateUrl(String url) throws Exception {
        URI uri = URI.create(url);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IOException("视频地址格式无效");
        for (String endpoint : Arrays.asList(storage.getEndpoint(), storage.getExternalEndpoint())) {
            if (endpoint == null || endpoint.isBlank()) continue;
            URI allowed = URI.create(endpoint);
            if (Objects.equals(allowed.getScheme(), uri.getScheme()) && Objects.equals(allowed.getHost(), uri.getHost()) && port(allowed) == port(uri)
                    && uri.getPath().startsWith(allowed.getPath().replaceAll("/+$", "") + "/" + storage.getBucketName() + "/")) return;
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc) throw new IOException("不允许的内部视频地址");
    }
    private static int port(URI uri) { return uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
}
