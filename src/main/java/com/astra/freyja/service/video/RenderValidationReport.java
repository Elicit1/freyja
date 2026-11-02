package com.astra.freyja.service.video;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** No semantic judgements: findings describe literal protocol or binding failures only. */
@Data
public class RenderValidationReport {
    private String rulesVersion = "h3-render-input/1";
    private boolean submissionReady;
    private boolean dispatchReady;
    private String inputFingerprint;
    private List<Slot> slots = new ArrayList<>();
    private List<Issue> issues = new ArrayList<>();

    public record Slot(String label, String referenceId, String kind, String source,
                       String role, boolean pending) {}
    public record Issue(String code, Long shotId, String label, String field, Integer line,
                        String message, String suggestion) {}
}
