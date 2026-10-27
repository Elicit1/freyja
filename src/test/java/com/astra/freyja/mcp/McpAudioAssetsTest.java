package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResAudioMapper;
import com.astra.freyja.entity.ResAudio;
import com.astra.freyja.util.ShotAudioReferenceValidator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(McpBusinessDatabaseTest.Config.class)
@TestPropertySource(properties = "spring.ai.mcp.server.enabled=true")
class McpAudioAssetsTest {
    @Autowired AutowireCapableBeanFactory factory;
    @Autowired ResAudioMapper mapper;
    McpBusinessDatabaseTest fixture;
    final long id = 2032095628944588901L;

    @BeforeEach void setup() {
        fixture = new McpBusinessDatabaseTest(); factory.autowireBean(fixture); fixture.setup();
    }
    ResAudio asset(long key, long project, int status) {
        var audio = new ResAudio(); audio.setId(key); audio.setDramaId(project); audio.setName("鼓点 " + key);
        audio.setAudioUrl("https://assets.example.test/music-" + key + ".wav"); audio.setDuration(new BigDecimal("4.25"));
        audio.setAudioKind("MUSIC"); audio.setStatus(status); audio.setSortOrder(0); audio.setText("原始歌词"); audio.setLanguage("zh");
        audio.setDescription("仅参考鼓点"); audio.setRemark("保留备注"); mapper.insert(audio);
        return mapper.selectById(key);
    }
    Map<String, Object> get(long key) {
        return fixture.map(fixture.call("get_audio_asset", fixture.caller, new McpRecordInputs.Get(null, Long.toString(key))));
    }
    Object update(String request, long key, String fingerprint, Map<String, Object> patch) {
        return fixture.call("update_audio_asset", fixture.caller, new McpRecordInputs.Update(request, null, Long.toString(key), fingerprint, patch));
    }
    List<?> search(McpAudioAssets.Search input) {
        return (List<?>) fixture.map(fixture.call("search_audio_assets", fixture.caller, input)).get("records");
    }

    @Test void projectSearchIncludesPublicButExcludesOtherProjectsDisabledAndDeleted() {
        asset(id, McpBusinessDatabaseTest.PROJECT, 1); asset(id + 1, 0L, 1);
        asset(id + 2, McpBusinessDatabaseTest.PROJECT + 1, 1); asset(id + 3, McpBusinessDatabaseTest.PROJECT, 0);
        asset(id + 4, McpBusinessDatabaseTest.PROJECT, 1); mapper.deleteById(id + 4);
        var records = search(new McpAudioAssets.Search(McpBusinessDatabaseTest.ROOT, null, "鼓点", "MUSIC", null, null, 1, 20));
        assertEquals(Set.of(Long.toString(id), Long.toString(id + 1)), records.stream().map(row -> fixture.map(row).get("id")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, search(new McpAudioAssets.Search(null, true, null, null, null, null, 1, 20)).size());
        assertEquals(1, search(new McpAudioAssets.Search(McpBusinessDatabaseTest.ROOT, null, null, null, null, 0, 1, 20)).size());
        assertThrows(BizException.class, () -> search(new McpAudioAssets.Search(Long.toString(McpBusinessDatabaseTest.PROJECT + 1), null, null, null, null, null, 1, 20)));
        assertThrows(BizException.class, () -> search(new McpAudioAssets.Search(McpBusinessDatabaseTest.ROOT, true, null, null, null, null, 1, 20)));
    }

    @Test void readsPreserveSnowflakesAndOnlyExposeWhitelistedFields() {
        var audio = asset(id, McpBusinessDatabaseTest.PROJECT, 1);
        var result = get(id); assertEquals(Long.toString(id), result.get("id")); assertEquals(audio.getAudioUrl(), result.get("audioUrl"));
        assertFalse(result.containsKey("createBy")); assertFalse(result.containsKey("deleted")); assertNotNull(result.get("editFingerprint"));
        var compact = fixture.map(fixture.call("get_audio_asset", fixture.caller, new McpRecordInputs.Get(null, Long.toString(id), List.of("duration"))));
        assertEquals(Set.of("id", "duration", "editFingerprint"), compact.keySet());
        assertThrows(BizException.class, () -> fixture.call("get_audio_asset", fixture.caller, new McpRecordInputs.Get(null, Long.toString(id), List.of("deleted"))));
        asset(id + 1, McpBusinessDatabaseTest.PROJECT + 1, 1);
        assertEquals(403, assertThrows(BizException.class, () -> get(id + 1)).getCode());
    }

    @Test void metadataPatchIsSparseVersionedIdempotentAndCannotReplaceMedia() {
        var audio = asset(id, McpBusinessDatabaseTest.PROJECT, 1); String fingerprint = (String) get(id).get("editFingerprint");
        Object result = update("audio-metadata01", id, fingerprint, Map.of("name", "新名称"));
        assertEquals(result, update("audio-metadata01", id, fingerprint, Map.of("name", "新名称")));
        assertEquals(audio.getAudioUrl(), mapper.selectById(id).getAudioUrl());
        assertEquals("原始歌词", mapper.selectById(id).getText()); assertEquals("保留备注", mapper.selectById(id).getRemark());
        assertEquals(409, assertThrows(BizException.class, () -> update("audio-stale-req1", id, fingerprint, Map.of("name", "覆盖"))).getCode());
        String latest = (String) get(id).get("editFingerprint");
        assertThrows(BizException.class, () -> update("audio-change-url", id, latest, Map.of("audioUrl", "https://other/file.wav")));
        var clear = new LinkedHashMap<String, Object>(); clear.put("text", null); clear.put("language", null); clear.put("remark", null); clear.put("description", null);
        update("audio-clear-text", id, latest, clear);
        var saved = mapper.selectById(id); assertNull(saved.getText()); assertNull(saved.getLanguage()); assertNull(saved.getDescription()); assertNull(saved.getRemark());
        assertEquals("MUSIC", saved.getAudioKind());
    }

    @Test void publicLibraryIsReadOnlyAndWriterCannotModifyOtherProjects() {
        asset(id, 0L, 1); String fingerprint = (String) get(id).get("editFingerprint");
        assertEquals(403, assertThrows(BizException.class, () -> update("audio-public-001", id, fingerprint, Map.of("name", "修改"))).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> fixture.call("delete_audio_asset", fixture.caller, new McpRecordInputs.Delete("audio-public-del", null, Long.toString(id), fingerprint))).getCode());
        asset(id + 1, McpBusinessDatabaseTest.PROJECT, 1);
        var reader = new McpPrincipal(false, false, false, Set.of(McpBusinessDatabaseTest.PROJECT));
        assertEquals(403, assertThrows(BizException.class, () -> fixture.call("update_audio_asset", reader, new McpRecordInputs.Update("audio-reader-001", null, Long.toString(id + 1), "0".repeat(64), Map.of("status", 0)))).getCode());
    }

    @Test void deleteCanRetryAfterLogicalDeletionAndDoesNotRewriteShotSnapshots() {
        var audio = asset(id, McpBusinessDatabaseTest.PROJECT, 1); var shot = fixture.shot(80L);
        shot.setRefAudiosJson(fixture.json.write(List.of(Map.of("resAudioId", Long.toString(id), "audioUrl", audio.getAudioUrl(), "referenceType", "reference")))); fixture.shots.updateById(shot);
        var input = new McpRecordInputs.Delete("audio-delete-001", null, Long.toString(id), (String) get(id).get("editFingerprint"));
        Object first = fixture.call("delete_audio_asset", fixture.caller, input);
        assertEquals(first, fixture.call("delete_audio_asset", fixture.caller, input)); assertNull(mapper.selectById(id));
        assertTrue(fixture.shots.selectById(80L).getRefAudiosJson().contains(audio.getAudioUrl()));
    }

    @Test void protocolEnforcesIdTypesCategoriesAndMusicReference() {
        var audio = asset(id, McpBusinessDatabaseTest.PROJECT, 1); fixture.shot(80L);
        assertTrue(fixture.protocolCall("get_audio_asset", fixture.caller, Map.of("recordId", id)).isError());
        assertTrue(fixture.protocolCall("search_audio_assets", fixture.caller, Map.of("dramaId", McpBusinessDatabaseTest.ROOT, "audioKind", "MADE_UP")).isError());
        String fingerprint = (String) get(id).get("editFingerprint");
        assertFalse(fixture.protocolCall("update_audio_asset", fixture.caller, Map.of("recordId", Long.toString(id), "requestId", "audio-protocol01", "expectedFingerprint", fingerprint, "data", Map.of("name", "协议更新"))).isError());
        var shot = fixture.shots.selectById(80L);
        var reference = Map.of("resAudioId", Long.toString(id), "audioKind", "MUSIC", "audioUrl", audio.getAudioUrl(), "referenceType", "reference", "duration", new BigDecimal("4.25"));
        assertFalse(fixture.protocolCall("update_shot", fixture.caller, Map.of("recordId", "80", "requestId", "audio-music-shot", "expectedFingerprint", fixture.views.fingerprint(shot), "data", Map.of("prompt", "non_diegetic_music: reference <Audio 1> drum rhythm, as requested by the creator", "refAudios", List.of(reference)))).isError());
        assertTrue(fixture.shots.selectById(80L).getPrompt().contains("non_diegetic_music"));
        var options = fixture.map(fixture.json.value(Map.of("values", fixture.reads.options(fixture.caller, new McpInputs.Options("audio_asset_kind")))));
        assertEquals(ShotAudioReferenceValidator.AUDIO_KINDS.size(), ((List<?>) options.get("values")).size());
    }
}
