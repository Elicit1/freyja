package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;
import com.astra.freyja.service.PromptAssembleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分镜 Prompt 动态组装 Controller。
 */
@RestController
@RequestMapping("/res/prompt")
@RequiredArgsConstructor
public class PromptAssembleController {

    private final PromptAssembleService promptAssembleService;

    @PostMapping("/assemble")
    public R<PromptAssembleResultVO> assemble(@RequestBody PromptAssembleRequestDTO request) {
        return R.ok(promptAssembleService.assemble(request));
    }
}
