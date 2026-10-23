package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.ShotVideoCapabilityService;
import com.astra.freyja.service.ShotVideoReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/drama/shot")
@RequiredArgsConstructor
public class ShotVideoReferenceController {
    private final ShotVideoCapabilityService capabilities;
    private final ShotVideoReferenceService references;

    @GetMapping("/video-model-options")
    public R<List<ShotVideoModelOptionVO>> options(@RequestParam Long providerId) { return R.ok(capabilities.options(providerId)); }

    @PostMapping("/{id}/video-reference-preflight")
    public R<VideoReferencePreflightVO> start(@PathVariable Long id, @RequestBody DramaShotRenderRequestDTO request) { return R.ok(references.start(id, request)); }

    @PostMapping("/{id}/video-reference-probe")
    public R<com.astra.freyja.dto.video.VideoProbeInfoVO> probe(@PathVariable Long id, @RequestBody ShotRefVideoDTO request) { return R.ok(references.probe(id, request)); }

    @GetMapping("/{id}/video-reference-preflight/{preflightId}")
    public R<VideoReferencePreflightVO> get(@PathVariable Long id, @PathVariable String preflightId) { return R.ok(references.get(id, preflightId)); }
}
