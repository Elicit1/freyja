package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.config.VideoExportProperties;
import com.astra.freyja.service.mediaexport.VideoAssetSourceResolver;
import com.astra.freyja.service.mediaexport.VideoExportModels.Snapshot;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoAssetSourceResolverTest {
    private final MinioProperties properties=new MinioProperties();
    private final MinioClient minio=mock(MinioClient.class);
    private final VideoAssetSourceResolver resolver=new VideoAssetSourceResolver(minio,properties,new VideoExportProperties());
    private Snapshot snapshot(String url){Snapshot snapshot=new Snapshot();snapshot.setVideoUrl(url);return snapshot;}
    @Test void parsesOnlyConfiguredOriginAndBucketWithoutSubstringMatching(){
        properties.setExternalEndpoint("https://media.example.test/storage");var s=snapshot("https://media.example.test/storage/video-assets/剧作/one%20video.mp4?token=old");resolver.resolve(s);assertEquals("video-assets",s.getBucket());assertEquals("剧作/one video.mp4",s.getObjectKey());verifyNoInteractions(minio);
    }
    @Test void rejectsForeignHostsCredentialsWrongBucketsAndTraversal(){
        for(String url:List.of("http://evil.test/video-assets/1.mp4","http://127.0.0.1:9000.evil.test/video-assets/1.mp4","http://user:secret@127.0.0.1:9000/video-assets/1.mp4","http://127.0.0.1:9000/other/video-assets/1.mp4","http://127.0.0.1:9000/video-assets/%2e%2e/1.mp4"))assertThrows(BizException.class,()->resolver.resolve(snapshot(url)),url);
        verifyNoInteractions(minio);
    }
    @Test void keepsActualVideoTypeAndFreezesObjectIdentity()throws Exception{
        var response=mock(StatObjectResponse.class);when(response.size()).thenReturn(1234L);when(response.etag()).thenReturn("etag-v1");when(response.versionId()).thenReturn("version-1");when(response.contentType()).thenReturn("video/webm");when(minio.statObject(any())).thenReturn(response);
        var s=snapshot("http://127.0.0.1:9000/video-assets/legacy.mp4");resolver.resolve(s);resolver.describe(s);assertEquals("webm",s.getExtension());assertEquals("version-1",s.getVersionId());assertEquals(1234L,s.getSourceSize());
    }
    @Test void explicitObjectVersionInBoundUrlIsHonoredBeforeStat()throws Exception{
        var response=mock(StatObjectResponse.class);when(response.contentType()).thenReturn("video/mp4");when(response.versionId()).thenReturn("original-version");when(minio.statObject(any())).thenReturn(response);
        var s=snapshot("http://127.0.0.1:9000/video-assets/one.mp4?versionId=original-version");resolver.resolve(s);resolver.describe(s);
        var args=org.mockito.ArgumentCaptor.forClass(io.minio.StatObjectArgs.class);verify(minio).statObject(args.capture());assertEquals("original-version",args.getValue().versionId());
    }
}
