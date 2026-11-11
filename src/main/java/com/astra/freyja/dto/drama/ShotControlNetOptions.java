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
