package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.RenderTaskMapper;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Published MCP and Agent adapters call the same workbench services, never private execution routes. */
@Component @RequiredArgsConstructor
public class McpContinuationTools {
    public static final Set<String> TOOL_NAMES=Set.of("get_shot_continuation_options","get_shot_continuation","plan_shot_continuation","save_shot_continuation","split_shot_reference_video","preview_shot_continuation","prepare_shot_continuation","get_shot_continuation_preparation","render_shot_continuation","get_shot_continuation_render","prepare_shot_reference_video","get_shot_reference_video_preparation","render_shot_video");
    private final McpScope scope;
    private final McpBusinessRecords records;
    private final McpBusinessView views;
    private final McpOperationStore journal;
    private final McpContinuationSchemas schemas;
    private final ShotContinuationApplication application;
    private final VideoRenderBatchService renders;
    private final RenderTaskMapper tasks;
    private final ObjectMapper json;
    public record Shot(String dramaId,String shotId){}
    public record Options(String dramaId,String shotId,@ToolParam(required=false) String providerId,@ToolParam(required=false) String modelCode){}
    public record Plan(String dramaId,String shotId,Map<String,Object> draft,@ToolParam(required=false) Boolean auto){}
    public record Save(String dramaId,String shotId,String requestId,String expectedFingerprint,Map<String,Object> draft){}
    public record Split(String dramaId,String shotId,Map<String,Object> draft,Map<String,Object> referenceVideo,@ToolParam(required=false) List<String> targetSegmentIds){}
    public record Preview(String dramaId,String shotId,Map<String,Object> draft,String providerId,String modelCode){}
    public record Prepare(String dramaId,String shotId,String requestId,String expectedFingerprint,String providerId,String modelCode,String size,String planHash){}
    public record Preparation(String dramaId,String shotId,String preparationId){}
    public record ReferencePrepare(String dramaId,String shotId,String requestId,String expectedFingerprint,String providerId,String modelCode){}
    public record ReferenceState(String dramaId,String shotId,String preflightId){}
    public record OrdinaryRender(String dramaId,String shotId,String requestId,String expectedFingerprint,String providerId,String modelCode,String size,@ToolParam(required=false) String preflightId){}
    public record Task(String dramaId,String shotId,String taskId){}

    public List<McpTool<?>> tools(){return List.of(
        read("get_shot_continuation_options","读取接续档位、当前启用景别/运镜、视频提供商与模型。提供providerId+modelCode时返回真实节点支持的ControlNet类型和capabilities.h3.continuationModes四模式可用性及缺项；两者必须配套。角色/造型/场景/道具用已发布资产查询工具选择。",Options.class,(c,i)->{authorized(c,i.dramaId(),i.shotId());return application.options(id(i.shotId()),optional(i.providerId()),i.modelCode());}),
        read("get_shot_continuation","读取镜头接续草稿与editFingerprint。片段独立配置四个标签页；超过15秒必须enabled=true。schemaVersion=2可设draft.continuity和片段continuityOverride：GUIDE、GUIDE_CHROMA、LATENT_REDRAW、LATENT_PROTECTED；覆盖为完整策略，null为继承。参考视频的ControlNet为可选，深度/骨架/灰度等按controlType在渲染时自动提取并应用，不独立导出。",Shot.class,(c,i)->view(authorized(c,i.dramaId(),i.shotId()))),
        read("plan_shot_continuation","纯帧规划与校验，不调用内部AI或GPU，不自动保存。auto=true自动分段，false校验手工微调；保护已编辑片段和锁定内容。先读取选项；外部Agent自行编写各段剧本与提示词。",Plan.class,(c,i)->{authorized(c,i.dramaId(),i.shotId());return application.plan(id(i.shotId()),draft(i.draft()),Boolean.TRUE.equals(i.auto()));}),
        write("save_shot_continuation","按当前editFingerprint保存完整接续草稿，requestId幂等。角色/造型/场景/图片用途等必须是有效选项；图片、视频只用于明确挂载的片段。可关闭接续并保留长草稿；普通镜头ControlNet放draft.controlNet。",Save.class,(c,i)->mutate(c,i.dramaId(),i.shotId(),i.requestId(),i.expectedFingerprint(),"save_shot_continuation",i,()->{try{application.save(id(i.shotId()),draft(i.draft()));}catch(BizException e){throw e;}catch(Exception e){throw new BizException(500,"接续保存失败");}return view(application.shot(id(i.shotId())));})),
        read("split_shot_reference_video","按当前接续时间轴切分一个参考视频的源范围，保留真实重叠；只返回绑定后的草稿，不自动保存或渲染。targetSegmentIds省略时作用全部片段，指定时须是当前有效且不重复的片段ID。",Split.class,(c,i)->{authorized(c,i.dramaId(),i.shotId());return application.split(id(i.shotId()),draft(i.draft()),json.convertValue(i.referenceVideo(),ShotRefVideoDTO.class),i.targetSegmentIds());}),
        read("preview_shot_continuation","使用启用的视频模型校验当前草稿、ControlNet类型和上下文窗口组合，返回renderable、issues、planHash及ranges生效策略和latent归档依赖。不保存、不渲染；renderable=false时必须修正问题，不能声称可生成。",Preview.class,(c,i)->{authorized(c,i.dramaId(),i.shotId());return application.preview(id(i.shotId()),draft(i.draft()),false,id(i.providerId()),i.modelCode());}),
        write("prepare_shot_continuation","对已保存且预览通过的同一planHash准备片段素材，异步返回preparationId。传当前editFingerprint及稳定requestId；先轮询至READY，再提交渲染。",Prepare.class,(c,i)->mutate(c,i.dramaId(),i.shotId(),i.requestId(),i.expectedFingerprint(),"prepare_shot_continuation",i,()->application.prepare(id(i.shotId()),id(i.providerId()),i.modelCode(),i.size(),i.planHash()))),
        read("get_shot_continuation_preparation","读取当前授权镜头的素材准备状态PREPARING/READY/FAILED；不接受其他镜头的preparationId。",Preparation.class,(c,i)->{authorized(c,i.dramaId(),i.shotId());return application.preparation(id(i.shotId()),i.preparationId());}),
        write("render_shot_continuation","将已保存、预览通过、素材READY的接续镜头提交现有视频队列，自动提取所选ControlNet信号并应用。必须创作者已授权生成；requestId幂等，返回真实任务ID，不代表生成成功。",Prepare.class,(c,i)->mutate(c,i.dramaId(),i.shotId(),i.requestId(),i.expectedFingerprint(),"render_shot_continuation",i,()->{var shot=application.shot(id(i.shotId()));var draft=application.read(shot.getId());if(!draft.isEnabled())throw new BizException(400,"请先开启并保存接续模式");var plan=application.preview(shot.getId(),draft,false,id(i.providerId()),i.modelCode());if(!plan.isRenderable()||!Objects.equals(plan.getPlanHash(),i.planHash()))throw new BizException(409,"接续计划已变化或尚未通过验收，请重新预览");application.validateSize(application.videoModel(shot,id(i.providerId()),i.modelCode()),i.size());var req=new DramaShotRenderRequestDTO();req.setProviderId(id(i.providerId()));req.setWorkflowTemplateId(i.modelCode());req.setSize(i.size());req.setExpectedRenderFingerprint(i.planHash());return renders.enqueueSingle(shot.getId(),req);})),
        write("prepare_shot_reference_video","为普通参考模式镜头准备已保存的参考视频，返回preflightId；与接续素材准备独立。ControlNet可选，深度/骨架/灰度等配置从get_shot_continuation返回的draft.controlNet保存。",ReferencePrepare.class,(c,i)->mutate(c,i.dramaId(),i.shotId(),i.requestId(),i.expectedFingerprint(),"prepare_shot_reference_video",i,()->application.prepareReference(id(i.shotId()),id(i.providerId()),i.modelCode()))),
        read("get_shot_reference_video_preparation","查询授权镜头的参考视频预检，READY才可提交普通参考模式渲染；其他镜头的preflightId不可用。",ReferenceState.class,(c,i)->{authorized(c,i.dramaId(),i.shotId());return application.referencePreparation(id(i.shotId()),i.preflightId());}),
        write("render_shot_video","提交普通单镜头视频（最多15秒）；使用已保存参考素材和draft.controlNet，渲染时自动提取所选控制信号。参考视频需先准备至READY；开启接续的镜头必须改用render_shot_continuation。",OrdinaryRender.class,(c,i)->mutate(c,i.dramaId(),i.shotId(),i.requestId(),i.expectedFingerprint(),"render_shot_video",i,()->{
            var shot=application.shot(id(i.shotId()));if(application.read(shot.getId()).isEnabled())throw new BizException(400,"该镜头已开启接续，请使用render_shot_continuation");
            application.validateSize(application.videoModel(shot,id(i.providerId()),i.modelCode()),i.size());
            var req=new DramaShotRenderRequestDTO();req.setProviderId(id(i.providerId()));req.setWorkflowTemplateId(i.modelCode());req.setSize(i.size());req.setPreflightId(i.preflightId());return renders.enqueueSingle(shot.getId(),req);
        })),
        read("get_shot_continuation_render","读取授权镜头的接续渲染任务状态、当前进度与最终视频地址。不会返回内部请求快照，也不修改或生成视频。",Task.class,(c,i)->{var shot=authorized(c,i.dramaId(),i.shotId());var task=tasks.selectOne(new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId,i.taskId()).eq(RenderTask::getShotId,shot.getId()).eq(RenderTask::getTaskType,"SHOT_VIDEO"));if(task==null)throw new BizException(404,"该镜头没有此视频任务");var result=new LinkedHashMap<String,Object>();result.put("taskId",task.getTaskId());result.put("status",task.getStatus());result.put("progress",task.getProgress());result.put("currentStage",task.getCurrentNode());result.put("videoUrl",task.getOutputUrl());result.put("errorMessage",task.getErrorMessage());return result;})
    );}
    private <T> McpTool<T> read(String name,String description,Class<T> type,BiFunction<McpPrincipal,T,Object> handler){return new McpTool<>(name,description,type,McpTool.Effect.READ,schemas.tool(type),(caller,input)->schemas.wire(handler.apply(caller,input)));}
    private <T> McpTool<T> write(String name,String description,Class<T> type,BiFunction<McpPrincipal,T,Object> handler){return new McpTool<>(name,description,type,McpTool.Effect.WRITE,schemas.tool(type),(caller,input)->schemas.wire(handler.apply(caller,input)));}
    private DramaShot authorized(McpPrincipal caller,String project,String shot){return scope.shot(id(shot),scope.project(caller,project));}
    private Long id(String raw){return McpScope.id(raw);}private Long optional(String raw){return raw==null?null:id(raw);}
    private ShotContinuation draft(Map<String,Object> value){if(value==null)throw new BizException(400,"draft不能为空");return json.convertValue(value,ShotContinuation.class);}
    private Object view(DramaShot shot){return Map.of("shotId",shot.getId().toString(),"generationMode",shot.getGenerationMode(),"draft",application.read(shot.getId()),"editFingerprint",views.fingerprint(shot));}
    private Object mutate(McpPrincipal caller,String drama,String shot,String request,String fingerprint,String tool,Object input,Supplier<Object> action){
        if(!caller.write())throw new BizException(403,"MCP_WRITE_DISABLED");authorized(caller,drama,shot);
        return journal.execute(request,tool,input,caller,()->{var current=records.lockScoped(caller,McpRecordType.SHOT,shot,drama);views.checkFingerprint(McpBusinessView.Kind.SHOT,current,fingerprint,Set.of());return new McpOperationStore.Outcome(action.get(),id(drama),false);});
    }
}
