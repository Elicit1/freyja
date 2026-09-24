package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.AssetImageGenerationRequest;
import com.astra.freyja.dto.res.AssetImageGenerationVO;
import com.astra.freyja.service.AssetImageGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 角色、场景及造型共用的参考图生成接口。 */
@RestController
@RequestMapping("/assets/image-generation")
@RequiredArgsConstructor
public class AssetImageGenerationController {
    private final AssetImageGenerationService generationService;

    @PostMapping("/submit")
    public R<AssetImageGenerationVO> submit(@RequestBody AssetImageGenerationRequest request) {
        return R.ok(generationService.submit(request));
    }

    @GetMapping("/task/{taskId}")
    public R<AssetImageGenerationVO> getTask(@PathVariable String taskId) {
        return R.ok(generationService.getTask(taskId));
    }

    @PostMapping("/apply")
    public R<Void> apply(@RequestParam String targetType,
                         @RequestParam Long targetId,
                         @RequestParam String slot,
                         @RequestParam String imageUrl) {
        generationService.apply(targetType, targetId, slot, imageUrl);
        return R.ok();
    }
}
