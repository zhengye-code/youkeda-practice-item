package com.claw.assistant.agent;

import com.claw.assistant.learning.LearningContentResult;
import com.claw.assistant.learning.LearningContentService;
import com.claw.assistant.learning.LearningTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningAgentTaskServiceImplTest {
    @TempDir
    Path temporaryDirectory;

    private final List<LearningAgentTaskServiceImpl> services = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        services.forEach(LearningAgentTaskServiceImpl::shutdown);
    }

    @Test
    void decomposesAndCompletesThreeStepLongTask() throws Exception {
        FakeLearningContentService learning = new FakeLearningContentService();
        LearningAgentTaskServiceImpl service = service(learning, temporaryDirectory);

        AgentTask created = service.create(
                "student-1", LearningTaskType.STUDY_PLAN,
                "制定下周物理复习计划", Instant.now().plusSeconds(3_600)
        );
        service.executeSynchronously(created.id());
        AgentTask completed = service.get(created.id());

        assertEquals(AgentTaskStatus.COMPLETED, completed.status());
        assertEquals(3, completed.nextStepIndex());
        assertTrue(completed.steps().stream().allMatch(step -> step.status() == AgentStepStatus.COMPLETED));
        assertEquals("# 完整学习计划", completed.resultMarkdown());
        assertEquals(1, learning.calls);
    }

    @Test
    void resumesFromFailedStepWithoutRepeatingCompletedCheckpoint() throws Exception {
        FakeLearningContentService learning = new FakeLearningContentService();
        learning.failNext = true;
        LearningAgentTaskServiceImpl service = service(learning, temporaryDirectory);
        AgentTask created = service.create(
                "student-2", LearningTaskType.KNOWLEDGE_SUMMARY,
                "整理线性代数知识点", Instant.now()
        );

        service.executeSynchronously(created.id());
        AgentTask failed = service.get(created.id());
        String firstCheckpointOutput = failed.steps().get(0).output();
        assertEquals(AgentTaskStatus.FAILED, failed.status());
        assertEquals(1, failed.nextStepIndex());
        assertEquals(AgentStepStatus.COMPLETED, failed.steps().get(0).status());

        service.executeSynchronously(created.id());
        AgentTask resumed = service.get(created.id());
        assertEquals(AgentTaskStatus.COMPLETED, resumed.status());
        assertEquals(firstCheckpointOutput, resumed.steps().get(0).output());
        assertEquals(2, learning.calls);
    }

    @Test
    void convertsInterruptedRunningTaskIntoScheduledCheckpointOnRestart() throws Exception {
        FileAgentTaskRepository repository = repository(temporaryDirectory);
        Instant now = Instant.now();
        AgentTask interrupted = new AgentTask(
                "restart-task", "student-3", LearningTaskType.WRONG_ANSWER_ANALYSIS, "解析C语言错题", now,
                AgentTaskStatus.RUNNING,
                List.of(
                        new AgentTaskStep(0, "解析目标与约束", AgentStepStatus.COMPLETED, "完成"),
                        new AgentTaskStep(1, "生成结构化学习内容", AgentStepStatus.RUNNING, null),
                        new AgentTaskStep(2, "整理最终成品", AgentStepStatus.PENDING, null)
                ),
                1, null, null, now, now
        );
        repository.save(interrupted);

        LearningAgentTaskServiceImpl restarted = new LearningAgentTaskServiceImpl(
                new FakeLearningContentService(), repository
        );
        services.add(restarted);
        restarted.restoreCheckpoints();
        AgentTask recovered = restarted.get("restart-task");

        assertEquals(AgentTaskStatus.SCHEDULED, recovered.status());
        assertEquals(1, recovered.nextStepIndex());
        assertEquals(AgentStepStatus.PENDING, recovered.steps().get(1).status());
        assertNotNull(recovered.lastError());
    }

    @Test
    void scheduledScanDispatchesDueTaskAsynchronously() throws Exception {
        FakeLearningContentService learning = new FakeLearningContentService();
        LearningAgentTaskServiceImpl service = service(learning, temporaryDirectory);
        AgentTask created = service.create(
                "student-4", LearningTaskType.STUDY_PLAN,
                "今晚九点生成复习计划", Instant.now()
        );

        service.runDueTasks();
        AgentTask current = service.get(created.id());
        for (int attempt = 0; attempt < 100 && current.status() != AgentTaskStatus.COMPLETED; attempt++) {
            Thread.sleep(10);
            current = service.get(created.id());
        }

        assertEquals(AgentTaskStatus.COMPLETED, current.status());
        assertEquals(1, learning.calls);
    }

    private LearningAgentTaskServiceImpl service(
            LearningContentService learningContentService,
            Path directory
    ) throws IOException {
        LearningAgentTaskServiceImpl service = new LearningAgentTaskServiceImpl(
                learningContentService, repository(directory)
        );
        services.add(service);
        service.restoreCheckpoints();
        return service;
    }

    private FileAgentTaskRepository repository(Path directory) {
        return new FileAgentTaskRepository(directory, new ObjectMapper().findAndRegisterModules());
    }

    private static class FakeLearningContentService implements LearningContentService {
        private int calls;
        private boolean failNext;

        @Override
        public LearningContentResult generate(
                String sessionId,
                LearningTaskType type,
                String userInput
        ) throws IOException {
            calls++;
            if (failNext) {
                failNext = false;
                throw new IOException("模拟模型调用中断");
            }
            return new LearningContentResult(sessionId, type, "# 完整学习计划", 1);
        }

        @Override
        public void clearContext(String sessionId, LearningTaskType type) {
        }
    }
}
