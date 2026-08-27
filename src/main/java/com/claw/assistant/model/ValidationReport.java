package com.claw.assistant.model;

import java.util.List;

public record ValidationReport(
        boolean passed,
        List<String> issues,
        int retryCount
) {
    public ValidationReport(boolean passed, List<String> issues) {
        this(passed, issues, 0);
    }
}
