package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.service.ShotVideoCapabilityService;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** Durable, content-addressed native H3 handoffs. No Java tensor deserialization. */
@Service @RequiredArgsConstructor
public class ContinuationArtifactService {
    private final AiProviderMapper providers;
    private final CryptoUtil crypto;
    private final MinioClient minio;
    private final MinioProperties storage;
    private final ObjectMapper json;
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final long LIMIT=2L*1024*1024*1024;

    public Map<String,Object> archive(Long providerId,String task,String prompt,String node,Long drama,Long shot)throws Exception{
        return archive(providerId,task,prompt,node,drama,shot,"FIRST_PASS");
    }
    public Map<String,Object> archive(Long providerId,String task,String prompt,String node,Long drama,Long shot,String stage)throws Exception{
        if(!Set.of("FIRST_PASS","SECOND_PASS").contains(stage))throw new BizException(400,"latent 阶段无效");
        if(prompt==null||node==null)throw new BizException(502,"原始 latent 缺少上游执行身份，不能恢复归档");
        var provider=providers.selectById(providerId);
        if(provider==null)throw new BizException(404,"视频提供商不存在");
        String base=ShotVideoCapabilityService.apiRoot(provider.getBaseUrl());
        String path=base+"/tasks/"+encode(task)+"/latent?prompt_id="+encode(prompt)+"&node_id="+encode(node);
        var request=HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofMinutes(15)).header("Authorization","Bearer "+Objects.toString(crypto.decrypt(provider.getApiKey()),"")).GET().build();
        Path temp=Files.createTempFile("freyja-h3-latent-",".safetensors");
        try{
            var response=HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=response.body();var output=new DigestOutputStream(Files.newOutputStream(temp),digest)){
                if(response.statusCode()!=200)throw new BizException(502,"上游 latent 归档失败 HTTP "+response.statusCode()+"，已完成视频可恢复，无需重新渲染");
                if(response.headers().firstValueAsLong("Content-Length").orElse(0)>LIMIT)throw new BizException(413,"latent 超过 2 GiB 归档预算");
                byte[] buffer=new byte[65536];long bytes=0;int n;
                while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("latent 归档已取消");bytes+=n;if(bytes>LIMIT)throw new BizException(413,"latent 超过 2 GiB 归档预算");output.write(buffer,0,n);}
            }
            if(Files.size(temp)<16)throw new BizException(502,"上游 latent 文件为空");
            String sha=HexFormat.of().formatHex(digest.digest());
            String key="projects/"+drama+"/shots/"+shot+"/continuation/latent/"+sha+".safetensors";
            minio.uploadObject(UploadObjectArgs.builder().bucket(storage.getBucketName()).object(key).filename(temp.toString()).contentType("application/octet-stream").build());
            return Map.of("objectPath",key,"sha256",sha,"bytes",Files.size(temp),"format","FREYJA_H3_AV_V1","stage",stage);
        }finally{Files.deleteIfExists(temp);}
    }
    public Map<String,Object> transport(Map<String,Object> artifact)throws Exception{
        if(artifact==null)throw new BizException(409,"前序片段缺少原始 latent，请重生成该前驱段或改用画面引导");
        String key=Objects.toString(artifact.get("objectPath"),"");
        if(!key.startsWith("projects/")||!key.endsWith(".safetensors")||key.contains(".."))throw new BizException(400,"接续 latent 归档引用无效");
        String url=minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(storage.getBucketName()).object(key).expiry(3600).build());
        return Map.of("url",url,"content_sha256",artifact.get("sha256"));
    }
    public Map<String,Object> read(String text)throws Exception{
        if(text==null||text.isBlank())throw new BizException(409,"历史片段缺少原始 latent，请重生成该前驱段或改用标准/彩噪引导");
        return json.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
}
