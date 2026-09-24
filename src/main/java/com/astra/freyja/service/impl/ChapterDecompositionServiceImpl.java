package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.*;
import com.astra.freyja.skill.service.ScriptSkillRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 章节剧情大纲与事件分段服务实现 (ChapterDecompositionServiceImpl / Planner AI)。
 * 严格遵循「Planner 仅负责整章分段，严禁生成分镜；Java 确定性校验与修正边界」架构原则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterDecompositionServiceImpl implements ChapterDecompositionService {

    private final AiModelFactory aiModelFactory;
    private final SysConfigService sysConfigService;
    private final AIOutputValidationService validationService;
    private final AiTaskService aiTaskService;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private ScriptSkillRuntime scriptSkillRuntime;

    private static final String DEFAULT_PLANNER_SYSTEM_PROMPT = """
        你是顶尖的影视剧本结构分析专家与架构策划师 (Chapter Planner)。
        你的任务不是生成分镜，也不是生成镜头组。
        
        你的核心任务是：深入理解整章小说/剧本文本，提炼出【核心登场角色与场景资产清单】，并将其划分为适合后续由独立 Worker 进行分镜解析的剧情分段 (Story Segments)。

        【角色身份职责】：在本次剧情规划中完成角色识别、身份消歧及角色引用关系确定。必须使用 Planner 阶段强制加载的 `character-disambiguation` Skill；角色身份判定细则由 Skill 提供。角色引用只能使用当前项目角色注册表中的真实 ID，Java 仅校验 ID 合法性，不推断或替换角色身份。

        【已登记场景复用职责】：比较名称、地点、空间结构、固定陈设与描述判断资产身份，不得只按字面名称匹配。同一物理地点的别名、简称或轻微命名变化应复用已登记资产，填写其数据库场景 ID 到 existingSceneId，sceneName 使用登记名称；昼夜、天气、灯光变化不应单独创建新场景资产。只有明确为不同物理地点或证据不足时才留空。场景 id 仍使用本次输出的局部编号；不得编造数据库场景 ID，也不得引用列表之外的场景 ID。
        
        【剧情分段核心原则与架构铁律】：
        1. 【以“完整戏剧事件”为切分单位】：
           - 严禁机械按照字数切分！
           - 严禁按照单句或标点切分！
           - 严禁在一个完整动作过程、一段连续对白或一个冲突事件中间生硬切断！
        2. 【分段摘要 (summary) 与戏剧语境深度提炼要求】：
           - 每个 Segment 的 summary 必须精准提炼当前情境的核心要素，为下游分镜导演 (Worker) 提供统一坚实的物理世界坐标：
             * 【空间物理属性与现场主光源自主判定 (极其重要)】：
               - AI 必须深入理解文本，自主判定当前空间的物理属性（是封闭无窗密室、地下室、电梯厢、KTV酒吧、地牢，还是有明窗的办公室、或开阔户外街道）；
               - 自主提炼当前空间真实存在的【物理主光源与光位基调】（如：封闭电梯顶棚LED面板冷光垂直下投、审讯室顶置单盏工业吊灯下投锥形冷光、古风内室案头红烛侧向暖光、酒吧幽暗蓝紫霓虹氛围、或日间高窗漫射天光）；
               - 严禁在无窗密闭空间脑补太阳日光！指导下游 Worker 必须以此现场真实光源作为整段分镜的统一打光基准。
             * 【时空物理环境与氛围感】：清晰界定场景的物理空间特性与视听基调（如：逼仄压抑的狭小密室、冷冽开阔的江边夜风、窗明几净的现代化办公厅）；
             * 【戏剧冲突与情绪张力曲线】：明确本段情绪从平静、试探到对峙爆发或沉寂的心理演进过程；
             * 【人物关系动态与微动作感知】：指出角色间的心理距离、权力对峙或情感羁绊，启发下游导演捕捉最贴切的情境动作；
             * 【人物困境视听烘托指导】：若涉及角色落难受挫，指导下游通过恶劣天气/空旷空间与坚毅神情烘托凄美破碎感，严禁将服装设计为破烂不堪。
        3. 【优先切分节点】：
           - 地点空间发生明显变化 (如从公司到家、从室内到街道)；
           - 时间时段发生明显跳跃 (如白天到夜晚、次日清晨)；
           - 剧情核心事件发生转折；
           - 出场人物组合或人物关系发生突变；
           - 叙事重点、戏剧冲突阶段切换；
           - 回忆/梦境/现实切换；
           - 一个完整戏剧事件结束。
        4. 【分段数量与规模控制】：
           - Segment 的目标不是越多越好，尽量以适度数量覆盖整章；
           - Segment 必须足够大以包含完整起承转合，同时不能过大（单个分段软参考范围 1200~3500 字，剧情完整性绝对优先于字数）；
        5. 【严禁越权输出与严禁原文复读】：
           - 【严禁输出 rawText 原文全文】：切勿在 segments 中复制粘贴章节原文（原文字段严禁输出）！只需提供分段起始句 startSnippet 与结束句 endSnippet（前/后 10~20 字）以及大致 startOffset/endOffset，原文由 Java 本地秒级高精切片；
           - 绝对不要生成 Shot (分镜)！
           - 绝对不要生成 ShotGroup (镜头组)！
           - 绝对不要生成 Camera (机位)！
           - 绝对不要生成 Duration (时长)！
           - 绝对不要生成 Video Prompt (视频提示词)！
           - 仅返回规范的轻量 JSON 结构。
        6. 【角色与地点中文命名铁律】：
           - 剧情分段 (segments) 中的出场角色 characterIds 必须直接填写具体的人物中文姓名（如 ["苏清雪", "苏明宇"]），严禁输出 char_001 等抽象编号！
           - 剧情分段 (segments) 中的环境地点 locationIds 必须直接填写具体的中文场景名称（如 ["顶层总裁办公室", "地下车库"]），严禁输出 loc_001 等抽象编号！

        【角色资产提取准则与严格过滤红线 (Character Filtering & Entity Rules)】：
        1. 【角色准入硬性门槛（必须同时满足以下条件，才允许提取为 Character 资产）】：
           - 物理在场 (Physical Presence)：必须在当前场景的物理时空中“实际登场并参与动作/对白”；
           - 剧情权重 (Dramatic Relevance)：必须具备独立台词 (Dialogue)，或拥有影响剧情推进的核心动作，或与主角有关键对峙/互动；
           - 严禁提取任何仅作为环境背景氛围存在的无名路人。
        2. 【绝对禁止提取的非角色负向清单 (Negative Constraints)】：
           - 禁止提取对话、回忆或引述中提到的不在场第三人（如“听张总说今晚有暴雨”中的“张总”、“我爷爷当年留下的玉佩”中的“爷爷”、“秦始皇统一度量衡”中的“秦始皇”，只要本段时空未实际登场，严禁提取）；
           - 禁止提取无台词或一次性背景板路人与泛指群体（如“路人”、“食客们”、“保镖们”、“前台小姐”、“外卖员”、“围观群众”，此类仅在分镜画面描述中作为背景呈现，严禁提取为独立角色资产）；
           - 禁止提取修辞、比喻与心像意象（如“他宛如一尊战神”、“心中的小恶魔”，严禁提取“战神”、“小恶魔”）；
           - 禁止提取旁白、画外音或机械音（如“画外音”、“电视播音员”、“系统提示音”）。
        4. 【人设、外貌与标志性衣着内外分离具象化提炼 (极重要)】：
           - 区分即时空间动作与固有视觉：严禁把“坐在最远处的椅子上”、“站在门口”、“手里拿刀”等空间动作与临时状态写进外貌；即时动作属于单个分镜，严禁污染角色固有资产！
           - 原著留白时的概念美术具象化补全 (自适应艺术载体)：若原著仅给出“年轻人”、“黑衣人”、“老者”或动作描写，外貌文字极简，AI 绝不能输出毫无辨识度的空洞废话！必须结合其年龄段、内在性格（如“善于观察、心理素质较强”）与身处情境，赋予其具备视觉辨识度的【五官面容 + 发型体态 + 标志性常服衣着】：
              * 艺术媒介与风格忠实度 (核心铁律)：
                - 当全剧艺术载体为【2D 动漫 (2D Anime)】或创作者在视觉基调指南中指定了动画/动漫/漫画风格时：
                  中文外貌 (appearanceDesc) 必须严格采用【2D 动漫概念美术设计 (2D Anime Character Design)】！突出利落清爽的动漫线条轮廓、动漫发型体态、特色眼神与服饰版型，并融入创作者指定的调色与光影，严禁使用真人写实皮肤毛孔等真人词汇；
                - 当全剧艺术载体为【电影写实 (Cinematic Realism)】时：采用电影概念美术视角刻画写实五官骨相与常服质感；
                - 当全剧艺术载体为【3D 动画 (3D Animation)】时：采用次世代 3D 角色建模与精细材质视角；
              * 标志性常服衣着 (必须包含)：原著有明确写出则精准提炼，原著未细写则根据身份与情境合理设计一套契合人设的标志性常服装束；
            - 【极速省 Token 铁律】：
              * appearanceDesc (中文原著视觉 SSOT)：包含上述面容骨相/动漫特征、发型体态与标志性常服衣着的完整中文描述；
              * 剧本拆解阶段严禁生成任何英文外貌/服装提示词（appearancePrompt 与 outfitPrompt 强制设为 null 或留空）！无需在拆解阶段生成英文生图词，以最大化节省大模型 Output Token；英文提示词后续由创作者在资产库定妆阶段按需一键生成。
        【场景空间陈设与核心道具资产提取准则 (Scene & Prop Extraction Rules - 动静分离铁律)】：
        1. 【场景空间与不可动固定陈设资产提取 (scenes)】：
           - 每个场景必须具备明确全局编号 id (如 SC001, SC002...) 与具体的中文名称 sceneName (如 "斑驳圆桌密闭暗室")；
            - 场景中文背景与环境视觉细节描述 (description)：详细描述该场景的空间格局、装潢陈设、色调氛围与原著环境细节 (如 "极简主义冷色调总裁办公室，正中摆放黑胡桃木办公桌，背景为一整面通顶落地玻璃幕墙")；
            - 场景生图英文 Prompt (scenePrompt)：必须详尽描述物理空间形状、墙地顶材质、【不可动的固有环境陈设】以及【现场自然/人工光影色温氛围】（如: "dilapidated dim vintage room, peeling paint walls, large round rustic wooden table in center, single hanging flickering tungsten bulb overhead, warm amber lighting"）；
            - 核心原则：任何固定在建筑或空间中、不可被角色随手拿取移动的物件（如天花板垂下的悬挂钨丝灯/壁灯、固定大圆桌），必须全部固化在场景资产中！无需单独拆分 lightingPrompt；
        2. 【核心叙事道具资产提取 (props) —— 动静分离准入红线】：
           - 【准入红线】：由你（AI）基于剧情上下文自主判断，有且仅能提取【角色可手持、可移动、有具体剧情交互的活动物品 (Dynamic Hand Props)】（如: 古董座钟、匕首、手提箱、特定武器、药瓶、信件、手机、钥匙、茶杯等）；
           - 【负向禁令（极重要）】：严禁将天花板吊灯、老旧钨丝灯、固有壁灯、固定大桌子、地毯、窗帘等【不可动的环境陈设 (Static Set Dressing)】误提取为独立道具！此类固定陈设已在场景 (scenes) 中统筹；
           - 每个道具必须具备全局编号 id (如 PR001, PR002...)、中文名称 name、道具类型 propType (KEY_PROP/WEAPON/DAILY)、中文特征与作用描述 description (如 "古典雕花黄铜怀表，表盖内侧刻有密文，表面有轻微岁月磨损痕迹")；
           - 【道具描述纯净契约 (propPrompt)】：只描述活动道具自身的物理材质、微观造型、做工雕花与指针状态（如: "ornate antique brass desk clock with intricate floral engravings, ticking hands pointing at twelve"），严禁在道具描述里带入房间大环境或周围陈设（严禁在钟表里写天花板吊灯、大桌子等）；
        3. 【剧情分段强绑定资产 (Segment Asset Binding)】：
           - 每个分段 segment 必须指定其发生的主场景编号 sceneId (填对应提取的 SC001 等编号)；
           - 每个分段 segment 必须在 propIds 中列出本段涉及的关键活动道具编号列表 (如 ["PR001", "PR002"])。
        """;

    @Override
    public PlannerDecomposeResultVO decomposeChapter(String chapterText,
                                                     ScriptDecomposeRequestDTO request,
                                                     GlobalStoryContext globalContext,
                                                     Consumer<String> stepLogger) {
        return decomposeChapter(chapterText, request, globalContext, null, stepLogger);
    }

    @Override
    public PlannerDecomposeResultVO decomposeChapter(String chapterText,
                                                     ScriptDecomposeRequestDTO request,
                                                     GlobalStoryContext globalContext,
                                                     java.util.function.BiConsumer<String, String> channelChunkConsumer,
                                                     Consumer<String> stepLogger) {
        if (StringUtils.isBlank(chapterText)) {
            throw new BizException("待分段的小说章节文本不能为空");
        }

        long startMs = System.currentTimeMillis();
        int rawLen = chapterText.length();
        Long taskId = globalContext == null ? null : globalContext.getTaskId();
        log.info("[PlannerAI] taskId={} started, textLength={}, model={}", taskId, rawLen, request.getModelCode());
        if (stepLogger != null) {
            stepLogger.accept(String.format("📖 [Planner AI] 启动整章宏观剧情结构分析与分段规划 (文本总长: %d 字)...", rawLen));
        }

        ChatModel chatModel = aiModelFactory.getChatModel(request.getProviderId(), request.getModelCode());
        BeanOutputConverter<PlannerDecomposeResultVO> converter = new BeanOutputConverter<>(PlannerDecomposeResultVO.class);

        String systemPrompt = sysConfigService.getConfigValue("ai.prompt.planner_system", DEFAULT_PLANNER_SYSTEM_PROMPT);
        if (scriptSkillRuntime == null || request.getSkillPolicy() == null
                || request.getSkillPolicy().getPlanner() == null
                || request.getSkillPolicy().getPlanner().getRequiredSkillNames() == null
                || request.getSkillPolicy().getPlanner().getRequiredSkillNames().stream()
                .noneMatch(name -> "character-disambiguation".equalsIgnoreCase(name))) {
            throw new BizException(400, "Planner 必须先加载 character-disambiguation Skill");
        }
        ScriptSkillRuntime.Invocation skillInvocation = scriptSkillRuntime.begin(request,
                request.getSkillPolicy().getPlanner(), globalContext, systemPrompt,
                "PLANNER", null, 0, channelChunkConsumer);
        if (skillInvocation == null) {
            throw new BizException(400, "Planner character-disambiguation Skill 未成功加载");
        }
        systemPrompt = skillInvocation.systemPrompt();
        // 读取软参考字数配置
        int minChars = getIntConfig("story.decomposition.minSegmentCharacters", 1200);
        int targetChars = getIntConfig("story.decomposition.targetSegmentCharacters", 2000);
        int maxChars = getIntConfig("story.decomposition.maxSegmentCharacters", 3500);

        StringBuilder userPromptSb = new StringBuilder();
        if (globalContext != null && StringUtils.isNotBlank(globalContext.getCharacterRegistryPromptText())) {
            userPromptSb.append("【已有角色注册表 (Character Registry)】：\n")
                    .append(globalContext.getCharacterRegistryPromptText()).append("\n\n");
        }
        if (globalContext != null && StringUtils.isNotBlank(globalContext.getSceneRegistryPromptText())) {
            userPromptSb.append("【当前短剧已登记场景资产（数据库动态事实）】：\n")
                    .append(globalContext.getSceneRegistryPromptText()).append("\n\n");
        }
        userPromptSb.append("【待解析的小说章节完整文本 (共 ").append(rawLen).append(" 字)】：\n")
                .append(chapterText).append("\n\n");
        userPromptSb.append("【分段软参考规范与轻量索引原则】：\n")
                .append(String.format("- 单段参考字数: %d ~ %d 字 (目标中位数 ~%d 字，剧情完整性绝对优先)\n", minChars, maxChars, targetChars))
                .append("- 【严禁输出原文全文】：切勿在 segments 中复制粘贴章节原文！只需提供分段起始句前10~15字 startSnippet、分段结束句后10~15字 endSnippet 以及大致 startOffset/endOffset (范围 0~").append(rawLen).append(")，原文由系统秒级高精切片；\n")
                .append("- 【强资产绑定要求】：提取完整的 characters、scenes (含英文 scenePrompt) 和 props (含英文 propPrompt)，并为每个 segment 显式绑定 sceneId 与 propIds。\n\n");
        // 读取剧本全局艺术载体与视觉基调指南 (Global Style Tone)
        String effectiveStylePreset = (globalContext != null && StringUtils.isNotBlank(globalContext.getStylePreset()))
                ? globalContext.getStylePreset() : StringUtils.defaultIfBlank(request.getStylePreset(), "anime-2d");
        String effectiveStyleTone = (globalContext != null && StringUtils.isNotBlank(globalContext.getStyleTone()))
                ? globalContext.getStyleTone() : request.getStyleTone();

        String mediumName = switch (effectiveStylePreset) {
            case "anime-2d", "anime-makoto" -> "2D 动漫 (2D Anime / Japanese Animation)";
            case "3d-animation", "3d-pixar" -> "3D 动画 (3D CGI Animation)";
            case "cyber-realism", "cyberpunk" -> "赛博朋克 (Cyberpunk)";
            case "retro-film" -> "复古胶片 (Retro Film)";
            default -> "电影级写实 (Cinematic Realism)";
        };

        userPromptSb.append("【剧本全局视觉风格与视觉基调指南 (Global Visual Style Tone - 视觉最高宪法)】：\n")
                .append("- 画面艺术载体：").append(mediumName).append("\n");
        if (StringUtils.isNotBlank(effectiveStyleTone)) {
            userPromptSb.append("- 剧本全局视觉基调指南：").append(effectiveStyleTone).append("\n")
                    .append("★ 核心执行指令：在提炼 characters 角色外貌 (appearanceDesc) 以及 scenes 场景 (scenePrompt) 时，必须一体化严格遵从上述画面艺术载体与视觉基调指南！若为 2D 动漫，角色外貌必须采用 2D 动漫概念美术设计（动漫利落线条、发型体态、特色眼神与常服），严禁输出好莱坞真人实拍皮肤毛孔词！characters 无需生成英文 appearancePrompt (强制为 null 以节省 Token)！\n\n");
        } else {
            userPromptSb.append("（注：请严格遵从【").append(mediumName).append("】的艺术规律一体化提取角色外貌与场景资产）\n\n");
        }
        userPromptSb.append("【严格输出格式要求 (JSON Schema)】：\n").append(converter.getFormat());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPromptSb.toString())
        ));

        AiTask aiTask = aiTaskService.createTask(
                request.getDramaId(),
                null,
                AiTaskType.PLOT_EXTRACTION,
                "PLANNER_" + System.currentTimeMillis() % 10000,
                globalContext != null ? globalContext.getTaskId() : null,
                "TextLen: " + rawLen,
                request.getModelCode(),
                null
        );
        aiTaskService.markRunning(aiTask.getId());

        PlannerDecomposeResultVO result;
        StringBuilder fullOutput = new StringBuilder();
        try {
            if (skillInvocation != null && skillInvocation.tool() != null) {
                fullOutput.append(scriptSkillRuntime.streamWithTool(chatModel, skillInvocation,
                        userPromptSb.toString(), token -> {
                            if (channelChunkConsumer != null) {
                                channelChunkConsumer.accept("PLANNER", token);
                                channelChunkConsumer.accept("ALL", token);
                            }
                        }));
            } else {
            try {
                chatModel.stream(prompt).toStream().forEach(chunk -> {
                    if (chunk != null && chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                        String token = chunk.getResult().getOutput().getText();
                        if (StringUtils.isNotEmpty(token)) {
                            fullOutput.append(token);
                            if (channelChunkConsumer != null) {
                                channelChunkConsumer.accept("PLANNER", token);
                                channelChunkConsumer.accept("ALL", token);
                            }
                        }
                    }
                });
            } catch (Exception streamEx) {
                if (!fullOutput.isEmpty()) throw streamEx;
                log.debug("[PlannerAI] 流式调用降级为同步调用: {}", streamEx.getMessage());
                ChatResponse response = chatModel.call(prompt);
                if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                    fullOutput.setLength(0);
                    String respText = response.getResult().getOutput().getText();
                    fullOutput.append(respText);
                    if (channelChunkConsumer != null && StringUtils.isNotEmpty(respText)) {
                        channelChunkConsumer.accept("PLANNER", respText);
                        channelChunkConsumer.accept("ALL", respText);
                    }
                }
            }
            }

            long callDuration = System.currentTimeMillis() - startMs;
            if (fullOutput.isEmpty()) {
                aiTaskService.markFailed(aiTask.getId(), "Planner AI 未返回有效响应");
                throw new BizException("Planner AI 未返回有效响应");
            }

            String rawOutput = fullOutput.toString();
            log.info("[PlannerAI] taskId={} model response received, durationMs={}, outputLength={}", taskId, callDuration, rawOutput.length());

            result = validationService.parseAndValidate(rawOutput, PlannerDecomposeResultVO.class);
            if (result == null) {
                aiTaskService.markFailed(aiTask.getId(), "Planner JSON 解析校验失败");
                throw new BizException("Planner AI JSON 解析校验失败");
            }

            aiTaskService.markSuccess(aiTask.getId(), globalContext != null && globalContext.getTaskId() != null
                    ? null : objectMapper.writeValueAsString(result), (int) callDuration);
        } catch (Exception e) {
            aiTaskService.markFailed(aiTask.getId(), e.getMessage());
            log.error("[PlannerAI] taskId={} model call failed", taskId, e);
            throw new BizException("Planner AI 章节分段解析失败: " + e.getMessage());
        }

        // Java 纯本地执行 Segment 边界校验与缝隙/重叠修正 (0 次 AI 调用)
        validateAndFixSegments(result, chapterText, stepLogger);

        long totalElapsed = System.currentTimeMillis() - startMs;
        log.info("[PlannerAI] taskId={} completed, durationMs={}, segments={}",
                taskId, totalElapsed, result.getSegments() == null ? 0 : result.getSegments().size());
        stepLogger.accept(String.format("✅ [Planner AI] 章节分段规划完成 (耗时: %.2fs) · 成功划分 %d 个戏剧事件分段",
                totalElapsed / 1000.0, result.getSegments().size()));

        return result;
    }

    /**
     * Java 本地对 Planner 产生的 StorySegment 列表进行确定性校验与边界自愈修正 (0 次 AI 调用)。
     */
    public void validateAndFixSegments(PlannerDecomposeResultVO result, String chapterText, Consumer<String> stepLogger) {
        int textLen = chapterText != null ? chapterText.length() : 0;
        if (textLen == 0) return;

        List<StorySegment> segments = result.getSegments();
        if (segments == null || segments.isEmpty()) {
            log.warn("[PlannerValidator] Planner 返回 Segment 为空，自动创建整章单个分段");
            StorySegment defaultSeg = StorySegment.builder()
                    .id("SEG001")
                    .sequence(1)
                    .startOffset(0)
                    .endOffset(textLen)
                    .title(StringUtils.defaultIfBlank(result.getDramaTitle(), "整章剧情单元"))
                    .summary(StringUtils.defaultIfBlank(result.getSynopsis(), "小说完整章节"))
                    .narrativePurpose("完整叙事单元")
                    .rawText(chapterText)
                    .build();
            result.setSegments(new ArrayList<>(List.of(defaultSeg)));
            return;
        }

        // 1. 确保 scenes 与 props 具有规范的局部 ID
        if (result.getScenes() != null) {
            int scIdx = 1;
            for (DecomposedSceneVO sc : result.getScenes()) {
                if (StringUtils.isBlank(sc.getId())) {
                    sc.setId(String.format("SC%03d", scIdx));
                }
                scIdx++;
            }
        }
        if (result.getProps() != null) {
            int prIdx = 1;
            for (DecomposedPropVO pr : result.getProps()) {
                if (StringUtils.isBlank(pr.getId())) {
                    pr.setId(String.format("PR%03d", prIdx));
                }
                prIdx++;
            }
        }

        // 2. 优先基于起止文本锚点 (startSnippet / endSnippet) 在原文中进行高精度定位
        locateOffsetsBySnippets(segments, chapterText);

        // 3. 过滤无效项并按 sequence 或 startOffset 排序
        segments.sort(Comparator.comparingInt(s -> s.getSequence() != null ? s.getSequence() : (s.getStartOffset() != null ? s.getStartOffset() : 0)));

        int fixedSequence = 1;
        for (StorySegment seg : segments) {
            seg.setSequence(fixedSequence);
            if (StringUtils.isBlank(seg.getId())) {
                seg.setId(String.format("SEG%03d", fixedSequence));
            }
            if (StringUtils.isBlank(seg.getSceneId()) && result.getScenes() != null && !result.getScenes().isEmpty()) {
                seg.setSceneId(result.getScenes().get(0).getId());
            }
            fixedSequence++;
        }

        // 4. 边界连续性校验与修正：SEG001.startOffset == 0, SEG[i].endOffset == SEG[i+1].startOffset, SEG[last].endOffset == textLen
        int currentOffset = 0;
        for (int i = 0; i < segments.size(); i++) {
            StorySegment seg = segments.get(i);
            boolean isFirst = (i == 0);
            boolean isLast = (i == segments.size() - 1);

            int start = seg.getStartOffset() != null ? seg.getStartOffset() : currentOffset;
            int end = seg.getEndOffset() != null ? seg.getEndOffset() : (isLast ? textLen : currentOffset + (textLen / segments.size()));

            if (isFirst && start != 0) {
                log.debug("[PlannerValidator] 修正首段起点: {} -> 0", start);
                start = 0;
            }

            if (start < currentOffset) {
                // 重叠修正
                start = currentOffset;
            } else if (start > currentOffset && !isFirst) {
                // 缝隙修正：前一段的 end 顺延至当前 start，或当前 start 前移至 currentOffset
                start = currentOffset;
            }

            if (end <= start) {
                end = Math.min(textLen, start + Math.max(100, textLen / segments.size()));
            }

            if (isLast && end != textLen) {
                log.debug("[PlannerValidator] 修正尾段终点: {} -> {}", end, textLen);
                end = textLen;
            }

            seg.setStartOffset(start);
            seg.setEndOffset(end);
            currentOffset = end;

            // 截取对应的 rawText
            String slice = chapterText.substring(Math.max(0, start), Math.min(textLen, end));
            seg.setRawText(slice);

            if (StringUtils.isBlank(seg.getTitle())) {
                seg.setTitle("剧情单元 " + seg.getSequence());
            }
            if (StringUtils.isBlank(seg.getSummary())) {
                seg.setSummary(slice.length() > 60 ? slice.substring(0, 60) + "..." : slice);
            }
        }

        // 确保覆盖全量文本
        StorySegment lastSeg = segments.get(segments.size() - 1);
        if (lastSeg.getEndOffset() < textLen) {
            lastSeg.setEndOffset(textLen);
            lastSeg.setRawText(chapterText.substring(lastSeg.getStartOffset(), textLen));
        }
    }

    /**
     * 基于起止文本锚点 (startSnippet / endSnippet) 在原文中进行高容错字符串定位。
     */
    private void locateOffsetsBySnippets(List<StorySegment> segments, String chapterText) {
        if (segments == null || StringUtils.isBlank(chapterText)) return;
        int searchCursor = 0;

        for (int i = 0; i < segments.size(); i++) {
            StorySegment seg = segments.get(i);
            boolean isFirst = (i == 0);
            boolean isLast = (i == segments.size() - 1);

            // 1. 定位起始点
            if (isFirst) {
                seg.setStartOffset(0);
            } else if (StringUtils.isNotBlank(seg.getStartSnippet())) {
                String cleanStart = cleanSnippet(seg.getStartSnippet());
                if (cleanStart.length() >= 4) {
                    int foundStart = chapterText.indexOf(cleanStart, Math.max(0, searchCursor - 80));
                    if (foundStart >= 0) {
                        seg.setStartOffset(foundStart);
                        searchCursor = foundStart;
                    }
                }
            }

            // 2. 定位结束点
            if (isLast) {
                seg.setEndOffset(chapterText.length());
            } else if (StringUtils.isNotBlank(seg.getEndSnippet())) {
                String cleanEnd = cleanSnippet(seg.getEndSnippet());
                if (cleanEnd.length() >= 4) {
                    int fromIdx = seg.getStartOffset() != null ? seg.getStartOffset() : searchCursor;
                    int foundEnd = chapterText.indexOf(cleanEnd, fromIdx);
                    if (foundEnd >= 0) {
                        int candidateEnd = foundEnd + cleanEnd.length();
                        // 吞下紧随其后的句子结束标点 (如 "。" "！" "？" "\n")
                        while (candidateEnd < chapterText.length() && isSentencePunctuation(chapterText.charAt(candidateEnd))) {
                            candidateEnd++;
                        }
                        seg.setEndOffset(candidateEnd);
                        searchCursor = candidateEnd;
                    }
                }
            }
        }
    }

    private String cleanSnippet(String snippet) {
        if (snippet == null) return "";
        return snippet.replaceAll("[\\r\\n\\t\\s]+", "")
                .replace("“", "").replace("”", "")
                .replace("\"", "").replace("'", "")
                .replace("…", "").trim();
    }

    private boolean isSentencePunctuation(char c) {
        return c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n' || c == '\r' || c == '”' || c == '’';
    }

    private int getIntConfig(String key, int defaultValue) {
        try {
            String val = sysConfigService.getConfigValue(key, String.valueOf(defaultValue));
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
