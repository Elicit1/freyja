package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.config.MyMetaObjectHandler;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.service.impl.*;
import com.astra.freyja.director.service.DirectorPlanStateService;
import com.astra.freyja.director.service.DirectorPlanValidator;
import com.astra.freyja.skill.tool.ReadSkillFileToolFactory;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual CRUD services, MyBatis SQL and Spring transactions, with external AI/storage mocked. */
@SpringJUnitConfig(McpBusinessDatabaseTest.Config.class)
@TestPropertySource(properties = "spring.ai.mcp.server.enabled=true")
class McpBusinessDatabaseTest {
    static final long PROJECT = 2032095628944588801L;
    static final String ROOT = Long.toString(PROJECT);
    final McpPrincipal caller = new McpPrincipal(true, false, false, Set.of(PROJECT));
    @Autowired DataSource dataSource;
    @Autowired McpBusinessTools tools;
    @Autowired McpBusinessRecords records;
    @Autowired McpOperationStore journal;
    @Autowired McpScope scope;
    @Autowired McpBusinessView views;
    @Autowired McpJson json;
    @Autowired McpReadTools reads;
    @Autowired DictService dictionaries;
    @Autowired McpSkillTools skills;
    @Autowired com.astra.freyja.skill.service.SkillCatalogService skillCatalog;
    @Autowired com.astra.freyja.skill.service.SkillContentService skillContents;
    @Autowired com.astra.freyja.skill.repository.SkillRepository skillRepository;
    @Autowired McpProperties properties;
    @Autowired DramaMapper dramas;
    @Autowired DramaEpisodeMapper episodes;
    @Autowired DramaSceneMapper scenes;
    @Autowired DramaShotMapper shots;
    @Autowired DramaShotGroupMapper groups;
    @Autowired ResCharacterMapper characters;
    @Autowired ResCharacterLookMapper looks;
    @Autowired ResPropMapper props;
    @Autowired ResKeyframeMapper keyframes;
    @Autowired ResSceneMapper resourceScenes;
    @Autowired DramaShotVideoTakeMapper takes;
    @Autowired AiModelFactory models;
    @Autowired AiImageApiService images;
    @Autowired PromptAssembleService prompts;
    @Autowired RenderTaskService renders;
    @Autowired ReadSkillFileToolFactory skillFiles;
    @Autowired MinioClient storage;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(basePackages = "com.astra.freyja.dao", annotationClass = Mapper.class)
    @Import({McpJson.class, McpScope.class, McpBusinessView.class, McpAssets.class, McpBusinessSchemas.class,
            McpSchemaMetadata.class, McpBusinessOptions.class, McpBusinessInputValidator.class,
            McpBusinessRecords.class, McpRecordTools.class, McpOperationStore.class, McpBusinessTools.class, McpReadTools.class, McpImageAssets.class, MediaAssetServiceImpl.class,
            McpPresignedImageUploads.class, McpImageTransferStorage.class, McpPresignedVideoUploads.class, McpVideoTransferStorage.class, com.astra.freyja.service.impl.ResVideoServiceImpl.class,
            DramaServiceImpl.class, DramaEpisodeServiceImpl.class, DramaSceneServiceImpl.class,
            DramaShotGroupServiceImpl.class, DramaShotServiceImpl.class, ResCharacterServiceImpl.class,
            ResCharacterLookServiceImpl.class, ResCharacterOutfitServiceImpl.class, ResSceneServiceImpl.class,
            ResPropServiceImpl.class, ResKeyframeServiceImpl.class, CharacterRegistryServiceImpl.class,
            ShotVideoTakeServiceImpl.class, DirectorPlanStateService.class, DirectorPlanValidator.class, McpSkillTools.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:mcp-business-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            MybatisPlusInterceptor pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:/mapper/**/*.xml"));
            factory.setDataSource(source); factory.setConfiguration(config); factory.setPlugins(pagination);
            factory.setGlobalConfig(new GlobalConfig().setMetaObjectHandler(new MyMetaObjectHandler()));
            return factory.getObject();
        }
        @Bean @Primary @ConfigurationProperties("freyja.mcp")
        McpProperties properties() { McpProperties p = new McpProperties(); p.setWriteEnabled(true); return p; }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean com.astra.freyja.service.AssetMediaCleanupService mediaCleanup() { return mock(com.astra.freyja.service.AssetMediaCleanupService.class); }
        @Bean com.astra.freyja.config.ReferenceVideoProperties referenceVideoProperties() { return new com.astra.freyja.config.ReferenceVideoProperties(); }
        @Bean com.astra.freyja.service.VideoFrameExtractService videoProbe() { return mock(com.astra.freyja.service.VideoFrameExtractService.class); }
        @Bean AiModelFactory models() { return mock(AiModelFactory.class); }
        @Bean AiProviderService providers() { return mock(AiProviderService.class); }
        @Bean SysConfigService configs() { return mock(SysConfigService.class); }
        @Bean DictService dictionaries() { return dictionaryFixture(); }
        @Bean AiImageApiService images() { return mock(AiImageApiService.class); }
        @Bean PromptAssembleService prompts() { return mock(PromptAssembleService.class); }
        @Bean RenderTaskService renders() { return mock(RenderTaskService.class); }
        @Bean com.astra.freyja.service.video.VideoRenderBatchService videoBatches() { return mock(com.astra.freyja.service.video.VideoRenderBatchService.class); }
        @Bean ReadSkillFileToolFactory skillFiles() { return mock(ReadSkillFileToolFactory.class); }
        @Bean com.astra.freyja.skill.service.SkillCatalogService skillCatalog() { return mock(com.astra.freyja.skill.service.SkillCatalogService.class); }
        @Bean com.astra.freyja.skill.service.SkillContentService skillContents() { return mock(com.astra.freyja.skill.service.SkillContentService.class); }
        @Bean com.astra.freyja.skill.repository.SkillRepository skillRepository() { return mock(com.astra.freyja.skill.repository.SkillRepository.class); }
        @Bean(name = "renderAsyncExecutor") Executor executor() { return mock(Executor.class); }
        @Bean MinioClient storage() { return mock(MinioClient.class); }
        @Bean MinioProperties minioProperties() { return new MinioProperties(); }
    }

    static DictService dictionaryFixture() {
        DictService service = mock(DictService.class);
        stubDictionaries(service);
        return service;
    }

    static void stubDictionaries(DictService service) {
        Map<String, List<String>> options = new HashMap<>(Map.of(
                "drama_aspect_ratio", List.of("9:16", "16:9", "1:1", "4:3"),
                "shot_type", List.of("AUTO", "EXTREME_CLOSE_UP", "CLOSE_UP", "MEDIUM_CLOSE_UP", "MEDIUM_SHOT", "FULL_SHOT", "LONG_SHOT", "OVER_SHOULDER", "TOP_DOWN"),
                "camera_movement", List.of("AUTO", "STATIC", "PUSH_IN", "PULL_OUT", "PAN_LEFT", "PAN_RIGHT", "TILT_UP", "TILT_DOWN", "TRACKING", "ORBIT", "ZOOM_IN"),
                "drama_style_preset", List.of("cinematic-realism", "3d-animation", "anime-2d", "cyber-realism", "retro-film"),
                "res_scene_type", List.of("INDOOR", "OUTDOOR", "STUDIO", "VIRTUAL"),
                "res_time_of_day", List.of("DAY", "NIGHT", "SUNSET", "DAWN"),
                "shot_generation_mode", List.of("FIRST_LAST_FRAME", "REFERENCE_MODE")));
        options.put("res_gender", List.of("MALE", "FEMALE", "OTHER", "UNKNOWN"));
        options.put("res_role_type", List.of("PROTAGONIST", "ANTAGONIST", "SUPPORTING", "EXTRA"));
        options.put("res_prop_type", List.of("KEY_PROP", "WEAPON", "COSTUME_ACCESSORY", "DAILY"));
        options.put("drama_status", List.of("PLANNING", "IN_PROGRESS", "COMPLETED", "ARCHIVED"));
        options.put("episode_status", List.of("DRAFT", "SCRIPT_PARSED", "SHOTS_READY", "RENDERING", "COMPLETED"));
        when(service.listByType(anyString())).thenAnswer(call -> options.getOrDefault(call.getArgument(0), List.of()).stream().map(value -> {
            SysDictData option = new SysDictData(); option.setDictValue(value); option.setDictLabel(value); option.setStatus(1); return option;
        }).toList());
    }

    @BeforeEach void setup() {
        properties.setWriteEnabled(true);
        reset(models, images, prompts, renders, skillFiles, storage);
        reset(skillCatalog, skillContents, skillRepository);
        reset(dictionaries);
        stubDictionaries(dictionaries);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (Class<?> type : List.of(Drama.class, DramaEpisode.class, DramaScene.class, DramaShotGroup.class,
                DramaShot.class, ResCharacter.class, ResCharacterLook.class, ResScene.class, ResProp.class,
                ResKeyframe.class, ResVideo.class, ResCharacterAlias.class, ResCharacterEvidence.class, ResCharacterResolution.class,
                DramaShotVideoTake.class, McpBusinessOperation.class, SysConfig.class, AiSkillFile.class)) {
            var info = TableInfoHelper.getTableInfo(type);
            List<String> columns = new ArrayList<>(); columns.add(info.getKeyColumn() + " BIGINT PRIMARY KEY");
            info.getFieldList().forEach(f -> {
                Class<?> t = f.getPropertyType();
                String sqlType = t == Long.class ? "BIGINT" : t == Integer.class || t == Boolean.class ? "INTEGER" :
                        Number.class.isAssignableFrom(t) ? "DECIMAL(30,8)" : t == java.time.LocalDateTime.class ? "TIMESTAMP" : "VARCHAR(100000)";
                columns.add(f.getColumn() + " " + sqlType);
            });
            if (type == McpBusinessOperation.class) columns.add("UNIQUE(request_id)");
            jdbc.execute("DROP TABLE IF EXISTS " + info.getTableName());
            jdbc.execute("CREATE TABLE " + info.getTableName() + " (" + String.join(",", columns) + ")");
        }
        for (long id : List.of(PROJECT, PROJECT + 1)) {
            Drama d = new Drama(); d.setId(id); d.setTitle("Project " + id); d.setSortOrder(0); dramas.insert(d);
        }
        DramaEpisode e = new DramaEpisode(); e.setId(20L); e.setDramaId(PROJECT); e.setEpisodeNo(1); e.setTitle("Episode"); episodes.insert(e);
        DramaScene s = new DramaScene(); s.setId(30L); s.setDramaId(PROJECT); s.setEpisodeId(20L); s.setSceneNo(1); s.setName("Scene"); scenes.insert(s);
        DramaShotGroup g = new DramaShotGroup(); g.setId(40L); g.setDramaId(PROJECT); g.setEpisodeId(20L); g.setSceneId(30L); g.setGroupNo(1); groups.insert(g);
    }

    @AfterEach void noInternalAiCalls() { verifyNoInteractions(models, images, prompts, renders, skillFiles); }
    @SuppressWarnings("unchecked") Map<String, Object> map(Object object) { return (Map<String, Object>) json.value(object); }
    @SuppressWarnings("unchecked") Object call(String name, McpPrincipal principal, Object input) {
        McpTool<Object> tool = (McpTool<Object>) java.util.stream.Stream.concat(reads.tools().stream(), tools.tools().stream()).filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        return tool.handler().apply(principal, input);
    }
    McpBatchInputs.CreateItem item(String key, McpRecordType type, Map<String, Object> data) { return new McpBatchInputs.CreateItem(key, type, data); }
    Map<String, Object> ref(String key) { return Map.of("ref", key); }
    Map<String, Object> create(String key, List<McpBatchInputs.CreateItem> items) {
        return map(call("batch_create_records", caller, new McpBatchInputs.Create(key, ROOT, items)));
    }
    DramaShot shot(long id) {
        DramaShot shot = new DramaShot(); shot.setId(id); shot.setDramaId(PROJECT); shot.setEpisodeId(20L);
        shot.setSceneId(30L); shot.setShotGroupId(40L); shot.setShotNo((int) id); shot.setSortOrder((int) id);
        shot.setShotName("Shot " + id); shot.setDuration(new BigDecimal("5")); shot.setSeed(123L); shots.insert(shot);
        return shots.selectById(id);
    }
    ResCharacter character(long id, Long project) { ResCharacter c = new ResCharacter(); c.setId(id); c.setDramaId(project); c.setName("C" + id); characters.insert(c); return c; }
    ResCharacterLook look(long id, long character, int defaultValue) {
        ResCharacterLook l = new ResCharacterLook(); l.setId(id); l.setCharacterId(character); l.setLookName("Look " + id);
        l.setDesignDesc("Business description"); l.setIsDefault(defaultValue); l.setStatus(1); looks.insert(l); return looks.selectById(id);
    }
    long count(String table) { return new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test void appendPersistsHierarchyReferencesAssetsAndLeavesExistingOutline() {
        var input = new McpBatchInputs.Create("append-request-01", ROOT, List.of(
                item("char", McpRecordType.CHARACTER, Map.of("name", "旅客", "aliases", List.of("小林"))),
                item("look", McpRecordType.CHARACTER_LOOK, Map.of("characterId", ref("char"), "lookName", "旅装", "designDesc", "灰色外套")),
                item("prop", McpRecordType.PROP, Map.of("name", "行李")),
                item("ep", McpRecordType.EPISODE, Map.of("title", "New episode")),
                item("scene", McpRecordType.SCENE, Map.of("episodeId", ref("ep"), "name", "Station")),
                item("group", McpRecordType.SHOT_GROUP, Map.of("sceneId", ref("scene"), "name", "Arrival")),
                item("shot", McpRecordType.SHOT, Map.of("sceneId", ref("scene"), "shotGroupId", ref("group"), "scriptContent", "旅客抵达。",
                        "characterRefs", List.of(Map.of("characterId", ref("char"), "lookId", ref("look"))), "propRefs", List.of(Map.of("propId", ref("prop")))))));
        Map<String, Object> result = map(call("append_storyboard", caller, input));
        Map<String, Object> ids = map(result.get("idMap"));
        DramaShot saved = shots.selectById(McpScope.id((String) ids.get("shot")));
        assertEquals(ids.get("ep"), saved.getEpisodeId().toString()); assertEquals(ids.get("group"), saved.getShotGroupId().toString());
        assertEquals(2, count("drama_episode")); assertNotNull(episodes.selectById(20L));
        assertEquals(2, count("res_character_alias")); assertEquals(1, count("res_character_evidence"));
        assertEquals(1, looks.selectById(McpScope.id((String) ids.get("look"))).getIsDefault());
        assertEquals("SUCCEEDED", map(journal.read(caller, input.requestId())).get("status"));
    }

    @Test void failedBatchRollsBackEarlierRecordsAndJournal() {
        BizException failure = assertThrows(BizException.class, () -> create("rollback-request", List.of(
                item("good", McpRecordType.EPISODE, Map.of("title", "Temporary")),
                item("bad", McpRecordType.SCENE, Map.of("episodeId", ref("missing"), "name", "Invalid")))));
        assertTrue(failure.getMessage().contains("items[1]")); assertEquals(1, count("drama_episode")); assertEquals(0, count("mcp_business_operation"));
    }

    @Test void invalidUploadedReferenceUsageNeverPersistsInSingleOrBatchWrites() {
        Map<String, Object> data = Map.of("sceneId", "30", "refImages", List.of(Map.of(
                "sourceType", "UPLOAD", "imageUrl", "https://media/ref.png", "usageRole", "UNSUPPORTED_ROLE")));
        long before = count("drama_shot");
        assertThrows(BizException.class, () -> call("create_shot", caller, new McpRecordInputs.Create("bad-role-single", ROOT, data)));
        assertThrows(BizException.class, () -> create("bad-role-batch", List.of(
                item("episode", McpRecordType.EPISODE, Map.of("title", "不能留下的剧集")), item("shot", McpRecordType.SHOT, data))));
        assertEquals(before, count("drama_shot"));
        assertEquals(1, count("drama_episode"));
        assertEquals(0, count("mcp_business_operation"));
    }

    @Test void retriesReplayAndChangedContentsConflict() {
        var items = List.of(item("ep", McpRecordType.EPISODE, Map.of("title", "Once")));
        var first = create("retry-request-01", items); var second = create("retry-request-01", items);
        assertEquals(first, second); assertEquals(2, count("drama_episode"));
        BizException error = assertThrows(BizException.class, () -> create("retry-request-01", List.of(item("ep", McpRecordType.EPISODE, Map.of("title", "Different")))));
        assertEquals(409, error.getCode()); assertTrue(error.getMessage().contains("REQUEST_ID_REUSED"));
        assertEquals(2, count("drama_episode"));
    }

    @Test void concurrentSameRequestCreatesOnlyOnce() throws Exception {
        var items = List.of(item("ep", McpRecordType.EPISODE, Map.of("title", "Concurrent")));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Map<String, Object>> action = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return create("concurrent-req-01", items); };
            Future<Map<String, Object>> a = pool.submit(action), b = pool.submit(action); start.countDown();
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(2, count("drama_episode")); assertEquals(1, count("mcp_business_operation"));
    }

    @Test void projectCreationRequiresExplicitEnabledRatioAndKeepsSelectedLandscape() {
        McpPrincipal creator = new McpPrincipal(true, false, true, Set.of());
        var missing = new McpRecordInputs.Create("ratio-missing-001", null, Map.of("title", "Needs user choice"));
        assertEquals(400, assertThrows(BizException.class, () -> call("create_project", creator, missing)).getCode());
        assertEquals(2, count("drama"));
        var unsupported = new McpRecordInputs.Create("ratio-invalid-001", null, Map.of("title", "Unknown ratio", "aspectRatio", "7:5"));
        assertEquals(400, assertThrows(BizException.class, () -> call("create_project", creator, unsupported)).getCode());
        assertEquals(2, count("drama"));
        var landscape = new McpRecordInputs.Create("ratio-landscape1", null, Map.of("title", "AI choice", "aspectRatio", "16:9"));
        Map<String, Object> created = map(call("create_project", creator, landscape));
        assertEquals("16:9", dramas.selectById(McpScope.id((String) created.get("recordId"))).getAspectRatio());
        SysDictData disabled = new SysDictData(); disabled.setDictValue("16:9"); disabled.setStatus(0);
        when(dictionaries.listByType("drama_aspect_ratio")).thenReturn(List.of(disabled));
        var inactive = new McpRecordInputs.Create("ratio-inactive01", null, Map.of("title", "Disabled ratio", "aspectRatio", "16:9"));
        assertEquals(400, assertThrows(BizException.class, () -> call("create_project", creator, inactive)).getCode());
        assertEquals(3, count("drama"));
    }

    @Test void optionalSceneEnvironmentStaysEmptyAndExplicitValuesAreReadBack() {
        var result = create("scene-env-create01", List.of(
                item("emptyAsset", McpRecordType.RESOURCE_SCENE, Map.of("name", "未指定环境")),
                item("emptyScene", McpRecordType.SCENE, Map.of("episodeId", "20")),
                item("river", McpRecordType.RESOURCE_SCENE, Map.of("name", "河畔", "sceneType", "OUTDOOR", "timeOfDay", "NIGHT", "weatherAtmosphere", "薄雾")),
                item("inherited", McpRecordType.SCENE, Map.of("episodeId", "20", "resSceneId", ref("river")))));
        var ids = map(result.get("idMap"));
        ResScene empty = resourceScenes.selectById(McpScope.id((String) ids.get("emptyAsset")));
        assertNull(empty.getSceneType()); assertNull(empty.getTimeOfDay()); assertNull(empty.getWeatherAtmosphere());
        DramaScene scene = scenes.selectById(McpScope.id((String) ids.get("emptyScene")));
        assertNull(scene.getSceneType()); assertNull(scene.getTimeOfDay());
        var inherited = map(call("get_scene", caller, new McpRecordInputs.Get(ROOT, (String) ids.get("inherited"))));
        assertEquals("OUTDOOR", inherited.get("sceneType")); assertEquals("NIGHT", inherited.get("timeOfDay"));
        assertEquals("薄雾", inherited.get("weatherAtmosphere"));
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("episodeId", "20"); blank.put("resSceneId", ids.get("river"));
        blank.put("sceneType", null); blank.put("timeOfDay", null); blank.put("weatherAtmosphere", null);
        var explicit = map(call("create_scene", caller, new McpRecordInputs.Create("scene-env-null01", ROOT, blank)));
        var saved = map(explicit.get("data"));
        assertNull(saved.get("sceneType")); assertNull(saved.get("timeOfDay")); assertNull(saved.get("weatherAtmosphere"));
        for (var entry : Map.of(McpRecordType.SCENE, "inherited", McpRecordType.RESOURCE_SCENE, "river").entrySet()) {
            String id = (String) ids.get(entry.getValue());
            BaseEntity current = records.require(caller, entry.getKey(), id, ROOT, false);
            String tool = entry.getKey() == McpRecordType.SCENE ? "update_scene" : "update_resource_scene";
            var preserved = map(call(tool, caller, new McpRecordInputs.Update("scene-env-preserve-" + id, ROOT, id,
                    views.fingerprint(current), Map.of("name", "只改名称"))));
            assertEquals("OUTDOOR", map(preserved.get("data")).get("sceneType"));
            assertEquals("NIGHT", map(preserved.get("data")).get("timeOfDay"));
            Map<String, Object> clear = new LinkedHashMap<>();
            clear.put("sceneType", null); clear.put("timeOfDay", null); clear.put("weatherAtmosphere", null);
            call(tool, caller, new McpRecordInputs.Update("scene-env-clear-" + id, ROOT, id,
                    views.fingerprint(records.require(caller, entry.getKey(), id, ROOT, false)), clear));
            var read = records.read(caller, entry.getKey(), id, ROOT);
            assertNull(read.get("sceneType")); assertNull(read.get("timeOfDay")); assertNull(read.get("weatherAtmosphere"));
        }
    }

    @Test void comfortableLongShotReadbackPreservesModePromptAndExplicitLook() {
        character(50L, PROJECT); look(60L, 50L, 1);
        String prompt = "subject_definitions: <Picture 1> 人物造型。\nnon_diegetic_music: none / no background music";
        var created = map(call("create_shot", caller, new McpRecordInputs.Create("long-shot-readback", ROOT,
                Map.of("sceneId", "30", "duration", 7.5, "generationMode", "REFERENCE_MODE", "prompt", prompt,
                        "characterRefs", List.of(Map.of("characterId", "50", "lookId", "60")),
                        "refImages", List.of(Map.of("sourceType", "CHARACTER_LOOK", "sourceId", "60", "characterId", "50", "imageUrl", "https://media/look.png", "usageRole", "SUBJECT"))))));
        var read = records.read(caller, McpRecordType.SHOT, (String) created.get("recordId"), ROOT);
        assertEquals(0, new BigDecimal("7.5").compareTo(new BigDecimal(read.get("duration").toString())));
        assertEquals("REFERENCE_MODE", read.get("generationMode")); assertEquals(prompt, read.get("prompt"));
        assertEquals("60", map(((List<?>) read.get("characterRefs")).getFirst()).get("lookId"));
        assertEquals("60", map(((List<?>) read.get("refImages")).getFirst()).get("lookId"));
        var draft = map(call("create_shot", caller, new McpRecordInputs.Create("draft-camera-empty", ROOT, Map.of("sceneId", "30"))));
        var saved = map(draft.get("data"));
        assertNull(saved.get("shotType")); assertNull(saved.get("cameraMovement"));
    }

    @Test void projectCreationGrantsAccessOnlyWhenPermissionRemainsEnabled() {
        McpPrincipal creator = new McpPrincipal(true, false, true, Set.of());
        var input = new McpBatchInputs.Create("new-project-req1", null, List.of(item("project", McpRecordType.PROJECT, Map.of("title", "New project", "aspectRatio", "16:9")),
                item("ep", McpRecordType.EPISODE, Map.of("title", "First"))));
        String id = (String) map(call("batch_create_records", creator, input)).get("dramaId");
        assertEquals(McpScope.id(id), scope.project(creator, id));
        assertEquals("1", map(reads.searchProjects(creator, new McpInputs.Search(null, 1, 20))).get("total"));
        assertThrows(BizException.class, () -> scope.project(new McpPrincipal(true, false, false, Set.of()), id));
        assertThrows(BizException.class, () -> call("batch_create_records", caller, input));
    }

    @Test void crossProjectParentsAndPublicWritesAreRejected() {
        DramaEpisode e = new DramaEpisode(); e.setId(21L); e.setDramaId(PROJECT + 1); episodes.insert(e);
        assertEquals(403, assertThrows(BizException.class, () -> create("foreign-parent-01", List.of(item("scene", McpRecordType.SCENE,
                Map.of("episodeId", "21", "name", "Invalid"))))).getCode());
        ResCharacter publicCharacter = character(50L, 0L);
        var update = new McpRecordInputs.BatchUpdate("public-update-01", ROOT, List.of(new McpRecordInputs.UpdateItem( "50",
                views.fingerprint(characters.selectById(publicCharacter.getId())), Map.of("name", "Changed"))));
        assertEquals(403, assertThrows(BizException.class, () -> call("batch_update_characters", caller, update)).getCode());
        assertEquals(0, count("mcp_business_operation"));
    }

    @Test void lookMustBelongToTheReferencedCharacterAndAllAssetsMustBeInScope() {
        character(50L, PROJECT); character(51L, PROJECT); look(60L, 51L, 1); character(52L, PROJECT + 1);
        for (Map<String, Object> refs : List.of(Map.<String, Object>of("characterId", "50", "lookId", "60"), Map.<String, Object>of("characterId", "52"))) {
            assertEquals(403, assertThrows(BizException.class, () -> create("bad-reference-01", List.of(item("shot", McpRecordType.SHOT,
                    Map.of("sceneId", "30", "characterRefs", List.of(refs)))))).getCode());
        }
        assertEquals(0, count("drama_shot"));
    }

    @Test void businessPatchPreservesPrivateFieldsAndRetainedReferenceDetails() {
        character(50L, PROJECT); look(60L, 50L, 1);
        ResProp p = new ResProp(); p.setId(70L); p.setDramaId(PROJECT); p.setName("Prop"); props.insert(p);
        DramaShot shot = shot(80L); shot.setPrompt("private prompt"); shot.setVideoPrompt("private video prompt");
        shot.setDirectorPlanJson("{\"private\":true}"); shot.setDirectorPlanMode("MANUAL");
        shot.setCharacterRefsJson("[{\"characterId\":50,\"lookId\":60,\"actionPrompt\":\"private action\",\"designDesc\":\"private design\"}]");
        shot.setPropRefsJson("[{\"propId\":70,\"propPrompt\":\"private prop\"}]");
        shots.updateById(shot);
        new JdbcTemplate(dataSource).update("UPDATE drama_shot SET director_plan_json=?,director_plan_mode=? WHERE id=80", shot.getDirectorPlanJson(), shot.getDirectorPlanMode());
        DramaShot before = shots.selectById(80L);
        var input = new McpRecordInputs.BatchUpdate("patch-request-01", ROOT, List.of(new McpRecordInputs.UpdateItem( "80", views.fingerprint(before),
                Map.of("scriptContent", "New dialogue", "characterRefs", List.of(Map.of("characterId", "50", "lookId", "60")), "propRefs", List.of(Map.of("propId", "70"))))));
        Object result = call("batch_update_shots", caller, input);
        DramaShot after = shots.selectById(80L);
        assertEquals("New dialogue", after.getScriptContent()); assertEquals(before.getPrompt(), after.getPrompt());
        assertEquals(before.getVideoPrompt(), after.getVideoPrompt()); assertEquals(before.getDirectorPlanJson(), after.getDirectorPlanJson());
        assertEquals(before.getDirectorPlanMode(), after.getDirectorPlanMode()); assertEquals(before.getSeed(), after.getSeed());
        assertTrue(after.getCharacterRefsJson().contains("private action")); assertTrue(after.getPropRefsJson().contains("private prop"));
        assertTrue(json.write(result).contains("private prompt"));
        assertFalse(json.write(result).contains("directorPlanJson"));
        assertEquals(map(result), map(call("batch_update_shots", caller, input)));
    }

    @Test void staleFingerprintAndCreatorLocksRollbackTheWholeUpdate() {
        DramaShot a = shot(80L), b = shot(81L); b.setShotType("CLOSE_UP"); b.setShotTypeLocked(true); shots.updateById(b); b = shots.selectById(81L);
        var change = new McpRecordInputs.BatchUpdate("locked-update-01", ROOT, List.of(
                new McpRecordInputs.UpdateItem( "80", views.fingerprint(a), Map.of("scriptContent", "Temporary")),
                new McpRecordInputs.UpdateItem( "81", views.fingerprint(b), Map.of("shotType", "FULL_SHOT"))));
        assertEquals(409, assertThrows(BizException.class, () -> call("batch_update_shots", caller, change)).getCode());
        assertNull(shots.selectById(80L).getScriptContent()); assertEquals(0, count("mcp_business_operation"));
        var stale = new McpRecordInputs.BatchUpdate("stale-update-01", ROOT, List.of(new McpRecordInputs.UpdateItem( "80", "0".repeat(64), Map.of("scriptContent", "Stale"))));
        assertEquals(409, assertThrows(BizException.class, () -> call("batch_update_shots", caller, stale)).getCode());
    }

    @Test void batchReadsReturnPerItemErrorsWithoutDisclosingOtherProjects() {
        character(50L, 0L); character(51L, PROJECT + 1);
        var input = new McpBatchInputs.Read(ROOT, List.of(new McpBatchInputs.RecordRef(McpRecordType.CHARACTER, "50"),
                new McpBatchInputs.RecordRef(McpRecordType.CHARACTER, "51"), new McpBatchInputs.RecordRef(McpRecordType.SHOT, "999")));
        List<?> result = (List<?>) map(call("batch_read_records", caller, input)).get("records");
        assertEquals(true, map(result.get(0)).get("ok")); assertEquals(false, map(result.get(1)).get("ok")); assertEquals(false, map(result.get(2)).get("ok"));
        assertFalse(json.write(result).contains("C51"));
    }

    @Test void workflowsKeepDefaultExclusiveAndRequireCompleteGroupOrdering() {
        character(50L, PROJECT); look(60L, 50L, 1); ResCharacterLook second = look(61L, 50L, 0);
        call("set_default_character_look", caller, new McpBatchInputs.DefaultLook("default-look-req1", ROOT, "61", views.fingerprint(second)));
        assertEquals(0, looks.selectById(60L).getIsDefault()); assertEquals(1, looks.selectById(61L).getIsDefault());
        shot(80L); shot(81L);
        call("reorder_group_shots", caller, new McpBatchInputs.Reorder("reorder-request1", ROOT, "40", List.of("81", "80")));
        assertEquals(1, shots.selectById(81L).getSortOrder()); assertEquals(2, shots.selectById(80L).getSortOrder());
        assertEquals(409, assertThrows(BizException.class, () -> call("reorder_group_shots", caller,
                new McpBatchInputs.Reorder("incomplete-order", ROOT, "40", List.of("80")))).getCode());
    }

    @Test void clonedShotDoesNotOwnSourceTakeOrBoundKeyframe() {
        DramaShot source = shot(80L); source.setCurrentVideoTakeId(90L); source.setVideoUrl("https://media/old.mp4");
        source.setLastFrameUrl("https://media/tail.png"); source.setFirstFrameSourceVideoTakeId(90L);
        ResKeyframe frame = new ResKeyframe(); frame.setId(100L); frame.setDramaId(PROJECT); frame.setShotId(80L); keyframes.insert(frame);
        source.setResKeyframeId(100L); shots.updateById(source); source = shots.selectById(80L);
        var result = map(call("clone_shot", caller, new McpBatchInputs.Clone("clone-request-01", ROOT, "80", views.fingerprint(source))));
        DramaShot copy = shots.selectById(McpScope.id((String) result.get("id")));
        assertNull(copy.getCurrentVideoTakeId()); assertNull(copy.getVideoUrl()); assertNull(copy.getLastFrameUrl());
        assertNull(copy.getFirstFrameSourceVideoTakeId()); assertNull(copy.getResKeyframeId());
        assertEquals(90L, shots.selectById(80L).getCurrentVideoTakeId());
    }

    @Test void videoVersionSelectionFiltersPrivateDataAndInvalidatesTailCache() {
        DramaShot shot = shot(80L); shot.setLastFrameUrl("https://media/old-tail.png"); shots.updateById(shot);
        DramaShotVideoTake take = new DramaShotVideoTake(); take.setId(90L); take.setShotId(80L); take.setTakeNo(1);
        take.setStatus("AVAILABLE"); take.setVideoUrl("http://127.0.0.1:9000/video-assets/clip.mp4");
        take.setPromptSnapshot("private video prompt"); take.setModelCode("private model"); takes.insert(take);
        var page = call("list_shot_video_versions", caller, new McpBatchInputs.VideoTakes(ROOT, "80", 1, 20));
        assertFalse(json.write(page).contains("private"));
        Object result = call("select_shot_video_version", caller, new McpBatchInputs.SelectVideo("select-video-req1", ROOT, "80", "90", views.fingerprint(shots.selectById(80L))));
        assertFalse(json.write(result).contains("private")); assertEquals(90L, shots.selectById(80L).getCurrentVideoTakeId());
        assertNull(shots.selectById(80L).getLastFrameUrl());
        assertEquals(403, assertThrows(BizException.class, () -> call("list_shot_video_versions", caller,
                new McpBatchInputs.VideoTakes(Long.toString(PROJECT + 1), "80", 1, 20))).getCode());
    }

    McpSchema.CallToolResult protocolCall(String name, McpPrincipal principal, Map<String, Object> arguments) {
        var tool = tools.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        return new McpToolCatalog(json).specification(tool).callHandler().apply(
                McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, principal)), new McpSchema.CallToolRequest(name, arguments));
    }

    @Test void completeCatalogContainsOnlyBusinessToolsAndWhitelistedSchemas() {
        Set<String> expected = new HashSet<>(Set.of("search_projects", "get_project", "get_project_outline", "list_shots", "get_shot", "search_assets",
                "get_asset", "list_character_looks", "get_business_options", "batch_read_records", "list_shot_video_versions", "batch_create_records",
                "append_storyboard", "batch_delete_records", "get_business_operation", "clone_shot", "set_default_character_look", "reorder_group_shots", "select_shot_video_version", "prepare_asset_image_upload", "complete_asset_image_upload", "prepare_asset_video_upload", "complete_asset_video_upload", "list_skills", "read_skill", "read_skill_file"));
        List<String> singulars = List.of("project", "episode", "scene", "shot_group", "shot", "character", "character_look", "resource_scene", "prop", "keyframe");
        for (String object : singulars) for (String action : List.of("get", "create", "update", "delete")) expected.add(action + "_" + object);
        for (String plural : List.of("projects", "episodes", "scenes", "shot_groups", "shots", "characters", "character_looks", "resource_scenes", "props", "keyframes")) expected.add("batch_update_" + plural);
        assertEquals(74, expected.size()); assertFalse(expected.contains("batch_update_records"));
        List<McpTool<?>> all = new ArrayList<>(reads.tools()); all.addAll(tools.tools()); all.addAll(skills.tools());
        assertEquals(expected, all.stream().map(McpTool::name).collect(java.util.stream.Collectors.toSet()));
        for (McpTool<?> tool : all) {
            var spec = new McpToolCatalog(json).specification(tool);
            assertEquals(tool.effect() == McpTool.Effect.READ, spec.tool().annotations().readOnlyHint());
            assertTrue(spec.tool().annotations().idempotentHint());
            assertFalse(spec.tool().annotations().openWorldHint());
            assertBusinessSchema(json.value(spec.tool().inputSchema()));
        }
        properties.setWriteEnabled(false);
        assertEquals(10, tools.tools().size()); assertTrue(tools.tools().stream().allMatch(t -> t.effect() == McpTool.Effect.READ));
    }

    void assertBusinessSchema(Object node) {
        Set<String> forbidden = Set.of("directorPlanJson", "directorPlanRevision", "directorPlanInputHash", "modelCode", "modelId", "providerId", "skills", "skillId", "skillName", "taskId", "latestTaskId");
        if (node instanceof Map<?, ?> object) {
            if (object.get("properties") instanceof Map<?, ?> fields) assertTrue(Collections.disjoint(fields.keySet(), forbidden), fields.keySet().toString());
            object.values().forEach(this::assertBusinessSchema);
        } else if (node instanceof List<?> array) array.forEach(this::assertBusinessSchema);
    }

    @Test void protocolHandlerAcceptsBatchButRejectsAiUnknownAndNumericNestedFields() {
        var valid = Map.<String, Object>of("requestId", "schema-request01", "dramaId", ROOT, "items", List.of(
                Map.of("clientKey", "ep", "type", "EPISODE", "data", Map.of("title", "Schema import"))));
        assertFalse(protocolCall("batch_create_records", caller, valid).isError());
        assertEquals(2, count("drama_episode"));
        for (Map<String, Object> data : List.of(Map.<String, Object>of("sceneId", 30), Map.<String, Object>of("sceneId", "30", "latestTaskId", "secret"),
                Map.<String, Object>of("sceneId", "30", "characterRefs", List.of(Map.of("characterId", "50", "modelId", "secret"))),
                Map.<String, Object>of("sceneId", "30", "propRefs", List.of(Map.of("propId", 70))),
                Map.<String, Object>of("sceneId", "30", "arbitrary", true), Map.<String, Object>of("sceneId", "9999999999999999999"))) {
            var invalid = Map.<String, Object>of("requestId", "invalid-schema01", "dramaId", ROOT, "items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", data)));
            var error = protocolCall("batch_create_records", caller, invalid);
            assertTrue(error.isError(), data.toString()); assertEquals(400, map(map(error.structuredContent()).get("error")).get("code"));
        }
        assertEquals(0, count("drama_shot")); assertEquals(1, count("mcp_business_operation"));
        var readOnly = new McpPrincipal(false, false, false, Set.of(PROJECT));
        assertEquals(403, map(map(protocolCall("batch_create_records", readOnly, valid).structuredContent()).get("error")).get("code"));
    }

    @Test void allBusinessProjectionsExcludePrivateFieldsIncludingNestedData() {
        Map<String, Object> raw = new HashMap<>(); raw.put("id", PROJECT);
        for (String field : List.of("promptSnapshot", "modelCode", "providerId", "latestTaskId", "comfyWorkflowTemplateId", "voiceId", "loraName", "visualPrompt")) raw.put(field, "secret");
        raw.put("prompt", "editable prompt"); raw.put("negativePrompt", "editable negative prompt"); raw.put("videoPrompt", "editable video prompt");
        Map<String, Object> plan = new LinkedHashMap<>(plan(5)); plan.put("skills", List.of(Map.of("name", "secret")));
        raw.put("directorPlanJson", json.write(plan));
        raw.put("characterRefsJson", "[{\"characterId\":2032095628944588801,\"lookId\":60,\"actionPrompt\":\"editable action\"}]");
        raw.put("propRefsJson", "[{\"propId\":70,\"propPrompt\":\"editable prop\"}]");
        for (McpBusinessView.Kind kind : McpBusinessView.Kind.values()) {
            Map<String, Object> projected = views.project(kind, raw);
            assertEquals(ROOT, projected.get("id")); assertFalse(json.write(projected).contains("secret"), kind.toString());
            if (kind == McpBusinessView.Kind.SHOT) assertEquals(ROOT, map(((List<?>) projected.get("characterRefs")).getFirst()).get("characterId"));
        }
    }

    static Map<String, Object> plan(int duration) {
        return Map.of("schemaVersion", "1.0", "duration", duration, "shotSize", "MEDIUM_SHOT", "cameraAngle", "EYE_LEVEL", "narrativeIntent", "展现人物抵达",
                "cameraBeats", List.of(Map.of("startSec", 0, "endSec", duration, "movement", "STATIC", "speed", "NONE")));
    }

    @Test void externalAgentCanCreateFullShotWithPromptsPlanAndReferencesWithoutAi() {
        character(50L, PROJECT); look(60L, 50L, 1);
        ResProp p = new ResProp(); p.setId(70L); p.setDramaId(PROJECT); p.setName("Prop"); props.insert(p);
        Map<String, Object> data = new LinkedHashMap<>(Map.ofEntries(Map.entry("sceneId", "30"), Map.entry("duration", 5), Map.entry("seed", "123"),
                Map.entry("prompt", "external positive"), Map.entry("firstFramePrompt", "external first"), Map.entry("endFramePrompt", "external last"),
                Map.entry("videoPrompt", "external video"), Map.entry("negativePrompt", "external negative"), Map.entry("customScenePrompt", "external background"),
                Map.entry("directorPlan", plan(5)), Map.entry("generationMode", "REFERENCE_MODE"), Map.entry("previewImageUrl", "https://media/first.png")));
        data.put("characterRefs", List.of(Map.of("characterId", "50", "lookId", "60", "actionPrompt", "walking", "emotionPrompt", "calm", "positionTag", "left")));
        data.put("propRefs", List.of(Map.of("propId", "70", "propName", "bag", "propPrompt", "leather bag")));
        data.put("refImages", List.of(Map.of("id", "image1", "sourceType", "CHARACTER_REFERENCE", "sourceId", "60", "characterId", "50", "lookId", "60", "imageUrl", "https://media/look.png", "usageRole", "SUBJECT")));
        data.put("refAudios", List.of(Map.of("id", "audio1", "referenceType", "reference", "characterId", "50", "audioUrl", "https://media/voice.mp3", "duration", 3, "text", "到了")));
        var result = protocolCall("batch_create_records", caller, Map.of("requestId", "external-shot-01", "dramaId", ROOT,
                "items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", data))));
        assertFalse(result.isError(), json.write(result.structuredContent()));
        Map<String, Object> output = map(map(result.structuredContent()).get("data")); String id = (String) map(output.get("idMap")).get("shot");
        DramaShot saved = shots.selectById(McpScope.id(id));
        assertEquals("external first", saved.getFirstFramePrompt()); assertEquals("external video", saved.getVideoPrompt()); assertEquals("MANUAL", saved.getDirectorPlanMode());
        assertEquals("MANUAL_UPLOAD", saved.getFirstFrameSourceType()); assertTrue(saved.getRefImagesJson().contains("look.png"));
        Map<String, Object> view = records.read(caller, McpRecordType.SHOT, id, ROOT);
        assertEquals("external positive", view.get("prompt")); assertEquals("123", view.get("seed"));
        assertEquals("walking", map(((List<?>) view.get("characterRefs")).getFirst()).get("actionPrompt"));
        assertEquals("60", map(((List<?>) view.get("refImages")).getFirst()).get("sourceId"));
        assertEquals(new BigDecimal("3"), new BigDecimal(map(((List<?>) view.get("refAudios")).getFirst()).get("duration").toString()));
        assertFalse(map(view.get("directorPlan")).containsKey("skills")); verifyNoInteractions(storage);
    }

    @Test void externalPlanUpdateUsesRevisionAndExplicitPromptClearWhilePreservingOmittedFields() {
        character(50L, PROJECT); look(60L, 50L, 1);
        DramaShot before = shot(80L); before.setPrompt("old positive"); before.setFirstFramePrompt("preserve first"); before.setCustomScenePrompt("old background");
        before.setCharacterRefsJson("[{\"characterId\":50,\"lookId\":60,\"actionPrompt\":\"old action\",\"emotionPrompt\":\"preserve emotion\"}]"); shots.updateById(before);
        Map<String, Object> data = new LinkedHashMap<>(Map.of("duration", 6, "videoPrompt", "external replacement", "directorPlan", plan(6),
                "characterRefs", List.of(Map.of("characterId", "50", "actionPrompt", "new action")))); data.put("prompt", null); data.put("customScenePrompt", null);
        var result = call("batch_update_shots", caller, new McpRecordInputs.BatchUpdate("external-patch01", ROOT, List.of(new McpRecordInputs.UpdateItem(
                "80", views.fingerprint(shots.selectById(80L)), data))));
        DramaShot after = shots.selectById(80L); assertNull(after.getPrompt()); assertNull(after.getCustomScenePrompt()); assertEquals("preserve first", after.getFirstFramePrompt());
        assertEquals("external replacement", after.getVideoPrompt()); assertEquals("MANUAL", after.getDirectorPlanMode()); assertEquals(1, after.getDirectorPlanRevision());
        assertTrue(after.getCharacterRefsJson().contains("preserve emotion")); assertTrue(after.getCharacterRefsJson().contains("new action")); assertTrue(after.getCharacterRefsJson().contains("60"));
        assertTrue(json.write(result).contains("external replacement"));
    }

    @Test void invalidDirectorPlanRollsBackShotAndEarlierBatchItems() {
        DramaShot before = shot(80L); DramaShot earlier = shot(81L);
        Map<String, Object> invalid = new LinkedHashMap<>(plan(5)); invalid.put("cameraBeats", List.of(Map.of("startSec", 1, "endSec", 5, "movement", "STATIC", "speed", "NONE")));
        var input = new McpRecordInputs.BatchUpdate("invalid-plan-001", ROOT, List.of(new McpRecordInputs.UpdateItem("81", views.fingerprint(earlier), Map.of("scriptContent", "Temporary")),
                new McpRecordInputs.UpdateItem( "80", views.fingerprint(before), Map.of("prompt", "Temporary", "directorPlan", invalid))));
        assertEquals(400, assertThrows(BizException.class, () -> call("batch_update_shots", caller, input)).getCode());
        assertNull(shots.selectById(81L).getScriptContent()); assertNull(shots.selectById(80L).getPrompt()); assertEquals(0, count("mcp_business_operation"));
    }

    @Test void referenceMediaCannotCrossProjectsAndInternalSkillProvenanceCannotBeSpoofed() {
        character(50L, PROJECT); character(51L, PROJECT + 1); look(60L, 51L, 1);
        var foreign = Map.<String, Object>of("sceneId", "30", "refImages", List.of(Map.of("sourceType", "CHARACTER_REFERENCE", "sourceId", "60", "characterId", "50", "imageUrl", "https://media/look.png", "usageRole", "SUBJECT")));
        assertEquals(403, assertThrows(BizException.class, () -> create("foreign-media-01", List.of(item("shot", McpRecordType.SHOT, foreign)))).getCode());
        var audio = Map.<String, Object>of("sceneId", "30", "refAudios", List.of(Map.of("referenceType", "reference", "characterId", "51", "audioUrl", "https://media/voice.mp3")));
        assertEquals(403, assertThrows(BizException.class, () -> create("foreign-audio-01", List.of(item("shot", McpRecordType.SHOT, audio)))).getCode());
        Map<String, Object> spoof = new LinkedHashMap<>(plan(5)); spoof.put("skills", List.of(Map.of("name", "internal")));
        assertTrue(protocolCall("batch_create_records", caller, Map.of("requestId", "spoof-plan-0001", "dramaId", ROOT, "items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", Map.of("sceneId", "30", "directorPlan", spoof))))).isError());
        assertEquals(0, count("drama_shot"));
    }

    @Test void externalAssetPromptContentIsPersistedWithoutGeneration() {
        var result = create("asset-prompts-01", List.of(item("character", McpRecordType.CHARACTER, Map.of("name", "External", "appearancePrompt", "external identity", "negativePrompt", "external negative")),
                item("look", McpRecordType.CHARACTER_LOOK, Map.of("characterId", ref("character"), "lookName", "External look", "designDesc", "Business look", "outfitPrompt", "external clothing")),
                item("frame", McpRecordType.KEYFRAME, Map.of("name", "External frame", "prompt", "external first frame"))));
        Map<String, Object> ids = map(result.get("idMap"));
        assertEquals("external identity", characters.selectById(McpScope.id((String) ids.get("character"))).getAppearancePrompt());
        assertEquals("external clothing", looks.selectById(McpScope.id((String) ids.get("look"))).getOutfitPrompt());
        assertEquals("external first frame", keyframes.selectById(McpScope.id((String) ids.get("frame"))).getPrompt()); verifyNoInteractions(storage);
    }

    @Test void minimalBusinessRecordsNeedNeitherAssetPromptsNorDirectorPlans() {
        var output = create("minimal-records-01", List.of(
                item("ep", McpRecordType.EPISODE, Map.of()),
                item("scene", McpRecordType.SCENE, Map.of("episodeId", ref("ep"))),
                item("group", McpRecordType.SHOT_GROUP, Map.of("sceneId", ref("scene"))),
                item("shot", McpRecordType.SHOT, Map.of("sceneId", ref("scene"), "shotGroupId", ref("group"))),
                item("character", McpRecordType.CHARACTER, Map.of("displayName", "旅客")),
                item("look", McpRecordType.CHARACTER_LOOK, Map.of("characterId", ref("character"), "lookName", "日常装", "designDesc", "灰色棉衣")),
                item("assetScene", McpRecordType.RESOURCE_SCENE, Map.of("name", "车站")),
                item("prop", McpRecordType.PROP, Map.of("name", "行李")),
                item("frame", McpRecordType.KEYFRAME, Map.of("name", "站台", "frameUrl", "https://media/station.png"))));
        Map<String, Object> ids = map(output.get("idMap"));
        ResCharacter character = characters.selectById(McpScope.id((String) ids.get("character")));
        ResCharacterLook look = looks.selectById(McpScope.id((String) ids.get("look")));
        assertEquals("旅客", character.getName()); assertNull(character.getAppearancePrompt());
        assertNull(look.getOutfitPrompt()); assertNull(look.getAppearancePrompt()); assertEquals(1, look.getIsDefault());
        DramaShot saved = shots.selectById(McpScope.id((String) ids.get("shot")));
        assertNull(saved.getDirectorPlanJson()); assertNull(saved.getPrompt()); assertEquals("FIRST_LAST_FRAME", saved.getGenerationMode());
        assertEquals(0, saved.getDuration().compareTo(new BigDecimal("3"))); verifyNoInteractions(storage);
    }

    @Test void orderedWorkshopCardsPreserveWeakBindingsWithoutDirectorPlanOrInference() {
        character(50L, PROJECT); look(60L, 50L, 1); look(61L, 50L, 0);
        Map<String, Object> data = Map.of("sceneId", "30", "generationMode", "REFERENCE_MODE", "prompt", "<Picture 1> 风格；<Picture 2> 画外人物；<Audio 1> 音效。",
                "refImages", List.of(Map.of("sourceType", "UPLOAD", "imageUrl", "https://media/style.png", "usageRole", "STYLE"),
                        Map.of("sourceType", "CHARACTER_REFERENCE", "sourceId", "61", "characterId", "50", "imageUrl", "https://media/look.png", "usageRole", "NON_SUBJECT", "referenceRole", "COMBINED")),
                "refAudios", List.of(Map.of("audioUrl", "https://media/sfx.wav", "duration", 3, "referenceType", "partially_copy")));
        var created = create("workshop-order-01", List.of(item("shot", McpRecordType.SHOT, data)));
        String id = (String) map(created.get("idMap")).get("shot");
        DramaShot saved = shots.selectById(McpScope.id(id)); assertNull(saved.getDirectorPlanJson());
        Map<String, Object> view = records.read(caller, McpRecordType.SHOT, id, ROOT);
        List<?> media = (List<?>) view.get("refImages");
        assertEquals("https://media/style.png", map(media.get(0)).get("imageUrl"));
        assertEquals("50", map(media.get(1)).get("characterId")); assertEquals("NON_SUBJECT", map(media.get(1)).get("usageRole"));
        assertEquals("61", map(media.get(1)).get("lookId"));
        assertEquals(List.of(), view.get("characterRefs"));
        assertNotEquals(map(media.get(0)).get("id"), map(media.get(1)).get("id")); assertNotNull(map(media.get(0)).get("id"));
        List<?> audio = (List<?>) view.get("refAudios"); assertNotNull(map(audio.getFirst()).get("id"));
        assertEquals(created, create("workshop-order-01", List.of(item("shot", McpRecordType.SHOT, data))));
        List<?> reordered = List.of(media.get(1), media.get(0));
        call("batch_update_shots", caller, new McpRecordInputs.BatchUpdate("workshop-order-02", ROOT, List.of(new McpRecordInputs.UpdateItem( id,
                views.fingerprint(saved), Map.of("prompt", "<Picture 1> 画外人物；<Picture 2> 风格；<Audio 1> 音效。", "refImages", reordered)))));
        Map<String, Object> updated = records.read(caller, McpRecordType.SHOT, id, ROOT);
        assertEquals(reordered, updated.get("refImages")); assertEquals(audio, updated.get("refAudios")); assertNull(shots.selectById(McpScope.id(id)).getDirectorPlanJson());
        verifyNoInteractions(storage);
    }

    @Test void incompleteMediaAndBusinessRequirementsRollBackEarlierRecords() {
        List<McpBatchInputs.CreateItem> invalid = List.of(
                item("bad", McpRecordType.CHARACTER, Map.of("personality", "calm")),
                item("bad", McpRecordType.CHARACTER_LOOK, Map.of("characterId", "50", "lookName", "look")),
                item("bad", McpRecordType.KEYFRAME, Map.of("name", "frame")),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refImages", List.of(Map.of("sourceType", "UPLOAD")))),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refImages", List.of(Map.of("sourceType", "UPLOAD", "imageUrl", "  ")))),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refImages", List.of(Map.of("sourceType", "CHARACTER_REFERENCE", "imageUrl", "https://media/look.png", "sourceId", "60")))),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refImages", List.of(Map.of("sourceType", "SCENE", "imageUrl", "https://media/scene.png")))),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refAudios", List.of(Map.of("sourceType", "TTS", "audioUrl", "https://media/voice.mp3")))),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refAudios", List.of(Map.of("sourceType", "UPLOAD", "audioUrl", "https://media/voice.mp3", "duration", 1)))),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refAudios", List.of(Map.of("sourceType", "UPLOAD", "audioUrl", "https://media/voice.mp3", "duration", 8), Map.of("sourceType", "UPLOAD", "audioUrl", "https://media/voice2.mp3", "duration", 8)))),
                item("bad", McpRecordType.SHOT, Map.of("sceneId", "30", "refImages", List.of(Map.of("id", "same", "sourceType", "UPLOAD", "imageUrl", "https://media/one.png"), Map.of("id", "same", "sourceType", "UPLOAD", "imageUrl", "https://media/two.png")))));
        for (var item : invalid) assertEquals(400, assertThrows(BizException.class, () -> create("incomplete-data-01", List.of(item("ep", McpRecordType.EPISODE, Map.of()), item))).getCode());
        assertEquals(1, count("drama_episode")); assertEquals(0, count("drama_shot")); assertEquals(0, count("mcp_business_operation"));
    }

    @Test void ordinaryWorkshopPatchPreservesExistingManualPlan() {
        shot(80L);
        call("batch_update_shots", caller, new McpRecordInputs.BatchUpdate("optional-plan-01", ROOT, List.of(new McpRecordInputs.UpdateItem( "80",
                views.fingerprint(shots.selectById(80L)), Map.of("directorPlan", plan(5))))));
        DramaShot before = shots.selectById(80L);
        call("batch_update_shots", caller, new McpRecordInputs.BatchUpdate("optional-plan-02", ROOT, List.of(new McpRecordInputs.UpdateItem( "80",
                views.fingerprint(before), Map.of("videoPrompt", "直接填写卡片")))));
        DramaShot after = shots.selectById(80L);
        assertEquals(before.getDirectorPlanJson(), after.getDirectorPlanJson()); assertEquals(before.getDirectorPlanRevision(), after.getDirectorPlanRevision());
        assertEquals("直接填写卡片", after.getVideoPrompt());
    }

    @Test void externalAgentCannotOverrideOrResetCreatorLockedDirectorPlan() {
        shot(80L);
        Map<String, Object> locked = new LinkedHashMap<>(plan(5)); locked.put("lockedFields", List.of("narrativeIntent"));
        call("batch_update_shots", caller, new McpRecordInputs.BatchUpdate("locked-plan-set1", ROOT, List.of(new McpRecordInputs.UpdateItem( "80",
                views.fingerprint(shots.selectById(80L)), Map.of("directorPlan", locked)))));
        Map<String, Object> changed = new LinkedHashMap<>(locked); changed.put("narrativeIntent", "覆盖创作者意图");
        for (Map<String, Object> data : List.of(Map.<String, Object>of("directorPlan", changed), Map.<String, Object>of("directorPlanMode", "AUTO"))) {
            var input = new McpRecordInputs.BatchUpdate("locked-plan-deny", ROOT, List.of(new McpRecordInputs.UpdateItem( "80", views.fingerprint(shots.selectById(80L)), data)));
            assertEquals(409, assertThrows(BizException.class, () -> call("batch_update_shots", caller, input)).getCode());
        }
        assertEquals(1, shots.selectById(80L).getDirectorPlanRevision()); assertEquals("MANUAL", shots.selectById(80L).getDirectorPlanMode());
    }

    Map<String, Object> singleRead(String object, McpPrincipal principal, String project, String id) {
        Object input = object.equals("project") ? new McpInputs.Project(project) : object.equals("shot") ? new McpInputs.Shot(project, id)
                : new McpRecordInputs.Get(project, id);
        return map(call("get_" + object, principal, input));
    }
    McpBatchInputs.DeleteItem deleteItem(McpRecordType type, String id) {
        return new McpBatchInputs.DeleteItem(type, id, views.fingerprint(records.require(caller, type, id, ROOT, true)));
    }

    @Test void everyObjectHasWorkingSingleCrudAndDedicatedBatchUpdate() {
        McpPrincipal creator = new McpPrincipal(true, false, true, Set.of());
        var projectInput = new McpRecordInputs.Create("single-create-project", null, Map.of("title", "Isolated project", "aspectRatio", "16:9"));
        Map<String, Object> created = map(call("create_project", creator, projectInput));
        assertEquals(created, map(call("create_project", creator, projectInput)));
        String project = (String) created.get("recordId");
        Map<String, String> ids = new LinkedHashMap<>(); ids.put("project", project);
        Map<String, Map<String, Object>> data = new LinkedHashMap<>();
        data.put("episode", Map.of("title", "Episode"));
        data.put("character", Map.of("name", "Actor"));
        data.put("resource_scene", Map.of("name", "Location"));
        data.put("prop", Map.of("name", "Prop"));
        data.put("keyframe", Map.of("name", "Frame", "frameUrl", "https://media/frame.png"));
        for (var entry : data.entrySet()) {
            var result = map(call("create_" + entry.getKey(), creator, new McpRecordInputs.Create("single-create-" + entry.getKey(), project, entry.getValue())));
            ids.put(entry.getKey(), (String) result.get("recordId"));
        }
        ids.put("scene", (String) map(call("create_scene", creator, new McpRecordInputs.Create("single-create-scene", project, Map.of("episodeId", ids.get("episode"))))).get("recordId"));
        ids.put("shot_group", (String) map(call("create_shot_group", creator, new McpRecordInputs.Create("single-create-group", project, Map.of("sceneId", ids.get("scene"))))).get("recordId"));
        ids.put("shot", (String) map(call("create_shot", creator, new McpRecordInputs.Create("single-create-shot", project, Map.of("sceneId", ids.get("scene"), "shotGroupId", ids.get("shot_group"))))).get("recordId"));
        ids.put("character_look", (String) map(call("create_character_look", creator, new McpRecordInputs.Create("single-create-look", project,
                Map.of("characterId", ids.get("character"), "lookName", "Day", "designDesc", "Grey coat")))).get("recordId"));
        for (var entry : ids.entrySet()) {
            String object = entry.getKey(), id = entry.getValue();
            var before = singleRead(object, creator, project, id);
            assertEquals(id, before.get("id"));
            var update = new McpRecordInputs.Update("single-update-" + object, project, id, (String) before.get("editFingerprint"), Map.of("remark", "Single update"));
            Object updated = call("update_" + object, creator, update);
            assertEquals(map(updated), map(call("update_" + object, creator, update)));
            var after = singleRead(object, creator, project, id); assertEquals("Single update", after.get("remark"));
            var batch = new McpRecordInputs.BatchUpdate("typed-batch-" + object, project, List.of(new McpRecordInputs.UpdateItem(id, (String) after.get("editFingerprint"), Map.of("remark", "Batch update"))));
            call("batch_update_" + object + "s", creator, batch);
            assertEquals("Batch update", singleRead(object, creator, project, id).get("remark"));
        }
        for (String object : List.of("keyframe", "prop", "resource_scene", "character_look", "character", "shot", "shot_group", "scene", "episode", "project")) {
            String id = ids.get(object);
            var delete = new McpRecordInputs.Delete("single-delete-" + object, project, id, (String) singleRead(object, creator, project, id).get("editFingerprint"));
            var result = map(call("delete_" + object, creator, delete)); assertEquals(true, result.get("deleted"));
            assertEquals(result, map(call("delete_" + object, creator, delete)));
            assertEquals("SUCCEEDED", map(journal.read(creator, delete.requestId())).get("status"));
            assertEquals(404, assertThrows(BizException.class, () -> singleRead(object, creator, project, id)).getCode());
        }
        verifyNoInteractions(storage);
    }

    @Test void batchDeleteChecksAllOriginalVersionsThenDeletesChildrenBeforeParentsAndReplays() {
        shot(80L);
        DramaShotVideoTake take = new DramaShotVideoTake(); take.setId(90L); take.setShotId(80L); takes.insert(take);
        var input = new McpBatchInputs.Delete("delete-hierarchy-01", ROOT, List.of(deleteItem(McpRecordType.SHOT, "80"),
                deleteItem(McpRecordType.SHOT_GROUP, "40"), deleteItem(McpRecordType.SCENE, "30"),
                deleteItem(McpRecordType.EPISODE, "20"), deleteItem(McpRecordType.PROJECT, ROOT)));
        Object result = call("batch_delete_records", caller, input);
        assertNull(dramas.selectById(PROJECT)); assertNull(takes.selectById(90L));
        assertEquals(1, new JdbcTemplate(dataSource).queryForObject("SELECT deleted FROM drama WHERE id=?", Integer.class, PROJECT));
        assertEquals(map(result), map(call("batch_delete_records", caller, input)));
        assertEquals("SUCCEEDED", map(journal.read(caller, input.requestId())).get("status"));
        verifyNoInteractions(storage);
    }

    @Test void deleteRollbackRejectsStaleDuplicateParentPublicForeignAndReadOnlyRequests() {
        shot(80L); shot(81L);
        var a = deleteItem(McpRecordType.SHOT, "80"); var b = deleteItem(McpRecordType.SHOT, "81");
        var parent = deleteItem(McpRecordType.SHOT_GROUP, "40");
        for (List<McpBatchInputs.DeleteItem> items : List.of(List.of(a, parent), List.of(a, a),
                List.of(a, new McpBatchInputs.DeleteItem(b.type(), b.recordId(), "0".repeat(64))))) {
            assertThrows(BizException.class, () -> call("batch_delete_records", caller, new McpBatchInputs.Delete("delete-fail-test", ROOT, items)));
            assertNotNull(shots.selectById(80L)); assertNotNull(groups.selectById(40L)); assertEquals(0, count("mcp_business_operation"));
        }
        character(50L, 0L); character(51L, PROJECT + 1);
        for (String id : List.of("50", "51")) assertEquals(403, assertThrows(BizException.class, () -> call("delete_character", caller,
                new McpRecordInputs.Delete("delete-scope-test", ROOT, id, views.fingerprint(characters.selectById(McpScope.id(id)))))).getCode());
        assertEquals(403, assertThrows(BizException.class, () -> call("delete_shot", new McpPrincipal(false, false, false, Set.of(PROJECT)),
                new McpRecordInputs.Delete("delete-readonly-01", ROOT, "80", a.expectedFingerprint()))).getCode());
        var unsupported = new McpRecordInputs.Create("single-foreign-01", Long.toString(PROJECT + 1), Map.of("name", "Forbidden"));
        assertEquals(403, assertThrows(BizException.class, () -> call("create_prop", caller, unsupported)).getCode());
        verifyNoInteractions(storage);
    }

    @Test void defaultLookPromotionDoesNotInvalidateLaterDeletionInSameBatch() {
        character(50L, PROJECT); look(60L, 50L, 1); look(61L, 50L, 0);
        var input = new McpBatchInputs.Delete("delete-looks-001", ROOT, List.of(deleteItem(McpRecordType.CHARACTER_LOOK, "60"),
                deleteItem(McpRecordType.CHARACTER_LOOK, "61"), deleteItem(McpRecordType.CHARACTER, "50")));
        call("batch_delete_records", caller, input);
        assertNull(looks.selectById(60L)); assertNull(looks.selectById(61L)); assertNull(characters.selectById(50L));
    }

    @Test void directReferencesWeakImageBindingsAndAudioReferencesBlockAssetDeletion() {
        character(50L, PROJECT); look(60L, 50L, 1); shot(80L);
        ResScene location = new ResScene(); location.setId(70L); location.setDramaId(PROJECT); location.setName("Location"); resourceScenes.insert(location);
        ResProp prop = new ResProp(); prop.setId(71L); prop.setDramaId(PROJECT); prop.setName("Prop"); props.insert(prop);
        ResKeyframe frame = new ResKeyframe(); frame.setId(72L); frame.setDramaId(PROJECT); frame.setName("Frame"); keyframes.insert(frame);
        record Case(McpRecordType type, String id, String column, Object value) {}
        List<Case> cases = List.of(
                new Case(McpRecordType.CHARACTER_LOOK, "60", "character_refs_json", "[{\"characterId\":50,\"lookId\":60}]"),
                new Case(McpRecordType.CHARACTER_LOOK, "60", "ref_images_json", "[{\"sourceType\":\"UPLOAD\",\"lookId\":\"60\"}]"),
                new Case(McpRecordType.CHARACTER_LOOK, "60", "ref_images_json", "[{\"sourceType\":\"CHARACTER_REFERENCE\",\"sourceId\":\"60\"}]"),
                new Case(McpRecordType.RESOURCE_SCENE, "70", "res_scene_id", 70L),
                new Case(McpRecordType.PROP, "71", "prop_refs_json", "[{\"propId\":\"71\"}]"),
                new Case(McpRecordType.PROP, "71", "ref_images_json", "[{\"sourceType\":\"PROP\",\"sourceId\":\"71\"}]"),
                new Case(McpRecordType.KEYFRAME, "72", "res_keyframe_id", 72L),
                new Case(McpRecordType.KEYFRAME, "72", "ref_images_json", "[{\"sourceType\":\"KEYFRAME\",\"sourceId\":\"72\"}]"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (Case c : cases) {
            jdbc.update("UPDATE drama_shot SET " + c.column() + "=? WHERE id=80", c.value());
            var input = new McpBatchInputs.Delete("delete-in-use-01", ROOT, List.of(deleteItem(c.type(), c.id())));
            assertTrue(assertThrows(BizException.class, () -> call("batch_delete_records", caller, input)).getMessage().contains("RECORD_IN_USE"));
            jdbc.update("UPDATE drama_shot SET " + c.column() + "=NULL WHERE id=80");
        }
        call("delete_character_look", caller, new McpRecordInputs.Delete("delete-empty-look", ROOT, "60", views.fingerprint(looks.selectById(60L))));
        for (String[] ref : List.of(new String[]{"character_refs_json", "[{\"characterId\":50}]"},
                new String[]{"ref_images_json", "[{\"sourceType\":\"UPLOAD\",\"characterId\":\"50\"}]"},
                new String[]{"ref_images_json", "[{\"sourceType\":\"CHARACTER\",\"sourceId\":\"50\"}]"},
                new String[]{"ref_audios_json", "[{\"sourceType\":\"UPLOAD\",\"characterId\":\"50\"}]"})) {
            jdbc.update("UPDATE drama_shot SET " + ref[0] + "=? WHERE id=80", ref[1]);
            var input = new McpBatchInputs.Delete("delete-char-used", ROOT, List.of(deleteItem(McpRecordType.CHARACTER, "50")));
            assertTrue(assertThrows(BizException.class, () -> call("batch_delete_records", caller, input)).getMessage().contains("RECORD_IN_USE"));
            jdbc.update("UPDATE drama_shot SET " + ref[0] + "=NULL WHERE id=80");
        }
        jdbc.update("UPDATE drama_scene SET res_scene_id=70 WHERE id=30");
        assertThrows(BizException.class, () -> call("batch_delete_records", caller, new McpBatchInputs.Delete("delete-sc-used01", ROOT, List.of(deleteItem(McpRecordType.RESOURCE_SCENE, "70")))));
        jdbc.update("UPDATE drama_scene SET res_scene_id=NULL WHERE id=30");
        call("batch_delete_records", caller, new McpBatchInputs.Delete("delete-free-001", ROOT, List.of(deleteItem(McpRecordType.CHARACTER, "50"),
                deleteItem(McpRecordType.RESOURCE_SCENE, "70"), deleteItem(McpRecordType.PROP, "71"), deleteItem(McpRecordType.KEYFRAME, "72"))));
        verifyNoInteractions(storage);
    }

    @Test void busyShotsBoundKeyframesVideoSourcesAndInvalidStoredReferencesRejectDeletion() {
        DramaShot a = shot(80L), b = shot(81L); b.setFirstFrameSourceShotId(80L); shots.updateById(b);
        assertTrue(assertThrows(BizException.class, () -> call("batch_delete_records", caller,
                new McpBatchInputs.Delete("delete-video-ref", ROOT, List.of(deleteItem(McpRecordType.SHOT, "80"))))).getMessage().contains("RECORD_IN_USE"));
        call("batch_delete_records", caller, new McpBatchInputs.Delete("delete-video-pair", ROOT, List.of(deleteItem(McpRecordType.SHOT, "81"), deleteItem(McpRecordType.SHOT, "80"))));
        a = shot(82L); a.setRenderStatus("RENDERING"); shots.updateById(a);
        assertTrue(assertThrows(BizException.class, () -> call("batch_delete_records", caller,
                new McpBatchInputs.Delete("delete-busy-shot", ROOT, List.of(deleteItem(McpRecordType.SHOT, "82"))))).getMessage().contains("RECORD_BUSY"));
        new JdbcTemplate(dataSource).update("UPDATE drama_shot SET render_status=NULL WHERE id=82");
        ResKeyframe frame = new ResKeyframe(); frame.setId(72L); frame.setDramaId(PROJECT); frame.setShotId(82L); keyframes.insert(frame);
        assertTrue(assertThrows(BizException.class, () -> call("batch_delete_records", caller,
                new McpBatchInputs.Delete("delete-bound-shot", ROOT, List.of(deleteItem(McpRecordType.SHOT, "82"))))).getMessage().contains("HAS_CHILDREN"));
        character(50L, PROJECT);
        new JdbcTemplate(dataSource).update("UPDATE drama_shot SET ref_images_json='invalid json' WHERE id=82");
        assertTrue(assertThrows(BizException.class, () -> call("batch_delete_records", caller,
                new McpBatchInputs.Delete("delete-invalid-ref", ROOT, List.of(deleteItem(McpRecordType.CHARACTER, "50"))))).getMessage().contains("REFERENCE_DATA_INVALID"));
    }

    @Test void batchSizeLimitsAndDuplicateKeysDoNotLeavePartialWrites() {
        for (List<McpBatchInputs.CreateItem> items : List.of(List.<McpBatchInputs.CreateItem>of(), Collections.nCopies(101, item("ep", McpRecordType.EPISODE, Map.of("title", "Too many"))),
                List.of(item("same", McpRecordType.EPISODE, Map.of("title", "A")), item("same", McpRecordType.EPISODE, Map.of("title", "B"))))) {
            assertEquals(400, assertThrows(BizException.class, () -> create("batch-limits-01", items)).getCode());
        }
        assertEquals(1, count("drama_episode")); assertEquals(0, count("mcp_business_operation"));
    }
}
