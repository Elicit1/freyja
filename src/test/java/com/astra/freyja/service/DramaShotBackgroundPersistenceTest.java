package com.astra.freyja.service;

import com.astra.freyja.config.JacksonConfig;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dto.drama.DramaShotDTO;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.service.impl.DramaShotServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证真实 MyBatis UPDATE SQL，避免 Mockito 掩盖 null 字段写入造成的数据丢失。 */
@ExtendWith(MockitoExtension.class)
class DramaShotBackgroundPersistenceTest {
    private static MybatisConfiguration configuration;
    @Mock private DramaShotMapper shotMapper;
    @Mock private DramaSceneMapper sceneMapper;
    @Mock private DramaEpisodeService episodeService;
    @InjectMocks private DramaShotServiceImpl service;
    private DramaShot existing;
    private final Map<String, Object> background = new HashMap<>();

    @BeforeAll
    static void initializeMapper() {
        configuration = new MybatisConfiguration();
        configuration.addMapper(DramaShotMapper.class);
    }

    @BeforeEach
    void setUp() {
        existing = new DramaShot();
        existing.setId(1000L);
        background.put("res_scene_id", 2000L);
        background.put("res_keyframe_id", null);
        background.put("custom_scene_prompt", "室内暖色光线");
        lenient().when(shotMapper.selectById(1000L)).thenAnswer(invocation -> {
            existing.setResSceneId((Long) background.get("res_scene_id"));
            existing.setResKeyframeId((Long) background.get("res_keyframe_id"));
            existing.setCustomScenePrompt((String) background.get("custom_scene_prompt"));
            return existing;
        });
        lenient().when(shotMapper.updateById(any(DramaShot.class))).thenAnswer(invocation -> {
            applySql("updateById", invocation.getArgument(0), null);
            return 1;
        });
        lenient().when(shotMapper.update(any(DramaShot.class), any(Wrapper.class))).thenAnswer(invocation -> {
            applySql("update", invocation.getArgument(0), invocation.getArgument(1));
            return 1;
        });
    }

    private void applySql(String method, DramaShot entity, Wrapper<DramaShot> wrapper) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("et", entity);
        parameters.put("ew", wrapper);
        BoundSql sql = configuration.getMappedStatement(DramaShotMapper.class.getName() + "." + method)
                .getBoundSql(parameters);
        // 每个 SET 列对应一个参数；只投影背景字段，模拟数据库执行生成的 SQL。
        String assignments = sql.getSql().split("(?i)SET", 2)[1].split("(?i)WHERE", 2)[0];
        String[] columns = assignments.split(",");
        for (int i = 0; i < columns.length; i++) {
            String column = columns[i].split("=", 2)[0].trim();
            if (background.containsKey(column)) {
                String property = sql.getParameterMappings().get(i).getProperty();
                Object value = configuration.newMetaObject(parameters).getValue(property);
                background.put(column, value);
            }
        }
    }

    private DramaShotDTO request(String json) {
        var builder = JsonMapper.builder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build().readValue(json, DramaShotDTO.class);
    }

    @Test
    void partialSavePreservesSceneAndCustomPrompt() {
        service.update(request("{\"id\":\"1000\",\"prompt\":\"更新提示词\"}"));
        assertEquals(2000L, background.get("res_scene_id"));
        assertEquals("室内暖色光线", background.get("custom_scene_prompt"));
    }

    @Test
    void partialSavePreservesKeyframe() {
        background.put("res_scene_id", null);
        background.put("res_keyframe_id", 3000L);
        service.update(request("{\"id\":\"1000\",\"generationMode\":\"REFERENCE_MODE\"}"));
        assertEquals(3000L, background.get("res_keyframe_id"));
    }

    @Test
    void sparseSeedUpdatePreservesBackground() {
        service.refreshSeed(1000L);
        assertEquals(2000L, background.get("res_scene_id"));
    }

    @Test
    void switchingToKeyframeClearsSceneButPreservesCustomPrompt() {
        service.update(request("{\"id\":\"1000\",\"resKeyframeId\":\"3000\"}"));
        assertEquals(3000L, background.get("res_keyframe_id"));
        assertNull(background.get("res_scene_id"));
        assertEquals("室内暖色光线", background.get("custom_scene_prompt"));
    }

    @Test
    void switchingToSceneClearsKeyframe() {
        background.put("res_scene_id", null);
        background.put("res_keyframe_id", 3000L);
        service.update(request("{\"id\":\"1000\",\"resSceneId\":\"4000\"}"));
        assertEquals(4000L, background.get("res_scene_id"));
        assertNull(background.get("res_keyframe_id"));
    }

    @Test
    void explicitNullClearsSceneWithoutClearingCustomPrompt() {
        service.update(request("{\"id\":\"1000\",\"resSceneId\":null}"));
        assertNull(background.get("res_scene_id"));
        assertEquals("室内暖色光线", background.get("custom_scene_prompt"));
    }

    @Test
    void explicitNullClearsKeyframe() {
        background.put("res_scene_id", null);
        background.put("res_keyframe_id", 3000L);
        service.update(request("{\"id\":\"1000\",\"resKeyframeId\":null}"));
        assertNull(background.get("res_keyframe_id"));
    }

    @Test
    void explicitNullClearsCustomPromptWithoutClearingScene() {
        service.update(request("{\"id\":\"1000\",\"customScenePrompt\":null}"));
        assertEquals(2000L, background.get("res_scene_id"));
        assertNull(background.get("custom_scene_prompt"));
    }

    @Test
    void fullFormCanClearAllBackgroundFields() {
        service.update(request("{\"id\":\"1000\",\"resSceneId\":null,\"resKeyframeId\":null,\"customScenePrompt\":null}"));
        assertTrue(background.values().stream().allMatch(value -> value == null));
    }

    @Test
    void clearingUnusedSceneFieldPreservesKeyframe() {
        background.put("res_scene_id", null);
        background.put("res_keyframe_id", 3000L);
        service.update(request("{\"id\":\"1000\",\"resSceneId\":null}"));
        assertEquals(3000L, background.get("res_keyframe_id"));
    }

    @Test
    void repeatedSavesPreserveKeyframeAndSnowflakePrecision() {
        service.update(request("{\"id\":\"1000\",\"resKeyframeId\":\"2032095628944588801\",\"resSceneId\":null}"));
        service.update(request("{\"id\":\"1000\",\"prompt\":\"再次保存提示词\"}"));
        service.refreshSeed(1000L);
        assertEquals(2032095628944588801L, background.get("res_keyframe_id"));
        assertNull(background.get("res_scene_id"));
        assertEquals("室内暖色光线", background.get("custom_scene_prompt"));
    }

    @Test
    void savingKeyframeAndCustomDescriptionKeepsBoth() {
        service.update(request("{\"id\":\"1000\",\"resKeyframeId\":\"3000\",\"customScenePrompt\":\"窗外大雨\"}"));
        assertEquals(3000L, background.get("res_keyframe_id"));
        assertEquals("窗外大雨", background.get("custom_scene_prompt"));
    }

    @Test
    void creatingKeyframeShotPreservesCustomDescription() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setDramaId(1L);
        scene.setEpisodeId(5L);
        when(sceneMapper.selectById(10L)).thenReturn(scene);
        doAnswer(invocation -> {
            DramaShot inserted = invocation.getArgument(0);
            assertEquals(3000L, inserted.getResKeyframeId());
            assertNull(inserted.getResSceneId());
            assertEquals("窗外大雨", inserted.getCustomScenePrompt());
            inserted.setId(1000L);
            return 1;
        }).when(shotMapper).insert(any(DramaShot.class));
        assertEquals(1000L, service.create(request("{\"sceneId\":\"10\",\"shotGroupId\":\"20\",\"shotNo\":1,\"shotName\":\"S01-01\",\"resKeyframeId\":\"3000\",\"customScenePrompt\":\"窗外大雨\"}")));
    }
}
