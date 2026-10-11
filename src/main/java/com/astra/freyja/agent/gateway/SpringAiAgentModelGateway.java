package com.astra.freyja.agent.gateway;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.tool.AgentToolRegistry;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.service.AiModelFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
public class SpringAiAgentModelGateway implements AgentModelGateway {
    private final AiModelFactory factory;
    private final AiModelMapper models;
    private final AiProviderMapper providers;
    private final AgentToolRegistry registry;
    private final ExecutorService agentModelExecutor;

    public ModelReply call(Snapshot snapshot, List<Message> messages, List<String> tools, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new BizException("Agent 执行时限已用尽");
        var settings = snapshot.config().getModel();
        var provider = providers.selectById(settings.getProviderId());
        var registeredModel = models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, settings.getProviderId())
                .eq(AiModel::getModelCode, settings.getModelCode()));
        if (provider == null || !Integer.valueOf(1).equals(provider.getStatus()) || registeredModel == null
                || !Integer.valueOf(1).equals(registeredModel.getStatus()) || !"CHAT".equals(registeredModel.getModelType()))
            throw new BizException("任务使用的模型或提供商已不可用");
        var model = factory.getChatModelForSnapshot(settings.getProviderId(), snapshot.pinnedModel());
        ChatOptions options;
        var definitions = registry.definitions(tools);
        // Preserve the pinned model defaults and provider connection options. Spring AI 2 requires
        // concrete provider options; generic ToolCallingChatOptions cannot be cast by OpenAiChatModel.
        if (model.getOptions() instanceof OpenAiChatOptions defaults) {
            var builder = defaults.mutate().model(settings.getModelCode()).temperature(settings.getTemperature())
                    .topP(settings.getTopP()).maxTokens(settings.getMaxTokens()).toolCallbacks(definitions);
            options = builder.build();
        } else if (model.getOptions() instanceof OllamaChatOptions defaults) {
            options = defaults.mutate().model(settings.getModelCode()).temperature(settings.getTemperature())
                    .topP(settings.getTopP()).numPredict(settings.getMaxTokens()).toolCallbacks(definitions).build();
        } else throw new BizException("Agent 模型适配器不支持当前提供商参数类型");
        // Spring AI 2.0 ChatModel.call returns tool requests. No ToolCallAdvisor is installed.
        Future<org.springframework.ai.chat.model.ChatResponse> pending = agentModelExecutor.submit(() ->
                ReasoningReplay.withMessages(messages, () -> model.call(new Prompt(messages, options))));
        try {
            long timeout = Math.min(timeoutMillis, snapshot.config().getRuntime().getModelTimeoutSeconds() * 1000L);
            var response = pending.get(timeout, TimeUnit.MILLISECONDS);
            if (response == null || response.getResult() == null) throw new BizException("模型未返回结果");
            var output = response.getResult().getOutput();
            var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            boolean knownUsage = usage != null && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0);
            // Ollama may omit call IDs. Assign them once, before the full reply is checkpointed.
            var calls = output.getToolCalls().stream().map(c -> new ToolCall(
                    c.id() == null || c.id().isBlank() ? "call_" + java.util.UUID.randomUUID() : c.id(), c.name(), c.arguments())).toList();
            return new ModelReply(output.getText(), calls, output.getMetadata() == null ? Map.of() : output.getMetadata(),
                    knownUsage ? usage.getPromptTokens() : null, knownUsage ? usage.getCompletionTokens() : null,
                    response.getMetadata() == null ? null : response.getMetadata().getId());
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new CancellationException("模型请求已中断");
        } catch (TimeoutException e) {
            pending.cancel(true); throw new BizException("模型调用超时");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new BizException("模型调用失败");
        }
    }
}
