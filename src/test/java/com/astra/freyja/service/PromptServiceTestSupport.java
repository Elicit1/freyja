package com.astra.freyja.service;

import com.astra.freyja.capability.service.CapabilityCatalogService;
import com.astra.freyja.director.service.DirectorPlanStateService;
import com.astra.freyja.director.service.DirectorPlanValidator;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.service.impl.ShotAiVisualPlanServiceImpl;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.tool.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prompt tests explicitly supply the same dependencies required in production. */
public final class PromptServiceTestSupport {
    public static void configure(ShotAiVisualPlanServiceImpl service) {
        if (ReflectionTestUtils.getField(service, "capabilityCatalog") == null) {
            var catalog = mock(CapabilityCatalogService.class); lenient().when(catalog.rules()).thenReturn("测试平台公共规则");
            ReflectionTestUtils.setField(service, "capabilityCatalog", catalog);
        }
        configureSkills(service);
        if (ReflectionTestUtils.getField(service, "directorPlanStateService") == null)
            ReflectionTestUtils.setField(service, "directorPlanStateService", new DirectorPlanStateService(
                    (DramaShotMapper) ReflectionTestUtils.getField(service, "shotMapper"), new DirectorPlanValidator()));
        if (ReflectionTestUtils.getField(service, "aiTaskService") == null) {
            var tasks = mock(AiTaskService.class);
            lenient().when(tasks.createTask(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
                var task = new AiTask(); task.setId(900L); return task;
            });
            ReflectionTestUtils.setField(service, "aiTaskService", tasks);
        }
        if (ReflectionTestUtils.getField(service, "shotPromptEventStore") == null)
            ReflectionTestUtils.setField(service, "shotPromptEventStore", mock(ShotPromptEventStore.class));
    }
    public static void configureSkills(Object service) {
        if (ReflectionTestUtils.getField(service, "skillPromptContextService") == null) {
            var skills = mock(SkillPromptContextService.class);
            lenient().when(skills.loadSelected(any(), any(), any())).thenReturn(SkillPromptContext.empty());
            lenient().when(skills.loadSelected(any(), any(), any(), any())).thenReturn(SkillPromptContext.empty());
            lenient().when(skills.appendToSystemPrompt(anyString(), any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(skills.appendCatalogToSystemPrompt(anyString())).thenAnswer(i -> i.getArgument(0));
            lenient().when(skills.createApiSession(any(), any(), any())).thenAnswer(i -> new LoadSkillToolSession(Map.of()));
            ReflectionTestUtils.setField(service, "skillPromptContextService", skills);
        }
        var loader = mock(LoadSkillToolFactory.class); var reader = mock(ReadSkillFileToolFactory.class);
        lenient().when(loader.createTool(any(), any())).thenReturn(callback("load_skill"));
        lenient().when(reader.createTool(any(), any())).thenReturn(callback("read_skill_file"));
        if (ReflectionTestUtils.getField(service, "loadSkillToolFactory") == null) ReflectionTestUtils.setField(service, "loadSkillToolFactory", loader);
        if (ReflectionTestUtils.getField(service, "readSkillFileToolFactory") == null) ReflectionTestUtils.setField(service, "readSkillFileToolFactory", reader);
    }
    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\",\"properties\":{}}").build(); }
            public String call(String input) { throw new AssertionError("Fixture model must not execute Skill tools"); }
        };
    }
}
