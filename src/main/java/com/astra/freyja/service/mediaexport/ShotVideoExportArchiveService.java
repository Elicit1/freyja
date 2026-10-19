package com.astra.freyja.service.mediaexport;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.config.VideoExportProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.LifecycleRule;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.Expiration;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;

@Service
@RequiredArgsConstructor
public class ShotVideoExportArchiveService {
    private final VideoAssetSourceResolver sources;
    private final VideoExportProperties properties;
    private final MinioProperties minioProperties;
    private final MinioClient minio;
    private final ObjectMapper json;
    private final ShotVideoConcatService concat;
    private String initializedBucket;
    private final java.util.concurrent.ConcurrentMap<Long,InputStream> activeReads=new java.util.concurrent.ConcurrentHashMap<>();
    public void interruptRead(Long id){InputStream input=activeReads.remove(id);if(input!=null)try{input.close();}catch(IOException ignored){}}
    public record ReadResult(long size,long crc,String sha256) {}
    public record Artifact(Path directory,Path file) {}
    public static class ArchiveFailure extends IOException { public ArchiveFailure(String message){super(message);} }

    public Artifact build(Task task,List<Item> items,BooleanSupplier active,BiConsumer<Long,String> progress,Consumer<Item> itemFinished) throws Exception {
        if(task.isConcatVideos())return buildConcatenated(task,items,active,progress,itemFinished);
        Path root=Path.of(properties.getTempDirectory()).toAbsolutePath().normalize();Files.createDirectories(root);
        Path directory=Files.createTempDirectory(root,task.getId()+"-");Path zip=directory.resolve("package.zip");
        var bytes=new java.util.concurrent.atomic.AtomicLong();int success=0;
        try {
            checkDisk(directory);
            try(var output=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)),StandardCharsets.UTF_8)){
                output.setLevel(0);
                for(Item item:items){
                    check(active);if("SKIPPED".equals(item.getStatus()))continue;
                    Path source=directory.resolve("source.tmp");ReadResult result;
                    try{
                        long already=bytes.get();
                        result=download(task.getId(),item.getSnapshot(),source,active,read->{bytes.set(already+read);progress.accept(bytes.get(),item.getName());});
                    }catch(java.util.concurrent.CancellationException e){throw e;}
                    catch(ArchiveFailure e){throw e;}
                    catch(Exception e){item.setStatus("FAILED");item.setErrorMessage("源视频读取失败或对象已变化");itemFinished.accept(item);Files.deleteIfExists(source);continue;}
                    check(active);
                    // ZIP entry is opened only after the complete source has passed size/ETag checks.
                    if(Files.size(zip)+result.size()+65536>properties.getMaxArchiveBytes())throw new ArchiveFailure("超过整包容量限制");
                    addStored(output,item.getZipEntryPath(),source,result,active);
                    Files.delete(source);item.setStatus("SUCCESS");item.setSize(result.size());item.setChecksum(result.sha256());item.setErrorMessage(null);itemFinished.accept(item);success++;
                }
                if(success==0)throw new ArchiveFailure("没有成功导出的视频，未生成空包");
                String folder=items.getFirst().getSnapshot().getRoot()+"/";
                // Item has no source URL, credentials, internal object key or signature fields.
                var manifestJson=json.copy().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule().addSerializer(Long.class,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));
                addText(output,folder+"manifest.json",manifestJson.writeValueAsString(items));
                addText(output,folder+"导出清单.csv",csv(items));
                var failed=items.stream().filter(i->!"SUCCESS".equals(i.getStatus())).toList();
                if(!failed.isEmpty())addText(output,folder+"失败清单.csv",csv(failed));
            }
            check(active);if(Files.size(zip)>properties.getMaxArchiveBytes())throw new ArchiveFailure("超过整包容量限制");
            return new Artifact(directory,zip);
        }catch(Exception e){cleanup(directory);throw e;}
    }
    private Artifact buildConcatenated(Task task,List<Item> items,BooleanSupplier active,BiConsumer<Long,String> progress,Consumer<Item> itemFinished) throws Exception {
        Path root=Path.of(properties.getTempDirectory()).toAbsolutePath().normalize();Files.createDirectories(root);
        Path directory=Files.createTempDirectory(root,task.getId()+"-");Path merged=directory.resolve("merged.mp4");
        var bytes=new java.util.concurrent.atomic.AtomicLong();List<Path> clips=new ArrayList<>();
        ShotVideoConcatService.Format target=null;long clipBytes=0;
        try{
            checkDisk(directory);
            for(Item item:items){
                check(active);if("SKIPPED".equals(item.getStatus()))continue;
                Path source=directory.resolve("source.tmp");ReadResult result;
                try{
                    long already=bytes.get();
                    result=download(task.getId(),item.getSnapshot(),source,active,read->{
                        bytes.set(already+read);progress.accept(bytes.get(),item.getName());
                    });
                }catch(java.util.concurrent.CancellationException e){throw e;}
                catch(ArchiveFailure e){throw e;}
                catch(Exception e){item.setStatus("FAILED");item.setErrorMessage("源视频读取失败或对象已变化");itemFinished.accept(item);Files.deleteIfExists(source);continue;}
                if(bytes.get()>properties.getMaxArchiveBytes())throw new ArchiveFailure("超过导出容量限制");
                progress.accept(bytes.get(),"正在统一视频格式 · "+item.getName());
                Path clip=directory.resolve("clip-"+clips.size()+".mp4");
                try{
                    var input=concat.probe(source,active);if(target==null)target=input;
                    concat.normalize(source,clip,input,target,properties.getMaxArchiveBytes()-clipBytes,active);
                    checkDisk(directory);clipBytes+=Files.size(clip);
                    if(clipBytes>properties.getMaxArchiveBytes())throw new ArchiveFailure("超过拼接视频容量限制");
                }catch(java.util.concurrent.CancellationException e){throw e;}
                catch(Exception e){item.setStatus("FAILED");item.setErrorMessage("源视频无法转换为拼接格式");itemFinished.accept(item);throw e;}
                Files.delete(source);clips.add(clip);
                item.setStatus("SUCCESS");item.setSize(result.size());item.setChecksum(result.sha256());item.setErrorMessage(null);itemFinished.accept(item);
            }
            if(clips.isEmpty())throw new ArchiveFailure("没有成功导出的视频，未生成拼接文件");
            progress.accept(bytes.get(),"正在拼接 "+clips.size()+" 个视频");
            concat.concat(clips,merged,active);check(active);checkDisk(directory);
            if(Files.size(merged)>properties.getMaxArchiveBytes())throw new ArchiveFailure("超过拼接视频容量限制");
            return new Artifact(directory,merged);
        }catch(Exception e){cleanup(directory);throw e;}
    }
    private ReadResult download(Long taskId,Snapshot snapshot,Path file,BooleanSupplier active,LongConsumer progress) throws Exception {
        CRC32 crc=new CRC32();MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(properties.getReadTimeoutSeconds());
        try(InputStream input=sources.open(snapshot);OutputStream output=new BufferedOutputStream(Files.newOutputStream(file))){
            activeReads.put(taskId,input);
            check(active);
            byte[] buffer=new byte[128*1024];int read;
            while((read=input.read(buffer))!=-1){
                check(active);if(System.nanoTime()>deadline)throw new IOException("视频读取超时");size+=read;
                if(size>properties.getMaxFileBytes())throw new IOException("视频超过单文件限制");
                checkDisk(file.getParent());output.write(buffer,0,read);crc.update(buffer,0,read);sha.update(buffer,0,read);progress.accept(size);
            }
        }finally{activeReads.remove(taskId);}
        if(size==0||snapshot.getSourceSize()!=null&&size!=snapshot.getSourceSize())throw new IOException("视频大小与原快照不一致");
        return new ReadResult(size,crc.getValue(),HexFormat.of().formatHex(sha.digest()));
    }
    private void addStored(ZipOutputStream output,String path,Path file,ReadResult result,BooleanSupplier active)throws Exception{
        if(path.startsWith("/")||path.contains("\\")||Arrays.asList(path.split("/")).contains(".."))throw new ArchiveFailure("非法导出路径");
        ZipEntry entry=new ZipEntry(path);entry.setMethod(ZipEntry.STORED);entry.setSize(result.size());entry.setCrc(result.crc());output.putNextEntry(entry);
        try(InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[128*1024];int read;while((read=input.read(buffer))!=-1){check(active);checkDisk(file.getParent());output.write(buffer,0,read);}}
        output.closeEntry();
    }
    private void addText(ZipOutputStream zip,String name,String text)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    public static String csv(List<Item> items){
        StringBuilder out=new StringBuilder("\uFEFF顺序,分集,场景,镜头组,分镜ID,分镜,TakeID,版本,路径,大小,SHA256,状态,说明\r\n");
        for(Item i:items){Object[] values={i.getExecutionOrder(),i.getEpisodeName(),i.getSceneName(),i.getGroupName(),i.getShotId(),i.getName(),i.getTakeId(),i.getTakeNo(),i.getZipEntryPath(),i.getSize(),i.getChecksum(),i.getStatus(),i.getErrorMessage()};for(int n=0;n<values.length;n++){if(n>0)out.append(',');String value=Objects.toString(values[n],"");String trimmed=value.stripLeading();if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0)value="'"+value;out.append('"').append(value.replace("\"","\"\"")).append('"');}out.append("\r\n");}return out.toString();
    }
    private void checkDisk(Path path)throws IOException{if(Files.getFileStore(path).getUsableSpace()<properties.getMinFreeBytes())throw new ArchiveFailure("磁盘可用空间不足");}
    private static void check(BooleanSupplier active){if(Thread.currentThread().isInterrupted()||!active.getAsBoolean())throw new java.util.concurrent.CancellationException("导出任务已取消或执行权已变更");}

    public void upload(String key,Path path)throws Exception{
        ensureBucket();minio.uploadObject(UploadObjectArgs.builder().bucket(properties.getBucket()).object(key).filename(path.toString()).contentType(key.endsWith(".mp4")?"video/mp4":"application/zip").build());
        if(minio.statObject(StatObjectArgs.builder().bucket(properties.getBucket()).object(key).build()).size()!=Files.size(path))throw new IOException("导出包上传校验失败");
    }
    public void remove(String key){if(key==null)return;try{minio.removeObject(RemoveObjectArgs.builder().bucket(properties.getBucket()).object(key).build());}catch(Exception ignored){/* Lifecycle also expires abandoned attempts. */}}
    public DownloadLink link(Task task){
        long seconds=Math.min(600,Duration.between(LocalDateTime.now(),task.getExpiresAt()).toSeconds());if(seconds<1)throw new BizException(410,"导出包已过期，请重新创建导出任务");
        try{
            minio.statObject(StatObjectArgs.builder().bucket(properties.getBucket()).object(task.getObjectKey()).build());
            try(var external=MinioClient.builder().endpoint(minioProperties.getExternalEndpoint()).credentials(minioProperties.getAccessKey(),minioProperties.getSecretKey()).region("us-east-1").build()){
                String encoded=java.net.URLEncoder.encode(task.getFilename(),StandardCharsets.UTF_8).replace("+","%20");
                String url=external.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket(properties.getBucket()).object(task.getObjectKey()).expiry((int)seconds).extraQueryParams(Map.of("response-content-disposition","attachment; filename="+(task.isConcatVideos()?"videos.mp4":"videos.zip")+"; filename*=UTF-8''"+encoded)).build());
                return new DownloadLink(url,task.getFilename(),LocalDateTime.now().plusSeconds(seconds));
            }
        }catch(BizException e){throw e;}catch(Exception e){throw new BizException(410,"导出包不可用或已被清理，请重新创建任务");}
    }
    private synchronized void ensureBucket()throws Exception{
        String bucket=properties.getBucket();if(bucket.equals(minioProperties.getBucketName())||bucket.equals(minioProperties.getMcpUploadBucket()))throw new BizException(500,"请为视频导出配置独立私有存储桶");
        if(bucket.equals(initializedBucket))return;
        if(!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        minio.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket(bucket).build());
        int days=Math.max(1,(int)Math.ceil(properties.getRetentionHours()/24.0));
        var rule=new LifecycleRule(Status.ENABLED,null,new Expiration((ZonedDateTime)null,days,null),new RuleFilter("shot-video-exports/"),"expire-shot-video-exports",null,null,null);
        minio.setBucketLifecycle(SetBucketLifecycleArgs.builder().bucket(bucket).config(new LifecycleConfiguration(List.of(rule))).build());initializedBucket=bucket;
    }
    public void cleanup(Path directory){
        if(directory==null)return;Path root=Path.of(properties.getTempDirectory()).toAbsolutePath().normalize();Path target=directory.toAbsolutePath().normalize();
        if(!target.startsWith(root)||target.equals(root))return;
        try(var walk=Files.walk(target)){for(Path file:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}catch(IOException ignored){}
    }
    public void cleanupStale(){
        Path root=Path.of(properties.getTempDirectory()).toAbsolutePath().normalize();if(!Files.isDirectory(root))return;
        try(var paths=Files.list(root)){for(Path path:paths.filter(Files::isDirectory).filter(p->p.getFileName().toString().matches("[0-9]{1,19}-[a-zA-Z0-9_-]+")).toList())cleanup(path);}catch(IOException ignored){}
    }
}
