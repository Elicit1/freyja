package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpReadToolsTest {
    static final long PROJECT = 2032095628944588801L;
    final DramaMapper dramas = mock(DramaMapper.class);
    final DramaEpisodeMapper episodes = mock(DramaEpisodeMapper.class);
    final DramaSceneMapper scenes = mock(DramaSceneMapper.class);
    final DramaShotGroupMapper groups = mock(DramaShotGroupMapper.class);
    final DramaShotMapper shots = mock(DramaShotMapper.class);
    final DramaService dramaService = mock(DramaService.class);
    final DramaShotService shotService = mock(DramaShotService.class);
    final McpQueryMapper queries = mock(McpQueryMapper.class);
    final McpAssets assets = mock(McpAssets.class);
    final McpScope scope = new McpScope(dramas, episodes, scenes, groups, shots);
    final McpJson json = new McpJson();
    final McpToolCatalog catalog = new McpToolCatalog(json);
    final McpPrincipal caller = new McpPrincipal(false, false, false, Set.of(PROJECT));
    McpReadTools reads;

    @BeforeEach void setup() {
        reads = new McpReadTools(dramas, episodes, scenes, groups, new McpBusinessOptions(mock(DictService.class), new McpSchemaMetadata(json), json), queries, assets, scope, json, new McpBusinessView(json, new McpSchemaMetadata(json)));
        Drama drama = new Drama(); drama.setId(PROJECT); when(dramas.selectById(PROJECT)).thenReturn(drama);
    }

    McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
        McpTool<?> tool = reads.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        return catalog.specification(tool).callHandler().apply(McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, caller)),
                new McpSchema.CallToolRequest(name, arguments));
    }

    @Test void scopeRejectsUnauthorizedProjectsBeforeQueryingBusinessServices() {
        var result = call("get_project", Map.of("dramaId", "2032095628944588802"));
        assertTrue(result.isError());
        verifyNoInteractions(dramaService);
        verify(dramas, never()).selectById(2032095628944588802L);
    }

    @Test void rejectsCrossProjectShotsAndOrphanedParents() {
        DramaShot shot = new DramaShot(); shot.setId(10L); shot.setDramaId(PROJECT + 1); when(shots.selectById(10L)).thenReturn(shot);
        assertTrue(call("get_shot", Map.of("dramaId", Long.toString(PROJECT), "shotId", "10")).isError());
        verifyNoInteractions(shotService);
        shot.setDramaId(PROJECT); shot.setSceneId(11L);
        assertTrue(call("get_shot", Map.of("dramaId", Long.toString(PROJECT), "shotId", "10")).isError());
    }

    @Test void paginationReachesSqlAndDoesNotLoadTheWholeProject() {
        Page<DramaShot> result = new Page<>(2, 5, 12); result.setRecords(List.of());
        when(queries.shots(any(), eq(PROJECT), isNull(), isNull())).thenReturn(result);
        assertFalse(call("list_shots", Map.of("dramaId", Long.toString(PROJECT), "page", 2, "size", 5)).isError());
        verify(queries).shots(argThat(p -> p.getCurrent() == 2 && p.getSize() == 5), eq(PROJECT), isNull(), isNull());
        assertTrue(call("list_shots", Map.of("dramaId", Long.toString(PROJECT), "size", 51)).isError());
    }

    @Test void missingUnknownAndNumericIdArgumentsAreRejected() {
        assertTrue(call("get_project", Map.of()).isError());
        assertTrue(call("get_project", Map.of("dramaId", PROJECT)).isError());
        assertTrue(call("get_project", Map.of("dramaId", Long.toString(PROJECT), "sql", "SELECT 1")).isError());
        verifyNoInteractions(dramaService);
    }

    @Test void identifierParserRejectsEmptyZeroNegativeAndOverflowValues() {
        for (String id : List.of("", "0", "-1", "20320956289445888010", "9223372036854775808", "1.0"))
            assertThrows(BizException.class, () -> McpScope.id(id));
        assertEquals(PROJECT, McpScope.id(Long.toString(PROJECT)));
    }

    @Test void allReadToolsHaveReadOnlyAnnotationsAndNoManagementTools() {
        assertEquals(9, reads.tools().size());
        for (var tool : reads.tools()) assertTrue(catalog.specification(tool).tool().annotations().readOnlyHint());
        assertFalse(reads.tools().stream().anyMatch(t -> t.name().contains("delete") || t.name().contains("config")));
    }
}
