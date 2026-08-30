package com.claw.assistant.agent;

import com.claw.assistant.learning.LearningTaskType;

import java.time.Instant;
import java.util.List;

public record AgentTask(
        String id,
        String sessionId,
        LearningTaskType intent,
        String goal,
        Instant scheduledAt,
        AgentTaskStatus status,
        List<AgentTaskStep> steps,
        int nextStepIndex,
        String resultMarkdown,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentTask {
        steps = List.copyOf(steps);
    }

    public AgentTask withExecutionState(
            AgentTaskStatus newStatus,
            List<AgentTaskStep> newSteps,
            int newNextStepIndex,
            String newResultMarkdown,
            String newLastError,
            Instant now
    ) {
        return new AgentTask(
                id, sessionId, intent, goal, scheduledAt, newStatus, newSteps,
                newNextStepIndex, newResultMarkdown, newLastError, createdAt, now
        );
    }

    public AgentTask reschedule(Instant newScheduledAt, AgentTaskStatus newStatus, Instant now) {
        return new AgentTask(
                id, sessionId, intent, goal, newScheduledAt, newStatus, steps,
                nextStepIndex, resultMarkdown, null, createdAt, now
        );
    }
}
