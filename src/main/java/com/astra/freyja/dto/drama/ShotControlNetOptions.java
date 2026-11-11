package com.astra.freyja.dto.drama;
import lombok.Data;
import com.astra.freyja.common.BizException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.*;
@Data public class ShotControlNetOptions {
    public static final Set<String> CONTROL_TYPES=Set.of("CANNY","POSE","DEPTH","HED","MLSD","SCRIBBLE","LAYOUT","GRAY","PREPARED");
    public static final Set<String> INPUT_MODES=Set.of("RAW","PREPROCESSED");
    public static final int MAX_CONTROLS=3;
    private boolean enabled;
    /** Null reads a historical single control; an empty list explicitly means no controls. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private List<Input> controls;
    private String referenceId;
    private String controlType="CANNY";
    private double strength=1;

    @Data public static class Input {
        private boolean enabled=true;
        private String referenceId;
        private String controlType="CANNY";
        private String inputMode="RAW";
        private double strength=1;
        @JsonIgnore public boolean isPreprocessed(){return "PREPROCESSED".equals(inputMode)||Set.of("PREPARED","LAYOUT").contains(Objects.toString(controlType,""));}
        public String requiredCapability(){return "PREPROCESSED".equals(inputMode)?"PREPARED":controlType;}
    }
    public List<Input> inputs(){
        if(controls!=null)return controls;
        var input=new Input();input.setReferenceId(referenceId);input.setControlType(controlType);input.setStrength(strength);
        return List.of(input);
    }
    public List<Input> activeInputs(){return enabled?inputs().stream().filter(Objects::nonNull).filter(Input::isEnabled).toList():List.of();}
    public static Set<String> sourceIds(Map<String,Object> h3,Map<String,Object> stage){
        var ids=new LinkedHashSet<String>();
        if(h3!=null&&h3.get("control_net") instanceof Map<?,?> control&&Boolean.TRUE.equals(control.get("enabled"))){
            if(control.get("controls") instanceof List<?> rows){
                for(var row:rows)if(row instanceof Map<?,?> input&&!Boolean.FALSE.equals(input.get("enabled"))&&input.get("reference_id") instanceof String id&&!id.isBlank())ids.add(id);
            }else if(control.get("reference_id") instanceof String id&&!id.isBlank())ids.add(id);
        }
        if(stage!=null&&stage.get("control_source_ids") instanceof List<?> sources)for(var source:sources)if(source instanceof String id&&!id.isBlank())ids.add(id);
        return ids;
    }
    public record VideoBinding(String sourceId,Integer controlIndex){}
    public static List<Map<String,Object>> transportInputs(Map<String,Object> h3,Map<String,Object> stage){
        Object rows=null;
        if(h3!=null&&h3.get("control_net") instanceof Map<?,?> control&&Boolean.TRUE.equals(control.get("enabled"))){
            rows=control.get("controls");
            if(rows==null&&control.get("reference_id") instanceof String id){
                var input=new LinkedHashMap<String,Object>();input.put("reference_id",id);input.put("control_type",Objects.toString(control.get("control_type"),"CANNY"));
                input.put("input_mode","RAW");input.put("strength",control.get("strength")==null?1:control.get("strength"));rows=List.of(input);
            }
        }else if(stage!=null)rows=stage.get("control_inputs");
        var result=new ArrayList<Map<String,Object>>();
        if(rows instanceof List<?> inputs)for(var value:inputs)if(value instanceof Map<?,?> input&&!Boolean.FALSE.equals(input.get("enabled"))){
            var item=new LinkedHashMap<String,Object>();for(var entry:input.entrySet())if(entry.getKey() instanceof String key)item.put(key,entry.getValue());result.add(item);
        }
        return result;
    }
    public static List<VideoBinding> videoBindings(List<String> sources,Map<String,Object> h3,Map<String,Object> stage){
        var inputs=transportInputs(h3,stage);var legacy=inputs.isEmpty()?sourceIds(h3,stage):Set.<String>of();
        var bindings=new ArrayList<VideoBinding>();
        for(var source:sources){
            boolean matched=false;
            for(int index=0;index<inputs.size();index++)if(Objects.equals(source,inputs.get(index).get("reference_id"))){bindings.add(new VideoBinding(source,index));matched=true;}
            if(!matched&&!legacy.contains(source))bindings.add(new VideoBinding(source,null));
        }
        return bindings;
    }
    @JsonIgnore public boolean isActive(){return !activeInputs().isEmpty();}
    public void validate(){
        if(controls!=null&&controls.size()>MAX_CONTROLS)throw new BizException(400,"ControlNet 最多配置 "+MAX_CONTROLS+" 行");
        for(var input:inputs())if(input==null||!CONTROL_TYPES.contains(Objects.toString(input.getControlType(),""))
                ||!INPUT_MODES.contains(Objects.toString(input.getInputMode(),""))||!Double.isFinite(input.getStrength())||input.getStrength()<0||input.getStrength()>2)
            throw new BizException(400,"ControlNet 控制类型、输入方式或强度无效");
    }
    public void validateSources(List<ShotRefVideoDTO> videos){
        validate();var sources=videos==null?List.<ShotRefVideoDTO>of():videos;
        if(enabled&&controls==null&&referenceId==null&&sources.size()==1)referenceId=sources.getFirst().getId();
        for(var input:inputs()){
            if(enabled&&input.isEnabled()&&(input.getReferenceId()==null||input.getReferenceId().isBlank()))throw new BizException(400,"请选择每一行 ControlNet 的参考视频");
            if(input.getReferenceId()!=null&&!input.getReferenceId().isBlank()&&sources.stream().noneMatch(v->Objects.equals(v.getId(),input.getReferenceId())))
                throw new BizException(400,"ControlNet 视频必须来自本镜头或本片段挂载的参考视频");
        }
    }
    public Map<String,Object> transport(){
        var result=new LinkedHashMap<String,Object>();result.put("enabled",isActive());
        if(controls==null){result.put("reference_id",referenceId);result.put("control_type",controlType);result.put("strength",strength);}
        else result.put("controls",activeInputs().stream().map(input->Map.<String,Object>of("enabled",true,"reference_id",input.getReferenceId(),
                "control_type",input.getControlType(),"input_mode",input.getInputMode(),"strength",input.getStrength())).toList());
        return result;
    }
}
