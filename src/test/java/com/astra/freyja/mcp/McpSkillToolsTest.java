package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.entity.AiSkillFile;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.repository.SkillRepository;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.astra.freyja.skill.service.SkillContentService;
import com.astra.freyja.skill.validation.SkillPackageValidator;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpSkillToolsTest {
    final SkillCatalogService catalog = mock(SkillCatalogService.class);
    final SkillContentService contents = mock(SkillContentService.class);
    final AiSkillFileMapper files = mock(AiSkillFileMapper.class);
    final SkillRepository repository = mock(SkillRepository.class);
    final McpSkillTools tools = new McpSkillTools(catalog, contents, files, repository);
    final McpPrincipal caller = new McpPrincipal(false, false, false, Set.of());

    @SuppressWarnings("unchecked") Object call(String name, Object input) {
        McpTool<Object> tool = (McpTool<Object>) tools.tools().stream().filter(t -> name.equals(t.name())).findFirst().orElseThrow();
        assertEquals(McpTool.Effect.READ, tool.effect()); return tool.handler().apply(caller, input);
    }
    SkillCatalogItem enabled() {
        var item = SkillCatalogItem.builder().name("h3-prompt-writing").displayName("H3").description("H3 guide").version("1").versionId(2032095628944588801L).build();
        when(catalog.getEnabledCatalog()).thenReturn(List.of(item)); return item;
    }
    @Test void catalogPaginatesWithoutLoadingTextOrStorage() {
        enabled(); var result = (Map<?, ?>) call("list_skills", new McpInputs.Search("H3", 2, 1));
        assertEquals(1, result.get("total")); assertEquals(List.of(), result.get("records")); assertEquals(false, result.get("hasMore"));
        verifyNoInteractions(contents, files, repository);
    }
    @Test void missingDisabledAndStaleVersionsNeverReadContentOrStorage() {
        var item = enabled();
        assertEquals(404, assertThrows(BizException.class, () -> call("read_skill", new McpSkillTools.Read("unknown", null))).getCode());
        assertEquals(409, assertThrows(BizException.class, () -> call("read_skill", new McpSkillTools.Read(item.getName(), "123"))).getCode());
        when(catalog.getEnabledCatalog()).thenReturn(List.of());
        assertEquals(404, assertThrows(BizException.class, () -> call("read_skill", new McpSkillTools.Read(item.getName(), item.getVersionId().toString()))).getCode());
        verifyNoInteractions(contents, files, repository);
    }
    @Test void skillEntryHasBoundedUtf8ContentAndCannotReturnMismatchedSkill() {
        var item = enabled();
        when(contents.loadSkillVersion(item.getVersionId())).thenReturn(LoadedSkill.builder().name(item.getName()).content("字".repeat(44000)).build());
        assertEquals(413, assertThrows(BizException.class, () -> call("read_skill", new McpSkillTools.Read(item.getName(), null))).getCode());
        when(contents.loadSkillVersion(item.getVersionId())).thenReturn(LoadedSkill.builder().name("other-skill").content("other content").build());
        assertEquals(409, assertThrows(BizException.class, () -> call("read_skill", new McpSkillTools.Read(item.getName(), null))).getCode()); verifyNoInteractions(files, repository);
    }
    @Test void oversizedReferencesAndMissingIntegrityMetadataDoNotAccessStorage() {
        var item = enabled(); var file = new AiSkillFile(); file.setContentSize(128 * 1024L + 1); when(files.selectOne(any())).thenReturn(file);
        var request = new McpSkillTools.ReadFile(item.getName(), item.getVersionId().toString(), "references/base-en.txt");
        assertEquals(413, assertThrows(BizException.class, () -> call("read_skill_file", request)).getCode());
        file.setContentSize(100L);
        assertEquals(409, assertThrows(BizException.class, () -> call("read_skill_file", request)).getCode()); verifyNoInteractions(repository);
    }

    @Test void publishedStoryPackageCanBeDiscoveredAndReadWithVersionPinnedReferences() throws Exception {
        Path archive = Path.of("skills", "story-writing.zip");
        var validator = new SkillPackageValidator();
        com.astra.freyja.skill.model.StandardSkillPackage pkg;
        try (var input = Files.newInputStream(archive)) {
            pkg = validator.validateZip(input, Files.size(archive));
        }
        long versionId = 2032095628944588801L;
        var item = SkillCatalogItem.builder().name(pkg.getMetadata().getName()).displayName("故事创作与自审")
                .description(pkg.getMetadata().getDescription()).version("1").versionId(versionId).build();
        when(catalog.getEnabledCatalog()).thenReturn(List.of(item));
        var listing = (Map<?, ?>) call("list_skills", new McpInputs.Search("故事", 1, 20));
        assertEquals(List.of(item), listing.get("records"));
        verifyNoInteractions(contents, files, repository);

        when(contents.loadSkillVersion(versionId)).thenReturn(LoadedSkill.builder().name(item.getName())
                .content(pkg.getEntrypointContent()).contentHash(pkg.getPackageHash()).build());
        var referenceFiles = new ArrayList<AiSkillFile>();
        for (String path : List.of("references/story-design.md", "references/story-review.md")) {
            var file = new AiSkillFile();
            file.setRelativePath(path);
            file.setContentSize((long) pkg.getFiles().get(path).length);
            file.setContentHash(pkg.getFileHashes().get(path));
            file.setObjectKey("test-story/" + path);
            referenceFiles.add(file);
        }
        when(files.selectList(any())).thenReturn(referenceFiles);
        var entry = (Map<?, ?>) call("read_skill", new McpSkillTools.Read(item.getName(), Long.toString(versionId)));
        assertEquals(Long.toString(versionId), entry.get("versionId"));
        assertEquals(pkg.getEntrypointContent(), entry.get("content"));
        assertEquals(pkg.getPackageHash(), entry.get("contentHash"));
        assertEquals(referenceFiles.stream().map(file -> Map.of("path", file.getRelativePath(), "size", file.getContentSize())).toList(),
                entry.get("references"));
        verifyNoInteractions(repository);

        for (var file : referenceFiles) {
            String content = new String(pkg.getFiles().get(file.getRelativePath()), StandardCharsets.UTF_8);
            when(files.selectOne(any())).thenReturn(file);
            when(repository.loadTextFile(file.getObjectKey(), file.getContentHash(), file.getContentSize())).thenReturn(content);
            var reference = (Map<?, ?>) call("read_skill_file",
                    new McpSkillTools.ReadFile(item.getName(), Long.toString(versionId), file.getRelativePath()));
            assertEquals(content, reference.get("content"));
            assertEquals(file.getContentHash(), reference.get("contentHash"));
            assertEquals(Long.toString(versionId), reference.get("versionId"));
            verify(repository).loadTextFile(file.getObjectKey(), file.getContentHash(), file.getContentSize());
        }
    }
}
