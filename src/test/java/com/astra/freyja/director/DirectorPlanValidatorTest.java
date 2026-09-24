package com.astra.freyja.director;

import com.astra.freyja.director.model.CameraBeat;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.director.service.DirectorPlanMergeServiceImpl;
import com.astra.freyja.director.service.DirectorPlanValidator;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DirectorPlanValidator 与 Merge 单元测试")
public class DirectorPlanValidatorTest {

    private final DirectorPlanValidator validator = new DirectorPlanValidator();
    private final DirectorPlanMergeServiceImpl mergeService = new DirectorPlanMergeServiceImpl();

    @Test
    @DisplayName("固定镜头 STATIC 时 direction 为空且 speed 归一化为 NONE")
    void testStaticShotNormalization() {
        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("4.0"))
                .shotSize("CLOSE_UP")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(new ArrayList<>(List.of(
                        CameraBeat.builder()
                                .startSec(BigDecimal.ZERO)
                                .endSec(new BigDecimal("4.0"))
                                .movement("STATIC")
                                .direction("FORWARD") // 非法参数，应该被置空
                                .speed("FAST")        // 非法参数，应该被置为 NONE
                                .build()
                )))
                .build();

        validator.validateAndNormalize(plan, new BigDecimal("4.0"), "AUTO", "AUTO", false, false, null);

        CameraBeat beat = plan.getCameraBeats().get(0);
        assertThat(beat.getMovement()).isEqualTo("STATIC");
        assertThat(beat.getDirection()).isNull();
        assertThat(beat.getSpeed()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("多 Beat 时间轴自动修正首尾与连续性空洞")
    void testMultiBeatContinuityCorrection() {
        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("6.0"))
                .shotSize("MEDIUM_SHOT")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(new ArrayList<>(List.of(
                        CameraBeat.builder()
                                .startSec(new BigDecimal("0.5")) // 未从0开始
                                .endSec(new BigDecimal("3.0"))
                                .movement("STATIC")
                                .build(),
                        CameraBeat.builder()
                                .startSec(new BigDecimal("3.5")) // 存在0.5s空洞
                                .endSec(new BigDecimal("5.0"))   // 未到 6.0 结束
                                .movement("PUSH_IN")
                                .speed("SLOW")
                                .build()
                )))
                .build();

        validator.validateAndNormalize(plan, new BigDecimal("6.0"), "AUTO", "AUTO", false, false, null);

        List<CameraBeat> beats = plan.getCameraBeats();
        assertThat(beats.get(0).getStartSec()).isEqualByComparingTo("0.0");
        assertThat(beats.get(0).getEndSec()).isEqualByComparingTo("3.0");
        assertThat(beats.get(1).getStartSec()).isEqualByComparingTo("3.0");
        assertThat(beats.get(1).getEndSec()).isEqualByComparingTo("6.0");
    }

    @Test
    @DisplayName("用户锁定具体景别时，强制保留用户锁定并声明 lockedFields")
    void testUserLockShotSizePreserved() {
        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("5.0"))
                .shotSize("FULL_SHOT") // AI 想用全景
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(new ArrayList<>(List.of(CameraBeat.builder()
                        .startSec(BigDecimal.ZERO)
                        .endSec(new BigDecimal("5.0"))
                        .movement("STATIC")
                        .speed("NONE")
                        .build())))
                .build();

        // 用户显式锁定了 CLOSE_UP
        validator.validateAndNormalize(plan, new BigDecimal("5.0"), "CLOSE_UP", "AUTO", true, false, null);

        assertThat(plan.getShotSize()).isEqualTo("CLOSE_UP");
        assertThat(plan.getLockedFields()).contains("shotSize");
    }

    @Test
    @DisplayName("用户锁定运镜时，AI 不得生成冲突运镜")
    void testUserLockCameraMovementEnforced() {
        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("5.0"))
                .shotSize("MEDIUM_SHOT")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(new ArrayList<>(List.of(
                        CameraBeat.builder()
                                .startSec(BigDecimal.ZERO)
                                .endSec(new BigDecimal("5.0"))
                                .movement("PAN_LEFT") // AI 输出了左摇
                                .speed("SLOW")
                                .build()
                )))
                .build();

        // 用户显式锁定了 PUSH_IN
        validator.validateAndNormalize(plan, new BigDecimal("5.0"), "AUTO", "PUSH_IN", false, true, null);

        assertThat(plan.getCameraBeats().get(0).getMovement()).isEqualTo("PUSH_IN");
        assertThat(plan.getLockedFields()).contains("cameraMovement");
    }

    @Test
    @DisplayName("AUTO 状态在分镜数据库实体中被严密保护，不被具体值覆盖")
    void testAutoStatusPreservedInDramaShot() {
        DramaShot shot = new DramaShot();
        shot.setId(9999L);
        shot.setShotType("AUTO");
        shot.setCameraMovement("AUTO");

        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("3.0"))
                .shotSize("MEDIUM_CLOSE_UP")
                .cameraBeats(List.of(CameraBeat.builder().movement("PULL_OUT").build()))
                .build();

        mergeService.applyPlanToShot(shot, plan);

        // 验证 shotType 和 cameraMovement 仍然是 AUTO，未被篡改
        assertThat(shot.getShotType()).isEqualTo("AUTO");
        assertThat(shot.getCameraMovement()).isEqualTo("AUTO");
        assertThat(shot.getDirectorPlanJson()).contains("MEDIUM_CLOSE_UP");

        // 验证下游适配器能提取有效单值
        String effectiveShot = mergeService.getEffectiveShotType(shot, plan);
        String primaryMovement = mergeService.getPrimaryCameraMovement(shot, plan);
        assertThat(effectiveShot).isEqualTo("MEDIUM_CLOSE_UP");
        assertThat(primaryMovement).isEqualTo("PULL_OUT");
    }

    @Test
    @DisplayName("实际加载的 Skill 严谨回填至 Plan 的 skills 数组")
    void testSkillsBackfilledFromToolSession() {
        LoadSkillToolSession session = new LoadSkillToolSession();
        session.recordCall("cinematography", LoadedSkill.builder()
                .name("cinematography")
                .version("1.0.0")
                .contentHash("sha256:123456")
                .build());

        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("4.0"))
                .shotSize("MEDIUM_SHOT")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(new ArrayList<>(List.of(CameraBeat.builder()
                        .startSec(BigDecimal.ZERO)
                        .endSec(new BigDecimal("4.0"))
                        .movement("PULL_OUT")
                        .speed("SLOW")
                        .build())))
                .build();

        validator.validateAndNormalize(plan, new BigDecimal("4.0"), "AUTO", "AUTO", false, false, session);

        assertThat(plan.getSkills()).hasSize(1);
        assertThat(plan.getSkills().get(0).getName()).isEqualTo("cinematography");
        assertThat(plan.getSkills().get(0).getVersion()).isEqualTo("1.0.0");
        assertThat(plan.getSkills().get(0).getContentHash()).isEqualTo("sha256:123456");
    }

    @Test
    @DisplayName("未指定运镜时保留 Prompt AI 的轻微横摇规划")
    void testAiMovementIsPreservedWhenUserDidNotSpecify() {
        DirectorPlan plan = validPlan("PAN_LEFT", "轻微横摇，随人物擦肩而过后收住");

        validator.validateAndNormalize(plan, new BigDecimal("5.0"), null, null, false, false, null);

        assertThat(plan.getCameraBeats().getFirst().getMovement()).isEqualTo("PAN_LEFT");
        assertThat(plan.getCameraBeats().getFirst().getNarrativePurpose()).contains("轻微横摇");
        assertThat(plan.getLockedFields()).doesNotContain("cameraMovement");
    }

    @Test
    @DisplayName("显式 AUTO 仍表示未指定，景别与运镜由 Prompt AI 自主规划")
    void testAutoValuesNeverBecomeLocksOrDefaults() {
        DirectorPlan plan = validPlan("TRACKING", "跟随人物移动");
        plan.setShotSize("TOP_DOWN");

        validator.validateAndNormalize(plan, new BigDecimal("5.0"), "AUTO", "AUTO", true, true, null);

        assertThat(plan.getShotSize()).isEqualTo("TOP_DOWN");
        assertThat(plan.getCameraBeats().getFirst().getMovement()).isEqualTo("TRACKING");
        assertThat(plan.getLockedFields()).doesNotContain("shotSize", "cameraMovement");
    }

    @Test
    @DisplayName("显式锁定 STATIC 时所有 Camera Beats 均保持固定")
    void testExplicitStaticLockIsEnforced() {
        DirectorPlan plan = validPlan("PAN_LEFT", "跟随擦肩动作");

        validator.validateAndNormalize(plan, new BigDecimal("5.0"), null, "STATIC", false, true, null);

        assertThat(plan.getCameraBeats().getFirst().getMovement()).isEqualTo("STATIC");
        assertThat(plan.getCameraBeats().getFirst().getSpeed()).isEqualTo("NONE");
        assertThat(plan.getLockedFields()).contains("cameraMovement");
    }

    @Test
    @DisplayName("未确认的历史 STATIC 值不会覆盖 Prompt AI 的规划")
    void testUnconfirmedHistoricalValueDoesNotLockMovement() {
        DirectorPlan plan = validPlan("TRACKING", "跟随人物走动");

        validator.validateAndNormalize(plan, new BigDecimal("5.0"), null, "STATIC", false, false, null);

        assertThat(plan.getCameraBeats().getFirst().getMovement()).isEqualTo("TRACKING");
        assertThat(plan.getLockedFields()).doesNotContain("cameraMovement");
    }

    @Test
    @DisplayName("导演规划缺少 Camera Beats 时拒绝生成虚假的 STATIC")
    void testMissingCameraBeatsAreNotReplacedWithStatic() {
        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("5.0"))
                .shotSize("MEDIUM_SHOT")
                .cameraAngle("EYE_LEVEL")
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validateAndNormalize(
                        plan, new BigDecimal("5.0"), null, null, false, false, null))
                .isInstanceOf(com.astra.freyja.common.BizException.class)
                .hasMessageContaining("Camera Beats");
        assertThat(plan.getCameraBeats()).isEmpty();
    }

    private DirectorPlan validPlan(String movement, String purpose) {
        return DirectorPlan.builder()
                .duration(new BigDecimal("5.0"))
                .shotSize("MEDIUM_CLOSE_UP")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(new ArrayList<>(List.of(CameraBeat.builder()
                        .startSec(BigDecimal.ZERO)
                        .endSec(new BigDecimal("5.0"))
                        .movement(movement)
                        .speed("SLOW")
                        .narrativePurpose(purpose)
                        .build())))
                .build();
    }
}
