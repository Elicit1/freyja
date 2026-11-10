package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.RenderTaskMapper;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.video.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class McpContinuationToolsTest {
    @Test void videoEnhancementSchemaAcceptsIndependentSwitchesAndRejectsPresetsAndInternalFields(){
        var schemas=new McpContinuationSchemas(new McpJson());var validator=new DefaultJsonSchemaValidator();
        var schema=schemas.tool(McpContinuationTools.OrdinaryRender.class);
        var selection=new LinkedHashMap<String,Object>();
        selection.put("options",Map.of("upscale",true,"faceRepair",true));
        var request=new LinkedHashMap<String,Object>(Map.of("dramaId","1","shotId","2","requestId","request-001","expectedFingerprint","hash","providerId","3","modelCode","h3","size","960x544","dynamicWorkflow",selection));
        assertTrue(validator.validate(schema,request).valid(),new McpJson().write(schema));
        selection.put("presetId","2032095628944588803");assertFalse(validator.validate(schema,request).valid());
        selection.remove("presetId");selection.put("source_url","http://forged");assertFalse(validator.validate(schema,request).valid());
    }
    final McpJson json=new McpJson();
    final McpContinuationSchemas schemas=new McpContinuationSchemas(json);
    final DefaultJsonSchemaValidator validator=new DefaultJsonSchemaValidator();
    Map<String,Object> draft(){return new LinkedHashMap<>(Map.of("schemaVersion",1,"enabled",true,"durationSeconds",30,"segmentFrames",124,"overlapFrames",22,"segments",new ArrayList<>()));}
    Map<String,Object> plan(){return new LinkedHashMap<>(Map.of("dramaId","2032095628944588801","shotId","2032095628944588802","draft",draft(),"auto",true));}
    @SuppressWarnings("unchecked") Map<String,Object> field(Map<String,Object> schema,String name){return (Map<String,Object>)((Map<?,?>)schema.get("properties")).get(name);}
    @Test void nestedSnowflakeIdsRemainStringsAndUnknownFieldsAreRejected(){
        var schema=schemas.tool(McpContinuationTools.Plan.class);var input=plan();assertTrue(validator.validate(schema,input).valid(),json.write(schema));
        var d=(Map<String,Object>)input.get("draft");var segment=new LinkedHashMap<String,Object>(Map.of("id","segment-1","startFrame",0,"endFrame",124,"resSceneId","2032095628944588803"));d.put("segments",List.of(segment));
        assertTrue(validator.validate(schema,input).valid(),json.write(schema));
        segment.put("resSceneId",2032095628944588803L);assertFalse(validator.validate(schema,input).valid());
        segment.put("resSceneId","2032095628944588803");segment.put("internalTaskId","forged");assertFalse(validator.validate(schema,input).valid());
        segment.remove("internalTaskId");segment.put("controlType","UNKNOWN");assertFalse(validator.validate(schema,input).valid());
    }
    @Test void returnedDraftCanBeCopiedBackWithoutNumericIdsOrNullDropdownSelections(){
        var d=new com.astra.freyja.dto.drama.ShotContinuation();d.setEnabled(true);d.setDurationSeconds(5);new ShotContinuationPlanner().plan(d,true);d.getSegments().getFirst().setResSceneId(2032095628944588803L);
        var input=plan();input.put("draft",schemas.wire(d));assertTrue(validator.validate(schemas.tool(McpContinuationTools.Plan.class),input).valid(),json.write(input));
        assertTrue(json.write(input).contains("\"2032095628944588803\""));assertFalse(json.write(input).contains(":null"));
    }
    @Test void mediaSchemaAllowsOptionalDescriptionsButRequiresExplicitDropdownValues(){
        var schema=schemas.tool(McpContinuationTools.Plan.class);var input=plan();var d=(Map<String,Object>)input.get("draft");var image=new LinkedHashMap<String,Object>(Map.of("id","i","sourceType","UPLOAD","imageUrl","https://archive.test/i.png","usageRole","STYLE"));
        var segment=new LinkedHashMap<String,Object>(Map.of("id","s","startFrame",0,"endFrame",124,"referenceImages",List.of(image),"referenceVideos",List.of(Map.of("id","v","sourceType","DIRECT_URL","sourceVideoUrl","https://archive.test/v.mp4"))));d.put("segments",List.of(segment));
        assertTrue(validator.validate(schema,input).valid(),json.write(schema));image.put("usageRole","free text");assertFalse(validator.validate(schema,input).valid());
    }
    @Test void dynamicDictionaryChoicesAreCheckedInsideEverySegment(){
        var dictionaries=mock(com.astra.freyja.service.DictService.class);var enabled=new com.astra.freyja.entity.SysDictData();enabled.setStatus(1);enabled.setDictValue("MEDIUM_SHOT");enabled.setDictLabel("中景");when(dictionaries.listByType("shot_type")).thenReturn(List.of(enabled));
        var options=new McpBusinessOptions(dictionaries,new McpSchemaMetadata(json),json);var schema=options.apply(schemas.tool(McpContinuationTools.Plan.class));var input=plan();var d=(Map<String,Object>)input.get("draft");var segment=new LinkedHashMap<String,Object>(Map.of("id","s","startFrame",0,"endFrame",124,"shotType","MEDIUM_SHOT"));d.put("segments",List.of(segment));
        assertTrue(validator.validate(schema,input).valid(),json.write(schema));segment.put("shotType","DISABLED");assertFalse(validator.validate(schema,input).valid());
    }
    @Test void allToolsAreInThePublishedInventoryWithScopedReadAndJournaledWrites(){
        var scope=mock(McpScope.class);var app=mock(ShotContinuationApplication.class);var journal=mock(McpOperationStore.class);var records=mock(McpBusinessRecords.class);
        var tools=new McpContinuationTools(scope,records,mock(McpBusinessView.class),journal,schemas,app,mock(VideoRenderBatchService.class),mock(RenderTaskMapper.class),new ObjectMapper(),mock(com.astra.freyja.service.video.DynamicWorkflowService.class));
        assertEquals(McpContinuationTools.TOOL_NAMES,new HashSet<>(tools.tools().stream().map(McpTool::name).toList()));
        var caller=new McpPrincipal(false,false,false,Set.of(1L));
        @SuppressWarnings("unchecked") var save=(McpTool<McpContinuationTools.Save>)tools.tools().stream().filter(t->t.name().equals("save_shot_continuation")).findFirst().orElseThrow();
        assertThrows(BizException.class,()->save.handler().apply(caller,new McpContinuationTools.Save("1","2","request-001","fingerprint",draft())));verifyNoInteractions(journal,app);
        when(scope.project(any(),eq("1"))).thenReturn(1L);when(scope.shot(2L,1L)).thenThrow(new BizException(403,"cross project"));
        @SuppressWarnings("unchecked") var read=(McpTool<McpContinuationTools.Shot>)tools.tools().stream().filter(t->t.name().equals("get_shot_continuation")).findFirst().orElseThrow();
        assertThrows(BizException.class,()->read.handler().apply(caller,new McpContinuationTools.Shot("1","2")));verifyNoInteractions(app);
    }
    @Test void agentSchemaRemovesOnlyInjectedProjectAndRequestFields(){
        var inventory=mock(McpToolInventory.class);var app=mock(ShotContinuationApplication.class);
        var tools=new McpContinuationTools(mock(McpScope.class),mock(McpBusinessRecords.class),mock(McpBusinessView.class),mock(McpOperationStore.class),schemas,app,mock(VideoRenderBatchService.class),mock(RenderTaskMapper.class),new ObjectMapper(),mock(com.astra.freyja.service.video.DynamicWorkflowService.class));
        when(inventory.registeredHandlers()).thenReturn(tools.tools());
        var business=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("inventory",inventory));
        var handlers=new com.astra.freyja.capability.service.CapabilityHandlers(business.getBeanProvider(McpToolInventory.class),new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(org.springframework.core.ResolvableType.forClassWithGenerics(List.class,com.astra.freyja.agent.tool.AgentTool.class)),json,new com.astra.freyja.agent.model.AgentJson());
        assertEquals(McpContinuationTools.TOOL_NAMES.size()*2,handlers.initialBindings().size());
    }
}
