package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.service.ChatModelCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
public class ChatModelCapabilityController {
    private final ChatModelCapabilityService service;
    @GetMapping("/ai/provider/{id}/model-capabilities")
    public R<List<ChatModelCapabilityService.Discovered>> discover(@PathVariable Long id) { return R.ok(service.discover(id)); }
}
