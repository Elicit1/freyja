package com.astra.freyja.mcp;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.director.model.DirectorPlanSchema;
import com.astra.freyja.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;
import java.util.*;

/** Strict public wire contracts, including string Snowflake IDs and current dictionary selections. */
@Component @RequiredArgsConstructor
public class McpContinuationSchemas {
    private final McpJson json;
    /** Return drafts that can be copied into the strict input schema, with string IDs and absent empty selections. */
    public Object wire(Object value){return json.mapper().copy().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL).convertValue(value,Object.class);}
    @SuppressWarnings("unchecked")
    public Map<String,Object> tool(Class<?> input) {
        Map<String,Object> root=json.read(parse(JsonSchemaGenerator.generateForType(input)),Map.class);
        Map<String,Object> props=(Map<String,Object>)root.get("properties");
        if(props.containsKey("draft"))props.put("draft",domain(ShotContinuation.class));
        if(props.containsKey("referenceVideo"))props.put("referenceVideo",domain(ShotRefVideoDTO.class));
        if(props.containsKey("dynamicWorkflow"))props.put("dynamicWorkflow",domain(DynamicWorkflowOptions.Selection.class));
        if(props.containsKey("selection"))props.put("selection",domain(DynamicWorkflowOptions.Selection.class));
        normalize(root);return root;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> parse(String text) {try{return json.mapper().readValue(text,Map.class);}catch(Exception e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked") private Map<String,Object> domain(Class<?> type) {
        var root=parse(JsonSchemaGenerator.generateForType(type));var result=(Map<String,Object>)inline(root,root);optionalDomain(result);normalize(result);return result;
    }
    @SuppressWarnings("unchecked") private void optionalDomain(Object value){
        if(value instanceof Map<?,?> raw){var map=(Map<String,Object>)raw;if(map.containsKey("properties"))map.put("required",List.of());new ArrayList<>(map.values()).forEach(this::optionalDomain);}
        else if(value instanceof List<?> list)list.forEach(this::optionalDomain);
    }
    private Object inline(Object value,Map<String,Object> root) {
        if(value instanceof Map<?,?> map){
            if(map.get("$ref") instanceof String ref&&ref.startsWith("#/")) {Object selected=root;for(String part:ref.substring(2).split("/"))selected=((Map<?,?>)selected).get(part);return inline(selected,root);}
            var out=new LinkedHashMap<String,Object>();map.forEach((k,v)->{if(!Set.of("$defs","definitions","$schema").contains(k.toString()))out.put(k.toString(),inline(v,root));});return out;
        }
        if(value instanceof List<?> list)return list.stream().map(v->inline(v,root)).toList();return value;
    }
    @SuppressWarnings("unchecked") private void normalize(Object value) {
        if(value instanceof Map<?,?> source){
            var map=(Map<String,Object>)source;
            if("integer".equals(map.get("type"))&&"int64".equals(map.get("format"))){map.put("type","string");map.remove("format");map.put("pattern","^[1-9][0-9]{0,18}$");}
            if(map.get("properties") instanceof Map<?,?> entries){
                var props=(Map<String,Object>)entries;map.put("additionalProperties",false);
                if(props.containsKey("segmentFrames"))map.put("required",List.of("schemaVersion","enabled","durationSeconds","segmentFrames","overlapFrames","segments"));
                if(props.containsKey("startFrame")&&props.containsKey("endFrame"))map.put("required",List.of("id","startFrame","endFrame"));
                if(props.containsKey("imageUrl"))map.put("required",List.of("id","sourceType","imageUrl","usageRole"));
                if(props.containsKey("sourceVideoUrl"))map.put("required",List.of("id","sourceType"));
                if(props.containsKey("audioUrl"))map.put("required",List.of("audioUrl","referenceType"));
                if(props.containsKey("actionPrompt")&&props.containsKey("characterId"))map.put("required",List.of("characterId"));
                if(props.containsKey("propId"))map.put("required",List.of("propId"));
                for(var entry:props.entrySet()){

                    if(!(entry.getValue() instanceof Map<?,?> raw))continue;var field=(Map<String,Object>)raw;String key=entry.getKey();
                    if("array".equals(field.get("type"))){Integer max=switch(key){case "segments"->100;case "referenceImages"->9;case "referenceVideos","referenceAudios"->3;case "characterRefs","propRefs"->30;case "targetSegmentIds"->100;default->null;};if(max!=null)field.put("maxItems",max);}

                    if(Set.of("dramaId","shotId","providerId").contains(key)&&"string".equals(field.get("type")))field.put("pattern","^[1-9][0-9]{0,18}$");
                    if(key.equals("controlType"))field.put("enum",ShotControlNetOptions.CONTROL_TYPES.stream().sorted().toList());
                    if(key.equals("inputMode"))field.put("enum",ShotControlNetOptions.INPUT_MODES.stream().sorted().toList());
                    if(key.equals("controls")||key.equals("controlInputs"))field.put("maxItems",ShotControlNetOptions.MAX_CONTROLS);
                    if(key.equals("shotType"))field.put("x-freyja-dict","shot_type");if(key.equals("cameraMovement"))field.put("x-freyja-dict","camera_movement");
                    if(key.equals("shotSize"))field.put("enum",new ArrayList<>(DirectorPlanSchema.SHOT_SIZES));if(key.equals("cameraAngle"))field.put("enum",new ArrayList<>(DirectorPlanSchema.CAMERA_ANGLES));
                    if(key.equals("movement"))field.put("enum",new ArrayList<>(DirectorPlanSchema.MOVEMENTS));if(key.equals("speed"))field.put("enum",new ArrayList<>(DirectorPlanSchema.SPEEDS));
                    if(key.equals("sourceType"))field.put("enum",props.containsKey("imageUrl")?ShotImageReferenceValidator.SOURCE_TYPES.stream().sorted().toList():List.of("DIRECT_URL","SHOT_CURRENT","SHOT_VIDEO_TAKE"));
                    if(key.equals("usageRole"))field.put("enum",new ArrayList<>(ShotImageReferenceValidator.USAGE_ROLES));
                    if(key.equals("referenceRole"))field.put("enum",ShotImageReferenceValidator.REFERENCE_ROLES.stream().sorted().toList());
                    if(key.equals("bindingMode"))field.put("enum",List.of("SNAPSHOT","ASSET_LINK"));
                    if(key.equals("referenceType"))field.put("enum",List.of("fully_copy","partially_copy","reference","weak_reference"));
                    if(key.equals("language"))field.put("enum",List.of("zh","en","ja","other"));
                    if(key.equals("sourceSlot"))field.put("enum",List.of("SCENE_REFERENCE","SCENE_COVER"));
                }
            }
            new ArrayList<>(map.values()).forEach(this::normalize);
        }else if(value instanceof List<?> list)list.forEach(this::normalize);
    }
}
