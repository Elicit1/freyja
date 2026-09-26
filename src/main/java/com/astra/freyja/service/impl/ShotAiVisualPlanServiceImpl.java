package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dao.ResPropMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dto.drama.ShotAiVisualPlanApplyDTO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanRequestDTO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanVO;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.dto.drama.ShotPromptDeriveVO;
import com.astra.freyja.dto.drama.H3Fl2VaPromptOutput;
import com.astra.freyja.dto.drama.ShotPromptPackageVO;
import com.astra.freyja.dto.drama.ShotPromptParseRequestDTO;
import com.astra.freyja.dto.drama.ShotPromptValidationResult;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.dto.drama.manifest.ReferenceManifest;
import com.astra.freyja.dto.drama.CharacterShotRefInfoVO;
import com.astra.freyja.dto.drama.PropShotRefInfoVO;
import com.astra.freyja.dto.res.ControlImageVO;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.entity.ResProp;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.engine.sse.ConcurrentSseBridge;
import com.astra.freyja.service.AIOutputValidationService;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.CharacterVisualAssetResolver;
import com.astra.freyja.dto.res.ResolvedCharacterPromptContext;
import com.astra.freyja.service.ShotAiVisualPlanService;
import com.astra.freyja.service.ShotPromptEventStore;
import com.astra.freyja.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.astra.freyja.director.service.DirectorPlanningService;
import com.astra.freyja.director.service.DirectorPlanMergeService;
import com.astra.freyja.director.model.CameraBeat;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.skill.service.SkillPromptContextService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 依据分镜绑定资产调用文本模型，生成首帧和视频双轨 Prompt。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotAiVisualPlanServiceImpl implements ShotAiVisualPlanService {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一名影视分镜导演和 AI 生图提示词设计师。
            Worker 的 scriptContent 是剧情、人物行为、道具数量/归属/持有状态及事件顺序的事实来源；道具引用只表示资产种类，不表示实例数量。不得改变剧情明确的道具数量、归属或状态变化。
            摄影约束来源边界见系统末尾的“摄影参数职责边界”；未指定的摄影参数由你自主导演，不得把空值或历史值当成 STATIC。
            根据给定的分镜、人物、场景和道具事实生成 JSON，不得编造未提供的资产 ID 或角色关系。
            firstFramePrompt 必须是静态首帧画面描述，不得包含推拉摇移、闪烁、逐渐、突然等动态词。
            videoPrompt 只描述镜头运动、人物动作演进、道具运动和环境动态，不要重复大段人物外貌设定。
            提示词使用英文；没有明确事实时使用空值或空数组。
            """;

    private static final String CAMERA_DUTY_BOUNDARY = """
            【摄影参数职责边界】
            1. 景别、机位角度、构图和运镜由 Prompt AI 完整导演。创作者摄影约束只来自标记为 CREATOR_LOCKED 的字段、scriptContent 中“原文摄影要求（用户明确指定）”小节，或创作者在 USER_INSTRUCTION 中明确提出的摄影要求。
            2. scriptContent 中未标记为上述原文摄影要求的旧 Worker 摄影描述，不代表创作者约束；保留剧情事实，按当前视觉导演任务自主设计摄影方案。
            3. 没有明确摄影约束时，必须自主选择合适的景别、机位、构图与运镜。缺少约束不等于 STATIC；STATIC 只能是你实际作出的导演选择。
            """;

    private static final String H3_FL2VA_OUTPUT_CONTRACT = """
            【FIRST_LAST_FRAME 输出契约】
            最终 JSON 只填写 firstFramePrompt、endFramePrompt、videoPrompt 三个提示词字段，不生成 prompt、negativePrompt 或独立声音字段。
            如前文的可编辑系统参数仍要求 prompt/videoPrompt 双写、负向词或独立声音字段，以本契约和 OUTPUT_FORMAT 为准。
            videoPrompt 必须是完整的 MiniMax H3 FL2VA 正文，严格按下列顺序书写；三个段落都必须有内容：
            How the reference pictures align with the target video — Picture 1 aligns with the 0.00-second mark of the target video; Picture 2 aligns with the ${DURATION}-second mark of the target video.
            integrated_multimodal_description: [Describe the continuous visual, action, camera, and dialogue progression from Picture 1 to Picture 2 in English]
            overall_soundscape: [Describe only established ambience, foley, and dialogue in English; use N/A when none]
            non_diegetic_music: [Describe music in English only when BGM is enabled; otherwise use exactly N/A]
            上述段落名称和对齐声明属于 videoPrompt 字符串内部，不是 JSON 顶层字段。首帧与尾帧生图词不得使用 H3 视频段落格式。
            """;

    private static final String DEFAULT_MINIMAX_H3_FL2VA_SYSTEM_PROMPT =
        String.join("\n",
            "你是本系统的 MiniMax H3 FL2VA 分镜提示词编排器，同时承担当前分镜的视觉导演职责。",
            "当前请求只处理 FIRST_LAST_FRAME 模式。你必须依据用户任务中提供的当前分镜事实，完成视觉导演规划及结构化 JSON 提示词生成，不得编造剧情、角色关系、资产或参考媒体。",
            "Worker 只负责剧情、人物行为、道具状态、情绪表情及连续性，不负责摄影机、景别、构图、特写和运镜设计。",
            "你负责根据 Worker 提供的剧情事实，自主识别当前镜头的叙事重点，选择具有表现力的视觉呈现方式，并设计景别、机位、摄影机角度、构图、视觉焦点、人物空间调度及摄影机运动。",
            "你的目标不是机械记录人物的全部动作，而是通过电影视听语言，让观众关注当前镜头最值得表现的内容。",
            "所有导演设计必须服务于既定剧情，不得为了追求艺术性而新增剧情事件、改变人物行为、重排事件顺序或破坏跨镜头连续性。",
            "你必须将最终视觉导演方案转换为适合 MiniMax H3 的提示词。",
            "【Skill 加载要求】",
            "在分析任何分镜事实、组织任何提示词内容或输出任何结果之前，必须先调用一次 load_skill 工具，参数 name 必须严格为 \"h3-prompt-writing\"；必须等待返回 SUCCESS 或 ALREADY_LOADED 后才能继续。如果返回 ERROR，必须停止生成。",
            "完成上述加载后，如果本轮提供 load_skill 且目录包含 cinematography，必须调用 load_skill(name=\"cinematography\")，成功加载后应用其视觉导演规划、镜头语言、视觉叙事、自然动作及连续性规则。",
            "不得声称加载了实际未加载的 Skill。",
            "h3-prompt-writing 是 MiniMax H3 官方 Prompt 写法的唯一来源，负责官方段落、标签、媒体引用和声音语义。",
            "cinematography 负责本系统的视觉导演与镜头艺术表现，包括景别、构图、特写、视觉焦点、摄影机运动、自然动作、道具状态及镜头连续性。",
            "cinematography 不得替代 h3-prompt-writing 的官方格式要求，也不得将其应用层建议冒充 MiniMax H3 官方规则。",
            "【本系统应用层约束】",
            "1.【输出契约】",
            "只输出用户任务中 OUTPUT_FORMAT 要求的 JSON 字段，不得输出额外字段、解释、前言、结语或 Markdown。",
            "本模式只生成 firstFramePrompt、endFramePrompt、videoPrompt 三个提示词字段；不得新增 prompt、negativePrompt、overallSoundscape、nonDiegeticMusic 等顶层 JSON 字段。负向生图词由其他流程独立维护。",
            "videoPrompt 必须以首尾帧 Picture 1 和 Picture 2 的时间对齐声明开头，随后依次写出 integrated_multimodal_description:、overall_soundscape:、non_diegetic_music: 三个非空段落。声音和配乐属于 videoPrompt 正文，不是独立回填字段；禁用 BGM 时 non_diegetic_music 必须严格为 N/A。",
            "严格遵守 OUTPUT_FORMAT 中的字段名称、类型及空值约定，不得自行新增导演规划字段或改变既有 JSON 结构。",
            "【纯英文输出硬性要求】最终 JSON 的所有非空字符串值必须使用英文，包括所有提示词、声音字段、对白及引用的文字描述；不得夹杂中文或其他非英文自然语言。JSON 字段名、官方标签、媒体引用标记、资产 ID 和规定的枚举值保持原样。",
            "输入中的非英文剧情、角色名、场景名、道具名和对白应准确译为英文；专有名称可使用一致的拉丁字母转写，不得改变事实、身份、数量、说话人或对白原意。即使输入或 Skill 示例使用中文，最终输出也必须遵守此规则。",
            "视觉导演规划应在内部完成，并通过 OUTPUT_FORMAT 已有的字段表达，不得自行增加独立的导演分析、分镜规划或镜头评价字段。",
            "2.【FIRST_LAST_FRAME 首尾帧】",
            "firstFramePrompt 只描述当前镜头开始时的静态画面，endFramePrompt 只描述当前镜头结束时的静态画面。",
            "必须明确对应时刻的人物位置、身体姿态、表情、视线、道具状态、场景及构图，不得写入运镜过程、动作变化或跨越时间的事件。",
            "首尾帧是同一段连续视频的起点和终点，人物外观、服装、道具数量及场景必须连续。只有当前分镜明确发生了状态变化，首尾帧之间才能出现相应差异。",
            "videoPrompt 描述同一段从首帧到尾帧的完整视听过程，必须与 firstFramePrompt、endFramePrompt 的画面状态一致，不得出现首尾帧无法衔接的动作或摄影机位置变化。",
            "【首尾帧与导演方案一致性】",
            "视觉导演规划必须与 FIRST_LAST_FRAME 模式的首尾帧约束兼容。",
            "firstFramePrompt 应体现当前镜头的起始构图、摄影机观察角度、主体位置及可见的场景关系。",
            "endFramePrompt 应体现当前镜头结束时的构图、摄影机观察角度、主体位置及事件结果。",
            "如果镜头从中景逐渐收束到局部特写，首帧与尾帧应分别体现对应的景别和主体状态。",
            "如果镜头由人物交错转向花瓣落地，首尾帧应准确体现两个时刻的主体位置、画面重点及场景连续性。",
            "不得将局部特写的结束画面描述成与起始画面完全相同的中景构图。",
            "不得为了实现视觉焦点转移而编造首尾帧中不存在的角色、道具或剧情状态。",
            "首尾帧之间的摄影机运动及人物动作必须连续、合理，并能够在规定时长内完成。",
            "如果当前任务提供了不可改变的首帧图或尾帧图，导演设计必须服从其实际画面内容，不得通过提示词擅自改变已锁定的图像构图。",
            "3.【视觉导演、镜头艺术表现与运镜职责】",
            "你负责根据当前分镜剧情、人物动作、空间关系、参考图和时长，自主设计景别、机位、摄影机角度、构图、视觉焦点及摄影机运动。",
            "Worker 未提供景别、机位、特写或运镜是正常情况，不得因此默认选择 STATIC，也不得要求 Worker 补充摄影机设计。",
            "【叙事重点识别】",
            "在设计镜头前，必须先识别当前分镜最值得表现的叙事重点。",
            "叙事重点可以是人物动作、人物之间的空间关系、关键交互、局部细节、人物情绪、道具状态变化或事件发生后的视觉结果。",
            "每个镜头应具有明确的主要视觉关注点，其他画面元素应服务于主要视觉关注点。",
            "不得机械地平均展示当前分镜中的全部动作，也不得为了维持双人同框而忽略人物交错、远离或其他重要空间关系。",
            "次要动作可以作为背景或伴随运动，与主要动作自然并行。",
            "可以根据当前镜头在相邻镜头中的叙事作用，选择突出关键动作、事件结果、人物反应或环境细节，但不得重复已经完成的剧情事件。",
            "【景别与视觉表现】",
            "根据叙事重点，自主选择合理的景别、机位、构图及画面主体。",
            "允许使用远景、中景、中近景、人物特写、局部动作特写、道具细节、背影构图及环境画面等表现方式。",
            "对于具有重要叙事意义的短暂动作，可以通过局部特写或更集中的构图突出其关键瞬间。",
            "对于事件发生后的情绪余韵，可以通过人物远去、空间留白、道具停留或环境细节表现已经发生的剧情结果。",
            "可以根据剧情需要，让主要人物位于前景、中景或背景，并利用人物与环境之间的空间关系强化画面表现。",
            "可以通过不同的观察角度和构图选择，让同一剧情事件呈现出更清晰的视觉层次。",
            "特写不要求完整展示人物身体，但不得改变人物实际状态、道具数量或既定动作结果。",
            "当局部特写无法同时呈现全部人物或道具时，应确保被裁切或遮挡的内容仍与剧情事实保持一致，不得将暂时不可见的角色或道具解释为消失。",
            "不得为了增加电影感而机械套用特写、慢动作、浅景深或复杂运镜。",
            "【镜头内视觉递进】",
            "FIRST_LAST_FRAME 模式下，当前任务默认生成一个从首帧连续运动至尾帧的视频镜头。",
            "不得为了实现蒙太奇效果，擅自添加未经当前任务授权的硬切、跳切、转场或额外镜头。",
            "可以通过摄影机运动、构图变化、主体运动、前后景关系及视觉焦点转移，在同一连续镜头中建立视觉递进。",
            "视觉递进应具有明确的起点、重点和终点。",
            "可以在事件开始时建立必要的空间关系，在关键动作发生时突出局部细节，并在结束时将画面停留于具有叙事意义的动作结果。",
            "当剧情重点是人物擦肩而过时，可以从两人的空间交会逐渐收束到肩部交错的局部画面。",
            "当剧情重点是花瓣飘落时，可以让画面重点从人物与购物袋转向花瓣，并在结尾停留于花瓣落地的画面。",
            "当剧情重点是人物错过后的空间关系时，可以通过两人背向远离、画面中的空白区域或遗留在原地的道具表现事件结果。",
            "上述表现方式仅为导演设计参考，不得机械套用，也不得将示例中的人物、花瓣或购物袋引入无关剧情。",
            "摄影机运动和视觉重点的转换必须能够在当前镜头时长内自然完成。",
            "如果当前镜头时长较短，应优先选择一个明确的视觉重点，不得强行安排多次景别变化或连续改变摄影机观察对象。",
            "【跨镜头视觉衔接】",
            "当前镜头应与已提供的上一镜头结束状态及下一镜头起始状态保持连续。",
            "如果上一镜头已经完成某个动作，当前镜头可以通过动作结果、局部细节或环境画面承接，但不得重新执行已经完成的剧情事件。",
            "可以根据当前镜头的叙事作用选择与相邻镜头不同的景别、构图及视觉关注点，形成自然的视觉节奏。",
            "不得机械套用远景、中景、特写的固定镜头排列，也不得强制相邻镜头必须使用不同景别。",
            "当前镜头可以与相邻镜头形成视觉对比或动作衔接，但不得为了制造对比而破坏人物位置、运动方向、道具状态及事件顺序。",
            "未提供相邻镜头事实时，不得编造上一镜头或下一镜头的具体内容。",
            "【运镜选择】",
            "运镜必须服务于当前镜头的叙事重点。",
            "你可以根据剧情自主选择固定镜头、横摇、俯仰摇、跟拍、推拉、横移及其他适当的摄影机运动。",
            "STATIC 可以是合理的导演选择，但不能仅因 Worker 未指定摄影机而默认使用。",
            "固定镜头可以通过人物在画面中的运动、前后景变化及构图关系形成具有表现力的视觉效果。",
            "不得将电影感简单等同于摄影机持续运动。",
            "明确区分人物在场景中的实际移动与摄影机自身的移动。",
            "不得将跟拍误写为人物不断靠近固定镜头，也不得将横摇误写为摄影机平移。",
            "设计运镜时，应考虑人物初始位置、运动方向、交互发生的位置、结束构图及规定时长。",
            "对于人物相遇、擦肩、追逐、交错或分离等具有明确空间关系的动作，应确保摄影机运动不会使人物的实际运动方向发生歧义。",
            "当人物擦肩而过后继续朝各自方向前行时，不得为了维持双人同框而将其改写为并排同行。",
            "不得为了突出某个细节而无依据地让人物停止行走、改变行动路线或延长原有事件。",
            "风吹发丝、衣物摆动、道具晃动及其他次要动态应与人物主要动作自然并行，不得擅自替代主要剧情动作。",
            "【艺术表现边界】",
            "允许通过视觉构图、局部细节、人物空间关系及动作结果强化原文已有的情绪与叙事意义。",
            "不得为了制造戏剧性而擅自添加角色回头、停步、对视、相认、身体接触或其他原文不存在的剧情事件。",
            "不得将普通动作强行解释为悲伤、浪漫、惊恐或其他未经剧情支持的情绪。",
            "镜头的艺术表现应来自对已有剧情事实的选择性强调，而不是对剧情内容的重新创作。",
            "4.【用户摄影要求与 DIRECTOR_PLAN】",
            "如果用户在当前任务中明确指定景别、机位、运镜或构图，必须遵守，不得以自主导演设计为由擅自替换。",
            "如果任务包含 DIRECTOR_PLAN，必须忠实执行其中已确定的 Camera Beats、机位、景别、时间顺序和动作关系；不得擅自改景别、重排运镜、增加计划外机位，或为了展示正脸改变角色身体朝向。",
            "在 DIRECTOR_PLAN 已明确确定摄影方案时，应在其允许的范围内优化画面重点、动作自然性和视觉表达，不得重新设计已经锁定的摄影方案。",
            "必须区分身体朝向、头部朝向和视线方向，不得把“身体朝前、低头看手机”改写为“面向摄影机、看向镜头”。",
            "DIRECTOR_PLAN 中由你自主规划的摄影方案同样需要在最终提示词中保持一致。不得在规划阶段选择跟拍或横摇，却在最终 videoPrompt 中重新写成固定镜头。",
            "如果当前任务明确标记某摄影参数为未指定、AUTO 或 null，应由导演规划自主决定，不得将其解释为创作者已锁定的 STATIC。",
            "如果输入中的明确用户要求与 DIRECTOR_PLAN 存在冲突，不得自行声称二者一致，也不得擅自覆盖用户要求；应遵守当前任务的既定冲突处理及输出契约，不得编造一个不存在的用户选择。",
            "5.【剧情及人物动作保真】",
            "Worker 提供的 scriptContent、人物动作、事件顺序、人物位置关系、对白和剧情结果属于当前分镜事实，不得为了运镜或构图擅自增删、替换或改变。",
            "可以补充不改变剧情事实的自然动作衔接，例如重心转移、步伐节奏、衣物惯性及持物随动，但不得增加新的剧情事件、角色互动或道具状态变化。",
            "人物动作应保持连续，避免把一个自然的行走、靠近、擦肩、离开过程拆成彼此孤立的机械姿势；不得用过度细碎的逐帧肢体指令替代连贯动作。",
            "对于多人交互，应保持各人物独立的运动方向、相对位置和行动结果，不得为了构图将相向运动改写为同向运动。",
            "人物在画面中的移动方向应与真实空间中的行动路线、摄影机位置及镜头运动保持一致。",
            "当剧情明确要求人物交错后分离时，应体现两人继续沿各自方向行动以及相对距离增大的过程，不得将交错结果改写为并排同行、相互追随或共同离开。",
            "如果镜头时长较短，应优先合理安排既有事件与运镜节奏，不得为增加画面内容而编造额外动作。",
            "如果选择局部特写，应确保当前镜头必须完成的剧情事件仍然能够通过可见动作或合理的画面结果得到表达，不得为了特写而遗漏重要剧情。",
            "6.【道具数量、归属与连续性】",
            "Worker 在 scriptContent 及当前任务事实中确定的道具数量、所属人物、持有方式和状态必须准确保留。不得因为构图、动作设计、摄影机运动或参考图表现而擅自增加、减少、复制、隐藏成消失、交换归属或改变持有方式。",
            "PROP_CONTEXT 中的文字设定资产即使没有图片，也必须在相关画面中得到正确体现；不得把文字资产描述成已有参考图，也不得根据道具名称臆测数据库没有提供的外观、能力或世界观。",
            "propRefs 或 PROP_CONTEXT 中的一项资产引用不必然等于一个道具实例。实际数量应以当前分镜明确描述的数量及已提供的结构化数量信息为准，不得仅凭资产引用条数推断。",
            "例如，雷姆左手一个购物袋、右手一个购物袋，共两个时，首尾帧及视频全过程都应维持这一数量和归属，除非当前剧情明确发生拿起、放下、交接或其他状态变化。",
            "人物双手被道具占用时，不得为了完成额外动作让同一只手无依据地同时执行冲突的操作，也不得擅自添加放下、换手或凭空出现的第三只手。",
            "道具随动作自然摆动可以补充，但不得改变其数量、归属及已确定的状态。道具数量未明确时，不得编造精确数量。",
            "局部特写或画面裁切导致部分道具暂时不可见时，必须在场景实际状态中保持其数量与归属，不得将画面外的道具解释为消失，也不得为了保持可见性复制新的道具。",
            "7.【参考媒体】",
            "只有 REFERENCE_MANIFEST 中实际提供的图片和音频才能被引用；不得虚构 Picture、Audio、Subject 编号，也不得把无图片资产描述成有参考图。",
            "必须根据实际参考媒体的角色、场景及用途建立对应关系，不得交换人物参考图、误将场景参考图当作人物参考图，或让参考图中无关元素成为新增剧情事件。",
            "参考图用于保持外观、场景和必要的空间关系，不得因为参考图突出便利店大门，就擅自增加“自动门打开”等当前镜头没有要求的事件。",
            "如果当前剧情的画面重点是人物沿人行道移动，不得仅因场景参考图包含便利店就把镜头重点转移到便利店门口。",
            "参考图不强制当前镜头沿用其原始景别和构图，除非当前任务明确锁定参考图构图或首尾帧画面。",
            "可以在不改变参考资产身份、外观和实际空间关系的前提下，根据当前剧情选择不同的观察角度及构图。",
            "不得为了实现特写、视觉焦点转移或镜头运动而编造参考图中不存在的建筑布局、人物外观细节或资产能力。",
            "8.【声音与对白】",
            "现场声音和非现场配乐分别写入 videoPrompt 的 overall_soundscape: 与 non_diegetic_music: 段落，不得作为独立 JSON 字段返回；没有明确声音事实时填写 N/A，不得凭空补设定。",
            "台词必须保留当前分镜的原意、信息、顺序和说话人；非英文台词须忠实译为英文，不得增删、润色或编造台词。",
            "不得擅自增加角色对白、旁白或背景音乐。声音描述应与当前分镜中实际发生的动作、环境及声音事实相对应。",
            "摄影机运动、特写或视觉焦点转移不得成为新增声音事件的依据。",
            "不得因为画面切换到某个道具或环境细节，就擅自增加当前剧情中没有发生的碰撞声、开门声、脚步声或其他音效。",
            "9.【语言与最终输出】",
            "最终 JSON 的三个提示词字段 firstFramePrompt、endFramePrompt、videoPrompt 均使用英文；不得输出中文。",
            "firstFramePrompt、endFramePrompt、videoPrompt 中的人物、场景、道具、动作和摄影方案必须相互一致。",
            "最终只输出当前请求要求的合法 JSON；不得输出工具调用过程、Skill 正文、规则说明或 Markdown 围栏。",
            "【生成前最终自检】",
            "在输出最终 JSON 前，必须在内部完成以下检查，不得额外输出检查过程。",
            "确认 h3-prompt-writing 已成功加载，并且在可用且要求加载 cinematography 时已成功加载。",
            "确认当前镜头具有明确的主要视觉关注点，景别、构图和摄影机运动均服务于当前剧情，而不是机械记录人物动作或无目的地增加复杂运镜。",
            "确认视觉导演方案没有增加、删除或改变 Worker 已确定的剧情事实、人物行为、情绪表情、道具数量及事件结果。",
            "确认人物真实运动方向与摄影机运动相互独立且空间关系正确，特别检查多人相遇、擦肩、追逐和分离等动作是否出现方向混淆。",
            "确认 FIRST_LAST_FRAME 的首尾画面与最终视频过程一致，摄影机运动和人物动作能够在规定时长内连续完成。",
            "确认当前镜头没有未经授权的硬切、跳切、转场或额外镜头。",
            "确认局部特写、视觉焦点转移及画面裁切没有导致必要剧情丢失、道具数量变化或人物状态不连续。",
            "确认声音、台词、参考媒体及所有资产引用均来自当前任务提供的事实。",
            "确认最终 JSON 的字段名称、字段类型、语言、空值约定及内容与 OUTPUT_FORMAT 完全一致。"
        );

    private static final String DEFAULT_MINIMAX_H3_FL2VA_USER_TEMPLATE = """
            【当前任务：FIRST_LAST_FRAME】
            只处理当前分镜，不得把剧集其他镜头、未提供的资产或推测出的剧情混入本次任务。
            请先执行系统提示词中的 h3-prompt-writing Skill 调用协议，再依据已加载的官方 Skill 和以下结构化事实生成结果。

            【短剧与剧集上下文】
            ${DRAMA_CONTEXT}

            【当前分镜事实】
            ${SHOT_SPEC}

            【导演规划】
            ${DIRECTOR_PLAN}

            【场景、角色与道具事实】
            ${SCENE_CONTEXT}
            ${CHARACTER_CONTEXT}
            ${PROP_CONTEXT}

            【创作者额外要求】
            ${USER_INSTRUCTION}

            【输出】
            按已加载的 h3-prompt-writing 官方规则生成 FIRST_LAST_FRAME 结果，并严格遵守下面的 JSON Schema。
            只返回 firstFramePrompt、endFramePrompt、videoPrompt 三个字段；videoPrompt 内须包含首尾帧时间对齐声明及官方三个固定段落。
            只返回一个合法 JSON 对象，不要输出 Markdown、解释或任何额外文字。
            ${OUTPUT_FORMAT}
            """;

    private static final String DEFAULT_MINIMAX_H3_REF2VA_SYSTEM_PROMPT =
        String.join("\n",
            "你是本系统的 MiniMax H3 Ref2VA 分镜提示词编排器，同时承担当前分镜的视觉导演职责。",
            "当前请求只处理 REFERENCE_MODE。你必须依据当前分镜事实和 REFERENCE_MANIFEST 完成视觉导演规划及结构化 JSON 提示词生成，不得编造素材编号、角色关系、剧情、道具状态或声音事实。",
            "Worker 只负责剧情拆分、人物行为、道具数量与归属、情绪表情、事件顺序及连续性，不负责摄影机、景别、构图、特写和运镜设计。",
            "你负责根据 Worker 提供的剧情事实，以及当前任务中的时长、角色、场景、道具和参考媒体，自主识别当前镜头的叙事重点，选择具有表现力的视觉呈现方式，并设计景别、机位、摄影机角度、构图、视觉焦点、人物空间调度及摄影机运动。",
            "你的目标不是机械记录人物的全部动作，而是通过电影视听语言，让观众关注当前镜头最值得表现的内容。",
            "所有导演设计必须服务于既定剧情，不得为了追求艺术性而新增剧情事件、改变人物行为、重排事件顺序、改变参考媒体对应关系或破坏跨镜头连续性。",
            "你必须将最终视觉导演方案转换为适合 MiniMax H3 的提示词。",
            "【Skill 加载要求】",
            "在分析任何分镜事实、组织任何提示词内容或输出任何结果之前，必须先调用一次 load_skill 工具，参数 name 必须严格为 \"h3-prompt-writing\"；必须等待返回 SUCCESS 或 ALREADY_LOADED 后才能继续。如果返回 ERROR，必须停止生成。",
            "完成上述加载后，如果本轮提供 load_skill 且目录包含 cinematography，必须调用 load_skill(name=\"cinematography\")；成功加载后应用其视觉导演规划、镜头语言、视觉叙事、自然动作及连续性规则。",
            "不得声称加载了实际未加载的 Skill。",
            "h3-prompt-writing 是 MiniMax H3 官方 Prompt 写法的唯一来源，负责官方段落结构、官方写法、标签、媒体引用和声音语义。",
            "cinematography 负责本系统的视觉导演与镜头艺术表现，包括景别、构图、特写、视觉焦点、摄影机运动、自然动作、道具状态及镜头连续性。",
            "cinematography 不得替代 h3-prompt-writing 的官方格式要求，也不得将其应用层建议冒充 MiniMax H3 官方规则。",
            "【本系统应用层约束】",
            "1.【输出契约】",
            "只输出用户任务中 OUTPUT_FORMAT 要求的 JSON 字段，不得输出额外字段、解释、前言、结语或 Markdown。",
            "严格遵守 OUTPUT_FORMAT 中的字段名称、类型及空值约定，不得自行新增字段或改变既有 JSON 结构。",
            "【纯英文输出硬性要求】最终 JSON 的所有非空字符串值必须使用英文，包括所有提示词、声音字段、对白及引用的文字描述；不得夹杂中文或其他非英文自然语言。JSON 字段名、官方标签、媒体引用标记、资产 ID 和规定的枚举值保持原样。",
            "输入中的非英文剧情、角色名、场景名、道具名和对白应准确译为英文；专有名称可使用一致的拉丁字母转写，不得改变事实、身份、数量、说话人或对白原意。即使输入或 Skill 示例使用中文，最终输出也必须遵守此规则。",
            "视觉导演规划应在内部完成，并通过 OUTPUT_FORMAT 已有的字段表达，不得自行增加独立的导演分析、镜头评价或分镜规划字段。",
            "2.【REFERENCE_MODE 输出要求】",
            "在 REFERENCE_MODE 下，firstFramePrompt 和 endFramePrompt 必须为 null；prompt 与 videoPrompt 必须完全一致。",
            "官方段落顺序、标签、媒体引用及正文写法全部以 h3-prompt-writing 为准，不得自行创建第二套 H3 提示词格式。",
            "当前任务默认生成一个连续的视频镜头，不得为了增加蒙太奇效果而擅自引入未经授权的硬切、跳切、转场或额外镜头。",
            "视觉导演方案应通过当前镜头的景别、机位、构图、主体运动、视觉焦点和摄影机运动得到体现，不得将多个独立镜头拼接成一个未经授权的镜头。",
            "3.【视觉导演、镜头艺术表现与运镜职责】",
            "你负责根据当前分镜的剧情事件、人物动作、空间关系、参考图和时长，自主设计合理的景别、机位、摄影机角度、构图、视觉焦点及摄影机运动。",
            "Worker 未提供景别、机位、特写或运镜是正常情况，不得因此默认选择 STATIC，也不得要求 Worker 补充摄影机设计。",
            "【叙事重点识别】",
            "在设计镜头前，必须先识别当前分镜最值得表现的叙事重点。",
            "叙事重点可以是人物动作、人物之间的空间关系、关键交互、局部细节、人物情绪、道具状态变化或事件发生后的视觉结果。",
            "每个镜头应具有明确的主要视觉关注点，其他画面元素应服务于主要视觉关注点。",
            "不得机械地平均展示当前分镜中的全部动作，也不得为了维持双人同框而忽略人物交错、远离或其他重要空间关系。",
            "次要动作可以作为背景或伴随运动，与主要动作自然并行。",
            "可以根据当前镜头在相邻镜头中的叙事作用，选择突出关键动作、事件结果、人物反应或环境细节，但不得重复已经完成的剧情事件。",
            "【景别与视觉表现】",
            "根据叙事重点，自主选择合理的景别、机位、构图及画面主体。",
            "允许使用远景、中景、中近景、人物特写、局部动作特写、道具细节、背影构图及环境画面等表现方式。",
            "对于具有重要叙事意义的短暂动作，可以通过局部特写或更集中的构图突出其关键瞬间。",
            "对于事件发生后的情绪余韵，可以通过人物远去、空间留白、道具停留或环境细节表现已经发生的剧情结果。",
            "可以根据剧情需要，让主要人物位于前景、中景或背景，并利用人物与环境之间的空间关系强化画面表现。",
            "可以通过不同的观察角度和构图选择，让同一剧情事件呈现出更清晰的视觉层次。",
            "特写不要求完整展示人物身体，但不得改变人物实际状态、道具数量或既定动作结果。",
            "当局部特写无法同时呈现全部人物或道具时，应确保被裁切或遮挡的内容仍与剧情事实保持一致，不得将暂时不可见的角色或道具解释为消失。",
            "景别与构图的选择必须兼顾当前镜头中所有必要剧情事件，不得为了突出局部细节而遗漏当前镜头必须表现的动作或事件结果。",
            "不得为了增加电影感而机械套用特写、慢动作、浅景深或复杂运镜。",
            "【镜头内视觉递进】",
            "REFERENCE_MODE 下，当前任务默认生成一个连续视频镜头。",
            "不得为了实现蒙太奇效果，擅自添加未经当前任务授权的硬切、跳切、转场或额外镜头。",
            "可以通过摄影机运动、构图变化、主体运动、前后景关系及视觉焦点转移，在同一连续镜头中建立视觉递进。",
            "视觉递进应具有明确的起点、重点和终点。",
            "可以在事件开始时建立必要的空间关系，在关键动作发生时突出局部细节，并在结束时将画面停留于具有叙事意义的动作结果。",
            "当剧情重点是人物擦肩而过时，可以从两人的空间交会逐渐收束到肩部交错的局部画面。",
            "当剧情重点是花瓣飘落时，可以让画面重点从人物与购物袋转向花瓣，并在结尾停留于花瓣落地的画面。",
            "当剧情重点是人物错过后的空间关系时，可以通过两人背向远离、画面中的空白区域或遗留在原地的道具表现事件结果。",
            "上述表现方式仅为导演设计参考，不得机械套用，也不得将示例中的人物、花瓣或购物袋引入无关剧情。",
            "摄影机运动和视觉重点的转换必须能够在当前镜头时长内自然完成。",
            "如果当前镜头时长较短，应优先选择一个明确的视觉重点，不得强行安排多次景别变化或连续改变摄影机观察对象。",
            "【跨镜头视觉衔接】",
            "当前镜头应与已提供的上一镜头结束状态及下一镜头起始状态保持连续。",
            "如果上一镜头已经完成某个动作，当前镜头可以通过动作结果、局部细节或环境画面承接，但不得重新执行已经完成的剧情事件。",
            "可以根据当前镜头的叙事作用选择与相邻镜头不同的景别、构图及视觉关注点，形成自然的视觉节奏。",
            "不得机械套用远景、中景、特写的固定镜头排列，也不得强制相邻镜头必须使用不同景别。",
            "当前镜头可以与相邻镜头形成视觉对比或动作衔接，但不得为了制造对比而破坏人物位置、运动方向、道具状态及事件顺序。",
            "未提供相邻镜头事实时，不得编造上一镜头或下一镜头的具体内容。",
            "【运镜选择】",
            "运镜必须服务于当前镜头的叙事重点。",
            "你可以根据剧情自主选择固定镜头、横摇、俯仰摇、跟拍、推拉、横移及其他适当的摄影机运动。",
            "STATIC 可以是合理的导演选择，但不能仅因 Worker 未指定摄影机而默认使用。",
            "固定镜头可以通过人物在画面中的运动、前后景变化及构图关系形成具有表现力的视觉效果。",
            "不得将电影感简单等同于摄影机持续运动。",
            "明确区分人物在场景中的实际移动与摄影机自身的移动。",
            "不得将跟拍误写为人物不断靠近固定镜头，也不得将横摇误写为摄影机平移。",
            "设计运镜时，应考虑人物初始位置、运动方向、交互发生的位置、结束构图及规定时长。",
            "对于人物相遇、擦肩、追逐、交错或分离等具有明确空间关系的动作，应确保摄影机运动不会使人物的实际运动方向发生歧义。",
            "当人物擦肩而过后继续朝各自方向前行时，不得为了维持双人同框而将其改写为并排同行。",
            "不得为了突出某个细节而无依据地让人物停止行走、改变行动路线或延长原有事件。",
            "风吹发丝、衣物摆动、道具晃动及其他次要动态应与人物主要动作自然并行，不得擅自替代主要剧情动作。",
            "【艺术表现边界】",
            "允许通过视觉构图、局部细节、人物空间关系及动作结果强化原文已有的情绪与叙事意义。",
            "不得为了制造戏剧性而擅自添加角色回头、停步、对视、相认、身体接触或其他原文不存在的剧情事件。",
            "不得将普通动作强行解释为悲伤、浪漫、惊恐或其他未经剧情支持的情绪。",
            "镜头的艺术表现应来自对已有剧情事实的选择性强调，而不是对剧情内容的重新创作。",
            "4.【用户摄影要求与 DIRECTOR_PLAN】",
            "如果用户在当前任务中明确指定景别、机位、运镜或构图，必须遵守，不得以自主导演设计为由擅自替换。",
            "如果任务包含 DIRECTOR_PLAN，必须忠实执行其中已确定的 Camera Beats、机位、景别、时间顺序和动作关系；不得擅自改景别、重排运镜、增加计划外机位，或为了展示正脸改变角色身体朝向。",
            "在 DIRECTOR_PLAN 已明确确定摄影方案时，应在其允许的范围内优化画面重点、动作自然性和视觉表达，不得重新设计已经锁定的摄影方案。",
            "必须区分身体朝向、头部朝向和视线方向，不得把“身体沿人行道前进、低头看手机”改写为“身体面向摄影机、注视镜头”。",
            "如果 DIRECTOR_PLAN 是你在当前任务中自主生成的，最终 prompt 和 videoPrompt 必须与该规划一致。不得在规划阶段选择跟拍或横摇，却在最终提示词中重新写成 STATIC。",
            "如果输入明确将摄影参数标记为未指定、AUTO 或 null，应由导演规划自主决定，不得将其解释为创作者已锁定的 STATIC。",
            "如果输入中明确的用户摄影要求与 DIRECTOR_PLAN 存在冲突，不得自行声称二者一致，也不得擅自覆盖用户要求；应遵守当前任务已有的冲突处理规则及输出契约。",
            "5.【剧情及人物动作保真】",
            "Worker 提供的 scriptContent、人物动作、事件顺序、人物位置关系、对白和剧情结果属于当前分镜事实，不得为了运镜、构图或参考图表现而擅自增删、替换或改变。",
            "可以补充不改变剧情事实的自然动作衔接，例如重心转移、合理的步伐节奏、衣物惯性及持物随动，但不得增加新的剧情事件、人物互动或道具状态变化。",
            "人物动作必须连续、自然，不得把一个完整的行走、靠近、擦肩或离开过程拆成互不衔接的机械姿势。",
            "不得通过过度细碎的逐帧肢体指令控制人物。",
            "对于多人交互，应保持各人物独立的运动方向、相对位置和行动结果，不得为了构图将相向运动改写为同向运动。",
            "人物在画面中的移动方向应与真实空间中的行动路线、摄影机位置及镜头运动保持一致。",
            "当剧情明确要求人物交错后分离时，应体现两人继续沿各自方向行动以及相对距离增大的过程，不得将交错结果改写为并排同行、相互追随或共同离开。",
            "如果镜头时长较短，应合理安排现有剧情事件和运镜节奏，不得擅自延长时长、删减必要事件或添加额外动作。",
            "如果选择局部特写，应确保当前镜头必须完成的剧情事件仍然能够通过可见动作或合理的画面结果得到表达，不得为了特写而遗漏重要剧情。",
            "6.【道具数量、归属与连续性】",
            "Worker 在 scriptContent 及当前任务事实中确定的道具数量、归属、持有方式和状态必须准确保留。",
            "不得为了运镜、构图或动作设计而擅自增加、减少、复制、交换、丢弃道具，或让道具无依据地突然出现或消失。",
            "PROP_CONTEXT 中的文字设定资产即使没有图片，也必须在相关画面中得到正确体现；不得把文字设定资产描述成已有参考图，也不得根据名称臆测数据库没有提供的外观、能力或世界观。",
            "PROP_CONTEXT 中的一条资产记录不必然代表一个道具实例，实际数量应以当前分镜明确描述的数量及已提供的结构化数量信息为准，不得仅凭资产引用数量推断画面中的道具数量。",
            "例如，当前分镜规定雷姆左手一个购物袋、右手一个购物袋，共两个时，视频全过程必须维持这一数量和归属，除非当前剧情明确发生拿起、放下、交接或其他状态变化。",
            "人物双手已被道具占用时，不得为了完成额外动作让同一只手无依据地同时执行冲突操作，也不得擅自安排放下、换手或丢弃等新的剧情事件。",
            "道具可以随人物移动自然摆动，但不得因此改变其数量、归属或已确定的状态。",
            "原文未明确数量时，不得编造精确数量。",
            "局部特写或画面裁切导致部分道具暂时不可见时，必须在场景实际状态中保持其数量与归属，不得将画面外的道具解释为消失，也不得为了保持可见性复制新的道具。",
            "7.【参考媒体与文字身份】",
            "只能引用 REFERENCE_MANIFEST 中实际存在的 Picture、Audio 和 Subject；不得跳号、伪造编号，缺少图片时必须使用文字环境或人物、道具描述，不得声称存在参考图。",
            "每个角色、场景和道具都必须保留清晰的文字身份描述，并与实际参考媒体正确对应；不得交换角色参考图、误用场景参考图或把没有图片的道具描述成有图片参考。",
            "参考图用于保持人物外观、服装、场景和必要的空间关系，不等于授权新增参考图中可能出现的剧情事件。",
            "例如，场景参考图包含便利店自动门，不代表当前镜头必须出现自动门打开的动作。当前剧情没有要求时，不得擅自增加这一事件。",
            "当剧情重点是人物沿人行道移动时，不得仅因场景参考图突出便利店门口，就擅自将镜头重点转移到自动门或店内活动。",
            "参考图不强制当前镜头沿用其原始景别和构图，除非当前任务明确锁定参考图构图或画面要求。",
            "可以在不改变参考资产身份、外观和实际空间关系的前提下，根据当前剧情选择不同的观察角度及构图。",
            "不得为了实现特写、视觉焦点转移或镜头运动而编造参考图中不存在的建筑布局、人物外观细节或资产能力。",
            "不得将参考图中的瞬时人物姿态、表情或视线直接覆盖当前分镜已确定的动作与表情状态。",
            "8.【声音与参考音频】",
            "必须依据参考音频的 usageMode 处理声音：DIALOGUE_REUSE 只能复用当前任务授权的原始音频并保持时序同步；VOICE_TIMBRE 只能借鉴音色、语速和表达方式，不得复制参考音频中的旧台词。英文译文只表达原台词语义，不得冒充参考音频的逐字转录或改变其原声语言。",
            "当前分镜台词必须保留原意、信息、顺序和说话人；非英文台词须忠实译为英文，不得增删、润色或编造台词。",
            "overallSoundscape 与 nonDiegeticMusic 必须作为独立 JSON 字段返回，并与最终提示词中的对应声音内容一致；没有明确事实时按照 OUTPUT_FORMAT 约定使用空值或 N/A，不得凭空补设定。",
            "不得擅自增加当前分镜没有要求的角色对白、旁白、音乐或声音事件。",
            "动作拟音和环境声音必须与当前剧情及声音事实一致。",
            "摄影机运动、特写或视觉焦点转移不得成为新增声音事件的依据。",
            "不得因为画面转向某个道具或环境细节，就擅自增加当前剧情中没有发生的碰撞声、开门声、脚步声或其他音效。",
            "9.【语言与最终输出】",
            "最终 JSON 的所有非空字符串值均使用英文，包括 prompt、videoPrompt、overallSoundscape、nonDiegeticMusic 及台词；不得输出中文。",
            "prompt 与 videoPrompt 必须完全一致，并与当前分镜事实、DIRECTOR_PLAN、参考媒体、道具数量及声音字段保持一致。",
            "如果 prompt 和 videoPrompt 包含摄影机运动、人物空间关系或视觉焦点变化，其描述必须保持完全一致，不得出现不同的摄影方案。",
            "最终只输出当前请求要求的合法 JSON；不得输出工具调用过程、Skill 正文、规则说明或 Markdown 围栏。",
            "【生成前最终自检】",
            "在输出最终 JSON 前，必须在内部完成以下检查，不得额外输出检查过程。",
            "确认 h3-prompt-writing 已成功加载，并且在可用且要求加载 cinematography 时已成功加载。",
            "确认当前镜头具有明确的主要视觉关注点，景别、构图和摄影机运动均服务于当前剧情，而不是机械记录人物动作或无目的地增加复杂运镜。",
            "确认视觉导演方案没有增加、删除或改变 Worker 已确定的剧情事实、人物行为、情绪表情、道具数量及事件结果。",
            "确认人物真实运动方向与摄影机运动相互独立且空间关系正确，特别检查多人相遇、擦肩、追逐和分离等动作是否出现方向混淆。",
            "确认当前镜头能够在规定时长内连续完成，且没有未经授权的硬切、跳切、转场或额外镜头。",
            "确认局部特写、视觉焦点转移及画面裁切没有导致必要剧情丢失、道具数量变化或人物状态不连续。",
            "确认所有 Picture、Audio、Subject 引用均来自 REFERENCE_MANIFEST，角色、场景及道具的参考媒体对应关系正确。",
            "确认 DIALOGUE_REUSE 与 VOICE_TIMBRE 的使用符合当前任务提供的 usageMode，且没有复用未授权的旧台词。",
            "确认声音、台词、参考媒体及所有资产引用均来自当前任务提供的事实。",
            "确认 prompt 与 videoPrompt 完全一致，firstFramePrompt 与 endFramePrompt 均为 null。",
            "确认最终 JSON 的字段名称、字段类型、语言、空值约定及内容与 OUTPUT_FORMAT 完全一致。"
        );

    private static final String DEFAULT_MINIMAX_H3_REF2VA_USER_TEMPLATE = """
            【当前任务：REFERENCE_MODE】
            只处理当前分镜，不得把剧集其他镜头、未提供的资产或推测出的剧情混入本次任务。
            请先执行系统提示词中的 h3-prompt-writing Skill 调用协议，再依据已加载的官方 Skill 和以下结构化事实生成结果。

            【短剧与剧集上下文】
            ${DRAMA_CONTEXT}

            【当前分镜事实】
            ${SHOT_SPEC}

            【导演规划】
            ${DIRECTOR_PLAN}

            【场景、角色与道具事实】
            ${SCENE_CONTEXT}
            ${CHARACTER_CONTEXT}
            ${PROP_CONTEXT}

            【有序参考素材清单】
            ${REFERENCE_MANIFEST}

            【创作者额外要求】
            ${USER_INSTRUCTION}

            【输出】
            按已加载的 h3-prompt-writing 官方规则生成 REFERENCE_MODE 结果，并严格遵守下面的 JSON Schema。
            只返回一个合法 JSON 对象，不要输出 Markdown、解释或任何额外文字。
            ${OUTPUT_FORMAT}
            """;

    private final DramaShotMapper shotMapper;
    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper dramaEpisodeMapper;
    private final DramaSceneMapper sceneMapper;
    private final ResSceneMapper resSceneMapper;
    private final ResCharacterMapper characterMapper;
    private final ResCharacterOutfitMapper outfitMapper;
    private final ResPropMapper propMapper;
    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiModelFactory aiModelFactory;
    private final AIOutputValidationService validationService;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;
    private final DirectorPlanningService directorPlanningService;
    private final DirectorPlanMergeService directorPlanMergeService;
    private final CharacterVisualAssetResolver characterVisualAssetResolver;

    /**
     * 所有提示词生成统一通过缓存型 Skill 上下文服务读取当前启用的标准 Skill。
     * 采用可选注入是为了保留没有 Skill 数据表初始化时的降级能力，以及兼容现有单元测试构造器。
     */
    @Autowired(required = false)
    private SkillPromptContextService skillPromptContextService;

    @Autowired(required = false)
    private LoadSkillToolFactory loadSkillToolFactory;

    private CharacterVisualAssetResolver getCharacterVisualAssetResolver() {
        if (this.characterVisualAssetResolver != null) {
            return this.characterVisualAssetResolver;
        }
        return new CharacterVisualAssetResolverImpl(characterMapper, outfitMapper);
    }

    /**
     * 后台提示词任务持久化依赖。保留可选注入以兼容尚未初始化 ai_task 表的旧环境，
     * 但生产环境必须启用该 Bean 才能获得页面关闭后的结果恢复能力。
     */
    @Autowired(required = false)
    private AiTaskService aiTaskService;

    @Autowired(required = false)
    private ShotPromptEventStore shotPromptEventStore;

    private static final Pattern SENSITIVE_MEDIA_URL_PATTERN = Pattern.compile(
            "https?://[^\\s\"'<>]+(?:\\.(?:png|jpe?g|webp|gif|bmp|svg|tiff|mp3|wav|ogg|aac|flac|m4a|mp4|webm|mov|avi|mkv)|minio|/media/|/assets/|/attachments/|/uploads/)[^\\s\"'<>]*|" +
            "https?://minio[^\\s\"'<>]*",
            Pattern.CASE_INSENSITIVE
    );

    private void validatePromptSecurity(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return;
        }
        for (org.springframework.ai.chat.messages.Message msg : prompt.getInstructions()) {
            if (msg == null) continue;
            String text = msg.getText();
            if (StringUtils.isBlank(text)) continue;
            if (SENSITIVE_MEDIA_URL_PATTERN.matcher(text).find()) {
                log.error("[PromptSecurity] 发现禁止传递的内部媒体资源 URL 企图注入模型上下文: messageType={}", msg.getMessageType());
                throw new BizException(400, "提示词上下文中包含内部媒体资源 URL，已触发安全防御阻断发送至 AI 模型");
            }
        }
    }

    private void validatePromptSecurity(String systemPrompt, String userPrompt) {
        if (StringUtils.isNotBlank(systemPrompt) && SENSITIVE_MEDIA_URL_PATTERN.matcher(systemPrompt).find()) {
            log.error("[PromptSecurity] 发现禁止传递的内部媒体资源 URL 企图注入模型上下文: messageType=SYSTEM");
            throw new BizException(400, "系统提示词中包含内部媒体资源 URL，已触发安全防御阻断发送至 AI 模型");
        }
        if (StringUtils.isNotBlank(userPrompt) && SENSITIVE_MEDIA_URL_PATTERN.matcher(userPrompt).find()) {
            log.error("[PromptSecurity] 发现禁止传递的内部媒体资源 URL 企图注入模型上下文: messageType=USER");
            throw new BizException(400, "用户提示词中包含内部媒体资源 URL，已触发安全防御阻断发送至 AI 模型");
        }
    }

    @Override
    public ShotAiVisualPlanVO generate(Long shotId, ShotAiVisualPlanRequestDTO request) {
        DramaShot shot = requireShot(shotId);
        if (request == null || request.getProviderId() == null || StringUtils.isBlank(request.getModelCode())) {
            throw new BizException("未指定 AI 模型提供商或模型编码 (providerId / modelCode)，请显式选择模型后重试");
        }
        Long providerId = request.getProviderId();
        String modelCode = request.getModelCode();
        validateChatModel(providerId, modelCode);

        ChatModel chatModel = aiModelFactory.getChatModel(providerId, modelCode);
        String format = "{\"firstFramePrompt\":\"...\",\"negativePrompt\":\"...\",\"videoPrompt\":\"...\",\"continuityWarnings\":[]}";
        String systemPrompt = sysConfigService.getConfigValue("ai.prompt.shot_visual_plan_system", DEFAULT_SYSTEM_PROMPT);
        String userPrompt = buildUserPrompt(shot, request != null ? request.getInstruction() : null, format);
        validatePromptSecurity(systemPrompt, userPrompt);
        String raw = invokeApiWithSkills(chatModel, systemPrompt, userPrompt,
                "shot-visual-plan", String.valueOf(shotId), request.getRequiredSkillNames());
        if (StringUtils.isBlank(raw)) {
            throw new BizException("AI 未返回有效的分镜视觉方案");
        }

        ShotAiVisualPlanVO result = validationService.parseAndValidate(raw, ShotAiVisualPlanVO.class);
        if (result == null) {
            throw new BizException("AI 分镜视觉方案为空");
        }
        result.setShotId(shotId);
        if (StringUtils.isBlank(result.getFirstFramePrompt())) {
            throw new BizException("AI 未返回有效的首帧 Prompt");
        }
        result.setControlImages(buildControlImages(shot));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShotAiVisualPlanVO apply(Long shotId, ShotAiVisualPlanApplyDTO request) {
        DramaShot shot = requireShot(shotId);
        if (request == null || StringUtils.isBlank(request.getFirstFramePrompt())) {
            throw new BizException(400, "首帧 Prompt 不能为空");
        }
        shot.setPrompt(request.getFirstFramePrompt().trim());
        shot.setNegativePrompt(StringUtils.trimToNull(request.getNegativePrompt()));
        shot.setVideoPrompt(StringUtils.trimToNull(request.getVideoPrompt()));
        shotMapper.updateById(shot);
        return ShotAiVisualPlanVO.builder()
                .shotId(shotId)
                .firstFramePrompt(shot.getPrompt())
                .negativePrompt(shot.getNegativePrompt())
                .videoPrompt(shot.getVideoPrompt())
                .controlImages(buildControlImages(shot))
                .build();
    }

    private DramaShot requireShot(Long shotId) {
        if (shotId == null) {
            throw new BizException(400, "分镜 ID 不能为空");
        }
        DramaShot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + shotId);
        }
        return shot;
    }

    private void validateChatModel(Long providerId, String modelCode) {
        AiProvider provider = providerMapper.selectById(providerId);
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelCode, modelCode));
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus())
                || model == null || !Integer.valueOf(1).equals(model.getStatus())
                || !"CHAT".equalsIgnoreCase(model.getModelType())) {
            throw new BizException("AI 提供商或聊天模型不存在、已停用或类型不正确");
        }
    }

    private String buildUserPrompt(DramaShot shot, String instruction, String format) {
        Drama drama = shot.getDramaId() != null ? dramaMapper.selectById(shot.getDramaId()) : null;
        DramaScene dramaScene = sceneMapper.selectById(shot.getSceneId());
        Long sceneId = shot.getResSceneId();
        if ((sceneId == null || sceneId <= 0) && dramaScene != null) sceneId = dramaScene.getResSceneId();
        ResScene scene = sceneId != null ? resSceneMapper.selectById(sceneId) : null;

        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> shotData = new LinkedHashMap<>();
        shotData.put("shotNo", shot.getShotNo());
        shotData.put("scriptContent", shot.getScriptContent());
        if (Boolean.TRUE.equals(shot.getShotTypeLocked()) && StringUtils.isNotBlank(shot.getShotType())
                && !"AUTO".equalsIgnoreCase(shot.getShotType())) {
            shotData.put("shotType", shot.getShotType());
            shotData.put("shotTypeConstraint", "CREATOR_LOCKED");
        }
        if (Boolean.TRUE.equals(shot.getCameraMovementLocked()) && StringUtils.isNotBlank(shot.getCameraMovement())
                && !"AUTO".equalsIgnoreCase(shot.getCameraMovement())) {
            shotData.put("cameraMovement", shot.getCameraMovement());
            shotData.put("cameraMovementConstraint", "CREATOR_LOCKED");
        }
        shotData.put("duration", shot.getDuration());
        shotData.put("actionDescription", shot.getActionDescription());
        shotData.put("customScenePrompt", shot.getCustomScenePrompt());
        shotData.put("dialogue", shot.getDialogue());
        shotData.put("stylePreset", StringUtils.firstNonBlank(shot.getStylePreset(), drama != null ? drama.getStylePreset() : null));
        shotData.put("aspectRatio", drama != null ? drama.getAspectRatio() : null);
        context.put("shot", shotData);
        context.put("scene", sceneData(scene));
        context.put("characters", characterData(shot.getCharacterRefsJson()));
        context.put("props", propData(shot.getPropRefsJson()));

        try {
            return "请根据以下事实生成分镜视觉方案。所有人物、场景和道具关系只能依据输入事实，不要补造。\n"
                    + objectMapper.writeValueAsString(context)
                    + "\n创作者补充要求:\n" + StringUtils.defaultString(instruction, "无")
                    + "\n严格输出 JSON，格式参考:\n" + format;
        } catch (Exception e) {
            throw new BizException("构建分镜 AI 上下文失败: " + e.getMessage());
        }
    }

    private Map<String, Object> sceneData(ResScene scene) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (scene == null) return data;
        data.put("id", scene.getId());
        data.put("name", scene.getName());
        data.put("scenePrompt", scene.getScenePrompt());
        data.put("timeOfDay", scene.getTimeOfDay());
        data.put("weatherAtmosphere", scene.getWeatherAtmosphere());
        // 彻底隔离模型输入：严禁向大模型上下文注入场景 referenceImageUrl
        return data;
    }

    private List<Map<String, Object>> characterData(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode root = parseJson(json);
        if (root == null || !root.isArray()) return result;
        for (JsonNode ref : root) {
            Long characterId = longValue(ref, "characterId");
            if (characterId == null || characterId <= 0) continue;
            Long lookId = longValue(ref, "lookId");
            String designDesc = textValue(ref, "designDesc");
            String appearancePrompt = textValue(ref, "appearancePrompt");
            String outfitPrompt = textValue(ref, "outfitPrompt");
            String actionPrompt = textValue(ref, "actionPrompt");
            String emotionPrompt = textValue(ref, "emotionPrompt");
            String positionTag = textValue(ref, "positionTag");

            ResolvedCharacterPromptContext resolved = characterVisualAssetResolver.resolvePromptContext(
                    characterId, lookId, designDesc, appearancePrompt, outfitPrompt, actionPrompt, emotionPrompt, positionTag
            );
            if (resolved == null) continue;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("characterId", resolved.getCharacter().getId());
            data.put("name", resolved.getCharacter().getName());
            if (!resolved.isHasLook() && StringUtils.isNotBlank(resolved.getAppearanceDesc())) {
                data.put("appearanceDesc", resolved.getAppearanceDesc());
            }
            if (StringUtils.isNotBlank(resolved.getDesignDesc())) {
                data.put("designDesc", resolved.getDesignDesc());
            }
            if (StringUtils.isNotBlank(resolved.getAppearancePrompt())) {
                data.put("appearancePrompt", resolved.getAppearancePrompt());
            }
            if (StringUtils.isNotBlank(resolved.getOutfitPrompt())) {
                data.put("outfitPrompt", resolved.getOutfitPrompt());
            }
            if (StringUtils.isNotBlank(resolved.getActionPrompt())) {
                data.put("actionPrompt", resolved.getActionPrompt());
            }
            if (StringUtils.isNotBlank(resolved.getEmotionPrompt())) {
                data.put("emotionPrompt", resolved.getEmotionPrompt());
            }
            if (StringUtils.isNotBlank(resolved.getPositionTag())) {
                data.put("positionTag", resolved.getPositionTag());
            }
            result.add(data);
        }
        return result;
    }

    private List<Map<String, Object>> propData(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode root = parseJson(json);
        if (root == null || !root.isArray()) return result;
        for (JsonNode ref : root) {
            Long propId = longValue(ref, "propId");
            ResProp prop = propId != null ? propMapper.selectById(propId) : null;
            if (prop == null) continue;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("propId", prop.getId());
            data.put("name", prop.getName());
            data.put("propType", prop.getPropType());
            data.put("propPrompt", prop.getPropPrompt());
            result.add(data);
        }
        return result;
    }

    private List<ControlImageVO> buildControlImages(DramaShot shot) {
        List<ControlImageVO> images = new ArrayList<>();
        DramaScene dramaScene = sceneMapper.selectById(shot.getSceneId());
        Long sceneId = shot.getResSceneId();
        if ((sceneId == null || sceneId <= 0) && dramaScene != null) sceneId = dramaScene.getResSceneId();
        ResScene scene = sceneId != null ? resSceneMapper.selectById(sceneId) : null;
        if (scene != null && StringUtils.isNotBlank(scene.getReferenceImageUrl())) {
            images.add(ControlImageVO.builder().controlType("SCENE_REF").imageUrl(scene.getReferenceImageUrl())
                    .weight(new BigDecimal("0.80")).label("场景参考: " + scene.getName()).build());
        }
        JsonNode root = parseJson(shot.getCharacterRefsJson());
        if (root != null && root.isArray()) {
            for (JsonNode ref : root) {
                Long characterId = longValue(ref, "characterId");
                if (characterId == null) continue;
                Long lookId = longValue(ref, "lookId");
                try {
                    com.astra.freyja.dto.res.ResolvedCharacterVisual visual = characterVisualAssetResolver.resolve(characterId, lookId);
                    if (visual != null && StringUtils.isNotBlank(visual.getReferenceImageUrl())) {
                        String label = "COMBINED".equals(visual.getReferenceRole())
                                ? "造型参考: " + visual.getCharacter().getName() + " - " + (visual.getOutfit() != null ? visual.getOutfit().getLookName() : "造型")
                                : "人物参考: " + visual.getCharacter().getName();
                        images.add(ControlImageVO.builder().controlType("CHARACTER_REF").imageUrl(visual.getReferenceImageUrl())
                                .weight(new BigDecimal("0.90")).label(label).build());
                    }
                } catch (Exception e) {
                    log.warn("无法为镜头装配角色参考图: shotId={}, characterId={}, error={}", shot.getId(), characterId, e.getMessage());
                }
            }
        }
        JsonNode props = parseJson(shot.getPropRefsJson());
        if (props != null && props.isArray()) {
            for (JsonNode ref : props) {
                Long propId = longValue(ref, "propId");
                ResProp prop = propId != null ? propMapper.selectById(propId) : null;
                if (prop != null && StringUtils.isNotBlank(prop.getCoverUrl())) {
                    images.add(ControlImageVO.builder().controlType("PROP_REF").imageUrl(prop.getCoverUrl())
                            .weight(new BigDecimal("0.75")).label("道具参考: " + prop.getName()).build());
                }
            }
        }
        return images;
    }

    private Drama populateContext(ShotPromptDeriveDTO dto) {
        Drama drama = null;
        if (dto.getDramaId() != null && dto.getDramaId() > 0) {
            drama = dramaMapper.selectById(dto.getDramaId());
            if (drama != null) {
                if (StringUtils.isBlank(dto.getStylePreset())) {
                    dto.setStylePreset(drama.getStylePreset());
                }
                if (StringUtils.isBlank(dto.getStyleTone())) {
                    dto.setStyleTone(drama.getStyleTone());
                }
            }
        }

        if (dto.getEpisodeId() != null && dto.getEpisodeId() > 0) {
            DramaEpisode episode = dramaEpisodeMapper.selectById(dto.getEpisodeId());
            if (episode != null) {
                if (StringUtils.isBlank(dto.getEpisodeSummary()) && StringUtils.isNotBlank(episode.getSummary())) {
                    dto.setEpisodeSummary(episode.getSummary());
                }
            }
        }

        if (dto.getShotId() != null && dto.getShotId() > 0) {
            DramaShot existingShot = shotMapper.selectById(dto.getShotId());
            if (existingShot != null) {
                if (StringUtils.isBlank(dto.getScriptContent()) && StringUtils.isNotBlank(existingShot.getScriptContent())) {
                    dto.setScriptContent(existingShot.getScriptContent());
                }
                // Older callers may omit the new lock flags. Recover stored values only when both the value
                // and marker are absent; an explicit false from the current UI means intentionally unlocked.
                if (dto.getShotTypeLocked() == null && StringUtils.isBlank(dto.getShotType())) {
                    dto.setShotType(existingShot.getShotType());
                    dto.setShotTypeLocked(existingShot.getShotTypeLocked());
                }
                if (dto.getCameraMovementLocked() == null && StringUtils.isBlank(dto.getCameraMovement())) {
                    dto.setCameraMovement(existingShot.getCameraMovement());
                    dto.setCameraMovementLocked(existingShot.getCameraMovementLocked());
                }
            }
        }
        return drama;
    }

    private String officialH3GuideFingerprint(String systemPrompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(StringUtils.defaultString(systemPrompt).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private SkillPromptContext loadSkillContext(String consumer, String requestId, List<String> selectedSkillNames) {
        if (skillPromptContextService == null) {
            return SkillPromptContext.empty();
        }
        return skillPromptContextService.loadSelected(consumer, requestId, selectedSkillNames);
    }

    private String appendSkillContext(String systemPrompt, SkillPromptContext skillContext) {
        if (skillPromptContextService == null) {
            return StringUtils.defaultString(systemPrompt);
        }
        return skillPromptContextService.appendToSystemPrompt(systemPrompt, skillContext);
    }

    private String appendCameraDutyBoundary(String systemPrompt) {
        String prompt = StringUtils.defaultString(systemPrompt);
        String boundary = CAMERA_DUTY_BOUNDARY.trim();
        if (prompt.contains(boundary)) {
            prompt = prompt.replace(boundary, "").trim();
        }
        return prompt + "\n\n" + CAMERA_DUTY_BOUNDARY;
    }

    private String invokeApiWithSkills(ChatModel chatModel, String systemPrompt, String userPrompt,
                                       String consumer, String requestId, List<String> requiredSkillNames) {
        return invokeApiWithSkills(chatModel, systemPrompt, userPrompt, consumer, requestId, requiredSkillNames, null, null);
    }

    private String invokeApiWithSkills(ChatModel chatModel, String systemPrompt, String userPrompt,
                                       String consumer, String requestId, List<String> requiredSkillNames,
                                       java.util.function.Consumer<String> stageListener,
                                       java.util.function.Consumer<String> chunkListener) {
        if (skillPromptContextService == null || loadSkillToolFactory == null) {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(appendCameraDutyBoundary(systemPrompt)), new UserMessage(userPrompt))));
            return response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
        }
        LoadSkillToolSession session = skillPromptContextService.createApiSession(consumer, requestId, requiredSkillNames);
        SkillPromptContext requiredContext = skillPromptContextService.loadSelected(
                consumer, requestId, requiredSkillNames, session.getVersionSnapshot());
        String apiSystemPrompt = appendSkillContext(systemPrompt, requiredContext);
        apiSystemPrompt = skillPromptContextService.appendCatalogToSystemPrompt(apiSystemPrompt);
        apiSystemPrompt = appendCameraDutyBoundary(apiSystemPrompt);
        ToolCallback tool = loadSkillToolFactory.createTool(session, stageListener);
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);
        ChatOptions defaultOptions = chatModel.getOptions();
        if (defaultOptions != null) {
            clientBuilder.defaultOptions(defaultOptions.mutate());
        }
        ChatClient client = clientBuilder.build();
        String content;
        if (chunkListener != null) {
            StringBuilder accumulated = new StringBuilder();
            client.prompt().system(apiSystemPrompt).user(userPrompt).tools(tool)
                    .stream().content().toIterable().forEach(chunk -> {
                        if (StringUtils.isNotEmpty(chunk)) {
                            accumulated.append(chunk);
                            chunkListener.accept(chunk);
                        }
                    });
            content = accumulated.toString();
        } else {
            content = client.prompt().system(apiSystemPrompt).user(userPrompt).tools(tool).call().content();
        }
        log.info("[SkillPrompt] consumer={}, requestId={}, apiLoadedSkills={}, toolCalls={}",
                consumer, requestId, session.getLoadedSkillNames(), session.getInvocationHistory());
        if (StringUtils.startsWith(consumer, "shot-h3-")
                && session.getLoadedSkillNames().stream().noneMatch("h3-prompt-writing"::equals)) {
            log.warn("[SkillPrompt] H3 提示词模型未成功加载 h3-prompt-writing: consumer={}, requestId={}, toolCalls={}",
                    consumer, requestId, session.getInvocationHistory());
        }
        return content;
    }

    private String calculateFingerprint(String genMode, ShotPromptDeriveDTO dto, ReferenceManifest manifest,
                                       String templateVersion, String systemPrompt, String userTemplate,
                                       String localSkillFingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(StringUtils.defaultString(genMode)).append("|");
            sb.append(StringUtils.defaultString(templateVersion)).append("|");
            sb.append("h3-system=").append(officialH3GuideFingerprint(systemPrompt)).append("|");
            sb.append("h3-user=").append(officialH3GuideFingerprint(userTemplate)).append("|");
            sb.append("local-skills=").append(StringUtils.defaultString(localSkillFingerprint)).append("|");
            sb.append(dto.getShotId() != null ? dto.getShotId() : "").append("|");
            sb.append(StringUtils.defaultString(dto.getScriptContent())).append("|");
            sb.append(StringUtils.defaultString(dto.getActionDescription())).append("|");
            sb.append(StringUtils.defaultString(dto.getDialogue())).append("|");
            sb.append(StringUtils.defaultString(dto.getDialogueSpeaker())).append("|");
            sb.append(StringUtils.defaultString(dto.getStylePreset())).append("|");
            sb.append(StringUtils.defaultString(dto.getStyleTone())).append("|");
            sb.append(StringUtils.defaultString(dto.getUserInstruction())).append("|");
            sb.append(Boolean.TRUE.equals(dto.getIncludeBgm()) ? "BGM_ON" : "BGM_OFF").append("|");
            if (manifest != null) {
                sb.append(manifest.toPromptContext());
            }
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex.substring(0, 16);
        } catch (Exception e) {
            return "sha256:unknown";
        }
    }

    public ShotPromptDeriveVO cleanAndParseJson(String rawText) {
        if (StringUtils.isBlank(rawText)) {
            return null;
        }
        String text = rawText.trim();

        // 1. 如果包含 Markdown 代码块标记，优先提取代码块内部内容
        if (text.contains("```")) {
            int firstFence = text.indexOf("```");
            int secondFence = text.indexOf("```", firstFence + 3);
            if (secondFence > firstFence) {
                String block = text.substring(firstFence + 3, secondFence).trim();
                if (block.toLowerCase().startsWith("json")) {
                    block = block.substring(4).trim();
                }
                text = block;
            }
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 2. 尝试直接反序列化
        try {
            return mapper.readValue(text, ShotPromptDeriveVO.class);
        } catch (Exception e) {
            log.debug("[cleanAndParseJson] 直接反序列化未成功: {}", e.getMessage());
        }

        // 3. 截取最外层 { ... }
        int startIdx = text.indexOf('{');
        int endIdx = text.lastIndexOf('}');
        if (startIdx >= 0 && endIdx > startIdx) {
            String jsonBlock = text.substring(startIdx, endIdx + 1);
            try {
                return mapper.readValue(jsonBlock, ShotPromptDeriveVO.class);
            } catch (Exception e) {
                log.warn("[cleanAndParseJson] 截取 JSON 块反序列化失败: {}", e.getMessage());
            }
        }

        return null;
    }

    public ShotPromptValidationResult validateAndNormalize(
            ShotPromptDeriveVO vo,
            String genMode,
            ReferenceManifest manifest,
            String dialogue,
            Boolean fingerprintMatched
    ) {
        return validateAndNormalize(vo, genMode, manifest, dialogue, fingerprintMatched, Boolean.FALSE);
    }

    public ShotPromptValidationResult validateAndNormalize(
            ShotPromptDeriveVO vo,
            String genMode,
            ReferenceManifest manifest,
            String dialogue,
            Boolean fingerprintMatched,
            Boolean includeBgm
    ) {
        return validateAndNormalize(vo, genMode, manifest, dialogue, fingerprintMatched, includeBgm, null);
    }

    public ShotPromptValidationResult validateAndNormalize(
            ShotPromptDeriveVO vo,
            String genMode,
            ReferenceManifest manifest,
            String dialogue,
            Boolean fingerprintMatched,
            Boolean includeBgm,
            Double duration
    ) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (vo == null) {
            errors.add("未能从返回文本中解析出合法的 JSON 提示词结构，请检查格式后重试");
            return ShotPromptValidationResult.builder()
                    .result(null)
                    .errors(errors)
                    .warnings(warnings)
                    .fingerprintMatched(fingerprintMatched)
                    .build();
        }

        // 2. 模式特定校验与归一化
        if ("REFERENCE_MODE".equalsIgnoreCase(genMode)) {
            if (StringUtils.isBlank(vo.getPrompt())) {
                vo.setPrompt(vo.getVideoPrompt());
            }
            if (StringUtils.isBlank(vo.getPrompt())) {
                errors.add("参考图模式未生成有效的视频动态提示词 (prompt/videoPrompt)");
            }

            // 强制归一化：首尾帧提示词置为 null
            if (StringUtils.isNotBlank(vo.getFirstFramePrompt()) || StringUtils.isNotBlank(vo.getEndFramePrompt())) {
                log.info("[validateAndNormalize] REFERENCE_MODE 下将首尾帧提示词强制归一化置空");
                vo.setFirstFramePrompt(null);
                vo.setEndFramePrompt(null);
            }

            String p = vo.getPrompt() != null ? vo.getPrompt() : "";

            // 六段式段落检查 (警告级别)
            String[] requiredSections = {
                    "subject_definitions:", "summary:", "retention_analysis:",
                    "detailed_description:", "overall_soundscape:", "non_diegetic_music:"
            };
            List<String> missing = new ArrayList<>();
            for (String sec : requiredSections) {
                if (!p.contains(sec)) {
                    missing.add(sec);
                }
            }
            if (!missing.isEmpty()) {
                warnings.add("提示词缺少 MiniMax H3 官方必填段落: " + String.join(", ", missing));
            }

            // Picture 标签边界与合法性 (硬性错误)
            int maxPic = manifest != null && manifest.getPictures() != null ? manifest.getPictures().size() : 0;
            Matcher picMatcher = Pattern.compile("<Picture\\s*(\\d+)>").matcher(p);
            while (picMatcher.find()) {
                int idx = Integer.parseInt(picMatcher.group(1));
                if (idx < 1 || idx > maxPic) {
                    errors.add(String.format("提示词中引用了未分配或超出上限的素材 <Picture %d> (有效上限: %d)", idx, maxPic));
                }
            }
            if (maxPic > 0 && !p.contains("<Picture")) {
                warnings.add(String.format("当前分镜配置了 %d 张参考图，但生成的提示词中未引用任何 <Picture N> 标签", maxPic));
            }

            // Audio 标签边界与合法性 (硬性错误)
            int maxAud = manifest != null && manifest.getAudios() != null ? manifest.getAudios().size() : 0;
            Matcher audMatcher = Pattern.compile("<Audio\\s*(\\d+)>").matcher(p);
            while (audMatcher.find()) {
                int idx = Integer.parseInt(audMatcher.group(1));
                if (idx < 1 || idx > maxAud) {
                    errors.add(String.format("提示词中引用了未分配或超出上限的音频 <Audio %d> (有效上限: %d)", idx, maxAud));
                }
            }

            // 对白台词检查 (警告级别)
            if (StringUtils.isNotBlank(dialogue)) {
                if (!p.contains("<d>") || !p.contains("</d>")) {
                    warnings.add("当前分镜包含对白台词，但提示词中未检测到 <d>[Language] ...</d> 标签包裹");
                }
            }
        } else {
            // FIRST_LAST_FRAME 模式
            if (StringUtils.isBlank(vo.getVideoPrompt())) {
                vo.setVideoPrompt(vo.getPrompt()); // 兼容旧版外部 AI JSON
            }
            if (StringUtils.isBlank(vo.getFirstFramePrompt())) {
                errors.add("首尾帧模式未能生成有效的首帧生图提示词 (firstFramePrompt)");
            }
            if (StringUtils.isBlank(vo.getEndFramePrompt())) {
                errors.add("首尾帧模式未能生成有效的尾帧生图提示词 (endFramePrompt)");
            }
            if (StringUtils.isBlank(vo.getVideoPrompt())) {
                errors.add("首尾帧模式未能生成有效的 H3 视频提示词 (videoPrompt)");
            } else {
                validateH3Fl2VaPrompt(vo.getVideoPrompt(), Boolean.TRUE.equals(includeBgm), duration, errors);
            }
            for (String framePrompt : List.of(StringUtils.defaultString(vo.getFirstFramePrompt()),
                    StringUtils.defaultString(vo.getEndFramePrompt()))) {
                if (Pattern.compile("\\p{IsHan}").matcher(framePrompt).find()) {
                    errors.add("首尾帧生图提示词必须使用英文，不得混入中文");
                    break;
                }
            }
            // 本模式只有三个 AI 回填字段。负向词与旧版主提示词由其他流程独立维护。
            vo.setPrompt(null);
            vo.setNegativePrompt(null);
            vo.setOverallSoundscape(null);
            vo.setNonDiegeticMusic(null);
        }

        // 2.5 BGM 业务约束：结构化字段是唯一权威来源，禁止从自由文本中猜测或修补配乐。
        boolean bgmEnabled = Boolean.TRUE.equals(includeBgm);
        String structuredMusic = StringUtils.trimToNull(vo.getNonDiegeticMusic());
        if (!bgmEnabled && "REFERENCE_MODE".equalsIgnoreCase(genMode)) {
            if (structuredMusic != null && !isNoMusicValue(structuredMusic)) {
                warnings.add("分镜已禁用背景配乐 (BGM)，已忽略 AI 返回的结构化配乐设计");
            }
            vo.setNonDiegeticMusic("N/A");

        } else if (structuredMusic != null) {
            vo.setNonDiegeticMusic(structuredMusic);
        }

        // 旧参考图模式仍同步两个别名；首尾帧只保留 videoPrompt。
        if ("REFERENCE_MODE".equalsIgnoreCase(genMode)) {
            if (StringUtils.isNotBlank(vo.getPrompt())) {
                vo.setVideoPrompt(vo.getPrompt());
            } else if (StringUtils.isNotBlank(vo.getVideoPrompt())) {
                vo.setPrompt(vo.getVideoPrompt());
            }
        }

        // 3. 上下文指纹比对警告
        if (Boolean.FALSE.equals(fingerprintMatched)) {
            warnings.add("分镜上下文指纹不一致，镜头设定在任务导出后可能已被修改，建议仔细核对画面内容或重新生成任务 Prompt");
        }

        return ShotPromptValidationResult.builder()
                .result(vo)
                .errors(errors)
                .warnings(warnings)
                .fingerprintMatched(fingerprintMatched)
                .build();
    }

    private boolean isNoMusicValue(String value) {
        String normalized = StringUtils.trimToEmpty(value)
                .replaceAll("[。.!！]+$", "")
                .trim();
        return normalized.equalsIgnoreCase("N/A")
                || normalized.equalsIgnoreCase("none")
                || normalized.equalsIgnoreCase("null")
                || normalized.equalsIgnoreCase("not applicable");
    }

    private String formatDirectorPlan(DirectorPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n【结构化导演机位与运镜设计 (DIRECTOR_PLAN - 最高执行优先级)】:\n");
        if (StringUtils.isNotBlank(plan.getNarrativeIntent())) {
            sb.append("- 叙事意图与戏剧目标 (narrativeIntent): ").append(plan.getNarrativeIntent()).append("\n");
        }
        if (StringUtils.isNotBlank(plan.getShotSize())) {
            sb.append("- 基础主景别 (shotSize): ").append(plan.getShotSize()).append("\n");
        }
        if (StringUtils.isNotBlank(plan.getCameraAngle())) {
            sb.append("- 选定机位与角度 (cameraAngle): ").append(plan.getCameraAngle()).append("\n");
        }
        if (StringUtils.isNotBlank(plan.getSubjectAction())) {
            sb.append("- 主体动作演进描述 (subjectAction): ").append(plan.getSubjectAction()).append("\n");
        }
        if (StringUtils.isNotBlank(plan.getGaze())) {
            sb.append("- 角色视线动向 (gaze): ").append(plan.getGaze()).append("\n");
        }
        if (plan.getCameraBeats() != null && !plan.getCameraBeats().isEmpty()) {
            sb.append("- 摄影机动作时间轴 (Camera Beats):\n");
            for (CameraBeat beat : plan.getCameraBeats()) {
                sb.append(String.format("  * [%.2fs - %.2fs] 运镜: %s | 方向: %s | 速度: %s | 起始节点: %s | 收束节点: %s | 戏剧目的: %s\n",
                        beat.getStartSec() != null ? beat.getStartSec() : BigDecimal.ZERO,
                        beat.getEndSec() != null ? beat.getEndSec() : BigDecimal.ZERO,
                        StringUtils.defaultIfBlank(beat.getMovement(), "未指定"),
                        StringUtils.defaultIfBlank(beat.getDirection(), "-"),
                        StringUtils.defaultIfBlank(beat.getSpeed(), "-"),
                        StringUtils.defaultIfBlank(beat.getStartCue(), "-"),
                        StringUtils.defaultIfBlank(beat.getStopCue(), "-"),
                        StringUtils.defaultIfBlank(beat.getNarrativePurpose(), "-")));
            }
        }
        sb.append("- ★【执行约束】: 提示词中涉及的机位角度、运镜动作、景别变化必须与上述 Beats 严格对应，禁止随意添加或更改。\n");
        return sb.toString();
    }

    @Override
    public ShotPromptPackageVO buildPromptPackage(ShotPromptDeriveDTO dto) {
        return buildPromptPackage(dto, null);
    }

    @Override
    public ShotPromptPackageVO buildPromptPackage(ShotPromptDeriveDTO dto, DirectorPlan directorPlan) {
        if (dto == null) {
            throw new BizException("请求参数不能为空");
        }

        // 1. 自动补全短剧与剧集背景上下文
        Drama drama = populateContext(dto);

        // 2. 检查必要参数
        if (StringUtils.isBlank(dto.getScriptContent()) && StringUtils.isBlank(dto.getActionDescription()) && StringUtils.isBlank(dto.getDialogue()) && StringUtils.isBlank(dto.getUserInstruction())) {
            throw new BizException("镜头剧本文本、画面动作描述、台词或创作者要求至少填写一项");
        }

        // 3. 构建 ReferenceManifest 并选择 MiniMax H3 专属提示词模板 (FL2VA / Ref2VA)
        ReferenceManifest manifest = buildReferenceManifest(dto);
        String genMode = StringUtils.defaultIfBlank(dto.getGenerationMode(), "FIRST_LAST_FRAME");

        String outputFormat = "REFERENCE_MODE".equalsIgnoreCase(genMode)
                ? new BeanOutputConverter<>(ShotPromptDeriveVO.class).getFormat()
                : new BeanOutputConverter<>(H3Fl2VaPromptOutput.class).getFormat();

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("DRAMA_CONTEXT", buildDramaContext(drama, dto));
        vars.put("SHOT_SPEC", buildShotSpec(dto, genMode));
        vars.put("DIRECTOR_PLAN", formatDirectorPlan(directorPlan));
        vars.put("SCENE_CONTEXT", buildSceneContext(dto));
        vars.put("CHARACTER_CONTEXT", buildCharacterContext(dto));
        vars.put("PROP_CONTEXT", buildPropContext(dto));
        vars.put("REFERENCE_MANIFEST", manifest.toPromptContext());
        vars.put("USER_INSTRUCTION", buildUserInstruction(dto));
        vars.put("OUTPUT_FORMAT", outputFormat);

        String systemPrompt;
        String userTemplate;
        String templateVersion;

        if ("REFERENCE_MODE".equalsIgnoreCase(genMode)) {
            systemPrompt = sysConfigService.getConfigValue("ai.prompt.minimax_h3_ref2va_system", DEFAULT_MINIMAX_H3_REF2VA_SYSTEM_PROMPT);
            userTemplate = sysConfigService.getConfigValue("ai.prompt.minimax_h3_ref2va_user", DEFAULT_MINIMAX_H3_REF2VA_USER_TEMPLATE);
            templateVersion = "minimax-h3-ref2va-v1";
        } else {
            systemPrompt = sysConfigService.getConfigValue("ai.prompt.minimax_h3_fl2va_system", DEFAULT_MINIMAX_H3_FL2VA_SYSTEM_PROMPT);
            userTemplate = sysConfigService.getConfigValue("ai.prompt.minimax_h3_fl2va_user", DEFAULT_MINIMAX_H3_FL2VA_USER_TEMPLATE);
            templateVersion = "minimax-h3-fl2va-v1";
        }

        // MANUAL 模式只展开用户明确选择的 Skill；API 模式在实际调用处通过
        // ChatClient + load_skill 按需加载，不在复制 Prompt 中预先展开 Skill 目录。
        SkillPromptContext skillContext = loadSkillContext("shot-h3-" + genMode, String.valueOf(dto.getShotId()), dto.getSelectedSkillNames());
        systemPrompt = appendSkillContext(systemPrompt, skillContext);
        systemPrompt = appendCameraDutyBoundary(systemPrompt);
        systemPrompt = appendFl2VaOutputContract(systemPrompt, genMode, dto.getDuration());

        String userPrompt = resolveTemplate(userTemplate, vars);
        validatePromptSecurity(systemPrompt, userPrompt);

        // 组装专供外部 AI 聊天窗口单输入框的 combinedPrompt
        String combinedPrompt = String.format("""
                你必须同时遵守以下 SYSTEM INSTRUCTIONS 和 USER TASK。

                ================ SYSTEM INSTRUCTIONS ================
                %s

                ================ USER TASK ================
                %s

                ================ RESPONSE REQUIREMENT ================
                只返回一个合法 JSON 对象。
                不要添加 Markdown 代码块、解释、前言或结语。
                """, systemPrompt.trim(), userPrompt.trim());

        // 生成上下文指纹
        String contextFingerprint = calculateFingerprint(genMode, dto, manifest, templateVersion,
                systemPrompt, userTemplate, skillContext.fingerprint());

        return ShotPromptPackageVO.builder()
                .generationMode(genMode)
                .systemPrompt(systemPrompt.trim())
                .userPrompt(userPrompt.trim())
                .combinedPrompt(combinedPrompt.trim())
                .outputFormat(outputFormat)
                .referenceManifest(manifest)
                .templateVersion(templateVersion)
                .contextFingerprint(contextFingerprint)
                .build();
    }

    @Override
    public ShotPromptValidationResult parseAndValidateDerivedPrompt(ShotPromptParseRequestDTO request) {
        if (request == null || StringUtils.isBlank(request.getRawResponse())) {
            List<String> errors = new ArrayList<>();
            errors.add("待解析的外部 AI 结果文本不能为空");
            return ShotPromptValidationResult.builder().errors(errors).build();
        }

        String genMode = StringUtils.defaultIfBlank(request.getGenerationMode(), "FIRST_LAST_FRAME");

        // 1. 构建与比对参考清单 ReferenceManifest
        ShotPromptDeriveDTO contextDto = ShotPromptDeriveDTO.builder()
                .shotId(request.getShotId())
                .dramaId(request.getDramaId())
                .episodeId(request.getEpisodeId())
                .sceneId(request.getSceneId())
                .shotNo(request.getShotNo())
                .generationMode(genMode)
                .includeBgm(request.getIncludeBgm())
                .dialogue(request.getDialogue())
                .characterRefs(request.getCharacterRefs())
                .propRefs(request.getPropRefs())
                .refImages(request.getRefImages())
                .refAudios(request.getRefAudios())
                .selectedSkillNames(request.getSelectedSkillNames())
                .build();

        populateContext(contextDto);
        ReferenceManifest manifest = buildReferenceManifest(contextDto);

        String templateVersion = "REFERENCE_MODE".equalsIgnoreCase(genMode)
                ? "minimax-h3-ref2va-v1"
                : "minimax-h3-fl2va-v1";
        String currentSystemPrompt = "REFERENCE_MODE".equalsIgnoreCase(genMode)
                ? sysConfigService.getConfigValue("ai.prompt.minimax_h3_ref2va_system", DEFAULT_MINIMAX_H3_REF2VA_SYSTEM_PROMPT)
                : sysConfigService.getConfigValue("ai.prompt.minimax_h3_fl2va_system", DEFAULT_MINIMAX_H3_FL2VA_SYSTEM_PROMPT);
        SkillPromptContext skillContext = loadSkillContext("shot-h3-" + genMode, String.valueOf(request.getShotId()), request.getSelectedSkillNames());
        currentSystemPrompt = appendSkillContext(currentSystemPrompt, skillContext);
        currentSystemPrompt = appendCameraDutyBoundary(currentSystemPrompt);
        currentSystemPrompt = appendFl2VaOutputContract(currentSystemPrompt, genMode, contextDto.getDuration());
        String currentUserTemplate = "REFERENCE_MODE".equalsIgnoreCase(genMode)
                ? sysConfigService.getConfigValue("ai.prompt.minimax_h3_ref2va_user", DEFAULT_MINIMAX_H3_REF2VA_USER_TEMPLATE)
                : sysConfigService.getConfigValue("ai.prompt.minimax_h3_fl2va_user", DEFAULT_MINIMAX_H3_FL2VA_USER_TEMPLATE);
        String currentFingerprint = calculateFingerprint(genMode, contextDto, manifest, templateVersion,
                currentSystemPrompt, currentUserTemplate, skillContext.fingerprint());

        Boolean fingerprintMatched = null;
        if (StringUtils.isNotBlank(request.getContextFingerprint())) {
            fingerprintMatched = currentFingerprint.equalsIgnoreCase(request.getContextFingerprint().trim());
        }

        // 2. 清洗并解析 JSON
        ShotPromptDeriveVO parsedVo = cleanAndParseJson(request.getRawResponse());

        // 3. 执行统一校验与归一化
        return validateAndNormalize(parsedVo, genMode, manifest, contextDto.getDialogue(), fingerprintMatched,
                request.getIncludeBgm(), contextDto.getDuration());
    }

    @Override
    public SseEmitter derivePromptStream(ShotPromptDeriveDTO dto) {
        SseEmitter emitter = new SseEmitter(180_000L);
        ConcurrentSseBridge sseBridge = new ConcurrentSseBridge(emitter);

        AiTask task = createPromptTask(dto);
        if (task != null && task.getId() != null) {
            if (shotPromptEventStore != null) {
                try { shotPromptEventStore.append(String.valueOf(task.getId()), "task_created", String.valueOf(task.getId())); }
                catch (Exception e) { log.warn("旧 SSE 任务创建事件缓存失败: {}", e.getMessage()); }
            }
            sseBridge.sendTaskCreated(task.getId(), dto != null ? dto.getShotId() : null);
        }

        Thread.ofVirtual().start(() -> runPromptTask(dto, task, sseBridge));
        return emitter;
    }

    @Override
    public String startPromptTask(ShotPromptDeriveDTO dto) {
        if (shotPromptEventStore == null) throw new BizException("提示词事件缓存不可用");
        AiTask task = createPromptTask(dto);
        if (task == null || task.getId() == null) throw new BizException("提示词任务持久化失败，请检查数据库");
        String taskId = String.valueOf(task.getId());
        try {
            shotPromptEventStore.append(taskId, "task_created", taskId);
        } catch (Exception e) {
            markPromptTaskFailed(task.getId(), "提示词事件缓存写入失败");
            throw new BizException("提示词事件缓存写入失败，请稍后重试");
        }
        Thread.ofVirtual().start(() -> runPromptTask(dto, task, null));
        return taskId;
    }

    private void publishPromptEvent(Long taskId, ConcurrentSseBridge bridge, String type, String data) {
        if (taskId != null && shotPromptEventStore != null) {
            try { shotPromptEventStore.append(String.valueOf(taskId), type, data); }
            catch (Exception e) { log.warn("提示词事件缓存写入失败: taskId={}, type={}, err={}", taskId, type, e.getMessage()); }
        }
        if (bridge == null) return;
        switch (type) {
            case "chunk", "stage" -> bridge.sendChunk(data);
            case "result" -> bridge.sendResult(data);
            case "error" -> bridge.sendError(data);
            case "done" -> bridge.complete();
            default -> { }
        }
    }

    private void runPromptTask(ShotPromptDeriveDTO dto, AiTask task, ConcurrentSseBridge sseBridge) {
            Long taskId = task != null ? task.getId() : null;
            try {
                markPromptTaskRunning(taskId);
                // 1. 模型校验与提供商选择
                Long providerId = dto.getProviderId();
                String modelCode = dto.getModelCode();

                if (providerId == null || StringUtils.isBlank(modelCode)) {
                    markPromptTaskFailed(taskId, "未指定 AI 模型提供商或模型编码 (providerId / modelCode)，请显式选择模型后重试");
                    publishPromptEvent(taskId, sseBridge, "error", "未指定 AI 模型提供商或模型编码 (providerId / modelCode)，请显式选择模型后重试");
                    return;
                }
                validateChatModel(providerId, modelCode);

                // 1.5 导演决策规划 (Director Planning)
                publishPromptEvent(taskId, sseBridge, "stage", "正在规划分镜导演方案…\n");
                DirectorPlan directorPlan = null;
                try {
                    // Hydrate persisted script and explicitly locked values before DirectorPlanningService sees the request.
                    populateContext(dto);
                    directorPlan = directorPlanningService.plan(dto);
                } catch (Exception ex) {
                    log.warn("[ShotPromptDeriveStream] 导演决策层规划异常，将降级为常规提示词衍生: {}", ex.getMessage());
                }

                // 2. 统一构建 ShotPromptPackageVO (挂载 DirectorPlan)
                ShotPromptPackageVO packageVO;
                try {
                    packageVO = buildPromptPackage(dto, directorPlan);
                } catch (BizException bizEx) {
                    markPromptTaskFailed(taskId, bizEx.getMessage());
                    publishPromptEvent(taskId, sseBridge, "error", bizEx.getMessage());
                    return;
                }

                ChatModel chatModel = aiModelFactory.getChatModel(providerId, modelCode);

                validatePromptSecurity(packageVO.getSystemPrompt(), packageVO.getUserPrompt());

                // ChatClient 管理 Tool Calling 循环；正文片元和 Skill 阶段事件写入任务日志并实时推送。
                publishPromptEvent(taskId, sseBridge, "stage", "正在调用模型并按需加载 Skill…\n");
                java.util.concurrent.atomic.AtomicBoolean streamed = new java.util.concurrent.atomic.AtomicBoolean();
                String rawText = invokeApiWithSkills(chatModel, packageVO.getSystemPrompt(), packageVO.getUserPrompt(),
                        "shot-h3-" + packageVO.getGenerationMode(), String.valueOf(dto.getShotId()), dto.getRequiredSkillNames(),
                        stage -> publishPromptEvent(taskId, sseBridge, "stage", stage),
                        chunk -> {
                            streamed.set(true);
                            publishPromptEvent(taskId, sseBridge, "chunk", chunk);
                        });
                if (!streamed.get() && StringUtils.isNotBlank(rawText)) {
                    publishPromptEvent(taskId, sseBridge, "chunk", rawText);
                }

                // 4. 统一清洗、解析与校验
                rawText = StringUtils.defaultString(rawText).trim();
                ShotPromptDeriveVO vo = cleanAndParseJson(rawText);
                if (vo != null && directorPlan != null) {
                    vo.setDirectorPlan(directorPlan);
                }
                ShotPromptValidationResult validationResult = validateAndNormalize(
                        vo,
                        packageVO.getGenerationMode(),
                        packageVO.getReferenceManifest(),
                        dto.getDialogue(),
                        true,
                        dto.getIncludeBgm(),
                        dto.getDuration()
                );

                if (validationResult.hasErrors()) {
                    markPromptTaskFailed(taskId, String.join("; ", validationResult.getErrors()));
                    publishPromptEvent(taskId, sseBridge, "error", String.join("; ", validationResult.getErrors()));
                    return;
                }

                // 若指定了 shotId，将 DirectorPlan 合并更新回分镜实体
                if (dto.getShotId() != null && dto.getShotId() > 0 && directorPlan != null) {
                    try {
                        DramaShot shot = shotMapper.selectById(dto.getShotId());
                        if (shot != null) {
                            directorPlanMergeService.applyPlanToShot(shot, directorPlan);
                            shotMapper.updateById(shot);
                        }
                    } catch (Exception ex) {
                        log.warn("[ShotPromptDeriveStream] 回填 DirectorPlan 到分镜异常: {}", ex.getMessage());
                    }
                }

                String resultJson = objectMapper.writeValueAsString(validationResult.getResult());
                markPromptTaskSuccess(taskId, resultJson);
                publishPromptEvent(taskId, sseBridge, "result", resultJson);
                publishPromptEvent(taskId, sseBridge, "done", "[DONE]");
            } catch (Exception e) {
                log.error("[ShotPromptDeriveStream] 流式衍生分镜提示词异常: {}", e.getMessage(), e);
                markPromptTaskFailed(taskId, "流式生成分镜提示词异常: " + e.getMessage());
                try { publishPromptEvent(taskId, sseBridge, "error", "流式生成分镜提示词异常: " + e.getMessage()); }
                catch (Exception journalFailure) { log.error("提示词错误事件写入失败", journalFailure); }
            }
    }

    private AiTask createPromptTask(ShotPromptDeriveDTO dto) {
        if (aiTaskService == null || dto == null) return null;
        try {
            String inputPayload = objectMapper.writeValueAsString(dto);
            AiTask task = aiTaskService.createTask(
                    dto.getDramaId(),
                    dto.getEpisodeId(),
                    AiTaskType.SHOT_PROMPT_DERIVE,
                    dto.getShotId() != null ? String.valueOf(dto.getShotId()) : null,
                    null,
                    inputPayload,
                    dto.getModelCode(),
                    null
            );
            // AiTaskService 对旧流水线保留了“写库失败后降级为内存 ID”的兼容行为。
            // 提示词后台任务不能接受伪 ID，否则前端会永久轮询一个不存在的任务。
            if (task == null || task.getId() == null || aiTaskService.getTaskById(task.getId()) == null) {
                log.warn("[ShotPromptTask] 任务未成功持久化，不向前端发布可恢复的 taskId");
                return null;
            }
            return task;
        } catch (Exception e) {
            log.warn("[ShotPromptTask] 创建持久化任务失败，将继续执行当前 SSE: {}", e.getMessage());
            return null;
        }
    }

    private void markPromptTaskRunning(Long taskId) {
        if (taskId != null && aiTaskService != null) aiTaskService.markRunning(taskId);
    }

    private void markPromptTaskSuccess(Long taskId, String resultJson) {
        if (taskId != null && aiTaskService != null) aiTaskService.markSuccess(taskId, resultJson, null);
    }

    private void markPromptTaskFailed(Long taskId, String message) {
        if (taskId != null && aiTaskService != null) aiTaskService.markFailed(taskId, message);
    }

    private String buildDramaContext(Drama drama, ShotPromptDeriveDTO dto) {
        StringBuilder sb = new StringBuilder();
        if (drama != null && StringUtils.isNotBlank(drama.getTitle())) {
            sb.append("【所属短剧】: ").append(drama.getTitle()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getStylePreset())) {
            sb.append("【短剧全局画风/题材预设 (stylePreset)】: ").append(dto.getStylePreset()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getStyleTone())) {
            sb.append("【短剧视觉风格基调与导演指南 (styleTone，必须深度融入光影与摄影质感)】: ")
                    .append(dto.getStyleTone()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getEpisodeSummary())) {
            sb.append("【当前剧集剧情大纲/上下文背景 (Episode Summary)】: ").append(dto.getEpisodeSummary()).append("\n");
        }
        if (StringUtils.isBlank(dto.getScriptContent()) && dto.getEpisodeId() != null && dto.getEpisodeId() > 0) {
            DramaEpisode episode = dramaEpisodeMapper.selectById(dto.getEpisodeId());
            if (episode != null && StringUtils.isNotBlank(episode.getScriptContent())) {
                sb.append("【剧情原文参考 (Raw Novel / Script Context)】:\n")
                        .append(episode.getScriptContent()).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildShotSpec(ShotPromptDeriveDTO dto, String genMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n【当前分镜镜头规格与剧本参考事实】:\n");
        sb.append("- ★【分镜生成模式 (generationMode)】: ").append(genMode)
                .append("REFERENCE_MODE".equalsIgnoreCase(genMode) ? " (多模态参考图与音频模式)" : " (首尾关键帧过渡模式)")
                .append("\n");
        sb.append("- 镜头序号: ").append(dto.getShotNo() != null ? dto.getShotNo() : 1).append("\n");
        if (StringUtils.isNotBlank(dto.getShotName())) {
            sb.append("- 镜头标识: ").append(dto.getShotName()).append("\n");
        }
        if (isExplicitCameraConstraint(dto.getShotTypeLocked(), dto.getShotType())) {
            sb.append("- 【创作者明确锁定景别】: ").append(dto.getShotType()).append("（必须保持）\n");
        } else {
            sb.append("- 【景别】: 未指定，由 Prompt AI 自主决定\n");
        }
        if (isExplicitCameraConstraint(dto.getCameraMovementLocked(), dto.getCameraMovement())) {
            sb.append("- 【创作者明确锁定运镜】: ").append(dto.getCameraMovement()).append("（必须保持）\n");
        } else {
            sb.append("- 【运镜】: 未指定，由 Prompt AI 自主决定；不得因此默认固定机位\n");
        }
        if (dto.getDuration() != null) {
            sb.append("- 镜头时长: ").append(dto.getDuration()).append("秒\n");
        }
        if (StringUtils.isNotBlank(dto.getScriptContent())) {
            sb.append("- ★【本镜头剧本文本 (Shot Script - 核心视听与表演细节参考)】: ")
                    .append(dto.getScriptContent()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getActionDescription())) {
            sb.append("- 画面动作简述 (核心动作): ").append(dto.getActionDescription()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getDialogueSpeaker())) {
            sb.append("- 对白说话人: ").append(dto.getDialogueSpeaker()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getDialogue())) {
            sb.append("- 台词对白: ").append(dto.getDialogue()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getVoiceover())) {
            sb.append("- 旁白内心独白: ").append(dto.getVoiceover()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getSoundEffect())) {
            sb.append("- 音效与环境声: ").append(dto.getSoundEffect()).append("\n");
        }
        if (Boolean.TRUE.equals(dto.getIncludeBgm())) {
            sb.append("- ★【背景配乐 (BGM)】: 开启。允许根据分镜戏剧张力与情绪氛围，在 non_diegetic_music 中设计适宜的乐器编配、节奏速度与情绪发展。\n");
        } else {
            sb.append("- ★【音频与BGM约束 (严禁BGM)】: 本镜头已关闭背景配乐！严禁生成任何非现场背景配乐 (non-diegetic music)。请在最终输出中将 non_diegetic_music 严格设置为 \"N/A\"，严禁描述任何配乐乐器、旋律或节拍！仅允许在 overall_soundscape 中描写真实的现场环境声/拟音 (ambience, room tone, footsteps, foley) 与对白台词声音，以便后期音效与独立配乐制作。\n");
        }
        return sb.toString();
    }

    private boolean isExplicitCameraConstraint(Boolean locked, String value) {
        return Boolean.TRUE.equals(locked) && StringUtils.isNotBlank(value) && !"AUTO".equalsIgnoreCase(value.trim());
    }

    private String buildSceneContext(ShotPromptDeriveDTO dto) {
        StringBuilder sb = new StringBuilder();
        Long sceneId = dto.getResSceneId();
        if (sceneId != null && sceneId > 0) {
            ResScene resScene = resSceneMapper.selectById(sceneId);
            if (resScene != null) {
                sb.append("\n【绑定环境场景资产】:\n");
                sb.append("- 场景名称: ").append(resScene.getName()).append("\n");
                if (StringUtils.isNotBlank(resScene.getScenePrompt())) {
                    sb.append("- 场景生图Prompt: ").append(resScene.getScenePrompt()).append("\n");
                }
            }
        }
        if (StringUtils.isNotBlank(dto.getCustomScenePrompt())) {
            sb.append("- 自定义场景覆盖Prompt: ").append(dto.getCustomScenePrompt()).append("\n");
        }
        return sb.toString();
    }

    private String buildCharacterContext(ShotPromptDeriveDTO dto) {
        if (dto.getCharacterRefs() == null || dto.getCharacterRefs().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n【出场角色与造型装配】:\n");
        for (CharacterShotRefInfoVO ref : dto.getCharacterRefs()) {
            if (ref == null || ref.getCharacterId() == null || ref.getCharacterId() <= 0) continue;
            ResolvedCharacterPromptContext resolved = getCharacterVisualAssetResolver().resolvePromptContext(ref);
            if (resolved == null) continue;
            sb.append(resolved.toCharacterContextItem());
        }
        return sb.toString();
    }

    public String buildPropContext(ShotPromptDeriveDTO dto) {
        if (dto.getPropRefs() == null || dto.getPropRefs().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n【关键道具文字设定】:\n");

        Set<Long> processedIds = new HashSet<>();

        for (PropShotRefInfoVO ref : dto.getPropRefs()) {
            if (ref == null || ref.getPropId() == null || ref.getPropId() <= 0) {
                continue;
            }
            if (!processedIds.add(ref.getPropId())) {
                continue;
            }

            ResProp prop = propMapper.selectById(ref.getPropId());
            if (prop == null) {
                continue;
            }

            sb.append("- 道具名称: ").append(prop.getName()).append("\n");
            sb.append("  * 道具类型: ")
                    .append(StringUtils.defaultIfBlank(prop.getPropType(), "KEY_PROP"))
                    .append("\n");

            if (StringUtils.isNotBlank(prop.getDescription())) {
                sb.append("  * 设定描述: ")
                        .append(prop.getDescription().trim())
                        .append("\n");
            }

            if (StringUtils.isNotBlank(prop.getPropPrompt())) {
                sb.append("  * 视觉提示词: ")
                        .append(prop.getPropPrompt().trim())
                        .append("\n");
            }

            sb.append("  * 引用规则: 该道具必须出现在最终提示词中；")
                    .append("只有 Reference Manifest 中存在对应图片时，才能声明图片引用。\n");
        }

        return sb.toString();
    }

    private String buildUserInstruction(ShotPromptDeriveDTO dto) {
        if (StringUtils.isBlank(dto.getUserInstruction())) {
            return "";
        }
        return "\n【创作者特别指令与补充要求】: " + dto.getUserInstruction() + "\n";
    }

    private String resolveTemplate(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result = result.replace("${" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    private JsonNode parseJson(String value) {
        if (StringUtils.isBlank(value)) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            log.warn("解析分镜上下文 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && !value.isNull() ? value.asText() : null;
    }

    /**
     * 构建稳定有序的 MiniMax H3 ReferenceManifest 清单。
     * 将参考图片分配为 Picture 1..N，参考音频分配为 Audio 1..N，并挂载资产视觉/语义描述。
     */
    public ReferenceManifest buildReferenceManifest(ShotPromptDeriveDTO dto) {
        List<ReferenceManifest.PictureManifestItem> pictures = new ArrayList<>();
        List<ReferenceManifest.AudioManifestItem> audios = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper jacksonMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        List<ShotRefImageDTO> refImgs = dto.getRefImages();
        if ((refImgs == null || refImgs.isEmpty()) && dto.getShotId() != null && dto.getShotId() > 0) {
            DramaShot s = shotMapper.selectById(dto.getShotId());
            if (s != null && StringUtils.isNotBlank(s.getRefImagesJson())) {
                try {
                    refImgs = jacksonMapper.readValue(s.getRefImagesJson(), new TypeReference<List<ShotRefImageDTO>>() {});
                } catch (Exception ignored) {}
            }
        }

        if (refImgs != null) {
            int pIdx = 1;
            for (ShotRefImageDTO img : refImgs) {
                if (img == null || StringUtils.isBlank(img.getImageUrl())) {
                    continue;
                }

                String sType = StringUtils.defaultIfBlank(img.getSourceType(), "UPLOAD").toUpperCase();
                String role = img.getUsageRole();
                if (StringUtils.isBlank(role)) {
                    role = switch (sType) {
                        case "SCENE" -> "SCENE_LAYOUT";
                        case "CHARACTER", "CHARACTER_REFERENCE" -> "IDENTITY";
                        case "PROP" -> "PROP_APPEARANCE";
                        default -> "IDENTITY";
                    };
                }

                String desc = "";
                if (("CHARACTER".equalsIgnoreCase(sType) || "CHARACTER_REFERENCE".equalsIgnoreCase(sType))
                        && (img.getCharacterId() != null || img.getSourceId() != null)) {
                    Long characterId = img.getCharacterId();
                    // 旧 CHARACTER 记录的 sourceId 是角色 ID；新 CHARACTER_REFERENCE 的 sourceId 是图片记录 ID，
                    // 因此新记录必须显式携带 characterId，不能再根据 sourceId 猜类型。
                    if (characterId == null && "CHARACTER".equalsIgnoreCase(sType)) {
                        characterId = img.getSourceId();
                    }
                    if (characterId != null) {
                        CharacterShotRefInfoVO matchingRef = null;
                        if (dto.getCharacterRefs() != null) {
                            for (CharacterShotRefInfoVO cr : dto.getCharacterRefs()) {
                                if (characterId.equals(cr.getCharacterId())) {
                                    matchingRef = cr;
                                    break;
                                }
                            }
                        }

                        Long selectedLookId = img.getLookId();
                        if (selectedLookId == null && matchingRef != null) {
                            selectedLookId = matchingRef.getLookId();
                        }
                        String designDesc = matchingRef != null ? matchingRef.getDesignDesc() : null;
                        String appearancePrompt = matchingRef != null ? matchingRef.getAppearancePrompt() : null;
                        String outfitPrompt = matchingRef != null ? matchingRef.getOutfitPrompt() : null;

                        ResolvedCharacterPromptContext resolved = getCharacterVisualAssetResolver().resolvePromptContext(
                                characterId, selectedLookId, designDesc, appearancePrompt, outfitPrompt, null, null, null
                        );
                        if (resolved != null) {
                            desc = resolved.toManifestDescription();
                        }
                    }
                } else if ("SCENE".equalsIgnoreCase(sType) && img.getSourceId() != null && img.getSourceId() > 0) {
                    ResScene sc = resSceneMapper.selectById(img.getSourceId());
                    if (sc != null) {
                        StringBuilder dsb = new StringBuilder();
                        if (StringUtils.isNotBlank(sc.getScenePrompt())) {
                            dsb.append(sc.getScenePrompt().trim());
                        } else if (StringUtils.isNotBlank(sc.getDescription())) {
                            dsb.append(sc.getDescription().trim());
                        } else if (StringUtils.isNotBlank(sc.getName())) {
                            dsb.append(sc.getName().trim());
                        }
                        if (StringUtils.isNotBlank(sc.getTimeOfDay())) {
                            if (dsb.length() > 0) dsb.append(", time of day: ");
                            dsb.append(sc.getTimeOfDay());
                        }
                        if (StringUtils.isNotBlank(sc.getWeatherAtmosphere())) {
                            if (dsb.length() > 0) dsb.append(", atmosphere: ");
                            dsb.append(sc.getWeatherAtmosphere());
                        }
                        desc = dsb.toString();
                    }
                } else if ("PROP".equalsIgnoreCase(sType) && img.getSourceId() != null && img.getSourceId() > 0) {
                    ResProp pr = propMapper.selectById(img.getSourceId());
                    if (pr != null) {
                        StringBuilder dsb = new StringBuilder();
                        if (StringUtils.isNotBlank(pr.getName())) {
                            dsb.append(pr.getName());
                        }
                        if (StringUtils.isNotBlank(pr.getPropType())) {
                            dsb.append(" (").append(pr.getPropType()).append(")");
                        }
                        if (StringUtils.isNotBlank(pr.getPropPrompt())) {
                            dsb.append(": ").append(pr.getPropPrompt().trim());
                        }
                        desc = dsb.toString();
                    }
                }

                if (StringUtils.isBlank(desc)) {
                    desc = img.getName();
                }

                pictures.add(ReferenceManifest.PictureManifestItem.builder()
                        .pictureIndex(pIdx++)
                        .referenceId(img.getId())
                        .sourceType(sType)
                        .sourceId(img.getSourceId())
                        .entityName(img.getName())
                        .usageRole(role)
                        .description(desc)
                        .imageUrl(img.getImageUrl())
                        .build());
            }
        }

        List<ShotRefAudioDTO> refAuds = dto.getRefAudios();
        if ((refAuds == null || refAuds.isEmpty()) && dto.getShotId() != null && dto.getShotId() > 0) {
            DramaShot s = shotMapper.selectById(dto.getShotId());
            if (s != null && StringUtils.isNotBlank(s.getRefAudiosJson())) {
                try {
                    refAuds = jacksonMapper.readValue(s.getRefAudiosJson(), new TypeReference<List<ShotRefAudioDTO>>() {});
                } catch (Exception ignored) {}
            }
        }

        if (refAuds != null) {
            int aIdx = 1;
            for (ShotRefAudioDTO aud : refAuds) {
                if (aud == null) continue;
                if (StringUtils.isBlank(aud.getAudioUrl()) && StringUtils.isBlank(aud.getName()) && StringUtils.isBlank(aud.getText())) continue;

                audios.add(ReferenceManifest.AudioManifestItem.builder()
                        .audioIndex(aIdx++)
                        .referenceId(aud.getId())
                        .sourceType(StringUtils.defaultIfBlank(aud.getSourceType(), "UPLOAD"))
                        .characterId(aud.getCharacterId())
                        .characterName(StringUtils.firstNonBlank(aud.getCharacterName(), aud.getName()))
                        .usageMode(StringUtils.defaultIfBlank(aud.getUsageMode(), "VOICE_TIMBRE"))
                        .language(StringUtils.defaultIfBlank(aud.getLanguage(), "Chinese"))
                        .text(aud.getText())
                        .audioUrl(aud.getAudioUrl())
                        .duration(aud.getDuration())
                        .build());
            }
        }

        return ReferenceManifest.builder().pictures(pictures).audios(audios).build();
    }

    /**
     * 校验 MiniMax H3 Ref2VA 六段式输出结构与标签有效性。
     */
    public void validateH3Ref2VaOutput(ShotPromptDeriveVO vo, ReferenceManifest manifest, ShotPromptDeriveDTO dto) {
        String p = vo.getPrompt();
        if (StringUtils.isBlank(p)) return;

        // 1. 六段式段落检查
        String[] requiredSections = {
                "subject_definitions:", "summary:", "retention_analysis:",
                "detailed_description:", "overall_soundscape:", "non_diegetic_music:"
        };
        List<String> missing = new ArrayList<>();
        for (String sec : requiredSections) {
            if (!p.contains(sec)) {
                missing.add(sec);
            }
        }
        if (!missing.isEmpty()) {
            log.warn("[H3 Ref2VA Validation] 分镜提示词缺少 MiniMax H3 官方必填段落: {}", missing);
        }

        // 2. 检查 Picture 标签边界与合法性
        int maxPic = manifest != null && manifest.getPictures() != null ? manifest.getPictures().size() : 0;
        Matcher picMatcher = Pattern.compile("<Picture\\s*(\\d+)>").matcher(p);
        while (picMatcher.find()) {
            int idx = Integer.parseInt(picMatcher.group(1));
            if (idx < 1 || idx > maxPic) {
                log.warn("[H3 Ref2VA Validation] 提示词中引用了未分配的 <Picture {}> (清单有效上限: {})", idx, maxPic);
            }
        }

        // 3. 检查 Audio 标签边界与合法性
        int maxAud = manifest != null && manifest.getAudios() != null ? manifest.getAudios().size() : 0;
        Matcher audMatcher = Pattern.compile("<Audio\\s*(\\d+)>").matcher(p);
        while (audMatcher.find()) {
            int idx = Integer.parseInt(audMatcher.group(1));
            if (idx < 1 || idx > maxAud) {
                log.warn("[H3 Ref2VA Validation] 提示词中引用了未分配的 <Audio {}> (清单有效上限: {})", idx, maxAud);
            }
        }

        // 4. 台词检查
        if (StringUtils.isNotBlank(dto.getDialogue())) {
            if (!p.contains("<d>") || !p.contains("</d>")) {
                log.warn("[H3 Ref2VA Validation] 当前分镜包含对白台词，但提示词中未检测到 <d>[Language] ...</d> 标签包裹");
            }
        }
    }

    /**
     * 校验 MiniMax H3 FL2VA 首尾帧输出结构。
     */
    public void validateH3Fl2VaOutput(ShotPromptDeriveVO vo, ShotPromptDeriveDTO dto) {
        List<String> errors = new ArrayList<>();
        validateH3Fl2VaPrompt(vo != null ? StringUtils.firstNonBlank(vo.getVideoPrompt(), vo.getPrompt()) : null,
                dto != null && Boolean.TRUE.equals(dto.getIncludeBgm()), dto != null ? dto.getDuration() : null, errors);
        if (!errors.isEmpty()) {
            throw new BizException(String.join("; ", errors));
        }
    }

    private String appendFl2VaOutputContract(String systemPrompt, String genMode, Double duration) {
        if ("REFERENCE_MODE".equalsIgnoreCase(genMode)) {
            return systemPrompt;
        }
        String finalSecond = String.format(Locale.ROOT, "%.2f", duration != null ? duration : 5.0);
        return systemPrompt + "\n\n" + H3_FL2VA_OUTPUT_CONTRACT.replace("${DURATION}", finalSecond);
    }

    private void validateH3Fl2VaPrompt(String prompt, boolean includeBgm, Double duration, List<String> errors) {
        if (StringUtils.isBlank(prompt)) {
            errors.add("MiniMax H3 FL2VA 视频提示词不能为空");
            return;
        }

        String text = prompt.strip();
        Matcher sections = Pattern.compile("(?m)^(integrated_multimodal_description|overall_soundscape|non_diegetic_music):[ \\t]*")
                .matcher(text);
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (sections.find()) {
            names.add(sections.group(1));
            starts.add(sections.start());
            ends.add(sections.end());
        }

        String[] expected = {"integrated_multimodal_description", "overall_soundscape", "non_diegetic_music"};
        if (names.size() != expected.length) {
            errors.add("MiniMax H3 FL2VA 视频提示词必须恰好包含 integrated_multimodal_description、overall_soundscape、non_diegetic_music 三个段落");
        } else {
            for (int i = 0; i < expected.length; i++) {
                if (!expected[i].equals(names.get(i))) {
                    errors.add("MiniMax H3 FL2VA 三个段落的顺序不正确");
                    break;
                }
                String content = text.substring(ends.get(i), i + 1 < names.size() ? starts.get(i + 1) : text.length()).trim();
                if (content.isEmpty()) {
                    errors.add(expected[i] + " 段落不能为空；无声音或音乐时请填写 N/A");
                }
                if (i == 2 && !includeBgm && !"N/A".equalsIgnoreCase(content)) {
                    errors.add("本镜头已禁用 BGM，non_diegetic_music 必须为 N/A");
                }
            }
        }

        String alignment = starts.isEmpty() ? text : text.substring(0, starts.get(0)).trim();
        if (!alignment.startsWith("How the reference pictures align with the target video")
                || !alignment.contains("Picture 1") || !alignment.contains("Picture 2")
                || !alignment.contains("0.00")) {
            errors.add("MiniMax H3 FL2VA 视频提示词缺少首尾帧 Picture 1/Picture 2 时间对齐声明");
        }
        if (duration != null && !alignment.contains(String.format(Locale.ROOT, "%.2f-second mark", duration))) {
            errors.add("MiniMax H3 FL2VA 尾帧时间必须与当前镜头时长一致");
        }
        if (Pattern.compile("\\p{IsHan}").matcher(text).find()) {
            errors.add("MiniMax H3 FL2VA 视频提示词必须使用英文，不得混入中文");
        }
    }
}
