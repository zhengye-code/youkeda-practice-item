package com.claw.assistant.agent;

import com.claw.assistant.learning.LearningTaskType;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public interface LearningAgentTaskService {
    AgentTask create(String sessionId, LearningTaskType intent, String goal, Instant scheduledAt) throws IOException;

    AgentTask get(String taskId) throws IOException;

    List<AgentTask> list() throws IOException;

    AgentTask runNow(String taskId) throws IOException;

    AgentTask pause(String taskId) throws IOException;

    AgentTask resume(String taskId) throws IOException;
}
