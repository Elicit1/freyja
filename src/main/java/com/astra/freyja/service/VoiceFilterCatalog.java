package com.astra.freyja.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.util.List;

/** Curated audio processing boundary shared by validation, compilation and the workbench. */
public final class VoiceFilterCatalog {
    private VoiceFilterCatalog() {}
    public record Option(String value,String label) {}
    public record Parameter(String key,String label,String kind,String unit,String description,
                            Double min,Double max,Double step,Object initial,List<Option> options,
                            Integer maxItems,boolean advanced,boolean required,String option,String suffix) {}
    public record Filter(String type,String label,String category,String description,String ffmpegFilter,
                         List<Parameter> parameters,List<String> filters) {}
    public static final List<Filter> FILTERS=load();
    private static List<Filter> load() {
        try(var input=new ClassPathResource("voice/filter-catalog.json").getInputStream()) {
            return List.copyOf(new ObjectMapper().readValue(input,new TypeReference<List<Filter>>(){}));
        } catch(Exception e) { throw new IllegalStateException("无法读取声音滤镜参数目录",e); }
    }
}
