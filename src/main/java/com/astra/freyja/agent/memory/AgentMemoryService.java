package com.astra.freyja.agent.memory;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AgentMemoryMapper;
import com.astra.freyja.dao.AgentMessageMapper;
import com.astra.freyja.entity.AgentMemory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentMemoryService {
    public record SaveNote(String scope, String key, String content, Long sourceMessageId, boolean enabled) {}
    private final AgentMemoryMapper memories;
    private final AgentMessageMapper messages;

    public List<AgentMemory> list(ToolContext context) {
        if ("CONFIG_TEST".equals(context.snapshot().runType())) return List.of();
        var query = new LambdaQueryWrapper<AgentMemory>().eq(AgentMemory::getEnabled, true);
        List<String> scopes = context.snapshot().config().getMemory().getScopes();
        if (scopes.isEmpty()) return List.of();
        query.and(q -> {
            if (scopes.contains("SESSION")) q.or().eq(AgentMemory::getScopeType, "SESSION").eq(AgentMemory::getScopeId, context.sessionId());
            if (scopes.contains("PROJECT") && context.dramaId() != null) q.or().eq(AgentMemory::getScopeType, "PROJECT").eq(AgentMemory::getScopeId, context.dramaId());
        });
        if (!scopes.contains("SESSION") && context.dramaId() == null) return List.of();
        return memories.selectList(query.orderByDesc(AgentMemory::getUpdateTime).last("LIMIT 100"));
    }
    /** Only invoked by a TRANSACTIONAL tool inside the durable checkpoint transaction. */
    public ToolResult save(SaveNote note, ToolContext context) {
        if ("CONFIG_TEST".equals(context.snapshot().runType())) return ToolResult.error("READ_ONLY_TEST", "测试对话不能保存记忆");
        if (note == null || note.scope() == null || !List.of("SESSION", "PROJECT").contains(note.scope())
                || note.key() == null || note.key().isBlank() || note.key().length() > 100
                || note.content() == null || note.content().length() > 6000 || (note.enabled() && note.content().isBlank()))
            return ToolResult.error("INVALID_ARGUMENT", "需要作用域、记忆键、内容和明确用户消息来源");
        if (!context.snapshot().config().getMemory().getScopes().contains(note.scope()))
            return ToolResult.error("SCOPE_DISABLED", "本次任务未启用此记忆作用域");
        var source = note.sourceMessageId() == null ? null : messages.selectById(note.sourceMessageId());
        if (source == null || !"USER".equals(source.getMessageType()) || !context.sessionId().equals(source.getSessionId())
                || !context.runId().equals(source.getRunId())) return ToolResult.error("INVALID_SOURCE", "来源必须是本次任务的真实用户消息");
        Long scopeId = "SESSION".equals(note.scope()) ? context.sessionId() : context.dramaId();
        if (scopeId == null) return ToolResult.error("NO_PROJECT_SCOPE", "此会话未绑定项目");
        AgentMemory memory = memories.selectOne(new LambdaQueryWrapper<AgentMemory>().eq(AgentMemory::getScopeType, note.scope())
                .eq(AgentMemory::getScopeId, scopeId).eq(AgentMemory::getMemoryKey, note.key()).last("FOR UPDATE"));
        if (memory == null) {
            memory = new AgentMemory(); memory.setScopeType(note.scope()); memory.setScopeId(scopeId); memory.setMemoryKey(note.key());
            memory.setContent(note.content()); memory.setSourceMessageId(note.sourceMessageId()); memory.setRevision(1); memory.setEnabled(note.enabled());
            memories.insert(memory);
        } else {
            if (memory.getRevision() == Integer.MAX_VALUE) throw new BizException("记忆版本达到上限");
            memory.setContent(note.content()); memory.setSourceMessageId(note.sourceMessageId()); memory.setEnabled(note.enabled());
            memory.setRevision(memory.getRevision() + 1);
            memories.update(null, new LambdaUpdateWrapper<AgentMemory>().eq(AgentMemory::getId, memory.getId())
                    .set(AgentMemory::getContent, memory.getContent()).set(AgentMemory::getSourceMessageId, memory.getSourceMessageId())
                    .set(AgentMemory::getEnabled, memory.getEnabled()).set(AgentMemory::getRevision, memory.getRevision()));
        }
        return ToolResult.success(memory);
    }
}
