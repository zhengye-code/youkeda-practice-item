package com.claw.assistant.learning;

public record LearningContentResult(
        String sessionId,
        LearningTaskType intent,
        String markdown,
        int contextTurns
) {
}
