package com.astra.freyja.service;

import com.astra.freyja.config.MediaToolProperties;
import com.astra.freyja.dto.video.VideoProbeInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Extracts video frames and metadata through native ffmpeg/ffprobe on Windows or Linux. */
@Slf4j
@Service
public class VideoFrameExtractService {
    private final MediaToolProperties properties;
    private final NativeProcessExecutor processExecutor;

    public VideoFrameExtractService() {
        this(new MediaToolProperties(), new NativeProcessExecutor());
    }

    @Autowired
    public VideoFrameExtractService(MediaToolProperties properties, NativeProcessExecutor processExecutor) {
        this.properties = properties;
        this.processExecutor = processExecutor;
    }

    public byte[] extractLastFrame(byte[] videoBytes, String extension) {
        return extractLastFrame(videoBytes, extension, properties.getTailOffsetMs());
    }

    public byte[] extractLastFrame(byte[] videoBytes, String extension, Integer tailOffsetMs) {
        if (videoBytes == null || videoBytes.length == 0 || StringUtils.isBlank(properties.getFfmpegPath())) return null;
        int offsetMs = tailOffsetMs != null && tailOffsetMs >= 0 && tailOffsetMs <= 2000 ? tailOffsetMs : properties.getTailOffsetMs();
        double offsetSec = offsetMs > 0 ? offsetMs / 1000.0 : 0.050;
        Path videoPath = null;
        Path framePath = null;
        try {
            videoPath = Files.createTempFile("freyja_frame_src_", "." + sanitizeExt(extension));
            framePath = Files.createTempFile("freyja_frame_out_", ".jpg");
            Files.write(videoPath, videoBytes);
            List<String> command = List.of(properties.getFfmpegPath(), "-hide_banner", "-loglevel", "error", "-y",
                    "-sseof", String.format(Locale.US, "-%.3f", offsetSec), "-i", videoPath.toString(),
                    "-frames:v", "1", "-q:v", "2", framePath.toString());
            NativeProcessExecutor.ProcessResult result = processExecutor.execute(command, timeout());
            if (result.timedOut() || result.exitCode() != 0) {
                log.warn("[VideoFrameExtract] ffmpeg failed, exit={}, timeout={}, output={}", result.exitCode(), result.timedOut(), result.output());
                return null;
            }
            if (!Files.exists(framePath) || Files.size(framePath) == 0) return null;
            return Files.readAllBytes(framePath);
        } catch (IOException e) {
            log.warn("[VideoFrameExtract] ffmpeg unavailable or inaccessible: {}", e.getMessage());
            return null;
        } finally {
            deleteQuietly(videoPath);
            deleteQuietly(framePath);
        }
    }

    public VideoProbeInfoVO probeVideoInfo(byte[] videoBytes, String extension) {
        if (videoBytes == null || videoBytes.length == 0 || StringUtils.isBlank(properties.getFfprobePath())) return null;
        Path videoPath = null;
        try {
            videoPath = Files.createTempFile("freyja_probe_src_", "." + sanitizeExt(extension));
            Files.write(videoPath, videoBytes);
            List<String> command = List.of(properties.getFfprobePath(), "-v", "error",
                    "-show_entries", "stream=width,height,r_frame_rate,codec_type,codec_name:format=duration",
                    "-of", "json", videoPath.toString());
            NativeProcessExecutor.ProcessResult result = processExecutor.execute(command, timeout());
            if (result.timedOut() || result.exitCode() != 0) {
                log.warn("[VideoFrameExtract] ffprobe failed, exit={}, timeout={}, output={}", result.exitCode(), result.timedOut(), result.output());
                return null;
            }
            return parseProbe(result.output());
        } catch (Exception e) {
            log.warn("[VideoFrameExtract] ffprobe unavailable or returned invalid JSON: {}", e.getMessage());
            return null;
        } finally {
            deleteQuietly(videoPath);
        }
    }

    private VideoProbeInfoVO parseProbe(String json) throws IOException {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        var vo = new VideoProbeInfoVO();
        var streams = root.path("streams");
        if (streams.isArray()) for (var stream : streams) {
            String type = stream.path("codec_type").asText();
            if ("video".equalsIgnoreCase(type) && vo.getWidth() == null) {
                vo.setWidth(stream.path("width").asInt());
                vo.setHeight(stream.path("height").asInt());
                vo.setVideoCodec(stream.path("codec_name").asText());
                String rate = stream.path("r_frame_rate").asText();
                if (rate.contains("/")) {
                    String[] parts = rate.split("/");
                    double den = Double.parseDouble(parts[1]);
                    if (den > 0) vo.setFps(Math.round(Double.parseDouble(parts[0]) / den * 100.0) / 100.0);
                }
            } else if ("audio".equalsIgnoreCase(type)) {
                vo.setHasAudio(true);
                vo.setAudioCodec(stream.path("codec_name").asText());
            }
        }
        if (root.path("format").hasNonNull("duration")) {
            vo.setDuration(BigDecimal.valueOf(root.path("format").path("duration").asDouble()).setScale(2, RoundingMode.HALF_UP));
        }
        return vo;
    }

    private Duration timeout() { return properties.getTimeout() == null ? Duration.ofSeconds(30) : properties.getTimeout(); }

    private String sanitizeExt(String ext) {
        if (StringUtils.isBlank(ext)) return "mp4";
        String clean = ext.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return StringUtils.isBlank(clean) ? "mp4" : clean;
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
