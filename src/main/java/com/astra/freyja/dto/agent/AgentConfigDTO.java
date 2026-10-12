package com.astra.freyja.dto.agent;

import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentConfigDTO {
    private String agentCode;
    private String displayName = "FREYJA 助手";
    private String description = "读取项目、查询资产与分镜，保存明确的会话规则";
    private boolean enabled;
    private Integer expectedRevision;
    private Integer revision;
    private Model model = new Model();
    private Instructions instructions = new Instructions();
    private Tools tools = new Tools();
    private Skills skills = new Skills();
    private Memory memory = new Memory();
    private Runtime runtime = new Runtime();
    private Avatar avatar = new Avatar();

    @Data public static class Model {
        private Long providerId;
        private String modelCode;
        private Double temperature;
        private Double topP;
        private Integer maxTokens = 4096;
        private String tokenMode = "CUSTOM";
        private String reasoningMode = "INHERIT";
        // Missing in legacy configurations and snapshots: preserve complete-response behavior.
        private String outputMode = "COMPLETE";
    }
    @Data public static class Instructions {
        private String systemPrompt = "你是 FREYJA 创作助手。根据用户目标读取项目事实，使用工具完成工作。需要补充信息时明确说明，使用中文回答。";
    }
    @Data public static class Tools {
        private List<String> names = new ArrayList<>(List.of("get_project_overview", "list_project_assets",
                "get_shot_detail", "save_session_note", "list_session_notes", "load_skill", "read_skill_file"));
    }
    @Data public static class Skills {
        private List<String> names = new ArrayList<>();
        private boolean dynamicLoading = true;
    }
    @Data public static class Memory {
        private boolean summaryEnabled = true;
        private int recentMessages = 20;
        private int contextTokens = 16000;
        private List<String> scopes = new ArrayList<>(List.of("SESSION", "PROJECT"));
        private Summary summary = new Summary();
    }
    @Data public static class Summary {
        private Long providerId;
        private String modelCode;
        private int maxTokens = 16384;
        private int retryMaxTokens = 32768;
        private String reasoningMode = "DISABLED";
        private int timeoutSeconds = 180;
        private int targetCharacters = 2500;
        private int maxCharacters = 4000;
        private int maxRetries = 2;
    }
    @Data public static class Runtime {
        private int maxModelTurns = 20; // 0 means unlimited; other execution budgets still apply.
        private int maxToolCalls = 50;
        private int executionTimeoutSeconds = 900;
        private int modelTimeoutSeconds = 120;
        private int externalWaitTimeoutSeconds = 1800;
        private int maxRetries = 2;
        private int maxConcurrentRuns = 2;
    }
    @Data public static class Avatar {
        private boolean visible = true;
        private Pet pet = new Pet();
        // Retained only for reading existing configuration and run snapshots.
        private Map<String, StateMedia> stateMedia = new LinkedHashMap<>();
        private Notifications notifications = new Notifications();
    }
    @Data public static class Pet {
        private String id = "freyja";
        private String displayName = "FREYJA";
        private String description = "银白发、紫金花饰的 Q 版创作助手";
        private String spritesheetUrl = "/agent/freyja/spritesheet.webp";
        private String contentType = "image/webp";
        private String objectPath;
        private int spriteVersionNumber = 1;
        private int frameWidth = 192;
        private int frameHeight = 208;
        private int columns = 8;
        private int rows = 9;
    }
    @Data public static class StateMedia {
        private String url;
        private String contentType;
        private String objectPath;
    }
    @Data public static class Notifications {
        private boolean completed = true;
        private boolean failed = true;
        private boolean needsAttention = true;
    }
}
