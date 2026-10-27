package com.astra.freyja.controller;
import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.service.ResVideoService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/res/video")
@RequiredArgsConstructor
public class ResVideoController {
    private final ResVideoService service;
    @GetMapping("/page") public R<Page<ResVideoVO>> page(ResVideoQuery query) { return R.ok(service.page(query)); }
    @GetMapping("/{id}") public R<ResVideoVO> get(@PathVariable Long id) { return R.ok(service.getById(id)); }
    @PostMapping("/upload") public R<ResVideoVO> upload(@RequestParam("file") MultipartFile file,
            @ModelAttribute ResVideoDTO metadata,
            @RequestParam(value = "sourceType", defaultValue = "MANUAL_UPLOAD") String sourceType) {
        return R.ok(service.upload(file, metadata, sourceType));
    }
    @PutMapping public R<Void> update(@RequestBody ResVideoDTO dto) { service.update(dto); return R.ok(); }
    @DeleteMapping("/{id}") public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
