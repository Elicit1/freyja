package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.McpBusinessOperationMapper;
import com.astra.freyja.entity.McpBusinessOperation;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Journal and database writes commit atomically; uploads use stable object paths. Concurrent retries wait on the unique request key. */
@Service
@RequiredArgsConstructor
public class McpOperationStore {
    public record Outcome(Object data, Long dramaId, boolean createdProject) {}
    private final McpBusinessOperationMapper operations;
    private final McpJson json;
    private final McpScope scope;

    @Transactional(rollbackFor = Exception.class)
    public Object execute(String key, String tool, Object input, Supplier<Outcome> action) {
        key(key);
        String hash = hash(tool + "\n" + json.write(canonical(json.value(input))));
        McpBusinessOperation proposed = new McpBusinessOperation(); proposed.setId(IdWorker.getId());
        proposed.setRequestId(key); proposed.setToolName(tool); proposed.setRequestHash(hash);
        operations.reserve(proposed);
        McpBusinessOperation stored = operations.lock(key);
        if (stored == null) throw new BizException(409, "业务请求记录暂不可用，请使用同一 requestId 重试");
        if (!tool.equals(stored.getToolName()) || !hash.equals(stored.getRequestHash()))
            throw new BizException(409, "REQUEST_ID_REUSED: 同一 requestId 不能用于不同工具或内容");
        if ("SUCCEEDED".equals(stored.getStatus())) {
            try { return json.mapper().readValue(stored.getResultJson(), Object.class); }
            catch (Exception e) { throw new BizException(500, "业务请求结果记录无效"); }
        }
        Outcome result = action.get();
        stored.setDramaId(result.dramaId()); stored.setCreatedProject(result.createdProject() ? 1 : 0);
        stored.setResultJson(json.write(result.data()));
        if (operations.complete(stored) != 1) throw new BizException(409, "业务请求状态冲突");
        return result.data();
    }

    public Object read(McpPrincipal caller, String key) {
        return read(caller, key, null);
    }

    /** Resolve a committed operation only after checking its project and original handler. */
    public Object read(McpPrincipal caller, String key, String expectedTool) {
        key(key);
        McpBusinessOperation result = operations.find(key);
        if (result == null) throw new BizException(404, "业务请求尚未提交或尚未完成；可使用原 requestId 重试");
        scope.authorizeProject(caller, result.getDramaId().toString());
        if (expectedTool != null && !expectedTool.equals(result.getToolName()))
            throw new BizException(409, "UPLOAD_ID_INVALID: 该请求不是所需类型的直传凭证");
        try { return Map.of("requestId", key, "status", result.getStatus(), "data", json.mapper().readValue(result.getResultJson(), Object.class)); }
        catch (Exception e) { throw new BizException(500, "业务请求结果记录无效"); }
    }

    public static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}")) throw new BizException(400, "requestId 必须是 8–80 字符的唯一标识，建议使用 UUID");
    }
    @SuppressWarnings("unchecked") private Object canonical(Object input) {
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>(); map.forEach((key, value) -> result.put(key.toString(), canonical(value))); return result;
        }
        if (input instanceof java.util.List<?> list) return list.stream().map(this::canonical).toList();
        return input;
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
