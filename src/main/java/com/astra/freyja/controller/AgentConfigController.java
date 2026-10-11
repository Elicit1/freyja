package com.astra.freyja.controller;

import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.agent.settings.AgentAvatarMediaService;
import com.astra.freyja.agent.settings.AgentConfigService;
import com.astra.freyja.common.BizException;
import com.astra.freyja.common.R;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import com.astra.freyja.dto.agent.AgentRequests.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/system/agent")
@RequiredArgsConstructor
public class AgentConfigController {
    private final AgentConfigService configs;
    private final AgentStore store;
    private final AgentAvatarMediaService avatars;
    @GetMapping("/configs") public R<List<AgentConfigDTO>> list() { return R.ok(configs.list()); }
    @GetMapping("/configs/{code}") public R<AgentConfigDTO> get(@PathVariable String code) { return R.ok(configs.get(code)); }
    @PutMapping("/configs/{code}") public R<AgentConfigDTO> save(@PathVariable String code, @RequestBody AgentConfigDTO request) {
        return R.ok(configs.save(code, request));
    }
    @GetMapping("/configs/{code}/catalog") public R<Map<String, Object>> catalog(@PathVariable String code) { return R.ok(configs.catalog(code)); }
    @PostMapping("/configs/{code}/validate") public R<ValidationResult> validate(@PathVariable String code, @RequestBody AgentConfigDTO request) {
        return R.ok(configs.validate(code, request, false));
    }
    @PostMapping("/configs/{code}/test-runs") public R<Submission> test(@PathVariable String code, @RequestBody TestRun request) {
        if (request.getConfig() == null) throw new BizException("测试配置不能为空");
        var validation = configs.validate(code, request.getConfig(), true);
        if (!validation.valid()) throw new BizException(400, validation.errors().getFirst().message());
        CreateSession sessionRequest = new CreateSession(); sessionRequest.setAgentCode(code);
        sessionRequest.setDramaId(request.getDramaId()); sessionRequest.setTitle("配置测试");
        SendMessage message = new SendMessage();
        message.setContent(request.getContent()); message.setClientRequestId(request.getClientRequestId() == null ? UUID.randomUUID().toString() : request.getClientRequestId());
        if (message.getClientRequestId().isBlank() || message.getClientRequestId().length() > 100) throw new BizException("测试请求 ID 无效");
        var session = store.createSession(sessionRequest, "CONFIG_TEST", code + "/" + message.getClientRequestId());
        return R.ok(store.submit(session.getId(), message, request.getConfig()));
    }
    @PostMapping("/avatar/upload") public R<AgentConfigDTO.Pet> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(avatars.upload(file));
    }
}
