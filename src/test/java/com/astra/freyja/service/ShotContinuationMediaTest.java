package com.astra.freyja.service;

import com.astra.freyja.config.*;
import com.astra.freyja.service.video.*;
import com.astra.freyja.service.mediaexport.ShotVideoConcatService;
import com.astra.freyja.dto.drama.ShotContinuation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual FFmpeg/FFprobe checks cover frame accounting and silent/audio inputs. */
class ShotContinuationMediaTest {
    @TempDir Path folder;
    final NativeProcessExecutor processes=new NativeProcessExecutor();
    final MediaToolProperties tools=new MediaToolProperties();
    final ObjectMapper json=new ObjectMapper();
    @Test void joinsSixtySecondsWithExactFramesAndAudioAndExtractsRealContext()throws Exception{
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(60);
        var plan=new ShotContinuationPlanner().plan(draft,true);
        var sources=new HashMap<String,Path>();var urls=new ArrayList<String>();
        Path master=folder.resolve("master.mp4");run(List.of("ffmpeg","-hide_banner","-loglevel","error","-y","-f","lavfi","-i","testsrc2=size=64x64:rate=24","-f","lavfi","-i","sine=frequency=440:sample_rate=48000","-t",Double.toString(plan.getRenderFrames()/24.0),"-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac",master.toString()));
        for(int i=0;i<plan.getRanges().size();i++){
            var range=plan.getRanges().get(i);Path clip=folder.resolve("source-"+i+".mp4");
            run(List.of("ffmpeg","-hide_banner","-loglevel","error","-y","-i",master.toString(),"-ss",Double.toString(range.getStartFrame()/24.0),"-t",Double.toString((range.getEndFrame()-range.getStartFrame())/24.0),"-c:v","libx264","-c:a","aac",clip.toString()));
            String url="https://archive.test/"+i;sources.put(url,clip);urls.add(url);
        }
        var archive=mock(ReferenceVideoMediaService.class);
        doAnswer(call->{Files.copy(sources.get(call.getArgument(0)),call.getArgument(1),StandardCopyOption.REPLACE_EXISTING);return null;}).when(archive).downloadPrepared(anyString(),any(Path.class));
        final int[] number={0};when(archive.archiveContinuation(any(Path.class))).thenAnswer(call->{Path saved=folder.resolve("artifact-"+(number[0]++)+".mp4");Files.copy(call.<Path>getArgument(0),saved);return new ReferenceVideoMediaService.ContinuationArtifact("reference-video/test",saved.toString(),"a".repeat(64));});
        var concat=new ShotVideoConcatService(tools,new VideoExportProperties(),processes,json);
        var service=new ShotContinuationMedia(archive,processes,tools,concat,json);
        String result=service.assemble(urls,plan);assertEquals(1440,frames(Path.of(result)));
        run(List.of("ffmpeg","-hide_banner","-loglevel","error","-i",result,"-f","null","-"));
        var context=service.tail(urls.getFirst(),362,39);assertEquals(39,frames(Path.of(context.url())));
    }
    private int frames(Path path)throws Exception{return json.readTree(run(List.of("ffprobe","-v","error","-count_frames","-select_streams","v:0","-show_entries","stream=nb_read_frames","-of","json",path.toString()))).path("streams").path(0).path("nb_read_frames").asInt();}
    private String run(List<String> command)throws Exception{var result=processes.execute(command,Duration.ofMinutes(2));assertEquals(0,result.exitCode(),result.output());assertFalse(result.timedOut());return result.output();}
}
