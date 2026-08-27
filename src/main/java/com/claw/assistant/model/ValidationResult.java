package com.claw.assistant.model;

public record ValidationResult(
        String finalContent,
        ValidationReport report
) {
}
