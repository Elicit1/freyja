package com.astra.freyja.util;

import com.astra.freyja.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** JSON structure and size checks only. Model instructions and tags belong to the caller. */
public final class VoiceModelRequest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private VoiceModelRequest() {}

    public static String snapshot(Map<String, Object> request) {
        if (request == null || !(request.get("messages") instanceof List<?> messages) || messages.isEmpty())
            throw new BizException(400, "modelRequest 必须包含非空 messages 数组");
        for (Object item : messages) {
            if (!(item instanceof Map<?, ?> message) || !(message.get("role") instanceof String role) || role.isBlank()
                    || !(message.get("content") instanceof String))
                throw new BizException(400, "每条消息必须包含字符串 role 和 content");
        }
        if (request.containsKey("stream") && !Boolean.FALSE.equals(request.get("stream")))
            throw new BizException(400, "配音归档仅支持 stream=false");
        if (request.containsKey("audio") && !(request.get("audio") instanceof Map<?, ?>))
            throw new BizException(400, "audio 必须为 JSON 对象");
        if (request.get("audio") instanceof Map<?, ?> audio) {
            if (audio.containsKey("voice")) throw new BizException(400, "参考声音由配音中心绑定，请勿传入 audio.voice");
            if (audio.containsKey("format") && !"wav".equals(audio.get("format")))
                throw new BizException(400, "配音归档仅支持 audio.format=wav");
        }
        if (text(request).length() > 4000) throw new BizException(400, "台词不能超过4000字");
        try {
            String snapshot = JSON.writeValueAsString(request);
            if (snapshot.length() > 65536) throw new BizException(400, "模型请求 JSON 不能超过64K字符");
            return snapshot;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new BizException(400, "模型请求必须为有效 JSON"); }
    }

    public static Map<String, Object> read(String snapshot) {
        if (snapshot == null || snapshot.isBlank())
            throw new BizException(409, "旧配音没有请求 JSON，请填写 JSON 并生成新版本");
        try { return JSON.readValue(snapshot, new TypeReference<>() {}); }
        catch (Exception e) { throw new BizException(400, "模型请求 JSON 无效"); }
    }

    public static String text(Map<String, Object> request) {
        String text = "";
        if (request.get("messages") instanceof List<?> messages)
            for (Object item : messages)
                if (item instanceof Map<?, ?> message && "assistant".equals(message.get("role"))
                        && message.get("content") instanceof String content) text = content;
        return text;
    }
}
