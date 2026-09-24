package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.AssetUploadResultVO;
import com.astra.freyja.service.MediaAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资源资产文件上传与管理 Controller。
 */
@RestController
@RequestMapping("/res/asset")
@RequiredArgsConstructor
public class MediaAssetController {

    private final MediaAssetService mediaAssetService;

    @PostMapping("/upload")
    public R<AssetUploadResultVO> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "category", required = false, defaultValue = "general") String category) {
        return R.ok(mediaAssetService.upload(file, category));
    }
}
