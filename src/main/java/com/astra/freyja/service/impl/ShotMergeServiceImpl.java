package com.astra.freyja.service.impl;

import com.astra.freyja.dto.script.*;
import com.astra.freyja.service.ShotMergeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 分镜合并引擎服务实现 (ShotMergeServiceImpl)。
 * 严格遵循「纯 Java 本地合并、按 segment.sequence 排序、全局 ID 与序号重新编排」架构原则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotMergeServiceImpl implements ShotMergeService {

    private static final Set<String> WORKER_SHOT_TYPES = Set.of(
            "AUTO", "EXTREME_CLOSE_UP", "CLOSE_UP", "MEDIUM_CLOSE_UP", "MEDIUM_SHOT",
            "FULL_SHOT", "LONG_SHOT", "OVER_SHOULDER", "TOP_DOWN"
    );

    private static final Set<String> WORKER_CAMERA_MOVEMENTS = Set.of(
            "AUTO", "STATIC", "PUSH_IN", "PULL_OUT", "PAN_LEFT", "PAN_RIGHT",
            "TILT_UP", "TILT_DOWN", "TRACKING", "ORBIT", "ZOOM_IN"
    );

    @Override
    public ScriptDecomposeResultVO mergeSegmentResults(List<SegmentShotResult> segmentResults,
                                                       PlannerDecomposeResultVO plannerResult,
                                                       GlobalStoryContext globalContext,
                                                       Consumer<String> stepLogger) {
        stepLogger.accept("🧩 [ShotMerge Engine] 启动 Java 本地分镜结果合并与全局序号重编 (0 次 AI 调用)...");

        // 1. 严格按 sequence 排序
        List<SegmentShotResult> sortedResults = new ArrayList<>(segmentResults != null ? segmentResults : Collections.emptyList());
        sortedResults.sort(Comparator.comparingInt(r -> r.getSequence() != null ? r.getSequence() : 0));

        List<DecomposedEpisodeSceneVO> mergedScenes = new ArrayList<>();
        int globalShotNo = 0;
        int globalGroupNo = 0;
        int globalSceneNo = 0;

        DecomposedShotGroupVO lastProcessedGroup = null;

        int targetEpNo = (globalContext != null && globalContext.getStartEpisodeNo() != null && globalContext.getStartEpisodeNo() > 0)
                ? globalContext.getStartEpisodeNo() : 1;
        String epTitle = (globalContext != null && StringUtils.isNotBlank(globalContext.getChapterTitle()))
                ? globalContext.getChapterTitle()
                : (StringUtils.isNotBlank(plannerResult.getDramaTitle()) ? plannerResult.getDramaTitle() : "第" + targetEpNo + "集");

        for (SegmentShotResult segResult : sortedResults) {
            WorkerShotResult shotResult = segResult.getShotResult();
            if (shotResult == null || shotResult.getScenes() == null) {
                continue;
            }

            for (WorkerShotResult.WorkerSceneVO workerScene : shotResult.getScenes()) {
                globalSceneNo++;
                String sceneName = StringUtils.defaultIfBlank(workerScene.getName(), "情景场次 " + globalSceneNo);

                DecomposedEpisodeSceneVO episodeScene = DecomposedEpisodeSceneVO.builder()
                        .sceneNo(globalSceneNo)
                        .sceneName(sceneName)
                        .sceneId(workerScene.getSceneId())
                        .summary(String.format("分段 %s 所属场次: %s", segResult.getSegmentId(), sceneName))
                        .shotGroups(new ArrayList<>())
                        .shots(new ArrayList<>())
                        .build();

                // 按 groupLocalId 将当前 scene 内的 shots 分组
                Map<String, List<WorkerShotResult.WorkerShotVO>> groupMap = new LinkedHashMap<>();
                if (workerScene.getShots() != null) {
                    for (WorkerShotResult.WorkerShotVO s : workerScene.getShots()) {
                        String gId = StringUtils.defaultIfBlank(s.getGroupLocalId(), "G001");
                        groupMap.computeIfAbsent(gId, k -> new ArrayList<>()).add(s);
                    }
                }

                for (Map.Entry<String, List<WorkerShotResult.WorkerShotVO>> entry : groupMap.entrySet()) {
                    List<WorkerShotResult.WorkerShotVO> rawShots = entry.getValue();
                    if (rawShots.isEmpty()) continue;

                    globalGroupNo++;
                    String groupCode = String.format("G%03d", globalGroupNo);
                    String firstAction = rawShots.get(0).getAction();
                    String groupName = StringUtils.firstNonBlank(rawShots.get(0).getGroupName(), sceneName + " - 连续动作组 " + globalGroupNo);

                    DecomposedShotGroupVO groupVO = DecomposedShotGroupVO.builder()
                            .groupNo(globalGroupNo)
                            .name(groupName)
                            .purpose(StringUtils.defaultIfBlank(firstAction, "连续视听动作单元"))
                            .shots(new ArrayList<>())
                            .build();

                    for (WorkerShotResult.WorkerShotVO rawShot : rawShots) {
                        globalShotNo++;
                        DecomposedShotVO shotVO = new DecomposedShotVO();
                        shotVO.setShotNo(globalShotNo);
                        shotVO.setShotName(String.format("S%02d-%02d", targetEpNo, globalShotNo));
                        shotVO.setGroupId(groupCode);
                        shotVO.setSceneId(StringUtils.firstNonBlank(rawShot.getSceneId(), workerScene.getSceneId()));
                        shotVO.setPropIds(rawShot.getPropIds());

                        // Worker 的摄影参数只允许标准枚举；缺值或历史中文描述归为未指定，绝不猜成 STATIC。
                        // 新版 Worker 契约固定输出 AUTO，Prompt AI 在后续导演规划阶段设计具体参数。
                        WorkerShotResult.WorkerCameraVO camera = rawShot.getCamera();
                        shotVO.setShotType(normalizeWorkerCameraValue(
                                camera != null ? camera.getShotSize() : null, WORKER_SHOT_TYPES, "shotSize"));
                        shotVO.setCameraMovement(normalizeWorkerCameraValue(
                                camera != null ? camera.getMovement() : null, WORKER_CAMERA_MOVEMENTS, "movement"));
                        shotVO.setShotTypeLocked(false);
                        shotVO.setCameraMovementLocked(false);

                        // 镜头时长 (默认 5~8s 规范)
                        Double duration = rawShot.getDuration();
                        if (duration == null || duration <= 0) {
                            duration = 5.0;
                        }
                        shotVO.setDuration(duration);

                        String scriptContent = rawShot.getScriptContent();
                        String action = StringUtils.firstNonBlank(rawShot.getAction(), scriptContent, "主体角色在场中");
                        shotVO.setScriptContent(scriptContent);
                        shotVO.setActionDescription(action);
                        // Worker AI 不再生成首帧图/运镜 prompt，置空供后续专属AI结合原文与镜头剧本生成
                        shotVO.setFirstFrameVisual(null);
                        shotVO.setPrompt(null);
                        shotVO.setVideoPrompt(null);
                        shotVO.setDialogueSpeaker(rawShot.getDialogueSpeaker());
                        shotVO.setDialogue(rawShot.getDialogue());
                        shotVO.setVoiceover(rawShot.getVoiceover());
                        shotVO.setSoundEffect(rawShot.getSoundEffect());
                        shotVO.setPropIds(rawShot.getPropIds());

                        // 智能提取、动作文本扫描与拼音/别名归一化绑定出场人物
                        List<String> resolvedCharacterNames = extractAndNormalizeCharacterNames(rawShot, globalContext);
                        shotVO.setCharacterNames(resolvedCharacterNames);

                        // Worker 不做画面构图和摄影焦点选择；这些字段仅为旧响应兼容保留为空。
                        shotVO.setPrimaryCharacter(null);
                        shotVO.setSecondaryCharacter(null);

                        groupVO.getShots().add(shotVO);
                        episodeScene.getShots().add(shotVO);
                    }

                    episodeScene.getShotGroups().add(groupVO);
                    lastProcessedGroup = groupVO;
                }

                mergedScenes.add(episodeScene);
            }
        }

        DecomposedEpisodeVO episodeVO = DecomposedEpisodeVO.builder()
                .episodeNo(targetEpNo)
                .title(epTitle)
                .summary(plannerResult.getSynopsis())
                .scenes(mergedScenes)
                .build();

        String dramaTitle = StringUtils.firstNonBlank(
                globalContext != null ? globalContext.getDramaTitle() : null,
                plannerResult.getDramaTitle(),
                "AI一键分镜生成"
        );
        String genre = StringUtils.firstNonBlank(
                globalContext != null ? globalContext.getGenre() : null,
                plannerResult.getGenre(),
                "DOMINANT_CEO"
        );
        String synopsis = StringUtils.defaultIfBlank(plannerResult.getSynopsis(), "小说剧本大纲");

        // 规范化 segment 中的 characterIds 和 locationIds (将可能残留的编号对齐为中文名)
        if (plannerResult.getSegments() != null) {
            Map<String, String> charMap = new HashMap<>();
            if (globalContext != null && globalContext.getCharacters() != null) {
                for (DecomposedCharacterVO c : globalContext.getCharacters()) {
                    String cName = StringUtils.firstNonBlank(c.getCanonicalName(), c.getName(), c.getDisplayName());
                    if (c.getName() != null) charMap.put(c.getName(), cName);
                    if (c.getCanonicalName() != null) charMap.put(c.getCanonicalName(), cName);
                    if (c.getDisplayName() != null) charMap.put(c.getDisplayName(), cName);
                }
            }
            Map<String, String> locMap = new HashMap<>();
            if (globalContext != null && globalContext.getScenes() != null) {
                for (DecomposedSceneVO sc : globalContext.getScenes()) {
                    String scName = StringUtils.firstNonBlank(sc.getSceneName(), sc.getLocationName());
                    if (sc.getSceneName() != null) locMap.put(sc.getSceneName(), scName);
                    if (sc.getLocationName() != null) locMap.put(sc.getLocationName(), scName);
                }
            }
            for (StorySegment seg : plannerResult.getSegments()) {
                if (seg.getCharacterIds() != null) {
                    List<String> mappedChars = new ArrayList<>();
                    for (String cid : seg.getCharacterIds()) {
                        mappedChars.add(charMap.getOrDefault(cid, cid));
                    }
                    seg.setCharacterIds(mappedChars);
                }
                if (seg.getLocationIds() != null) {
                    List<String> mappedLocs = new ArrayList<>();
                    for (String lid : seg.getLocationIds()) {
                        mappedLocs.add(locMap.getOrDefault(lid, lid));
                    }
                    seg.setLocationIds(mappedLocs);
                }
            }
        }

        ScriptDecomposeResultVO resultVO = ScriptDecomposeResultVO.builder()
                .dramaTitle(dramaTitle)
                .genre(genre)
                .synopsis(synopsis)
                .aspectRatio(globalContext != null && StringUtils.isNotBlank(globalContext.getAspectRatio()) ? globalContext.getAspectRatio() : "9:16")
                .stylePreset(globalContext != null && StringUtils.isNotBlank(globalContext.getStylePreset()) ? globalContext.getStylePreset() : "cinematic-realism")
                .styleTone(globalContext != null ? globalContext.getStyleTone() : null)
                .characters(plannerResult.getCharacters() != null ? plannerResult.getCharacters() : new ArrayList<>())
                .scenes(plannerResult.getScenes() != null ? plannerResult.getScenes() : new ArrayList<>())
                .props(plannerResult.getProps() != null ? plannerResult.getProps() : new ArrayList<>())
                .episodes(List.of(episodeVO))
                .segments(plannerResult.getSegments())
                .build();

        stepLogger.accept(String.format("✅ [ShotMerge Engine] 分镜合并就绪: 沉淀 %d 场次, %d 连续镜头组, %d 个分镜镜头",
                mergedScenes.size(), globalGroupNo, globalShotNo));

        return resultVO;
    }

    private String normalizeWorkerCameraValue(String value, Set<String> allowedValues, String fieldName) {
        if (StringUtils.isBlank(value)) {
            return "AUTO";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (allowedValues.contains(normalized)) {
            return normalized;
        }
        log.warn("[ShotMerge] Worker {} 不符合标准枚举 [{}]；按未指定 AUTO 处理", fieldName, value);
        return "AUTO";
    }

    private List<String> extractAndNormalizeCharacterNames(WorkerShotResult.WorkerShotVO rawShot, GlobalStoryContext globalContext) {
        Set<String> resultNames = new LinkedHashSet<>();
        List<DecomposedCharacterVO> knownChars = (globalContext != null && globalContext.getCharacters() != null)
                ? globalContext.getCharacters() : Collections.emptyList();

        // 1. 优先读取 AI 输出的 characterIds
        if (rawShot.getCharacterIds() != null) {
            for (String rawId : rawShot.getCharacterIds()) {
                if (StringUtils.isNotBlank(rawId)) {
                    String matchedName = matchKnownCharacter(rawId.trim(), knownChars);
                    resultNames.add(matchedName != null ? matchedName : rawId.trim());
                }
            }
        }

        // 2. 补偿：如果有对白说话人 (dialogueSpeaker)，自动加入出场人物
        if (StringUtils.isNotBlank(rawShot.getDialogueSpeaker())) {
            String speaker = rawShot.getDialogueSpeaker().trim();
            String matchedName = matchKnownCharacter(speaker, knownChars);
            resultNames.add(matchedName != null ? matchedName : speaker);
        }

        // 3. 自愈补偿：如果出场人物仍为空，从 action 画面描述中扫描已知角色名
        if (resultNames.isEmpty() && !knownChars.isEmpty()) {
            String action = rawShot.getAction();
            String dialogue = rawShot.getDialogue();
            String combinedText = (action != null ? action : "") + " " + (dialogue != null ? dialogue : "");
            if (StringUtils.isNotBlank(combinedText)) {
                for (DecomposedCharacterVO ch : knownChars) {
                    String primaryName = StringUtils.firstNonBlank(ch.getCanonicalName(), ch.getName(), ch.getDisplayName());
                    if (StringUtils.isNotBlank(primaryName) && combinedText.contains(primaryName)) {
                        resultNames.add(primaryName.trim());
                    } else if (ch.getAliases() != null) {
                        for (String alias : ch.getAliases()) {
                            if (StringUtils.isNotBlank(alias) && combinedText.contains(alias)) {
                                resultNames.add(primaryName != null ? primaryName.trim() : alias.trim());
                                break;
                            }
                        }
                    }
                }
            }
        }

        return new ArrayList<>(resultNames);
    }

    private String matchKnownCharacter(String input, List<DecomposedCharacterVO> knownChars) {
        if (StringUtils.isBlank(input) || knownChars.isEmpty()) {
            return null;
        }
        String cleanInput = input.trim();
        for (DecomposedCharacterVO ch : knownChars) {
            String primaryName = StringUtils.firstNonBlank(ch.getCanonicalName(), ch.getName(), ch.getDisplayName());
            if (cleanInput.equalsIgnoreCase(primaryName)
                    || cleanInput.equalsIgnoreCase(ch.getName())
                    || cleanInput.equalsIgnoreCase(ch.getDisplayName())
                    || cleanInput.equalsIgnoreCase(ch.getCanonicalName())) {
                return primaryName != null ? primaryName.trim() : cleanInput;
            }
            if (ch.getAliases() != null) {
                for (String alias : ch.getAliases()) {
                    if (cleanInput.equalsIgnoreCase(alias)) {
                        return primaryName != null ? primaryName.trim() : cleanInput;
                    }
                }
            }
            // 支持下划线或短横线拼音匹配 (如 su_qing_xue -> 苏清雪)
            String normalizedInput = cleanInput.replace("_", "").replace("-", "").toLowerCase();
            if (primaryName != null) {
                String normalizedPrimary = primaryName.replace("_", "").replace("-", "").toLowerCase();
                if (normalizedInput.equals(normalizedPrimary)) {
                    return primaryName.trim();
                }
            }
        }
        return null;
    }

    @Override
    public FragmentationStatsVO calculateFragmentationStats(List<DecomposedShotVO> shots) {
        if (shots == null || shots.isEmpty()) {
            return FragmentationStatsVO.builder()
                    .totalShots(0)
                    .shortShots(0)
                    .normalShots(0)
                    .longShots(0)
                    .averageDuration(0.0)
                    .shortShotRatio(0.0)
                    .isHighFragmentation(false)
                    .build();
        }

        int total = shots.size();
        int shortCount = 0;
        int normalCount = 0;
        int longCount = 0;
        double totalDuration = 0.0;

        for (DecomposedShotVO s : shots) {
            double dur = (s.getDuration() != null && s.getDuration() > 0) ? s.getDuration() : 5.0;
            totalDuration += dur;

            if (dur < 5.0) {
                shortCount++;
            } else if (dur <= 8.0) {
                normalCount++;
            } else {
                longCount++;
            }
        }

        double avgDuration = Math.round((totalDuration / total) * 10.0) / 10.0;
        double shortRatio = Math.round(((double) shortCount / total) * 1000.0) / 1000.0;
        boolean isHigh = shortRatio > 0.30;
        String warnMsg = isHigh
                ? String.format("⚠️ 当前章节短镜头比例偏高 (%.1f%% > 30%%)，建议将碎片动作镜头合并以保证 5~8s 影视叙事沉浸感", shortRatio * 100)
                : null;

        return FragmentationStatsVO.builder()
                .totalShots(total)
                .shortShots(shortCount)
                .normalShots(normalCount)
                .longShots(longCount)
                .averageDuration(avgDuration)
                .shortShotRatio(shortRatio)
                .isHighFragmentation(isHigh)
                .warningMessage(warnMsg)
                .build();
    }
}
