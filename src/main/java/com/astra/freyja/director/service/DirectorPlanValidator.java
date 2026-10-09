package com.astra.freyja.director.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.director.model.AppliedSkillRef;
import com.astra.freyja.director.model.CameraBeat;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.director.model.DirectorPlanSchema;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 结构化导演决策确定性校验器 (DirectorPlanValidator)。
 * 纯物理时间轴、结构与枚举校验，严禁包含基于自然语言剧情关键字的运镜猜测代码。
 */
@Slf4j
@Component
public class DirectorPlanValidator {

    private static final BigDecimal EPSILON = new BigDecimal("0.05");

    private static final Set<String> VALID_SHOT_SIZES = DirectorPlanSchema.SHOT_SIZES;
    private static final Set<String> VALID_CAMERA_ANGLES = DirectorPlanSchema.CAMERA_ANGLES;
    private static final Set<String> VALID_MOVEMENTS = DirectorPlanSchema.MOVEMENTS;
    private static final Set<String> VALID_SPEEDS = DirectorPlanSchema.SPEEDS;

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
        if (!DirectorPlanSchema.VERSION.equals(plan.getSchemaVersion())) {
            throw new BizException("DirectorPlan $.schemaVersion 无效，实际值=" + plan.getSchemaVersion()
                    + "，要求=" + DirectorPlanSchema.VERSION);
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
            String canonicalLockedShot = lockedShotType.trim().toUpperCase(Locale.ROOT);
            if (!VALID_SHOT_SIZES.contains(canonicalLockedShot)) {
                throw DirectorPlanSchema.invalidEnum("创作者锁定 shotType", lockedShotType, VALID_SHOT_SIZES);
            }
            if (!canonicalLockedShot.equalsIgnoreCase(plan.getShotSize())) {
                log.info("[DirectorPlanValidator] 套用创作者锁定的景别 [{}], 覆盖 AI 建议 [{}]",
                        canonicalLockedShot, plan.getShotSize());
                plan.setShotSize(canonicalLockedShot);
            }
        } else {
            if (StringUtils.isBlank(plan.getShotSize()) || !VALID_SHOT_SIZES.contains(plan.getShotSize().toUpperCase(Locale.ROOT))) {
                throw DirectorPlanSchema.invalidEnum("$.shotSize", plan.getShotSize(), VALID_SHOT_SIZES);
            } else {
                plan.setShotSize(plan.getShotSize().toUpperCase(Locale.ROOT));
            }
        }

        // 3. 摄影角度 (cameraAngle) 归一化
        if (StringUtils.isBlank(plan.getCameraAngle()) || !VALID_CAMERA_ANGLES.contains(plan.getCameraAngle().toUpperCase(Locale.ROOT))) {
            throw DirectorPlanSchema.invalidEnum("$.cameraAngle", plan.getCameraAngle(), VALID_CAMERA_ANGLES);
        } else {
            plan.setCameraAngle(plan.getCameraAngle().toUpperCase(Locale.ROOT));
        }

        // 4. Camera Beats 时间轴与物理校验
        List<CameraBeat> beats = plan.getCameraBeats();
        if (beats == null || beats.isEmpty()) {
            throw new BizException("DirectorPlan $.cameraBeats 必须为非空数组，实际值=" + beats);
        }
        for (int i = 0; i < beats.size(); i++) {
            if (beats.get(i) == null) throw new BizException("DirectorPlan $.cameraBeats[" + i + "] 必须为对象，实际值=null");
        }
        beats = new ArrayList<>(beats);
        plan.setCameraBeats(beats);

        // 节拍数量上限检查 (10秒内最多允许 3 段)
        int maxBeats = DirectorPlanSchema.maxBeats(duration);
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
                && !VALID_MOVEMENTS.contains(lockedCameraMovement.trim().toUpperCase(Locale.ROOT))) {
            throw DirectorPlanSchema.invalidEnum("创作者锁定 cameraMovement", lockedCameraMovement, VALID_MOVEMENTS);
        }
        String lockedMovement = isMovementLocked ? lockedCameraMovement.trim().toUpperCase(Locale.ROOT) : null;

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
            String mov = StringUtils.trimToEmpty(beat.getMovement()).toUpperCase(Locale.ROOT);
            if (!VALID_MOVEMENTS.contains(mov)) {
                throw DirectorPlanSchema.invalidEnum("$.cameraBeats[" + i + "].movement", beat.getMovement(), VALID_MOVEMENTS);
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
                } else if (!VALID_SPEEDS.contains(beat.getSpeed().toUpperCase(Locale.ROOT))) {
                    beat.setSpeed("SLOW");
                } else {
                    beat.setSpeed(beat.getSpeed().toUpperCase(Locale.ROOT));
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

    /** Strict validation for creator content. Never repairs, sorts or truncates it. */
    public void validateManual(DirectorPlan plan, BigDecimal duration, String shotType,
                               String movement, boolean sizeLocked, boolean movementLocked) {
        if (plan == null || !DirectorPlanSchema.VERSION.equals(plan.getSchemaVersion())) fail("计划及 schemaVersion 1.0 必须有效");
        if (duration == null || duration.signum() <= 0 || plan.getDuration() == null
                || plan.getDuration().compareTo(duration) != 0) fail("计划时长必须与分镜时长一致且大于零");
        if (!VALID_SHOT_SIZES.contains(plan.getShotSize())) fail("$.shotSize 无效，实际值=" + plan.getShotSize() + "，合法值=" + VALID_SHOT_SIZES);
        if (!VALID_CAMERA_ANGLES.contains(plan.getCameraAngle())) fail("$.cameraAngle 无效，实际值=" + plan.getCameraAngle() + "，合法值=" + VALID_CAMERA_ANGLES);
        if (sizeLocked && isMovementLocked(shotType) && !plan.getShotSize().equals(shotType)) fail("计划景别与分镜锁定冲突");
        List<CameraBeat> beats = plan.getCameraBeats();
        int max = DirectorPlanSchema.maxBeats(duration);
        if (beats == null || beats.isEmpty() || beats.size() > max) fail("运镜节拍数量必须为 1 到 " + max);
        BigDecimal cursor = BigDecimal.ZERO;
        for (int i = 0; i < beats.size(); i++) {
            CameraBeat beat = beats.get(i);
            if (beat == null || beat.getStartSec() == null || beat.getEndSec() == null
                    || beat.getStartSec().compareTo(cursor) != 0
                    || beat.getEndSec().compareTo(cursor) <= 0
                    || beat.getEndSec().compareTo(duration) > 0) fail("节拍必须从 0 连续覆盖分镜时长，不能重叠或留空");
            if (!VALID_MOVEMENTS.contains(beat.getMovement())) fail("$.cameraBeats[" + i + "].movement 无效，实际值=" + beat.getMovement() + "，合法值=" + VALID_MOVEMENTS);
            if (!VALID_SPEEDS.contains(beat.getSpeed())) fail("$.cameraBeats[" + i + "].speed 无效，实际值=" + beat.getSpeed() + "，合法值=" + VALID_SPEEDS);
            if (movementLocked && isMovementLocked(movement) && !beat.getMovement().equals(movement)) fail("运镜与分镜锁定冲突");
            if ("STATIC".equals(beat.getMovement())) {
                if (StringUtils.isNotBlank(beat.getDirection()) || !"NONE".equals(beat.getSpeed())) fail("STATIC 方向必须为空、速度必须为 NONE");
            } else if ("NONE".equals(beat.getSpeed())) fail("运动镜头速度不能为 NONE");
            cursor = beat.getEndSec();
        }
        if (cursor.compareTo(duration) != 0) fail("节拍终点必须等于分镜时长");
    }

    private void fail(String message) { throw new BizException(400, "手工导演计划：" + message); }

    private boolean isMovementLocked(String movement) {
        return StringUtils.isNotBlank(movement) && !"AUTO".equalsIgnoreCase(movement.trim());
    }
}
