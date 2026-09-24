package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 人物角色分页查询参数。
 */
@Data
public class ResCharacterQuery {

    /** 当前页，默认 1 */
    private Integer current = 1;

    /** 每页大小，默认 10 */
    private Integer size = 10;

    /** 归属短剧ID，0或null为全部公共库/不限 */
    private Long dramaId;

    /** 角色名称模糊匹配 (包含 name, canonicalName, displayName) */
    private String name;

    /** 性别过滤 */
    private String gender;

    /** 角色定位过滤 */
    private String roleType;

    /** 身份状态过滤 (UNKNOWN/PARTIAL/CONFIRMED/UNRESOLVED/MERGED) */
    private String identityStatus;

    /** 是否排除已合并角色 (默认 true) */
    private Boolean excludeMerged = true;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
