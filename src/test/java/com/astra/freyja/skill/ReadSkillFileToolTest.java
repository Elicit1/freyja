package com.astra.freyja.skill;

import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.entity.AiSkillFile;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.repository.SkillRepository;
import com.astra.freyja.skill.service.SkillContentService;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.skill.tool.ReadSkillFileToolFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadSkillFileToolTest {

    private final AiSkillFileMapper fileMapper = mock(AiSkillFileMapper.class);
    private final SkillRepository repository = mock(SkillRepository.class);
    private final LoadSkillToolSession session = new LoadSkillToolSession(Map.of("h3-prompt-writing", 42L));
    private final ToolCallback tool = new ReadSkillFileToolFactory(fileMapper, repository).createTool(session);

    @Test
    void readsReferenceTextFromPinnedVersionAfterSkillIsLoaded() {
        session.preload("h3-prompt-writing", LoadedSkill.builder().name("h3-prompt-writing").content("# Skill").build());
        AiSkillFile file = new AiSkillFile();
        file.setSkillVersionId(42L);
        file.setRelativePath("references/ref-en.txt");
        file.setFileType("REFERENCE");
        file.setObjectKey("private/object-key");
        file.setContentHash("sha256:abc");
        file.setContentSize(100L);
        when(fileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(file);
        when(repository.loadTextFile("private/object-key", "sha256:abc", 100L))
                .thenReturn("Ref2VA six-section rules");

        String response = tool.call("{\"name\":\"h3-prompt-writing\",\"path\":\"references/ref-en.txt\"}");

        assertThat(response).contains("SUCCESS", "Ref2VA six-section rules", "references/ref-en.txt")
                .doesNotContain("private/object-key");
        assertThat(session.hasReadReferenceFile("h3-prompt-writing", "references/ref-en.txt")).isTrue();
        verify(fileMapper).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void reportsReferenceFileProgressWithoutExposingContent() {
        session.preload("h3-prompt-writing", LoadedSkill.builder().name("h3-prompt-writing").build());
        AiSkillFile file = new AiSkillFile();
        file.setFileType("REFERENCE");
        file.setObjectKey("private/object-key");
        file.setContentHash("sha256:abc");
        file.setContentSize(10L);
        when(fileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(file);
        when(repository.loadTextFile("private/object-key", "sha256:abc", 10L)).thenReturn("private rules");
        List<String> progress = new ArrayList<>();

        ToolCallback reportingTool = new ReadSkillFileToolFactory(fileMapper, repository)
                .createTool(session, progress::add);
        reportingTool.call("{\"name\":\"h3-prompt-writing\",\"path\":\"references/ref-en.txt\"}");

        assertThat(progress).containsExactly(
                "正在加载 h3-prompt-writing/references/ref-en.txt 文件…\n",
                "已加载 h3-prompt-writing/references/ref-en.txt 文件\n");
        assertThat(String.join("", progress)).doesNotContain("private rules", "private/object-key");
    }

    @Test
    void readsReferenceAfterDynamicLoadUsingTheSameSession() {
        SkillContentService contentService = mock(SkillContentService.class);
        when(contentService.loadSkillVersion(42L)).thenReturn(LoadedSkill.builder()
                .name("h3-prompt-writing").version("1").content("Read references/ref-en.txt").build());
        ToolCallback loadTool = new LoadSkillToolFactory(contentService).createTool(session);
        assertThat(loadTool.call("{\"name\":\"h3-prompt-writing\"}")).contains("SUCCESS");

        AiSkillFile file = new AiSkillFile();
        file.setSkillVersionId(42L);
        file.setRelativePath("references/ref-en.txt");
        file.setFileType("REFERENCE");
        file.setObjectKey("private/object-key");
        file.setContentHash("sha256:abc");
        file.setContentSize(10L);
        when(fileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(file);
        when(repository.loadTextFile("private/object-key", "sha256:abc", 10L)).thenReturn("full rules");

        assertThat(tool.call("{\"name\":\"h3-prompt-writing\",\"path\":\"references/ref-en.txt\"}"))
                .contains("SUCCESS", "full rules");
        assertThat(session.getLoadedReferenceFiles()).containsExactly("h3-prompt-writing/references/ref-en.txt");
    }

    @Test
    void refusesFileAccessBeforeSkillLoadAndOutsideReferences() {
        assertThat(tool.call("{\"name\":\"h3-prompt-writing\",\"path\":\"references/ref-en.txt\"}"))
                .contains("ERROR");
        session.preload("h3-prompt-writing", LoadedSkill.builder().name("h3-prompt-writing").build());
        assertThat(tool.call("{\"name\":\"h3-prompt-writing\",\"path\":\"references/../secret.txt\"}"))
                .contains("ERROR");
        assertThat(tool.call("{\"name\":\"h3-prompt-writing\",\"path\":\"scripts/run.sh\"}"))
                .contains("ERROR");
        verifyNoInteractions(fileMapper, repository);
    }

    @Test
    void emptyReferenceDoesNotCountAsLoaded() {
        session.preload("h3-prompt-writing", LoadedSkill.builder().name("h3-prompt-writing").build());
        AiSkillFile file = new AiSkillFile();
        file.setSkillVersionId(42L);
        file.setRelativePath("references/ref-en.txt");
        file.setFileType("REFERENCE");
        file.setObjectKey("private/object-key");
        file.setContentHash("sha256:abc");
        file.setContentSize(0L);
        when(fileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(file);
        when(repository.loadTextFile("private/object-key", "sha256:abc", 0L)).thenReturn("");

        assertThat(tool.call("{\"name\":\"h3-prompt-writing\",\"path\":\"references/ref-en.txt\"}"))
                .contains("ERROR");
        assertThat(session.hasReadReferenceFile("h3-prompt-writing", "references/ref-en.txt")).isFalse();
    }
}
