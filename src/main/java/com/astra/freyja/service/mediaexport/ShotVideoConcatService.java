package com.astra.freyja.service.mediaexport;

import com.astra.freyja.config.MediaToolProperties;
import com.astra.freyja.config.VideoExportProperties;
import com.astra.freyja.service.NativeProcessExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Normalizes one clip at a time, keeping FFmpeg input counts and command lengths bounded. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShotVideoConcatService {
    private final MediaToolProperties mediaTools;
    private final VideoExportProperties properties;
    private final NativeProcessExecutor processes;
    private final ObjectMapper json;

    public record Format(int width, int height, boolean hasAudio, BigDecimal duration) {}

    public Format probe(Path source, BooleanSupplier active) throws IOException {
        String result = execute(List.of(mediaTools.getFfprobePath(), "-v", "error", "-show_entries",
                "stream=codec_type,width,height,duration:format=duration", "-of", "json", source.toString()), active);
        var metadata = json.readTree(result);
        var streams = metadata.path("streams");
        int width = 0, height = 0;
        boolean hasAudio = false;
        BigDecimal duration = null;
        for (var stream : streams) {
            if ("video".equals(stream.path("codec_type").asText()) && width == 0) {
                width = stream.path("width").asInt();
                height = stream.path("height").asInt();
                duration = positiveDuration(stream.path("duration").asText());
            }
            if ("audio".equals(stream.path("codec_type").asText())) hasAudio = true;
        }
        if (width <= 0 || height <= 0) throw new IOException("源文件没有可拼接的视频轨道");
        if (duration == null) duration = positiveDuration(metadata.path("format").path("duration").asText());
        if (duration == null) throw new IOException("无法获取源视频的实际时长");
        return new Format((width + 1) / 2 * 2, (height + 1) / 2 * 2, hasAudio, duration);
    }

    public void normalize(Path source, Path output, Format input, Format target, long remainingBytes, BooleanSupplier active) throws IOException {
        List<String> command = new ArrayList<>(List.of(mediaTools.getFfmpegPath(), "-hide_banner", "-loglevel", "error",
                "-nostdin", "-y", "-i", source.toString()));
        if (!input.hasAudio()) command.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"));
        String filter = "scale=" + target.width() + ":" + target.height()
                + ":force_original_aspect_ratio=decrease:force_divisible_by=2,pad=" + target.width() + ":" + target.height()
                + ":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,setpts=PTS-STARTPTS";
        command.addAll(List.of("-map", "0:v:0", "-map", input.hasAudio() ? "0:a:0" : "1:a:0",
                "-vf", filter, "-af", "aresample=48000,asetpts=PTS-STARTPTS,apad",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2", "-shortest",
                "-video_track_timescale", "15360", "-map_metadata", "-1", "-t", input.duration().toPlainString(), output.toString()));
        execute(command, active, output, remainingBytes);
        requireOutput(output);
    }

    public void concat(List<Path> clips, Path output, BooleanSupplier active) throws IOException {
        if (clips.isEmpty()) throw new IOException("没有可拼接的视频");
        Path list = output.resolveSibling("concat.txt");
        // Every clip lives beside this file and has a generated ASCII name, independent of user titles.
        StringBuilder content = new StringBuilder();
        BigDecimal totalDuration = BigDecimal.ZERO;
        for (Path clip : clips) {
            String filename = clip.getFileName().toString();
            if (!filename.matches("clip-[0-9]+\\.mp4") || !clip.getParent().equals(output.getParent()))
                throw new IOException("非法拼接临时文件路径");
            content.append("file '").append(filename).append("'\n");
            // AAC encoder padding can outlast the video. Advance to the next clip at the video boundary.
            var info = json.readTree(execute(List.of(mediaTools.getFfprobePath(), "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=duration", "-of", "json", clip.toString()), active));
            var duration = info.path("streams").path(0).path("duration").asText();
            BigDecimal seconds = positiveDuration(duration);
            if (seconds == null) throw new IOException("无法获取拼接片段的实际时长");
            content.append("duration ").append(seconds.toPlainString()).append('\n');
            totalDuration = totalDuration.add(seconds);
        }
        Files.writeString(list, content, StandardCharsets.UTF_8);
        execute(List.of(mediaTools.getFfmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "concat", "-safe", "1", "-i", list.toString(), "-map", "0:v:0", "-map", "0:a:0",
                "-c", "copy", "-t", totalDuration.toPlainString(), "-movflags", "+faststart", output.toString()), active, output, properties.getMaxArchiveBytes());
        requireOutput(output);
    }

    private String execute(List<String> command, BooleanSupplier active) throws IOException {
        return execute(command, active, null, 0);
    }

    private String execute(List<String> command, BooleanSupplier active, Path output, long maxBytes) throws IOException {
        check(active);
        var result = processes.execute(command, Duration.ofSeconds(properties.getConcatTimeoutSeconds()), () -> {
            check(active);
            if (output != null) {
                if (Files.getFileStore(output.getParent()).getUsableSpace() < properties.getMinFreeBytes())
                    throw new IOException("拼接临时磁盘可用空间不足");
                if (Files.exists(output) && Files.size(output) > maxBytes) throw new IOException("超过拼接视频容量限制");
            }
        });
        check(active);
        if (result.timedOut() || result.exitCode() != 0) {
            log.warn("视频拼接工具执行失败: timeout={}, exit={}, output={}", result.timedOut(), result.exitCode(), result.output());
            throw new IOException(result.timedOut() ? "视频拼接超时" : "视频拼接失败，请检查源视频与 FFmpeg 配置");
        }
        return result.output();
    }

    private void requireOutput(Path output) throws IOException {
        if (!Files.isRegularFile(output) || Files.size(output) == 0) throw new IOException("视频拼接未生成有效文件");
    }

    private BigDecimal positiveDuration(String value) {
        try { BigDecimal seconds = new BigDecimal(value); return seconds.signum() > 0 ? seconds : null; }
        catch (NumberFormatException e) { return null; }
    }

    private void check(BooleanSupplier active) {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) throw new CancellationException("视频拼接已取消");
    }
}
