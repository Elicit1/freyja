package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.service.VideoFrameExtractService;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Extract from an immutable video version without updating either shot's configured images. */
@Service
@RequiredArgsConstructor
public class VideoBatchFrameService {
    private final MinioClient minio;
    private final MinioProperties properties;
    private final VideoFrameExtractService frames;

    public String extract(Long shotId,String url,Long takeId,String frameType) {
        String bucket=properties.getBucketName();
        URI video=URI.create(url), endpoint=URI.create(properties.getExternalEndpoint());
        String prefix=(endpoint.getPath()==null?"":endpoint.getPath().replaceAll("/$",""))+"/"+bucket+"/";
        if(video.getPath()==null||!video.getPath().startsWith(prefix)) throw new BizException(400,"来源视频不是可抽帧的归档视频");
        String object=URLDecoder.decode(video.getRawPath().substring(prefix.length()),StandardCharsets.UTF_8);
        if(object.contains("..")||object.startsWith("/")||object.contains("\\")) throw new BizException(400,"来源视频路径无效");
        String key="video-batch/frames/"+shotId+"/"+VideoBatchPreparation.hash(url+"|"+takeId+"|"+frameType+"|decoded-v1")+".jpg";
        try {
            boolean cached=false;
            try{cached=minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size()>0;}
            catch(ErrorResponseException e){if(!java.util.Set.of("NoSuchKey","NoSuchObject","NotFound").contains(e.errorResponse().code()))throw e;}
            if(!cached){
                byte[] bytes;try(var input=minio.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())){bytes=input.readAllBytes();}
                String ext=object.lastIndexOf('.')>=0?object.substring(object.lastIndexOf('.')+1):"mp4";
                byte[] frame="FIRST".equals(frameType)?frames.extractFirstFrame(bytes,ext):frames.extractLastFrame(bytes,ext);
                if(frame==null||frame.length==0)throw new BizException(500,"邻镜视频抽帧失败，请检查 FFmpeg");
                try(var input=new ByteArrayInputStream(frame)){minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(input,frame.length,-1).contentType("image/jpeg").build());}
            }
            return properties.getExternalEndpoint().replaceAll("/$","")+"/"+bucket+"/"+key;
        }catch(BizException e){throw e;}catch(Exception e){throw new BizException(500,"邻镜视频抽帧失败: "+e.getMessage());}
    }
}
