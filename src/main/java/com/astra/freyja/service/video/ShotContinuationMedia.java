package com.astra.freyja.service.video;

import com.astra.freyja.config.MediaToolProperties;
import com.astra.freyja.service.*;
import com.astra.freyja.service.mediaexport.ShotVideoConcatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

@Service @RequiredArgsConstructor
public class ShotContinuationMedia {
    private final ReferenceVideoMediaService media;
    private final NativeProcessExecutor processes;
    private final MediaToolProperties tools;
    private final ShotVideoConcatService concat;
    private final ObjectMapper json;

    public ReferenceVideoMediaService.ContinuationArtifact tail(String url,int sourceFrames,int overlap)throws Exception{
        Path folder=Files.createTempDirectory("freyja-h3-context-");
        try{Path source=folder.resolve("source.mp4"),clip=folder.resolve("context.mp4");media.downloadPrepared(url,source);trim(source,clip,sourceFrames-overlap,sourceFrames);return media.archiveContinuation(clip);}
        finally{cleanup(folder);}
    }
    public String assemble(List<String> urls,com.astra.freyja.dto.drama.ShotContinuationPlan plan)throws Exception{
        Path folder=Files.createTempDirectory("freyja-h3-join-");
        try{
            List<Path> clips=new ArrayList<>();
            for(int i=0;i<urls.size();i++){
                var range=plan.getRanges().get(i);Path source=folder.resolve("source.mp4"),clip=folder.resolve("clip-"+i+".mp4");
                media.downloadPrepared(urls.get(i),source);trim(source,clip,range.getKeepStartFrame()-range.getStartFrame(),range.getKeepEndFrame()-range.getStartFrame());clips.add(clip);
            }
            Path output=folder.resolve("merged.mp4");concat.concat(clips,output,()->!Thread.currentThread().isInterrupted());
            checkFrames(output,plan.getOutputFrames());return media.archiveContinuation(output).url();
        }finally{cleanup(folder);}
    }
    private void trim(Path source,Path target,int start,int end)throws Exception{
        var format=concat.probe(source,()->!Thread.currentThread().isInterrupted());
        if(start<0||end<=start||format.duration().doubleValue()*24+1<end)throw new IllegalStateException("生成片段帧数不足，不能截取接续范围");
        var cmd=new ArrayList<>(List.of(tools.getFfmpegPath(),"-hide_banner","-loglevel","error","-nostdin","-y","-i",source.toString()));
        if(!format.hasAudio())cmd.addAll(List.of("-f","lavfi","-i","anullsrc=r=48000:cl=stereo"));
        String filter="[0:v]fps=24,trim=start_frame="+start+":end_frame="+end+",setpts=PTS-STARTPTS[v];["+(format.hasAudio()?"0:a":"1:a")+"]atrim=start="+(start/24.0)+":end="+(end/24.0)+",asetpts=PTS-STARTPTS,aresample=48000,apad[a]";
        cmd.addAll(List.of("-filter_complex",filter,"-map","[v]","-map","[a]","-c:v","libx264","-preset","fast","-crf","18","-pix_fmt","yuv420p","-c:a","aac","-ar","48000","-ac","2","-t",Double.toString((end-start)/24.0),"-movflags","+faststart",target.toString()));
        run(cmd);checkFrames(target,end-start);
    }
    private void checkFrames(Path file,int expected)throws Exception{
        String result=run(List.of(tools.getFfprobePath(),"-v","error","-count_frames","-select_streams","v:0","-show_entries","stream=nb_read_frames,r_frame_rate,width,height","-of","json",file.toString()));
        var stream=json.readTree(result).path("streams").path(0);
        if(stream.path("nb_read_frames").asInt()!=expected||!"24/1".equals(stream.path("r_frame_rate").asText()))throw new IllegalStateException("接续产物帧数或帧率与计划不一致");
    }
    private String run(List<String> command)throws Exception{
        var result=processes.execute(command,Duration.ofMinutes(15),()->{if(Thread.currentThread().isInterrupted())throw new java.io.IOException("接续处理已取消");});
        if(result.timedOut()||result.exitCode()!=0)throw new IllegalStateException("接续媒体处理失败："+result.output());return result.output();
    }
    private void cleanup(Path folder)throws Exception{try(var files=Files.walk(folder)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
}
