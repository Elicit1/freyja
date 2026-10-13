package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.entity.AiSkillFile;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.repository.SkillRepository;
import com.astra.freyja.skill.service.SkillCatalogService;
import com.astra.freyja.skill.service.SkillContentService;
import org.junit.jupiter.api.Test;

import java.util.*;

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
}
