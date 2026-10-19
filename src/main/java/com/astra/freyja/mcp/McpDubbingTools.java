package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.voice.DubbingInputs.*;
import com.astra.freyja.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;

/** Dubbing writes share exactly the same invariants and job pipeline as the workbench. */
@Component
@RequiredArgsConstructor
public class McpDubbingTools {
    private final McpScope scope;
    private final McpBusinessRecords records;
    private final McpOperationStore journal;
    private final DubbingService dubbing;
    private final VoiceEffectService effects;
    private final AiTaskService tasks;
    public record Query(String dramaId,String characterId,
        @ToolParam(required=false) Integer page,@ToolParam(required=false) Integer size,
        @ToolParam(required=false) List<String> emotions,@ToolParam(required=false) List<String> effectIds) {}
    public record Presets(String dramaId,@ToolParam(required=false) String name,@ToolParam(required=false) Integer page,@ToolParam(required=false) Integer size) {}
    public record Filters(String dramaId) {}
    public record Selection(String effectId,@ToolParam(required=false) List<Step> steps) {}
    public record Generate(String dramaId,String characterId,String requestId,String name,String providerId,String modelCode,String method,
        @ToolParam(description="模型请求 JSON 对象；使用前读取 mimo-speech-synthesis Skill。") Map<String,Object> modelRequest,
        @ToolParam(required=false) List<Selection> effects,@ToolParam(required=false) String replaceBaseId) {}
    public record Modify(String dramaId,String characterId,String audioId,String requestId,Integer revision,List<Selection> effects) {}
    public record Upload(String dramaId,String characterId,String requestId,String name,String text,
        @ToolParam(description="实际音频文件的Base64内容，文件最大7 MiB；不能传本地路径或URL。必须为无特效干声。") String audioBase64,
        @ToolParam(required=false) String replaceBaseId) {}
    public record Audio(String dramaId,String characterId,String audioId) {}
    public record Remove(String dramaId,String characterId,String audioId,String requestId,Integer revision) {}

    public List<McpTool<?>> tools() { return List.of(
        McpTool.read("dubbing_list_filters","读取配音特效的20种处理与类型化参数目录、取值范围、默认值、枚举及服务器可用性。仅开放语音滤镜，不接受原始FFmpeg命令。自定义steps前先读取此目录。",Filters.class,(caller,input)-> {
            scope.project(caller,input.dramaId());return effects.filterCapabilities();
        }),
        McpTool.read("dubbing_list_audios","按角色与情绪/特效标签分页查询配音，同时返回独立基础参考声音。playbackUrl为当前可用播放地址，READY且特效非空时为特效成品，否则为原始声音；失败/处理中不得声称特效已完成。",Query.class,(caller,input)-> {
            authorize(caller,input.dramaId(),input.characterId(),false);
            Map<String,Object> result=new LinkedHashMap<>();result.put("base",dubbing.baseView(McpScope.id(input.characterId())));
            result.put("page",dubbing.page(McpScope.id(input.characterId()),McpScope.page(input.page()),McpScope.size(input.size()),input.emotions(),input.effectIds(),false));return result;
        }),
        McpTool.read("dubbing_list_effects","查询启用的声音特效模板，返回参数快照；仅FFmpeg处理，不调用TTS。",Presets.class,(caller,input)-> {
            scope.project(caller,input.dramaId());return effects.page(McpScope.page(input.page()),McpScope.size(input.size()),input.name(),true);
        }),
        new McpTool<>("dubbing_create_audio","创建配音并返回异步任务。先读取 mimo-speech-synthesis Skill，提交 modelRequest JSON。method=DESIGN创建唯一无特效基础声音，使用mimo-v2.5-tts-voicedesign；method=CLONE使用角色基础声音，使用mimo-v2.5-tts-voiceclone。克隆先保存TTS原声再执行可选FFmpeg特效。替换基础需replaceBaseId，会删除关联克隆，须创作者明确要求。",Generate.class,McpTool.Effect.WRITE,null,(caller,input)->
            write(caller,input.dramaId(),input.characterId(),input.requestId(),"dubbing_create_audio",input,()->dubbing.create(McpScope.id(input.characterId()),new Create(input.name(),McpScope.id(input.providerId()),input.modelCode(),input.modelRequest(),selections(input.effects()),input.method(),McpScope.optionalId(input.replaceBaseId()))))),
        new McpTool<>("dubbing_update_effects","修改或清空克隆配音特效，始终复用原始克隆音频，不调用TTS。effects=[]立即切回干声；非空从原始音频执行FFmpeg。需要当前revision。",Modify.class,McpTool.Effect.WRITE,null,(caller,input)->
            write(caller,input.dramaId(),input.characterId(),input.requestId(),"dubbing_update_effects",input,()-> {
                requireAudio(input.audioId(),input.characterId()); if(input.effects()==null) throw new BizException(400,"effects必须是数组，清空传[]");
                return dubbing.updateEffects(McpScope.id(input.audioId()),new Effects(input.revision(),selections(input.effects())));
            })),
        new McpTool<>("dubbing_upload_base_audio","自定义上传角色无特效基础参考音频。传实际文件Base64，不传路径或URL；音频最大7 MiB、最长30秒，建议5至10秒，text填写真实台词。基础声音唯一，替换需要replaceBaseId并删除相关克隆。",Upload.class,McpTool.Effect.WRITE,null,(caller,input)->
            write(caller,input.dramaId(),input.characterId(),input.requestId(),"dubbing_upload_base_audio",input,()-> {
                if(input.audioBase64()==null || input.audioBase64().length()>10*1024*1024) throw new BizException(400,"上传音频过大");
                byte[] bytes;try {bytes=Base64.getDecoder().decode(input.audioBase64());} catch(IllegalArgumentException e) {throw new BizException(400,"音频Base64无效");}
                if(bytes.length>7*1024*1024) throw new BizException(400,"上传音频最大7 MiB");
                return dubbing.uploadBase(McpScope.id(input.characterId()),input.name(),input.text(),bytes,McpScope.optionalId(input.replaceBaseId()));
            })),
        McpTool.read("dubbing_get_audio","读取配音最新状态、版本、原始音频、当前成品和关联任务。用于轮询异步TTS/FFmpeg结果。",Audio.class,(caller,input)-> {
            authorize(caller,input.dramaId(),input.characterId(),false);var audio=requireAudio(input.audioId(),input.characterId());
            Map<String,Object> result=new LinkedHashMap<>();result.put("audio",dubbing.view(audio));
            result.put("task",audio.getTaskId()==null?null:tasks.getTaskById(audio.getTaskId()));return result;
        }),
        new McpTool<>("dubbing_delete_audio","删除配音，基础声音删除会级联删除全部关联克隆。必须在创作者明确要求删除后调用；分镜引用须先解除。需要当前revision。",Remove.class,McpTool.Effect.WRITE,null,(caller,input)->
            write(caller,input.dramaId(),input.characterId(),input.requestId(),"dubbing_delete_audio",input,()-> {
                requireAudio(input.audioId(),input.characterId());dubbing.delete(McpScope.id(input.audioId()),input.revision());return Map.of("deleted",true);
            }))
    ); }
    private List<EffectSelection> selections(List<Selection> input) {
        return input==null?List.of():input.stream().map(s -> new EffectSelection(McpScope.id(s.effectId()),s.steps())).toList();
    }
    private void authorize(McpPrincipal caller,String drama,String character,boolean write) {
        scope.project(caller,drama);records.require(caller,McpRecordType.CHARACTER,character,drama,write);
    }
    private com.astra.freyja.entity.CharacterVoiceAudio requireAudio(String id,String character) {
        var audio=dubbing.require(McpScope.id(id));
        if(!audio.getCharacterId().equals(McpScope.id(character))) throw new BizException(403,"配音不属于指定角色");return audio;
    }
    private Object write(McpPrincipal caller,String drama,String character,String request,String tool,Object input,Supplier<Object> action) {
        if(!caller.write()) throw new BizException(403,"MCP_WRITE_DISABLED");
        authorize(caller,drama,character,true);
        return journal.execute(request,tool,input,()-> {
            records.lockScoped(caller,McpRecordType.CHARACTER,character,drama);
            return new McpOperationStore.Outcome(action.get(),McpScope.id(drama),false);
        });
    }
}
