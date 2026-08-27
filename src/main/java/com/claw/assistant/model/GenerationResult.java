package com.claw.assistant.model;

public record GenerationResult(
        String userQuery,
        String intentType,
        String generatedContent
) {
}
