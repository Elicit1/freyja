package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import lombok.Getter;
import java.util.stream.Collectors;

@Getter
public class RenderValidationException extends BizException {
    private final RenderValidationReport report;
    public RenderValidationException(RenderValidationReport report) {
        super(422, report.getIssues().stream().map(RenderValidationReport.Issue::message)
                .collect(Collectors.joining("；")));
        this.report = report;
    }
}
