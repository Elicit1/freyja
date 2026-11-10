package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.ShotVideoCapabilityService;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class DynamicWorkflowService {
    private final DynamicWorkflowPresetMapper presets;
    private final AiModelMapper models;
    private final AiProviderMapper providers;
    private final CryptoUtil crypto;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private record Cached(long expires,JsonNode value){}
    private final java.util.concurrent.ConcurrentHashMap<String,Cached> capabilityCache=new java.util.concurrent.ConcurrentHashMap<>();
    @Data public static class Edit {
        private Long id;
        private Integer version;
        private String name;
        private Long providerId;
        private Long modelId;
        private Integer status=1;
        private DynamicWorkflowOptions options=new DynamicWorkflowOptions();
    }
    public List<DynamicWorkflowPreset> list(Long provider,Long model) {
        return presets.selectList(new LambdaQueryWrapper<DynamicWorkflowPreset>()
                .eq(provider!=null,DynamicWorkflowPreset::getProviderId,provider)
                .eq(model!=null,DynamicWorkflowPreset::getModelId,model).orderByDesc(DynamicWorkflowPreset::getUpdateTime));
    }
    public List<Map<String,Object>> steps(String task){return jdbc.queryForList("SELECT step_key,stage,status,output_url,source_task_id,error_message,updated_at FROM dynamic_workflow_step WHERE task_id=? ORDER BY step_key",task);}
    public String previousTask(Long shot){
        var rows=jdbc.query("SELECT s.task_id FROM dynamic_workflow_step s JOIN render_task r ON r.task_id=s.task_id WHERE r.shot_id=? AND s.status='SUCCESS' GROUP BY s.task_id ORDER BY MAX(s.updated_at) DESC LIMIT 1",(rs,n)->rs.getString(1),shot);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public JsonNode capabilities(Long providerId) {
        var provider=providers.selectById(providerId);
        if(provider==null||!Objects.equals(provider.getStatus(),1))throw new BizException(400,"请选择已启用的提供商");
        String cacheKey=providerId+"|"+provider.getBaseUrl()+"|"+Objects.hashCode(provider.getApiKey());
        var cached=capabilityCache.get(cacheKey);if(cached!=null&&System.nanoTime()<cached.expires())return cached.value().deepCopy();
        try {
            var req=HttpRequest.newBuilder(URI.create(ShotVideoCapabilityService.apiRoot(provider.getBaseUrl())+"/dynamic-workflows/capabilities"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+Objects.toString(crypto.decrypt(provider.getApiKey()),"")).GET().build();
            var response=HTTP.send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new BizException(502,"此提供商未提供动态工作流能力，请更新网关");
            var catalog=json.readTree(response.body());if(!catalog.path("models").isArray())throw new BizException(502,"网关动态工作流能力格式无效");
            capabilityCache.put(cacheKey,new Cached(System.nanoTime()+Duration.ofSeconds(5).toNanos(),catalog));return catalog.deepCopy();
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new BizException(409,"读取动态工作流能力已取消"); }
        catch(BizException e){throw e;}catch(Exception e){throw new BizException(502,"无法读取动态工作流能力，请检查网关连接");}
    }
    @Transactional public DynamicWorkflowPreset save(Edit edit) {
        if(edit.getName()==null||edit.getName().isBlank()||edit.getName().length()>120||edit.getOptions()==null||!Objects.equals(edit.getStatus(),0)&&!Objects.equals(edit.getStatus(),1))throw new BizException(400,"工作流名称、状态或设置无效");
        edit.getOptions().validate();route(edit.getProviderId(),edit.getModelId());
        var selection=new Selection();selection.setOptions(edit.getOptions());resolve(selection,edit.getProviderId(),edit.getModelId(),"960x544",null);
        var preset=new DynamicWorkflowPreset();preset.setName(edit.getName().trim());preset.setProviderId(edit.getProviderId());preset.setModelId(edit.getModelId());preset.setStatus(edit.getStatus());preset.setOptionsJson(write(edit.getOptions()));
        if(edit.getId()==null){preset.setVersion(1);if(presets.insert(preset)!=1)throw new BizException(500,"动态工作流保存失败");}
        else {
            if(edit.getVersion()==null)throw new BizException(400,"缺少工作流版本");
            preset.setId(edit.getId());preset.setVersion(edit.getVersion()+1);
            if(presets.update(preset,new UpdateWrapper<DynamicWorkflowPreset>().eq("id",edit.getId()).eq("version",edit.getVersion()))!=1)throw new BizException(409,"工作流已被修改或删除，请刷新后重试");
        }
        if(jdbc.update("INSERT INTO dynamic_workflow_version(preset_id,version,snapshot_json,created_at) VALUES (?,?,?,?)",preset.getId(),preset.getVersion(),write(preset),LocalDateTime.now())!=1)throw new BizException(500,"工作流版本保存失败");
        return presets.selectById(preset.getId());
    }
    @Transactional public void delete(Long id,Integer version) {
        if(version==null||presets.update(null,new UpdateWrapper<DynamicWorkflowPreset>().eq("id",id).eq("version",version).set("deleted",1))!=1)throw new BizException(409,"工作流版本已变化，请刷新后删除");
    }
    private AiModel route(Long provider,Long model) {
        if(provider==null||model==null)throw new BizException(400,"请选择视频提供商与基础模型");
        var found=models.selectById(model);
        if(found==null||!Objects.equals(found.getProviderId(),provider)||!Objects.equals(found.getStatus(),1)||!Set.of("TXT2VIDEO_REF","TXT2VIDEO_FIRST_LAST").contains(found.getModelType()))throw new BizException(400,"请选择已启用的基础视频模型");
        return found;
    }
    public Plan resolve(Selection selection,Long provider,Long model,String size,String preferredNode) {
        if(selection==null)return null;
        var base=route(provider,model);DynamicWorkflowOptions options=selection.getOptions();
        Plan plan=new Plan();plan.setName("本次自定义工作流");
        if(selection.getPresetId()!=null) {
            var preset=presets.selectById(selection.getPresetId());
            if(preset==null||!Objects.equals(preset.getStatus(),1)||!Objects.equals(preset.getProviderId(),provider)||!Objects.equals(preset.getModelId(),model))throw new BizException(400,"工作流与所选基础模型不匹配或已停用");
            if(!Objects.equals(selection.getVersion(),preset.getVersion()))throw new BizException(409,"工作流已更新，请重新选择");
            plan.setPresetId(preset.getId());plan.setVersion(preset.getVersion());plan.setName(preset.getName());
            if(options==null)try{options=json.readValue(preset.getOptionsJson(),DynamicWorkflowOptions.class);}catch(Exception e){throw new BizException(500,"工作流参数无法读取");}
        }
        if(options==null)options=new DynamicWorkflowOptions();options.validate();
        JsonNode match=null;
        for(var cap:capabilities(provider).path("models"))if(base.getModelCode().equals(cap.path("modelCode").asText())&&cap.path("supported").asBoolean()&&(preferredNode==null||preferredNode.equals(cap.path("nodeId").asText()))){match=cap;break;}
        if(match==null)throw new BizException(400,"所选模型不支持动态工作流，请选择基础 H3 模型");
        if(options.isUpscale()&&"FREYJA".equals(options.getUpscaleEngine())) {
            if(!match.path("freyjaUpscale").path("supported").asBoolean())throw new BizException(400,"请更新网关以使用 FREYJA 二采组件");
            if(options.getUpscaleSteps()>match.path("freyjaUpscale").path("maxSteps").asInt(0))throw new BizException(400,"高清采样步数超过当前基础模型上限");
        }
        if(options.isFaceRepair()&&!java.util.stream.StreamSupport.stream(match.path("stages").spliterator(),false).anyMatch(n->"FACE".equals(n.asText())))throw new BizException(400,"当前人脸修复模块需要参考生成基础模型；首尾帧模型可使用二采放大");
        plan.setCapabilityRevision(match.path("revision").asText());plan.setNodeId(match.path("nodeId").asText());plan.setBaseSize(VideoBatchPreparation.normalizeSize(size));plan.setOptions(options);
        var wh=plan.getBaseSize().split("x");int scale=options.isUpscale()?2:1;
        long w=(long)Integer.parseInt(wh[0])*scale,h=(long)Integer.parseInt(wh[1])*scale;
        if(options.isUpscale()&&"FULL_HD".equals(options.getUpscaleOutput())){
            boolean portrait=w<h;
            double ratio=(double)Math.max(w,h)/Math.min(w,h);
            if(Math.abs(ratio-16.0/9)>0.06)throw new BizException(400,"1080P 输出请选择接近 16:9 或 9:16 的基础画幅");
            w=portrait?1080:1920;h=portrait?1920:1080;
        }
        if(w*h>match.path("maxTargetPixels").asLong(4194304))throw new BizException(400,"二采输出超过当前预设的像素预算，请降低基础尺寸");
        plan.setOutputSize(w+"x"+h);
        plan.setProcessingSize(((w+31)/32*32)+"x"+((h+31)/32*32));return plan;
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
