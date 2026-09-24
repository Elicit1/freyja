package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资产文件（图片/三视图/模型）上传返回 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetUploadResultVO {

    /** MinIO 存储桶名 */
    private String bucket;

    /** MinIO 对象相对路径 */
    private String objectPath;

    /** 可公开访问/预览的完整 URL */
    private String url;

    /** 文件原始名称 */
    private String originalFilename;

    /** 文件大小（字节） */
    private Long size;

    /** 文件类型/扩展名 */
    private String contentType;
}
