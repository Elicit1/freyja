package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MediaToolProperties;
import com.astra.freyja.dto.voice.DubbingInputs.Step;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Compiles typed, bounded parameters to a local-only FFmpeg graph. No free-form commands. */
@Service
@RequiredArgsConstructor
public class VoiceEffectEngine {
    public record Parameter(String key, String label, double min, double max, double initial, double step) {}
    public record Filter(String type, String label, List<Parameter> parameters, List<String> filters) {}
    public record Audio(byte[] bytes, double duration) {}
    private final MediaToolProperties properties;
    private final NativeProcessExecutor processes;
    private final VideoFrameExtractService probe;
    private volatile Set<String> supported;
    public static final List<Filter> FILTERS = List.of(
        new Filter("highpass", "高通滤波", List.of(p("frequency","截止频率 Hz",20,20000,300,10)), List.of("highpass")),
        new Filter("lowpass", "低通滤波", List.of(p("frequency","截止频率 Hz",20,20000,3400,10)), List.of("lowpass")),
        new Filter("compressor", "动态压缩", List.of(p("threshold","阈值",0.01,1,0.2,0.01),p("ratio","压缩比",1,20,4,0.5)), List.of("acompressor")),
        new Filter("distortion", "失真", List.of(p("amount","失真量",0,1,0.1,0.01)), List.of("volume","asoftclip")),
        new Filter("echo", "回声", List.of(p("delay","延迟 ms",1,1000,200,1),p("decay","衰减",0.01,0.8,0.35,0.01)), List.of("aecho")),
        new Filter("reverb", "空间混响", List.of(p("amount","混响量",0.01,0.7,0.3,0.01)), List.of("aecho")),
        new Filter("pitch", "变调", List.of(p("semitones","半音",-12,12,3,0.5)), List.of("asetrate","aresample","atempo")),
        new Filter("dual", "男女双声", List.of(p("semitones","叠加声半音",-12,12,5,0.5),p("mix","叠加比例",0,1,0.5,0.05),p("delay","错开 ms",0,200,20,1)), List.of("asplit","asetrate","aresample","atempo","adelay","amix","volume"))
    );
    private static Parameter p(String key,String label,double min,double max,double initial,double step) { return new Parameter(key,label,min,max,initial,step); }

    public List<Map<String,Object>> capabilities() {
        Set<String> available = availableFilters();
        return FILTERS.stream().map(f -> Map.<String,Object>of("type",f.type(),"label",f.label(),"parameters",f.parameters(),"available",available.containsAll(f.filters()))).toList();
    }

    private Set<String> availableFilters() {
        if (supported != null) return supported;
        try {
            var result = processes.execute(List.of(properties.getFfmpegPath(),"-hide_banner","-filters"),Duration.ofSeconds(10));
            Set<String> names = new HashSet<>();
            if (result.exitCode() == 0) for (String line : result.output().split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 2) names.add(parts[1]);
            }
            if (!names.isEmpty()) supported = Set.copyOf(names);
            return names;
        } catch (Exception e) { return Set.of(); }
    }

    public void validate(List<Step> steps) {
        if (steps == null || steps.size() > 24) throw new BizException(400,"特效处理步骤不能超过24步");
        for (Step step : steps) {
            if (step == null) throw new BizException(400,"处理步骤不能为空");
            Filter filter = FILTERS.stream().filter(f -> f.type().equals(step.type())).findFirst().orElseThrow(() -> new BizException(400,"不支持的特效滤镜"));
            Set<String> keys = new HashSet<>();
            for (Parameter parameter : filter.parameters()) {
                keys.add(parameter.key());
                Double value = step.params() == null ? null : step.params().get(parameter.key());
                if (value == null || !Double.isFinite(value) || value < parameter.min() || value > parameter.max())
                    throw new BizException(400,filter.label()+"参数无效: "+parameter.label());
            }
            if (!keys.equals(step.params().keySet())) throw new BizException(400,"包含未声明的特效参数");
        }
    }

    public String graph(List<Step> steps) {
        validate(steps);
        StringBuilder graph = new StringBuilder("[0:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=mono[a0]");
        for (int i=0;i<steps.size();i++) {
            Step step=steps.get(i); var p=step.params(); String from="[a"+i+"]", to="[a"+(i+1)+"]";
            graph.append(';').append(from);
            String filter = switch(step.type()) {
                case "highpass" -> "highpass=f="+p.get("frequency");
                case "lowpass" -> "lowpass=f="+p.get("frequency");
                case "compressor" -> "acompressor=threshold="+p.get("threshold")+":ratio="+p.get("ratio");
                case "distortion" -> "volume="+(1+p.get("amount")*10)+",asoftclip=type=tanh";
                case "echo" -> "aecho=0.8:0.9:"+p.get("delay")+":"+p.get("decay");
                case "reverb" -> "aecho=0.8:0.9:40|75|110:"+p.get("amount")+"|"+(p.get("amount")*0.7)+"|"+(p.get("amount")*0.4);
                case "pitch" -> pitch(p.get("semitones"));
                case "dual" -> "asplit=2[dry"+i+"][wet"+i+"];[wet"+i+"]"+pitch(p.get("semitones"))+",adelay="+p.get("delay").intValue()+",volume="+p.get("mix")+"[shift"+i+"];[dry"+i+"]volume="+(1-p.get("mix"))+"[original"+i+"];[original"+i+"][shift"+i+"]amix=inputs=2:normalize=0";
                default -> throw new BizException(400,"不支持的滤镜");
            };
            graph.append(filter).append(to);
        }
        graph.append(";[a").append(steps.size()).append(steps.isEmpty() ? "]anull[out]" : "]alimiter=limit=0.95[out]");
        return graph.toString();
    }
    private String pitch(double semitones) {
        double ratio=Math.pow(2,semitones/12);
        return "asetrate="+(48000*ratio)+",aresample=48000,atempo="+(1/ratio);
    }

    public Double measureDuration(byte[] bytes) {
        var info=probe.probeVideoInfo(bytes,"audio");
        return info!=null && Boolean.TRUE.equals(info.getHasAudio()) && info.getDuration()!=null && info.getDuration().signum()>0
            ? info.getDuration().doubleValue():null;
    }

    public Audio process(byte[] source,List<Step> steps) {
        validate(steps);
        for (Step step : steps) {
            Filter filter=FILTERS.stream().filter(f -> f.type().equals(step.type())).findFirst().orElseThrow();
            if (!availableFilters().containsAll(filter.filters())) throw new BizException(503,"服务器 FFmpeg 不支持: "+filter.label());
        }
        if (source == null || source.length == 0 || source.length > DubbingAudioStorage.MAX_BYTES) throw new BizException(400,"音频为空或超过30 MiB");
        Path input=null,output=null;
        try {
            input=Files.createTempFile("freyja_voice_src_", ".audio"); output=Files.createTempFile("freyja_voice_out_", ".wav");
            Files.write(input,source);
            var result=processes.execute(List.of(properties.getFfmpegPath(),"-hide_banner","-loglevel","error","-nostdin","-y",
                    "-protocol_whitelist","file,pipe","-format_whitelist","wav,mp3,flac,ogg,mov,aac","-i",input.toString(),"-filter_complex",graph(steps),"-map","[out]",
                    "-t","121","-c:a","pcm_s16le","-ar","48000","-ac","1",output.toString()),properties.getTimeout());
            if (result.exitCode()!=0 || result.timedOut()) throw new BizException(502,"FFmpeg 音频处理失败或超时");
            if (Files.size(output)>DubbingAudioStorage.MAX_BYTES) throw new BizException(400,"处理后的音频过大");
            byte[] bytes=Files.readAllBytes(output);
            var info=probe.probeVideoInfo(bytes,"wav");
            if (info==null || !Boolean.TRUE.equals(info.getHasAudio()) || info.getDuration()==null || info.getDuration().doubleValue()<=0 || info.getDuration().doubleValue()>120)
                throw new BizException(400,"音频必须可解码且时长不超过120秒");
            return new Audio(bytes,info.getDuration().doubleValue());
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(502,"FFmpeg 不可用或音频无法解码"); }
        finally { for (Path path : new Path[]{input,output}) if (path!=null) try { Files.deleteIfExists(path); } catch(Exception ignored) {} }
    }
}
