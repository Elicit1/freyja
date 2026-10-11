package com.astra.freyja.controller;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.runtime.AgentExecutionScheduler;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.agent.settings.AgentConfigService;
import com.astra.freyja.common.R;
import com.astra.freyja.dto.agent.AgentRequests.*;
import com.astra.freyja.entity.AgentRun;
import com.astra.freyja.entity.AgentSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentStore store;
    private final AgentConfigService configs;
    private final AgentExecutionScheduler scheduler;
    private final AgentJson json;
    public record RunView(Long id, Long sessionId, String agentCode, String runType, String status, String objective, int modelTurns,
                          int toolCalls, Long executionMillis, Integer promptTokens, Integer completionTokens,
                          Boolean usageUnknown, Object result, String errorMessage, int configRevision,
                          LocalDateTime createTime, LocalDateTime finishedAt, LocalDateTime readAt) {}
    public RunView view(AgentRun run) {
        return new RunView(run.getId(), run.getSessionId(), store.session(run.getSessionId()).getAgentCode(), run.getRunType(), run.getStatus(), run.getObjective(),
                run.getModelTurns(), run.getToolCalls(), run.getExecutionMillis(), run.getPromptTokens(), run.getCompletionTokens(),
                run.getUsageUnknown(), run.getFinalResultJson() == null ? null : json.read(run.getFinalResultJson(), Map.class),
                run.getErrorMessage(), run.getConfigRevision(), run.getCreateTime(), run.getFinishedAt(), run.getReadAt());
    }
    @GetMapping("/profiles") public R<List<AgentConfigService.Display>> profiles() { return R.ok(configs.enabled()); }
    @PostMapping("/sessions") public R<AgentSession> create(@RequestBody CreateSession request) { return R.ok(store.createSession(request, "NORMAL")); }
    @GetMapping("/sessions") public R<List<AgentSession>> sessions() { return R.ok(store.listSessions()); }
    @GetMapping("/notifications") public R<List<RunView>> notifications() { return R.ok(store.notifications().stream().map(this::view).toList()); }
    @GetMapping("/sessions/{id}") public R<AgentSession> session(@PathVariable Long id) { return R.ok(store.session(id)); }
    @GetMapping("/sessions/{id}/messages") public R<List<AgentStore.MessageView>> messages(@PathVariable Long id, @RequestParam(defaultValue = "0") int afterSeq) {
        return R.ok(store.after(id, Math.max(afterSeq, 0)));
    }
    @PostMapping("/sessions/{id}/messages") public R<Submission> send(@PathVariable Long id, @RequestBody SendMessage request) {
        return R.ok(store.submit(id, request, null));
    }
    @GetMapping("/runs/{id}") public R<RunView> run(@PathVariable Long id) { return R.ok(view(store.run(id))); }
    @GetMapping("/runs/{id}/report") public R<Object> report(@PathVariable Long id) {
        var run = store.run(id); return R.ok(run.getFinalResultJson() == null ? null : json.read(run.getFinalResultJson(), Map.class));
    }
    @PostMapping("/runs/{id}/cancel") public R<Void> cancel(@PathVariable Long id) {
        store.cancel(id); scheduler.interrupt(id); return R.ok();
    }
    @PostMapping("/runs/{id}/resume") public R<Void> resume(@PathVariable Long id) { store.resume(id); return R.ok(); }
    @PostMapping("/runs/{id}/read") public R<Void> read(@PathVariable Long id) { store.run(id); store.markRead(id); return R.ok(); }
}
