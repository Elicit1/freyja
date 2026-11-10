package com.astra.freyja.service.video;

/** Physical request phases, never inferred from exception text. One context per consumer invocation. */
public final class VideoExecutionState {
    private VideoExecutionState() {}
    public static final class State {
        public volatile String phase="UNKNOWN";
        public volatile String recoveryUrl;
        public volatile boolean continuationChild;
        public java.util.function.BiConsumer<String,String> upstream;
        private java.util.function.BiConsumer<String,String> progress;
        public int percentage;
        private int segment, segments=1, stageIndex, stages=1;
        private boolean upscale;
        private String stage="BASE", lastMessage;
        private long started=System.nanoTime(), lastPublished;
        public boolean uncertain(){return "UNKNOWN".equals(phase)||"SENT".equals(phase);}
    }
    private static final ThreadLocal<State> CURRENT=new ThreadLocal<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,State> ACTIVE=new java.util.concurrent.ConcurrentHashMap<>();
    public static void begin(String task){State state=new State();CURRENT.set(state);ACTIVE.put(task,state);}
    public static void begin(String task,java.util.function.BiConsumer<String,String> progress){begin(task);CURRENT.get().progress=progress;}
    public static void progress(String stage,String message){
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("视频任务已取消");
        State state=CURRENT.get();if(state==null)return;
        if("UPSTREAM_QUEUED".equals(stage))state.started=System.nanoTime();
        if("ASSEMBLING".equals(stage))state.percentage=Math.max(state.percentage,96);
        else if("COMMITTING".equals(stage))state.percentage=Math.max(state.percentage,99);
        else if("ARCHIVING".equals(stage)||"DOWNLOADING".equals(stage))fraction(state,.94);
        else if("PREPARING".equals(stage))state.percentage=Math.max(state.percentage,1);
        String prefix=state.segments>1&&state.percentage<96?"片段 "+(state.segment+1)+"/"+state.segments+" · ":"";
        String text=prefix+message;
        long now=System.nanoTime();
        if(text.equals(state.lastMessage)&&now-state.lastPublished<java.time.Duration.ofSeconds(5).toNanos())return;
        state.lastMessage=text;state.lastPublished=now;
        if(state.progress!=null)state.progress.accept(stage,text);
    }
    public static void configure(VideoBatchModels.Snapshot snapshot){
        State s=CURRENT.get();if(s==null)return;
        var request=snapshot.getRequest();s.segments=request.getContinuationSegments()==null?1:Math.max(1,request.getContinuationSegments().size());
        var plan=request.getDynamicWorkflowPlan();s.upscale=plan!=null&&plan.getOptions().isUpscale();
        s.stages=1+(s.upscale?1:0)+(plan!=null&&plan.getOptions().isFaceRepair()?1:0);
    }
    public static void segment(int index){State s=CURRENT.get();if(s!=null){s.segment=index;s.stage="";stage("BASE");}}
    public static void stage(String stage){
        State s=CURRENT.get();if(s==null||stage.equals(s.stage))return;
        s.stage=stage;s.stageIndex="FACE".equals(stage)?(s.upscale?2:1):"UPSCALE".equals(stage)?1:0;s.started=System.nanoTime();
    }
    private static void fraction(State s,double fraction){
        int value=(int)(95*(s.segment+(s.stageIndex+fraction)/s.stages)/s.segments);
        s.percentage=Math.max(s.percentage,Math.min(98,value));
    }
    public static void sampling(com.fasterxml.jackson.databind.JsonNode sample){
        State s=CURRENT.get();if(s==null)return;
        String label=switch(s.stage){case "UPSCALE"->"二采放大";case "FACE"->"人脸修复";default->"基础生成";};
        boolean fresh=sample!=null&&sample.isObject()&&sample.path("updatedAt").asDouble(0)>System.currentTimeMillis()/1000.0-15;
        if(fresh&&sample.path("max").asDouble(0)>0){
            double max=sample.path("max").asDouble(),value=Math.clamp(sample.path("value").asDouble(0),0,max);
            fraction(s,.1+.75*value/max);
            progress(s.stage,label+" · "+sample.path("nodeType").asText("ComfyUI")+" · 当前节点 "+(int)value+"/"+(int)max+" · 总体估算 "+s.percentage+"%");
        }else{
            double elapsed=(System.nanoTime()-s.started)/1_000_000_000.0;
            fraction(s,.05+.75*(1-Math.exp(-elapsed/180.0)));
            String node=fresh&&!sample.path("nodeType").asText().isBlank()?" · "+sample.path("nodeType").asText():"";
            progress(s.stage,label+node+" · 总体估算 "+s.percentage+"%（等待实际完成）");
        }
    }
    public static State task(String task){return ACTIVE.get(task);}
    public static State current(){return CURRENT.get();}
    public static void phase(String phase){if(CURRENT.get()!=null)CURRENT.get().phase=phase;}
    public static void accepted(String prompt,String node){if(CURRENT.get()!=null&&CURRENT.get().upstream!=null)CURRENT.get().upstream.accept(prompt,node);}
    public static void completed(String url){if(CURRENT.get()!=null){CURRENT.get().phase="ARCHIVE";if(!CURRENT.get().continuationChild)CURRENT.get().recoveryUrl=url;}}
    public static void clear(String task){CURRENT.remove();ACTIVE.remove(task);}
}
