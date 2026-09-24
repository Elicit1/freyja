package com.astra.freyja.director.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.director.model.AppliedSkillRef;
import com.astra.freyja.director.model.CameraBeat;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构化导演决策确定性校验器 (DirectorPlanValidator)。
 * 纯物理时间轴、结构与枚举校验，严禁包含基于自然语言剧情关键字的运镜猜测代码。
 */
@Slf4j
@Component
public class DirectorPlanValidator {

    private static final BigDecimal EPSILON = new BigDecimal("0.05");

    private static final Set<String> VALID_SHOT_SIZES = new HashSet<>(Arrays.asList(
            "EXTREME_CLOSE_UP", "CLOSE_UP", "MEDIUM_CLOSE_UP", "MEDIUM_SHOT",
            "FULL_SHOT", "LONG_SHOT", "OVER_SHOULDER", "TOP_DOWN"
    ));

    private static final Set<String> VALID_CAMERA_ANGLES = new HashSet<>(Arrays.asList(
            "EYE_LEVEL", "LOW_ANGLE", "HIGH_ANGLE", "DUTCH_ANGLE", "OVER_THE_SHOULDER", "BIRDS_EYE"
    ));

    private static final Set<String> VALID_MOVEMENTS = new HashSet<>(Arrays.asList(
            "STATIC", "PUSH_IN", "PULL_OUT", "PAN_LEFT", "PAN_RIGHT",
            "TILT_UP", "TILT_DOWN", "TRACKING", "ORBIT", "ZOOM_IN"
    ));

    private static final Set<String> VALID_SPEEDS = new HashSet<>(Arrays.asList(
            "NONE", "SLOW", "NORMAL", "FAST"
    ));

    /**
     * 对生成的 DirectorPlan 进行确定性物理与结构校验，并回填技能与锁定
     */
    public void validateAndNormalize(
            DirectorPlan plan,
            BigDecimal expectedDuration,
            String lockedShotType,
            String lockedCameraMovement,
            boolean shotTypeExplicitlyLocked,
            boolean cameraMovementExplicitlyLocked,
            LoadSkillToolSession session
    ) {
        if (plan == null) {
            throw new BizException("DirectorPlan 不能为空");
        }

        // 1. 时长确定性校验
        BigDecimal duration = plan.getDuration();
        if (duration == null || duration.compareTo(BigDecimal.ZERO) <= 0) {
            if (expectedDuration != null && expectedDuration.compareTo(BigDecimal.ZERO) > 0) {
                plan.setDuration(expectedDuration);
                duration = expectedDuration;
            } else {
                throw new BizException("DirectorPlan 镜头时长 (duration) 必须大于 0");
            }
        }

        if (expectedDuration != null && expectedDuration.compareTo(BigDecimal.ZERO) > 0) {
            if (duration.subtract(expectedDuration).abs().compareTo(EPSILON) > 0) {
                log.warn("[DirectorPlanValidator] 模型生成的 duration ({}) 与分镜要求 ({}) 不一致，强制以分镜锁定为准",
                        duration, expectedDuration);
                plan.setDuration(expectedDuration);
                duration = expectedDuration;
            }
        }

        // 2. 景别 (shotSize) 校验与用户锁定套用
        boolean isShotTypeLocked = shotTypeExplicitlyLocked
                && StringUtils.isNotBlank(lockedShotType) && !"AUTO".equalsIgnoreCase(lockedShotType.trim());
        if (isShotTypeLocked) {
            String canonicalLockedShot = lockedShotType.trim().toUpperCase();
            if (!VALID_SHOT_SIZES.contains(canonicalLockedShot)) {
                throw new BizException("创作者锁定的景别无效: " + lockedShotType);
            }
            if (!canonicalLockedShot.equalsIgnoreCase(plan.getShotSize())) {
                log.info("[DirectorPlanValidator] 套用创作者锁定的景别 [{}], 覆盖 AI 建议 [{}]",
                        canonicalLockedShot, plan.getShotSize());
                plan.setShotSize(canonicalLockedShot);
            }
        } else {
            if (StringUtils.isBlank(plan.getShotSize()) || !VALID_SHOT_SIZES.contains(plan.getShotSize().toUpperCase())) {
                throw new BizException("DirectorPlan 必须由 Prompt AI 返回有效景别 (shotSize)");
            } else {
                plan.setShotSize(plan.getShotSize().toUpperCase());
            }
        }

        // 3. 摄影角度 (cameraAngle) 归一化
        if (StringUtils.isBlank(plan.getCameraAngle()) || !VALID_CAMERA_ANGLES.contains(plan.getCameraAngle().toUpperCase())) {
            throw new BizException("DirectorPlan 必须由 Prompt AI 返回有效机位角度 (cameraAngle)");
        } else {
            plan.setCameraAngle(plan.getCameraAngle().toUpperCase());
        }

        // 4. Camera Beats 时间轴与物理校验
        List<CameraBeat> beats = plan.getCameraBeats();
        if (beats == null || beats.isEmpty()) {
            throw new BizException("DirectorPlan 必须由 Prompt AI 返回 Camera Beats；Java 不会补造固定镜头");
        }
        beats = new ArrayList<>(beats);
        plan.setCameraBeats(beats);

        // 节拍数量上限检查 (10秒内最多允许 3 段)
        int maxBeats = duration.compareTo(new BigDecimal("10.0")) <= 0 ? 3 : 5;
        if (beats.size() > maxBeats) {
            log.warn("[DirectorPlanValidator] beats 数量 ({}) 超过上限 ({}), 截断保留前 {} 个",
                    beats.size(), maxBeats, maxBeats);
            beats = new ArrayList<>(beats.subList(0, maxBeats));
            plan.setCameraBeats(beats);
        }

        // 排序并检查连续性
        beats.sort((b1, b2) -> {
            BigDecimal s1 = b1.getStartSec() != null ? b1.getStartSec() : BigDecimal.ZERO;
            BigDecimal s2 = b2.getStartSec() != null ? b2.getStartSec() : BigDecimal.ZERO;
            return s1.compareTo(s2);
        });

        BigDecimal expectedStart = BigDecimal.ZERO;
        boolean isMovementLocked = cameraMovementExplicitlyLocked
                && isMovementLocked(lockedCameraMovement);
        if (cameraMovementExplicitlyLocked && isMovementLocked
                && !VALID_MOVEMENTS.contains(lockedCameraMovement.trim().toUpperCase())) {
            throw new BizException("创作者锁定的运镜无效: " + lockedCameraMovement);
        }
        String lockedMovement = isMovementLocked ? lockedCameraMovement.trim().toUpperCase() : null;

        for (int i = 0; i < beats.size(); i++) {
            CameraBeat beat = beats.get(i);
            if (beat.getStartSec() == null) beat.setStartSec(expectedStart);
            if (beat.getEndSec() == null) {
                if (i == beats.size() - 1) {
                    beat.setEndSec(duration);
                } else {
                    beat.setEndSec(beat.getStartSec().add(new BigDecimal("2.0")).min(duration));
                }
            }

            // 修正首个 Beat 起始点
            if (i == 0 && beat.getStartSec().compareTo(BigDecimal.ZERO) != 0) {
                beat.setStartSec(BigDecimal.ZERO);
            }

            // 修正连续性与空洞
            if (i > 0 && beat.getStartSec().subtract(expectedStart).abs().compareTo(EPSILON) > 0) {
                log.debug("[DirectorPlanValidator] 修正第 {} 节拍起始时间 {} -> {}", i + 1, beat.getStartSec(), expectedStart);
                beat.setStartSec(expectedStart);
            }

            // 修正最后一个 Beat 终点
            if (i == beats.size() - 1 && beat.getEndSec().subtract(duration).abs().compareTo(EPSILON) > 0) {
                beat.setEndSec(duration);
            }

            // 保证 endSec > startSec
            if (beat.getEndSec().compareTo(beat.getStartSec()) <= 0) {
                beat.setEndSec(beat.getStartSec().add(new BigDecimal("1.0")).min(duration));
            }

            // 运镜枚举归一化
            String mov = StringUtils.trimToEmpty(beat.getMovement()).toUpperCase();
            if (!VALID_MOVEMENTS.contains(mov)) {
                throw new BizException("DirectorPlan Camera Beat 必须由 Prompt AI 返回有效运镜: " + beat.getMovement());
            }

            // 用户运镜锁定检查：不得引入冲突运动
            if (isMovementLocked) {
                if (!lockedMovement.equalsIgnoreCase(mov)) {
                    log.info("[DirectorPlanValidator] 将第 {} 节拍运镜 [{}] 套用用户锁定 [{}]", i + 1, mov, lockedMovement);
                    mov = lockedMovement;
                }
            }

            beat.setMovement(mov);

            // STATIC 规则：direction 必须为空，speed 为 NONE
            if ("STATIC".equalsIgnoreCase(mov)) {
                beat.setDirection(null);
                beat.setSpeed("NONE");
            } else {
                if (StringUtils.isBlank(beat.getSpeed()) || "NONE".equalsIgnoreCase(beat.getSpeed())) {
                    beat.setSpeed("SLOW");
                } else if (!VALID_SPEEDS.contains(beat.getSpeed().toUpperCase())) {
                    beat.setSpeed("SLOW");
                } else {
                    beat.setSpeed(beat.getSpeed().toUpperCase());
                }
            }

            if (StringUtils.isBlank(beat.getStartCue())) {
                beat.setStartCue("镜头进行中");
            }
            if (StringUtils.isBlank(beat.getStopCue())) {
                beat.setStopCue("运镜完成");
            }

            expectedStart = beat.getEndSec();
        }

        // 5. 锁定字段声明更新
        List<String> lockedFields = new ArrayList<>();
        lockedFields.add("duration");
        if (isShotTypeLocked && shotTypeExplicitlyLocked) {
            lockedFields.add("shotSize");
        }
        if (isMovementLocked && cameraMovementExplicitlyLocked) {
            lockedFields.add("cameraMovement");
        }
        plan.setLockedFields(lockedFields);

        // 6. 依据后端实际执行工具记录回填 skills (绝不信任模型自报)
        List<AppliedSkillRef> actualSkills = new ArrayList<>();
        if (session != null && !session.getLoadedSkills().isEmpty()) {
            for (LoadedSkill ls : session.getLoadedSkills().values()) {
                actualSkills.add(AppliedSkillRef.builder()
                        .name(ls.getName())
                        .version(ls.getVersion())
                        .contentHash(ls.getContentHash())
                        .build());
            }
        }
        plan.setSkills(actualSkills);
    }

    private boolean isMovementLocked(String movement) {
        return StringUtils.isNotBlank(movement) && !"AUTO".equalsIgnoreCase(movement.trim());
    }
}
