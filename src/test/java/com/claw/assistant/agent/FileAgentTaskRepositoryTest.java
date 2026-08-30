package com.claw.assistant.agent;

import com.claw.assistant.learning.LearningTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAgentTaskRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsTaskCheckpoint() throws Exception {
        FileAgentTaskRepository repository = new FileAgentTaskRepository(
                temporaryDirectory,
                new ObjectMapper().findAndRegisterModules()
        );
        Instant now = Instant.parse("2026-08-27T02:00:00Z");
        AgentTask task = new AgentTask(
                "task-1", "student-1", LearningTaskType.STUDY_PLAN, "制定复习计划", now,
                AgentTaskStatus.RUNNING,
                List.of(new AgentTaskStep(0, "解析目标与约束", AgentStepStatus.COMPLETED, "完成")),
                1, null, null, now, now
        );

        repository.save(task);

        assertEquals(task, repository.findById("task-1").orElseThrow());
        assertEquals(List.of(task), repository.findAll());
        assertTrue(temporaryDirectory.resolve("task-1.json").toFile().isFile());
    }
}
