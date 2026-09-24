package com.astra.freyja.skill;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.dao.AiSkillVersionMapper;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.entity.AiSkillVersion;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.service.SkillCatalogServiceImpl;
import com.astra.freyja.skill.service.SkillContentService;
import com.astra.freyja.skill.tool.LoadSkillResponse;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillCatalog 与 Tool Calling 单元测试")
public class SkillCatalogAndToolTest {

    @Mock
    private AiSkillMapper skillMapper;
    @Mock
    private AiSkillVersionMapper versionMapper;
    @Mock
    private SkillContentService skillContentService;

    @InjectMocks
    private SkillCatalogServiceImpl catalogService;

    @Test
    @DisplayName("仅已启用且具备当前标准 SKILL.md 版本的 Skill 进入 AI 目录")
    void testOnlyEnabledAndCurrentVersionEnterCatalog() {
        AiSkill skill1 = new AiSkill();
        skill1.setId(1L);
        skill1.setName("cinematography");
        skill1.setEnabled(1);
        skill1.setCurrentVersionId(10L);

        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(skill1));

        AiSkillVersion ver1 = new AiSkillVersion();
        ver1.setId(10L);
        ver1.setRevisionNo(1);

        when(versionMapper.selectById(10L)).thenReturn(ver1);

        List<SkillCatalogItem> items = catalogService.getEnabledCatalog();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("cinematography");
        assertThat(items.get(0).getVersion()).isEqualTo("1");

        String promptCatalog = catalogService.formatCatalogForPrompt();
        assertThat(promptCatalog).contains("cinematography (v1)");

        // 目录查询与当前版本查询应命中缓存；手动刷新后下一次读取才重新访问数据库。
        catalogService.getEnabledCatalog();
        verify(skillMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
        catalogService.refreshCache();
        catalogService.getEnabledCatalog();
        verify(skillMapper, times(2)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("load_skill 工具按版本快照加载并允许按需加载任意数量 Skill")
    void testLoadSkillToolGuardrails() {
        LoadSkillToolFactory toolFactory = new LoadSkillToolFactory(skillContentService);
        LoadSkillToolSession session = new LoadSkillToolSession(Map.of(
                "cinematography", 10L, "skill-2", 11L, "skill-3", 12L, "skill-4", 13L));
        ToolCallback tool = toolFactory.createTool(session);

        LoadedSkill loaded = LoadedSkill.builder()
                .name("cinematography")
                .version("1.0.0")
                .contentHash("sha256:abc")
                .content("# Cinematography Content")
                .build();
        when(skillContentService.loadSkillVersion(10L)).thenReturn(loaded);

        // 1. 第一次调用：成功加载
        String res1 = tool.call("{\"name\":\"cinematography\"}");
        assertThat(res1).contains("SUCCESS");
        assertThat(res1).contains("# Cinematography Content");
        assertThat(session.hasLoaded("cinematography")).isTrue();
        assertThat(session.getInvokedSkillNames()).containsExactly("cinematography");

        // 2. 第二次调用相同 Skill：返回 ALREADY_LOADED 避免重复注入
        String res2 = tool.call("{\"name\":\"cinematography\"}");
        assertThat(res2).contains("ALREADY_LOADED");
        verify(skillContentService, times(1)).loadSkillVersion(10L);

        // 3. 不再有项目级固定数量上限，后续 Skill 仍可按需加载。
        when(skillContentService.loadSkillVersion(11L)).thenReturn(LoadedSkill.builder().name("skill-2").version("1.0.0").build());
        when(skillContentService.loadSkillVersion(12L)).thenReturn(LoadedSkill.builder().name("skill-3").version("1.0.0").build());
        when(skillContentService.loadSkillVersion(13L)).thenThrow(new BizException("Skill 正文加载失败"));

        tool.call("{\"name\":\"skill-2\"}");
        tool.call("{\"name\":\"skill-3\"}");

        // 第 4 个不同 Skill 调用不会因为项目级上限被拦截，但正文错误仍返回 ERROR。
        String res4 = tool.call("{\"name\":\"skill-4\"}");
        assertThat(res4).contains("ERROR");
        assertThat(res4).contains("Skill 正文加载失败");
        assertThat(session.getInvocationHistory())
                .containsExactly("cinematography", "cinematography", "skill-2", "skill-3", "skill-4");
        assertThat(session.getInvokedSkillNames())
                .containsExactly("cinematography", "skill-2", "skill-3", "skill-4");
    }

    @Test
    @DisplayName("加载不存在或被停用的 Skill 优雅返回错误提示")
    void testLoadUnknownSkillGracefulError() {
        LoadSkillToolFactory toolFactory = new LoadSkillToolFactory(skillContentService);
        LoadSkillToolSession session = new LoadSkillToolSession(Map.of("unknown", 99L));
        ToolCallback tool = toolFactory.createTool(session);

        when(skillContentService.loadSkillVersion(99L)).thenThrow(new BizException("未找到名为 [unknown] 的 AI 技能"));

        String res = tool.call("{\"name\":\"unknown\"}");
        assertThat(res).contains("ERROR");
        assertThat(res).contains("未找到名为 [unknown] 的 AI 技能");
    }
}
