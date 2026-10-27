package com.astra.freyja.controller;
import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.service.ResAudioService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/res/audio")
@RequiredArgsConstructor
public class ResAudioController {
    private final ResAudioService service;
    @GetMapping("/page") public R<Page<ResAudioVO>> page(ResAudioQuery query) { return R.ok(service.page(query)); }
    @GetMapping("/{id}") public R<ResAudioVO> get(@PathVariable Long id) { return R.ok(service.getById(id)); }
    @PostMapping("/upload") public R<ResAudioVO> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "dramaId", required = false) Long dramaId,
            @RequestParam(value = "shotId", required = false) Long shotId,
            @RequestParam(value = "sourceType", defaultValue = "MANUAL_UPLOAD") String sourceType,
            @RequestParam("audioKind") String audioKind,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "language", required = false) String language) {
        return R.ok(service.upload(file, name, dramaId, shotId, sourceType, audioKind, text, language));
    }
    @PutMapping public R<Void> update(@RequestBody ResAudioDTO dto) { service.update(dto); return R.ok(); }
    @DeleteMapping("/{id}") public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
