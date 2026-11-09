package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.*;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.video.*;
import io.minio.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All media and native processes are simulated; no encoding or GPU calls. */
class ReferenceVideoPreparationSessionTest {
    final MinioClient minio=mock(MinioClient.class);
    final VideoFrameExtractService probe=mock(VideoFrameExtractService.class);
    final NativeProcessExecutor processes=mock(NativeProcessExecutor.class);
    final List<Path> sourcePaths=new ArrayList<>(),clipPaths=new ArrayList<>();
    ReferenceVideoMediaService service;

    @BeforeEach void setup() throws Exception {
        var storage=new MinioProperties();storage.setEndpoint("http://storage.test:9000");storage.setBucketName("assets");
        service=new ReferenceVideoMediaService(new ReferenceVideoProperties(),new MediaToolProperties(),probe,processes,minio,storage);
        var stat=mock(StatObjectResponse.class);when(stat.size()).thenReturn(3L);when(stat.etag()).thenReturn("etag");
        when(minio.statObject(any())).thenReturn(stat);
        when(minio.getObject(any())).thenAnswer(call->new GetObjectResponse(okhttp3.Headers.of(),"assets","region","source",new java.io.ByteArrayInputStream(new byte[]{1,2,3})));
        doAnswer(call->{sourcePaths.add(call.getArgument(0));return metadata(30,720,true);}).when(probe).probeVideoFile(any(),eq(false));
        doAnswer(call->metadata(124.0/24,124,false)).when(probe).probeVideoFile(any(),eq(true));
        doAnswer(call->{
            List<String> command=call.getArgument(0);var path=Path.of(command.getLast());clipPaths.add(path);
            assertEquals("124",command.get(command.indexOf("-frames:v")+1));
            Files.write(path,new byte[]{4,5,6});return new NativeProcessExecutor.ProcessResult(0,false,"");
        }).when(processes).execute(anyList(),any(),any());
    }
    VideoProbeInfoVO metadata(double seconds,long frames,boolean audio){
        var info=new VideoProbeInfoVO();info.setWidth(384);info.setHeight(256);info.setFps(24.0);
        info.setDuration(BigDecimal.valueOf(seconds));info.setFrameCount(frames);info.setHasAudio(audio);return info;
    }
    ShotRefVideoDTO input(int start,String id){
        var ref=new ShotRefVideoDTO();ref.setId(id);ref.setSourceType("DIRECT_URL");ref.setSourceStartFrame(start);ref.setSourceEndFrame(start+124);
        ref.setStartSeconds(BigDecimal.valueOf(start).divide(BigDecimal.valueOf(24),12,java.math.RoundingMode.HALF_UP));
        ref.setEndSeconds(BigDecimal.valueOf(start+124).divide(BigDecimal.valueOf(24),12,java.math.RoundingMode.HALF_UP));return ref;
    }
    ResolvedVideoProcessSource source(String query){return ResolvedVideoProcessSource.builder().videoUrl("http://storage.test:9000/assets/source.mp4"+query).build();}

    @Test void sevenSlicesDownloadProbeAndArchiveSourceOnceAndCleanTemporaryFiles() throws Exception {
        var results=new ArrayList<ResolvedShotVideoReference>();
        try(var session=service.openPreparation()){
            for(int i=0;i<7;i++)results.add(session.prepare(input(i*85,"v"+i),source(""),null));
            assertEquals(1,results.stream().map(ResolvedShotVideoReference::getSourceObjectPath).distinct().count());
            assertEquals(7,results.stream().map(ResolvedShotVideoReference::getObjectPath).distinct().count());
            assertTrue(Files.exists(sourcePaths.getFirst()));
        }
        verify(minio,times(1)).getObject(any());verify(probe,times(1)).probeVideoFile(any(),eq(false));
        verify(minio,times(8)).putObject(any());verify(processes,times(7)).execute(anyList(),any(),any());
        assertTrue(sourcePaths.stream().noneMatch(Files::exists));assertTrue(clipPaths.stream().noneMatch(Files::exists));
    }

    @Test void duplicateClipReusesMediaButKeepsSeparateBindingsAndDifferentAudioIsNotReused() throws Exception {
        try(var session=service.openPreparation()){
            var first=session.prepare(input(0,"a"),source(""),null);first.setOrdinal(9);
            var input=input(0,"b");input.setReferenceDescription("第二个用途");
            var second=session.prepare(input,source(""),null);
            assertNotSame(first,second);assertEquals("b",second.getId());assertEquals(1,second.getOrdinal());
            assertEquals("第二个用途",second.getReferenceDescription());assertEquals(first.getObjectPath(),second.getObjectPath());
            input.setIncludeAudio(true);doReturn(metadata(124.0/24,124,true)).when(probe).probeVideoFile(any(),eq(true));
            var audio=session.prepare(input,source(""),null);assertNotEquals(first.getObjectPath(),audio.getObjectPath());
            var restrictive=new ReferenceVideoCapability();restrictive.setMaxDecodedFrames(100);
            assertThrows(BizException.class,()->session.prepare(input,source(""),restrictive));
        }
        verify(processes,times(2)).execute(anyList(),any(),any());verify(minio,times(3)).putObject(any());
    }

    @Test void sourceVersionAndJobBoundariesAreNeverHiddenByCache() throws Exception {
        try(var session=service.openPreparation()){
            session.prepare(input(0,"a"),source("?versionId=one"),null);
            session.prepare(input(0,"b"),source("?versionId=two"),null);
        }
        try(var session=service.openPreparation()){session.prepare(input(0,"c"),source("?versionId=two"),null);}
        var reads=org.mockito.ArgumentCaptor.forClass(GetObjectArgs.class);verify(minio,times(3)).getObject(reads.capture());
        assertEquals(List.of("one","two","two"),reads.getAllValues().stream().map(GetObjectArgs::versionId).toList());
    }

    @Test void failedPreparationAlsoReleasesSourceFilesAndCapacity() throws Exception {
        doThrow(new java.io.IOException("simulated failure")).when(processes).execute(anyList(),any(),any());
        try(var session=service.openPreparation()){assertThrows(BizException.class,()->session.prepare(input(0,"a"),source(""),null));}
        assertTrue(sourcePaths.stream().noneMatch(Files::exists));
        try(var session=service.openPreparation()){assertThrows(BizException.class,()->session.prepare(input(0,"b"),source(""),null));}
        verify(minio,times(2)).getObject(any());
    }
}
