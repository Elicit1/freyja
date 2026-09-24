package com.astra.freyja.service;

import com.astra.freyja.dto.res.AssetUploadResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 多模态资源文件上传与存储管理服务。
 */
public interface MediaAssetService {

    /**
     * 上传资源文件（图片/三视图/音频等）至 MinIO
     *
     * @param file     上传的文件
     * @param category 资源类别 (character / scene / outfit / general)
     * @return 上传结果包含完整访问 URL
     */
    AssetUploadResultVO upload(MultipartFile file, String category);
}
