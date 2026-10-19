package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MediaToolProperties;
import com.astra.freyja.dto.voice.DubbingInputs.Step;
import com.astra.freyja.service.VoiceFilterCatalog.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Compiles a curated typed audio catalog; input/output, graph links and file access stay internal. */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class VoiceEffectEngine {
    public record Audio(byte[] bytes, double duration) {}
    private final MediaToolProperties properties;
    private final NativeProcessExecutor processes;
    private final VideoFrameExtractService probe;
    private volatile Set<String> supported;
    public static final List<Filter> FILTERS=VoiceFilterCatalog.FILTERS;

    private Filter definition(String type) {
        return FILTERS.stream().filter(f -> f.type().equals(type)).findFirst()
            .orElseThrow(() -> new BizException(400,"不支持的声音滤镜: "+type));
    }
    public List<Map<String,Object>> capabilities() {
        Set<String> available=availableFilters();
        return FILTERS.stream().map(f -> {
            List<String> missing=f.filters().stream().filter(name -> !available.contains(name)).toList();
            return Map.<String,Object>of("type",f.type(),"label",f.label(),"category",f.category(),
                "description",f.description(),"ffmpegFilter",String.join(" + ",f.filters()),
                "parameters",f.parameters(),"available",missing.isEmpty(),"missingFilters",missing);
        }).toList();
    }
    private Set<String> availableFilters() {
        if(supported!=null) return supported;
        try {
            var result=processes.execute(List.of(properties.getFfmpegPath(),"-hide_banner","-filters"),Duration.ofSeconds(10));
            Set<String> names=new HashSet<>();
            if(result.exitCode()==0) for(String line:result.output().split("\\R")) {
                String[] parts=line.trim().split("\\s+");
                if(parts.length>2 && parts[2].contains("->")) names.add(parts[1]);
            }
            if(!names.isEmpty()) supported=Set.copyOf(names);
            return names;
        } catch(Exception e) { return Set.of(); }
    }
    public void validate(List<Step> steps) { normalize(steps); }

    /** Persist expanded defaults so each saved template/recording keeps a complete parameter snapshot. */
    public List<Step> normalize(List<Step> steps) {
        if(steps==null || steps.size()>24) throw new BizException(400,"特效处理步骤不能超过24步");
        List<Step> result=new ArrayList<>();
        for(Step step:steps) {
            if(step==null || step.params()==null) throw new BizException(400,"处理步骤与参数不能为空");
            Filter filter=definition(step.type());
            Set<String> keys=filter.parameters().stream().map(Parameter::key).collect(Collectors.toSet());
            if(!keys.containsAll(step.params().keySet())) throw new BizException(400,filter.label()+"包含未开放的参数");
            Map<String,Object> params=new LinkedHashMap<>();
            for(Parameter parameter:filter.parameters()) {
                if(parameter.required() && !step.params().containsKey(parameter.key()))
                    throw new BizException(400,filter.label()+"缺少参数: "+parameter.label());
                Object value=step.params().containsKey(parameter.key())?step.params().get(parameter.key()):parameter.initial();
                validateValue(filter,parameter,value);
                params.put(parameter.key(),value instanceof List<?> list?list.stream().map(v -> ((Number)v).doubleValue()).toList()
                    :value instanceof Number n?n.doubleValue():value);
            }
            if("chorus".equals(step.type())) sameLength(params,"delays","decays","speeds","depths");
            if("reverb".equals(step.type())) sameLength(params,"delays","decays");
            result.add(new Step(step.type(),Collections.unmodifiableMap(params),step.active()));
        }
        return List.copyOf(result);
    }
    private void validateValue(Filter filter,Parameter parameter,Object value) {
        boolean valid=switch(parameter.kind()) {
            case "number" -> validNumber(parameter,value);
            case "boolean" -> value instanceof Boolean;
            case "select" -> value instanceof String text && parameter.options().stream().anyMatch(o -> o.value().equals(text));
            case "numberList" -> value instanceof List<?> list && !list.isEmpty() && list.size()<=parameter.maxItems()
                && list.stream().allMatch(item -> validNumber(parameter,item));
            default -> false;
        };
        if(!valid) throw new BizException(400,filter.label()+"参数无效: "+parameter.label());
    }
    private boolean validNumber(Parameter p,Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()>=p.min() && n.doubleValue()<=p.max();
    }
    private void sameLength(Map<String,Object> params,String... keys) {
        int count=((List<?>)params.get(keys[0])).size();
        for(String key:keys) if(((List<?>)params.get(key)).size()!=count)
            throw new BizException(400,"同一步骤的延迟、衰减及调制列表必须逐项对应，数量相同");
    }
    private static double number(Map<String,Object> params,String key) { return ((Number)params.get(key)).doubleValue(); }
    private static String decimal(double n) { return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString(); }
    private static String value(Object value) {
        if(value instanceof Number n) return decimal(n.doubleValue());
        if(value instanceof Boolean b) return b?"1":"0";
        if(value instanceof List<?> list) return list.stream().map(VoiceEffectEngine::value).collect(Collectors.joining("|"));
        return (String)value; // Only catalog enum values reach this branch.
    }
    private String nativeFilter(Filter filter,Map<String,Object> params) {
        return filter.ffmpegFilter()+"="+filter.parameters().stream().map(p -> p.option()+"="+value(params.get(p.key()))
            +(p.suffix()==null?"":p.suffix())).collect(Collectors.joining(":"));
    }
    public String graph(List<Step> steps) {
        List<Step> active=normalize(steps).stream().filter(Step::active).toList();
        StringBuilder graph=new StringBuilder("[0:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=mono[a0]");
        for(int i=0;i<active.size();i++) {
            Step step=active.get(i);var p=step.params();Filter definition=definition(step.type());
            graph.append(';').append("[a").append(i).append(']');
            String filter;
            if(definition.ffmpegFilter()!=null) filter=nativeFilter(definition,p);
            else filter=switch(step.type()) {
                case "distortion" -> "volume="+decimal(1+number(p,"amount")*10)+",asoftclip=type="+p.get("curve")
                    +":threshold="+value(p.get("threshold"))+":output="+value(p.get("output"));
                case "echo" -> "aecho="+value(p.get("in_gain"))+":"+value(p.get("out_gain"))+":"+value(p.get("delay"))+":"+value(p.get("decay"));
                case "reverb" -> "aecho="+value(p.get("in_gain"))+":"+value(p.get("out_gain"))+":"+value(p.get("delays"))+":"
                    +((List<?>)p.get("decays")).stream().map(v -> decimal(((Number)v).doubleValue()*number(p,"amount"))).collect(Collectors.joining("|"));
                case "pitch" -> pitch(number(p,"semitones"));
                case "dual" -> "asplit=2[dry"+i+"][wet"+i+"];[wet"+i+"]"+pitch(number(p,"semitones"))
                    +",adelay="+value(p.get("delay"))+",volume="+value(p.get("mix"))+"[shift"+i+"];[dry"+i+"]volume="
                    +decimal(1-number(p,"mix"))+"[original"+i+"];[original"+i+"][shift"+i+"]amix=inputs=2:normalize=0";
                default -> throw new BizException(400,"不支持的声音滤镜");
            };
            // Some native filters (e.g. loudnorm) change sample rate internally.
            // Keep every following pitch/mix step on the same 48 kHz processing clock.
            graph.append(filter).append(",aresample=48000[a").append(i+1).append(']');
        }
        graph.append(";[a").append(active.size()).append(active.isEmpty()?"]anull[out]":"]alimiter=limit=0.95[out]");
        return graph.toString();
    }
    private String pitch(double semitones) {
        double ratio=Math.pow(2,semitones/12);
        return "asetrate="+decimal(48000*ratio)+",aresample=48000,atempo="+decimal(1/ratio);
    }

    public Double measureDuration(byte[] bytes) {
        var info=probe.probeVideoInfo(bytes,"audio");
        return info!=null && Boolean.TRUE.equals(info.getHasAudio()) && info.getDuration()!=null && info.getDuration().signum()>0
            ? info.getDuration().doubleValue():null;
    }

    public Audio process(byte[] source,List<Step> steps) {
        validate(steps);
        for (Step step : steps) {
            if (!step.active()) continue;
            Filter filter=definition(step.type());
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
            if (result.exitCode()!=0 || result.timedOut()) {
                log.warn("声音滤镜执行失败: filters={}, output={}",steps.stream().map(Step::type).toList(),result.output());
                throw new BizException(502,"FFmpeg 音频处理失败或超时，请检查处理参数");
            }
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
