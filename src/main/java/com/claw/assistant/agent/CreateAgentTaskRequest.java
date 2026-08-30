package com.claw.assistant.agent;

import java.time.Instant;

public record CreateAgentTaskRequest(
        String sessionId,
        String intent,
        String goal,
        Instant scheduledAt
) {
}
