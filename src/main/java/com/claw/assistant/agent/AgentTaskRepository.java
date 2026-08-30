package com.claw.assistant.agent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository {
    AgentTask save(AgentTask task) throws IOException;

    Optional<AgentTask> findById(String taskId) throws IOException;

    List<AgentTask> findAll() throws IOException;
}
