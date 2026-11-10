package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.ShotVideoCapabilityService;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class DynamicWorkflowService {
    private final AiModelMapper models;
    private final AiProviderMapper providers;
    private final CryptoUtil crypto;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private record Cached(long expires,JsonNode value){}
    private final java.util.concurrent.ConcurrentHashMap<String,Cached> capabilityCache=new java.util.concurrent.ConcurrentHashMap<>();
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
            if(response.statusCode()!=200)throw new BizException(502,"此提供商未提供视频增强能力，请更新网关");
            var catalog=json.readTree(response.body());if(!catalog.path("models").isArray())throw new BizException(502,"网关视频增强能力格式无效");
            capabilityCache.put(cacheKey,new Cached(System.nanoTime()+Duration.ofSeconds(5).toNanos(),catalog));return catalog.deepCopy();
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new BizException(409,"读取视频增强能力已取消"); }
        catch(BizException e){throw e;}catch(Exception e){throw new BizException(502,"无法读取视频增强能力，请检查网关连接");}
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
        if(options==null||!options.isUpscale()&&!options.isFaceRepair())return null;
        options.validate();options=json.convertValue(options,DynamicWorkflowOptions.class);
        Plan plan=new Plan();plan.setName("视频增强");
        JsonNode match=null;
        for(var cap:capabilities(provider).path("models"))if(base.getModelCode().equals(cap.path("modelCode").asText())&&cap.path("supported").asBoolean()&&(preferredNode==null||preferredNode.equals(cap.path("nodeId").asText()))){match=cap;break;}
        if(match==null)throw new BizException(400,"所选模型不支持视频增强，请选择基础 H3 模型");
        if(options.isUpscale()&&"FREYJA".equals(options.getUpscaleEngine())) {
            if(!match.path("freyjaUpscale").path("supported").asBoolean())throw new BizException(400,"请更新网关以使用 FREYJA 二采组件");
            if(options.getUpscaleSteps()>match.path("freyjaUpscale").path("maxSteps").asInt(0))throw new BizException(400,"高清采样步数超过当前基础模型上限");
        }
        if(options.isFaceRepair()&&!java.util.stream.StreamSupport.stream(match.path("stages").spliterator(),false).anyMatch(n->"FACE".equals(n.asText())))throw new BizException(400,"当前人脸修复需要参考生成基础模型；首尾帧模型可使用二采放大");
        plan.setCapabilityRevision(match.path("revision").asText());plan.setNodeId(match.path("nodeId").asText());plan.setBaseSize(VideoBatchPreparation.normalizeSize(size));plan.setOptions(options);
        var wh=plan.getBaseSize().split("x");int scale=options.isUpscale()?2:1;
        long w=(long)Integer.parseInt(wh[0])*scale,h=(long)Integer.parseInt(wh[1])*scale;
        if(options.isUpscale()&&"FULL_HD".equals(options.getUpscaleOutput())){
            boolean portrait=w<h;
            double ratio=(double)Math.max(w,h)/Math.min(w,h);
            if(Math.abs(ratio-16.0/9)>0.06)throw new BizException(400,"1080P 输出请选择接近 16:9 或 9:16 的基础画幅");
            w=portrait?1080:1920;h=portrait?1920:1080;
        }
        if(w*h>match.path("maxTargetPixels").asLong(4194304))throw new BizException(400,"二采输出超过当前模型的像素预算，请降低基础尺寸");
        plan.setOutputSize(w+"x"+h);
        plan.setProcessingSize(((w+31)/32*32)+"x"+((h+31)/32*32));return plan;
    }
}
