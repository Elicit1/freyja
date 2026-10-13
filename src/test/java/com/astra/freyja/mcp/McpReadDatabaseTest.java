package com.astra.freyja.mcp;

import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Exercises actual SQL and the MyBatis pagination plugin without touching local development data. */
@SpringJUnitConfig(McpReadDatabaseTest.Config.class)
class McpReadDatabaseTest {
    static final long PROJECT = 2032095628944588801L;
    @Autowired DataSource dataSource;
    @Autowired DramaMapper dramas;
    @Autowired DramaEpisodeMapper episodes;
    @Autowired DramaSceneMapper scenes;
    @Autowired DramaShotGroupMapper groups;
    @Autowired DramaShotMapper shots;
    @Autowired ResCharacterMapper characters;
    @Autowired ResCharacterLookMapper looks;
    @Autowired ResSceneMapper resourceScenes;
    @Autowired ResPropMapper props;
    @Autowired ResKeyframeMapper keyframes;
    @Autowired McpQueryMapper queries;
    McpReadTools reads;
    McpAssets assets;
    final McpPrincipal caller = new McpPrincipal(false, false, false, Set.of(PROJECT));

    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackages = "com.astra.freyja.dao", annotationClass = Mapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:mcp-read-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            MybatisPlusInterceptor pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver().getResources("classpath*:/mapper/**/*.xml"));
            factory.setDataSource(source); factory.setConfiguration(config); factory.setPlugins(pagination); return factory.getObject();
        }
    }

    @BeforeEach void setup() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (Class<? extends BaseEntity> type : List.of(Drama.class, DramaEpisode.class, DramaScene.class, DramaShotGroup.class,
                DramaShot.class, ResCharacter.class, ResCharacterLook.class, ResScene.class, ResProp.class, ResKeyframe.class)) {
            var info = TableInfoHelper.getTableInfo(type);
            List<String> columns = new ArrayList<>(); columns.add(info.getKeyColumn() + " BIGINT PRIMARY KEY");
            info.getFieldList().forEach(f -> {
                Class<?> t = f.getPropertyType();
                String sqlType = t == Long.class ? "BIGINT" : t == Integer.class || t == Boolean.class ? "INTEGER" :
                        Number.class.isAssignableFrom(t) ? "DECIMAL(30,8)" : t == java.time.LocalDateTime.class ? "TIMESTAMP" : "VARCHAR(100000)";
                columns.add(f.getColumn() + " " + sqlType);
            });
            jdbc.execute("DROP TABLE IF EXISTS " + info.getTableName());
            jdbc.execute("CREATE TABLE " + info.getTableName() + " (" + String.join(",", columns) + ")");
        }
        McpScope scope = new McpScope(dramas, episodes, scenes, groups, shots);
        McpJson json = new McpJson();
        assets = new McpAssets(characters, looks, resourceScenes, props, keyframes, scope, json, new McpBusinessView(json), queries);
        reads = new McpReadTools(dramas, episodes, scenes, groups, mock(DictService.class), queries, assets, scope, json, new McpBusinessView(json));
        for (long id : List.of(PROJECT, PROJECT + 1)) {
            Drama d = new Drama(); d.setId(id); d.setTitle("Project " + id); d.setSortOrder(0); d.setDeleted(0); dramas.insert(d);
        }
    }

    @SuppressWarnings("unchecked") Map<String, Object> value(Object value) { return (Map<String, Object>) value; }

    @Test void projectSearchFiltersBeforeCountingAndPagination() {
        Map<String, Object> result = value(reads.searchProjects(caller, new McpInputs.Search(null, 1, 1)));
        assertEquals(1L, result.get("total")); assertEquals(1, ((List<?>) result.get("records")).size());
        assertEquals(PROJECT, value(((List<?>) result.get("records")).getFirst()).get("id"));
    }

    @Test void assetSearchSeparatesProjectPublicAndOtherProjects() {
        for (long id : List.of(10L, 11L, 12L)) {
            ResCharacter c = new ResCharacter(); c.setId(id); c.setName("Character " + id); c.setSortOrder(0); c.setDeleted(0);
            if (id == 10) c.setDramaId(PROJECT);
            if (id == 12) c.setDramaId(PROJECT + 1);
            characters.insert(c);
        }
        var project = value(assets.search(caller, new McpInputs.Assets(McpInputs.AssetType.CHARACTER, Long.toString(PROJECT), false, null, null, 1, 20)));
        var shared = value(assets.search(caller, new McpInputs.Assets(McpInputs.AssetType.CHARACTER, null, true, null, null, 1, 20)));
        assertEquals(1L, project.get("total")); assertEquals(1L, shared.get("total"));
        assertEquals("10", value(((List<?>) project.get("records")).getFirst()).get("id"));
        assertEquals("11", value(((List<?>) shared.get("records")).getFirst()).get("id"));
        assertThrows(com.astra.freyja.common.BizException.class, () -> assets.resolve(caller, McpInputs.AssetType.CHARACTER, "12", Long.toString(PROJECT), false));
        assertThrows(com.astra.freyja.common.BizException.class, () -> assets.resolve(caller, McpInputs.AssetType.CHARACTER, "11", null, true));
    }

    @Test void queryPaginationExcludesShotsWithDeletedParentsAndOtherProjects() {
        for (int i = 0; i < 2; i++) {
            DramaEpisode e = new DramaEpisode(); e.setId(20L + i); e.setDramaId(PROJECT); e.setEpisodeNo(i + 1); e.setDeleted(i); episodes.insert(e);
            DramaScene s = new DramaScene(); s.setId(30L + i); s.setDramaId(PROJECT); s.setEpisodeId(e.getId()); s.setSceneNo(1); s.setDeleted(0); scenes.insert(s);
        }
        for (int i = 0; i < 4; i++) {
            DramaShot s = new DramaShot(); s.setId(40L + i); s.setDramaId(i == 3 ? PROJECT + 1 : PROJECT);
            s.setEpisodeId(i == 2 ? 21L : 20L); s.setSceneId(i == 2 ? 31L : 30L); s.setShotNo(i + 1); s.setSortOrder(i + 1); s.setDeleted(0); shots.insert(s);
        }
        var result = value(reads.shots(caller, new McpInputs.Shots(Long.toString(PROJECT), null, null, 2, 1)));
        assertEquals(2L, result.get("total")); assertEquals(1, ((List<?>) result.get("records")).size());
        assertEquals(41L, value(((List<?>) result.get("records")).getFirst()).get("id"));
        assertEquals(false, result.get("hasMore"));
    }

    @Test void outlineFiltersInconsistentParentRelationshipsInSql() {
        for (int i = 0; i < 2; i++) {
            DramaEpisode e = new DramaEpisode(); e.setId(20L + i); e.setDramaId(i == 0 ? PROJECT : PROJECT + 1);
            e.setEpisodeNo(i + 1); e.setDeleted(0); episodes.insert(e);
            DramaScene s = new DramaScene(); s.setId(30L + i); s.setDramaId(PROJECT); s.setEpisodeId(e.getId());
            s.setSceneNo(i + 1); s.setDeleted(0); scenes.insert(s);
            DramaShotGroup g = new DramaShotGroup(); g.setId(40L + i); g.setDramaId(PROJECT); g.setEpisodeId(e.getId());
            g.setSceneId(30L); g.setGroupNo(i + 1); g.setDeleted(0); groups.insert(g);
        }
        var sceneResult = value(reads.outline(caller, new McpInputs.Outline(Long.toString(PROJECT), McpInputs.OutlineLevel.SCENE, null, null, 1, 20)));
        var groupResult = value(reads.outline(caller, new McpInputs.Outline(Long.toString(PROJECT), McpInputs.OutlineLevel.SHOT_GROUP, null, null, 1, 20)));
        assertEquals(1L, sceneResult.get("total")); assertEquals(1L, groupResult.get("total"));
        assertEquals("30", value(((List<?>) sceneResult.get("records")).getFirst()).get("id"));
        assertEquals("40", value(((List<?>) groupResult.get("records")).getFirst()).get("id"));
    }

    @Test void keyframePaginationExcludesForeignOrInvalidShotBindings() {
        for (int i = 0; i < 2; i++) {
            long project = PROJECT + i;
            DramaEpisode e = new DramaEpisode(); e.setId(20L + i); e.setDramaId(project); e.setDeleted(0); episodes.insert(e);
            DramaScene s = new DramaScene(); s.setId(30L + i); s.setDramaId(project); s.setEpisodeId(e.getId()); s.setDeleted(0); scenes.insert(s);
            DramaShot shot = new DramaShot(); shot.setId(40L + i); shot.setDramaId(project); shot.setEpisodeId(e.getId()); shot.setSceneId(s.getId()); shot.setDeleted(0); shots.insert(shot);
        }
        for (int i = 0; i < 5; i++) {
            ResKeyframe frame = new ResKeyframe(); frame.setId(50L + i); frame.setName("Frame " + i); frame.setDeleted(0); frame.setSortOrder(i);
            if (i < 3) frame.setDramaId(PROJECT);
            if (i == 1 || i == 3) frame.setShotId(40L);
            if (i == 2) frame.setShotId(41L);
            keyframes.insert(frame);
        }
        var project = value(assets.search(caller, new McpInputs.Assets(McpInputs.AssetType.KEYFRAME, Long.toString(PROJECT), false, null, null, 1, 20)));
        var shared = value(assets.search(caller, new McpInputs.Assets(McpInputs.AssetType.KEYFRAME, null, true, null, null, 1, 20)));
        assertEquals(2L, project.get("total")); assertEquals(1L, shared.get("total"));
        assertEquals("54", value(((List<?>) shared.get("records")).getFirst()).get("id"));
        DramaShot invalid = shots.selectById(40L); invalid.setShotGroupId(999L); shots.updateById(invalid);
        assertEquals(1L, value(assets.search(caller, new McpInputs.Assets(McpInputs.AssetType.KEYFRAME, Long.toString(PROJECT), false, null, null, 1, 20))).get("total"));
        assertEquals(0L, value(reads.shots(caller, new McpInputs.Shots(Long.toString(PROJECT), null, null, 1, 20))).get("total"));
    }
}
