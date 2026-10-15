package com.astra.freyja.service.video;

/** Physical request phases, never inferred from exception text. One context per consumer invocation. */
public final class VideoExecutionState {
    private VideoExecutionState() {}
    public static final class State {
        public volatile String phase="UNKNOWN";
        public volatile String recoveryUrl;
        public boolean uncertain(){return "UNKNOWN".equals(phase)||"SENT".equals(phase);}
    }
    private static final ThreadLocal<State> CURRENT=new ThreadLocal<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,State> ACTIVE=new java.util.concurrent.ConcurrentHashMap<>();
    public static void begin(String task){State state=new State();CURRENT.set(state);ACTIVE.put(task,state);}
    public static State task(String task){return ACTIVE.get(task);}
    public static State current(){return CURRENT.get();}
    public static void phase(String phase){if(CURRENT.get()!=null)CURRENT.get().phase=phase;}
    public static void completed(String url){if(CURRENT.get()!=null){CURRENT.get().phase="ARCHIVE";CURRENT.get().recoveryUrl=url;}}
    public static void clear(String task){CURRENT.remove();ACTIVE.remove(task);}
}
