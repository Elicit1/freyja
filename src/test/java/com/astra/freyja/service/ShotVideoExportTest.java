package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.*;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.mediaexport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL transactions and real ZIP bytes; no MySQL, Redis, MinIO or GPU is required. */
class ShotVideoExportTest {
    @TempDir Path temporary;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    DramaShotMapper shots;DramaShotVideoTakeMapper takes;VideoAssetSourceResolver sources;
    ShotVideoExportSelection selection;ShotVideoExportStore store;ShotVideoExportService service;ShotVideoExportArchiveService archive;
    JdbcTemplate jdbc;TransactionTemplate tx;VideoExportProperties properties;
    final Map<Long,DramaShot> shotData=new LinkedHashMap<>();final Map<String,byte[]> media=new HashMap<>();
    final Map<Long,Path> archives=new HashMap<>();
    @BeforeAll static void metadata(){var assistant=new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"exports");for(Class<?> type:List.of(Drama.class,DramaEpisode.class,DramaScene.class,DramaShotGroup.class,DramaShot.class,DramaShotVideoTake.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,type);}
    @BeforeEach void setup()throws Exception{
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        String ddl=new ClassPathResource("sql/shot-video-export.sql").getContentAsString(StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new ShotVideoExportStore(jdbc,json);
        var dramas=mock(DramaMapper.class);var episodes=mock(DramaEpisodeMapper.class);var scenes=mock(DramaSceneMapper.class);var groups=mock(DramaShotGroupMapper.class);shots=mock(DramaShotMapper.class);takes=mock(DramaShotVideoTakeMapper.class);sources=mock(VideoAssetSourceResolver.class);
        Drama drama=new Drama();drama.setId(1L);drama.setTitle("测试短剧 / 中文");when(dramas.selectById(1L)).thenReturn(drama);
        DramaEpisode ep=new DramaEpisode();ep.setId(10L);ep.setDramaId(1L);ep.setEpisodeNo(1);ep.setTitle("第01集");ep.setSortOrder(1);when(episodes.selectList(any())).thenReturn(List.of(ep));
        DramaScene scene=new DramaScene();scene.setId(20L);scene.setDramaId(1L);scene.setEpisodeId(10L);scene.setSceneNo(1);scene.setName("客厅");scene.setSortOrder(1);when(scenes.selectList(any())).thenReturn(List.of(scene));
        DramaShotGroup group=new DramaShotGroup();group.setId(30L);group.setDramaId(1L);group.setEpisodeId(10L);group.setSceneId(20L);group.setGroupNo(1);group.setName("连续动作");group.setSortOrder(1);when(groups.selectList(any())).thenReturn(List.of(group));
        when(shots.selectList(any())).thenAnswer(call->new ArrayList<>(shotData.values()));when(shots.lockById(anyLong())).thenAnswer(call->shotData.get(call.getArgument(0)));
        doAnswer(call->{Snapshot s=call.getArgument(0);s.setBucket("media");s.setObjectKey(s.getVideoUrl().substring(s.getVideoUrl().lastIndexOf('/')+1));return null;}).when(sources).resolve(any());
        doAnswer(call->{Snapshot s=call.getArgument(0);s.setSourceSize((long)media.get(s.getObjectKey()).length);s.setEtag("etag-"+s.getObjectKey());s.setExtension("mp4");return null;}).when(sources).describe(any());
        when(sources.open(any())).thenAnswer(call->new ByteArrayInputStream(media.get(((Snapshot)call.getArgument(0)).getObjectKey())));
        properties=new VideoExportProperties();properties.setTempDirectory(temporary.resolve("work").toString());properties.setMinFreeBytes(0);
        selection=new ShotVideoExportSelection(dramas,episodes,scenes,groups,shots,takes,sources,properties,json);
        archive=spy(new ShotVideoExportArchiveService(sources,properties,new MinioProperties(),mock(MinioClient.class),json));
        doAnswer(call->{String key=call.getArgument(0);Path source=call.getArgument(1);Long id=Long.valueOf(key.split("/")[1]);Path target=temporary.resolve(id+".zip");Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);archives.put(id,target);return null;}).when(archive).upload(anyString(),any());
        service=new ShotVideoExportService(selection,store,archive,properties,tx,mock(ApplicationEventPublisher.class));
    }
    private DramaShot shot(long id,int order,boolean video){DramaShot s=new DramaShot();s.setId(id);s.setDramaId(1L);s.setEpisodeId(10L);s.setSceneId(20L);s.setShotGroupId(30L);s.setSortOrder(order);s.setShotNo(order);s.setShotName("镜头"+order);s.setDuration(BigDecimal.valueOf(3));if(video){s.setVideoUrl("http://media/"+id+".mp4");media.put(id+".mp4",("video-bytes-"+id).getBytes(StandardCharsets.UTF_8));}shotData.put(id,s);return s;}
    private Request request(Long...ids){Request request=new Request();request.setDramaId(1L);request.setShotIds(List.of(ids));request.setClientRequestId(UUID.randomUUID().toString());return request;}
    private Task create(Request request){request.setExpectedPreviewHash(service.preview(request).getPreviewHash());return service.create(request);}

    @Test void overlappingParentSelectionDeduplicatesAndIncludesUngroupedShots(){
        shot(41,1,true);shot(42,2,true).setShotGroupId(null);shot(43,3,false);
        Request request=request();request.setShotIds(null);var scopes=new Selection();var episode=new Scope();episode.setType("EPISODE");episode.setId(10L);var group=new Scope();group.setType("SHOT_GROUP");group.setId(30L);scopes.setInclude(List.of(episode,group));scopes.setExcludeShotIds(List.of(43L));request.setSelection(scopes);
        Preview preview=service.preview(request);assertEquals(List.of(41L,42L),preview.getShotIds());assertEquals(2,preview.getAccepted());assertTrue(preview.getItems().get(1).getZipEntryPath().contains("未归组"));
        var tree=service.tree(1L);assertEquals(3,tree.getFirst().getShotCount());assertEquals(2,tree.getFirst().getVideoCount());
        request.setShotIds(List.of(41L));assertThrows(BizException.class,()->service.preview(request));
    }
    @Test void stalePreviewIsRejectedButCommittedSnapshotKeepsOriginalVideoAndPaths()throws Exception{
        var s=shot(41,1,true);Request request=request(41L);request.setExpectedPreviewHash(service.preview(request).getPreviewHash());s.setShotName("改名");assertEquals(409,assertThrows(BizException.class,()->service.create(request)).getCode());
        Task task=create(request);String originalPath=store.items(task.getId()).getFirst().getZipEntryPath();s.setVideoUrl("http://media/new.mp4");s.setShotName("后续名称");media.put("new.mp4","new-version".getBytes(StandardCharsets.UTF_8));assertTrue(service.runNext());
        assertEquals("SUCCESS",service.detail(task.getId()).getStatus());
        try(var zip=new ZipFile(archives.get(task.getId()).toFile(),StandardCharsets.UTF_8)){assertArrayEquals(media.get("41.mp4"),zip.getInputStream(zip.getEntry(originalPath)).readAllBytes());assertFalse(originalPath.contains("后续名称"));}
    }
    @Test void zipKeepsOriginalBytesAndManifestHasStringIdsWithoutSourceAddresses()throws Exception{
        long id=2032095628944588801L;shot(id,1,true).setShotName("../../CON:镜头?");Task task=create(request(id));service.runNext();
        try(var zip=new ZipFile(archives.get(task.getId()).toFile(),StandardCharsets.UTF_8)){
            var entries=zip.stream().toList();var video=entries.stream().filter(e->e.getName().endsWith(".mp4")).findFirst().orElseThrow();assertEquals(ZipEntry.STORED,video.getMethod());assertFalse(Arrays.asList(video.getName().split("/")).contains(".."));assertArrayEquals(media.get(id+".mp4"),zip.getInputStream(video).readAllBytes());
            var manifest=entries.stream().filter(e->e.getName().endsWith("manifest.json")).findFirst().orElseThrow();String encoded=new String(zip.getInputStream(manifest).readAllBytes(),StandardCharsets.UTF_8);assertTrue(json.readTree(encoded).get(0).get("shotId").isTextual());assertFalse(encoded.contains("http://"));assertFalse(encoded.contains("objectKey"));assertFalse(encoded.contains("etag-"));
        }
        assertTrue(service.detail(task.getId()).isDownloadAvailable());assertTrue(Files.list(Path.of(properties.getTempDirectory())).findAny().isEmpty());
    }
    @Test void interruptedSourceDoesNotLeavePartialVideoInSuccessfulArchive()throws Exception{
        shot(41,1,true);shot(42,2,true);Task task=create(request(41L,42L));
        doReturn(new InputStream(){int count;public int read()throws IOException{if(count++>3)throw new IOException("connection lost");return 1;}}).when(sources).open(argThat(s->s!=null&&s.getShotId().equals(42L)));
        service.runNext();assertEquals("PARTIAL_SUCCESS",service.detail(task.getId()).getStatus());assertEquals(1,service.detail(task.getId()).getFailed());
        try(var zip=new ZipFile(archives.get(task.getId()).toFile(),StandardCharsets.UTF_8)){assertEquals(1,zip.stream().filter(e->e.getName().endsWith(".mp4")).count());assertTrue(zip.stream().anyMatch(e->e.getName().endsWith("失败清单.csv")));}
    }
    @Test void missingVideoPolicyDoesNotSelectAHistoricalTake(){
        shot(41,1,true);shot(42,2,false);Request strict=request(41L,42L);strict.setMissingVideoPolicy("FAIL");strict.setExpectedPreviewHash(service.preview(strict).getPreviewHash());assertThrows(BizException.class,()->service.create(strict));
        Task task=create(request(41L,42L));service.runNext();assertEquals("PARTIAL_SUCCESS",service.detail(task.getId()).getStatus());assertEquals(1,service.detail(task.getId()).getSkipped());verifyNoInteractions(takes);
    }
    @Test void inconsistentCurrentTakeIsBlocked(){
        var s=shot(41,1,true);s.setCurrentVideoTakeId(91L);var take=new DramaShotVideoTake();take.setId(91L);take.setShotId(999L);take.setVideoUrl(s.getVideoUrl());take.setStatus("AVAILABLE");when(takes.selectById(91L)).thenReturn(take);
        assertEquals(1,service.preview(request(41L)).getBlocked());assertThrows(BizException.class,()->create(request(41L)));
    }
    @Test void duplicateRequestsReturnOriginalTaskWithoutRereadingStorage(){
        shot(41,1,true);Request request=request(41L);Task task=create(request);clearInvocations(sources);assertEquals(task.getId(),service.create(request).getId());verifyNoInteractions(sources);request.setName("different");assertEquals(409,assertThrows(BizException.class,()->service.create(request)).getCode());
    }
    @Test void cancellationBeforeExecutionNeverReadsOrUploadsMedia()throws Exception{
        shot(41,1,true);Task task=create(request(41L));clearInvocations(sources);assertTrue(service.cancel(task.getId()).isCancelled());assertFalse(service.runNext());verify(sources,never()).open(any());verify(archive,never()).upload(anyString(),any());assertFalse(service.detail(task.getId()).isDownloadAvailable());
    }
    @Test void cancelRacingWithUploadCannotPublishADownloadablePackage()throws Exception{
        shot(41,1,true);Task task=create(request(41L));doAnswer(call->{service.cancel(task.getId());return null;}).when(archive).upload(anyString(),any());service.runNext();assertEquals("CANCELLED",service.detail(task.getId()).getStatus());assertFalse(service.detail(task.getId()).isDownloadAvailable());verify(archive).remove(contains(String.valueOf(task.getId())));
    }
    @Test void retryAndRecoveryUseOriginalSnapshotsInsteadOfCurrentBindings()throws Exception{
        var s=shot(41,1,true);Task task=create(request(41L));doThrow(new IOException("upload failed")).when(archive).upload(anyString(),any());service.runNext();assertEquals("FAILED",service.detail(task.getId()).getStatus());s.setVideoUrl("http://media/changed.mp4");
        Task retry=service.retry(task.getId(),UUID.randomUUID().toString());assertEquals("41.mp4",store.items(retry.getId()).getFirst().getSnapshot().getObjectKey());
        tx.executeWithoutResult(status->{store.lock();store.claim();});service.recover();assertEquals("QUEUED",service.detail(retry.getId()).getStatus());assertEquals("41.mp4",store.items(retry.getId()).getFirst().getSnapshot().getObjectKey());
    }
    @Test void csvEscapesFormulaCells(){Item item=new Item();item.setName(" =HYPERLINK(\"bad\")");assertTrue(ShotVideoExportArchiveService.csv(List.of(item)).contains("' =HYPERLINK"));}

    @Test void duplicateSourceAddressesRemainSeparateFiles()throws Exception{
        shot(41,1,true);shot(42,2,true).setVideoUrl("http://media/41.mp4");Task task=create(request(41L,42L));service.runNext();
        try(var zip=new ZipFile(archives.get(task.getId()).toFile(),StandardCharsets.UTF_8)){assertEquals(2,zip.stream().filter(e->e.getName().endsWith(".mp4")).count());}
    }
    @Test void expiredPackageDoesNotRegenerateOrIssueADownloadLink(){
        shot(41,1,true);Task task=create(request(41L));jdbc.update("UPDATE shot_video_export_task SET status='SUCCESS',expires_at=? WHERE id=?",java.time.LocalDateTime.now().minusHours(1),task.getId());
        assertFalse(service.detail(task.getId()).isDownloadAvailable());assertThrows(BizException.class,()->service.download(task.getId()));assertFalse(service.runNext());
    }
    @Test void archiveCapacityFailureDoesNotPublishAPartialPackage()throws Exception{
        shot(41,1,true);Task task=create(request(41L));properties.setMaxArchiveBytes(1);service.runNext();assertEquals("FAILED",service.detail(task.getId()).getStatus());verify(archive,never()).upload(anyString(),any());assertFalse(service.detail(task.getId()).isDownloadAvailable());
    }
    @Test void publicJsonUsesStringIdsAndDoesNotExposeFrozenSource(){
        long id=2032095628944588801L;shot(id,1,true);Preview preview=service.preview(request(id));
        var builder=tools.jackson.databind.json.JsonMapper.builder();new JacksonConfig().longToStringCustomizer().customize(builder);String encoded=builder.build().writeValueAsString(preview);
        assertTrue(encoded.contains("\"shotId\":\""+id+"\""));assertFalse(encoded.contains("videoUrl"));assertFalse(encoded.contains("etag-"));
    }
}
