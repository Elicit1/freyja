package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 分镜提示词解析与统一结构校验结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotPromptValidationResult {

    /** 解析并归一化后的提示词结果对象 (当有阻塞性硬错误时可能为空) */
    private ShotPromptDeriveVO result;

    /** 阻塞性硬错误列表 (如无法解析 JSON、<Picture N> 超界等) */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    /** 软性合规与建议警告列表 (如缺少六段式字段、缺少对白标签、指纹不一致等) */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /** 任务上下文指纹是否一致 (true: 完全一致; false: 发生变动; null: 未传指纹) */
    private Boolean fingerprintMatched;

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
