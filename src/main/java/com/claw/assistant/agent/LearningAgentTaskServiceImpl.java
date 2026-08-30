package com.claw.assistant.agent;

import com.claw.assistant.learning.LearningContentResult;
import com.claw.assistant.learning.LearningContentService;
import com.claw.assistant.learning.LearningTaskType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class LearningAgentTaskServiceImpl implements LearningAgentTaskService {
    private static final Logger logger = LoggerFactory.getLogger(LearningAgentTaskServiceImpl.class);
    private static final int MAX_GOAL_LENGTH = 4_000;

    private final LearningContentService learningContentService;
    private final AgentTaskRepository repository;
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
    private final Set<String> runningTaskIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LearningAgentTaskServiceImpl(
            LearningContentService learningContentService,
            AgentTaskRepository repository
    ) {
        this.learningContentService = learningContentService;
        this.repository = repository;
    }

    @PostConstruct
    void restoreCheckpoints() throws IOException {
        Instant now = Instant.now();
        for (AgentTask storedTask : repository.findAll()) {
            AgentTask recovered = storedTask;
            if (storedTask.status() == AgentTaskStatus.RUNNING) {
                List<AgentTaskStep> steps = new ArrayList<>(storedTask.steps());
                if (storedTask.nextStepIndex() < steps.size()) {
                    AgentTaskStep interrupted = steps.get(storedTask.nextStepIndex());
                    steps.set(storedTask.nextStepIndex(), interrupted.withStatus(AgentStepStatus.PENDING, null));
                }
                recovered = new AgentTask(
                        storedTask.id(), storedTask.sessionId(), storedTask.intent(), storedTask.goal(), now,
                        AgentTaskStatus.SCHEDULED, steps, storedTask.nextStepIndex(), storedTask.resultMarkdown(),
                        "检测到上次运行中断，已从检查点恢复", storedTask.createdAt(), now
                );
                repository.save(recovered);
            }
            tasks.put(recovered.id(), recovered);
        }
    }

    @Override
    public AgentTask create(
            String sessionId,
            LearningTaskType intent,
            String goal,
            Instant scheduledAt
    ) throws IOException {
        String validSessionId = validateSessionId(sessionId);
        if (intent == null) {
            throw new IllegalArgumentException("intent 不能为空");
        }
        String validGoal = validateGoal(goal);
        Instant now = Instant.now();
        Instant executionTime = scheduledAt == null || scheduledAt.isBefore(now) ? now : scheduledAt;
        String taskId = UUID.randomUUID().toString();
        List<AgentTaskStep> steps = List.of(
                new AgentTaskStep(0, "解析目标与约束", AgentStepStatus.PENDING, null),
                new AgentTaskStep(1, "生成结构化学习内容", AgentStepStatus.PENDING, null),
                new AgentTaskStep(2, "整理最终成品", AgentStepStatus.PENDING, null)
        );
        AgentTask task = new AgentTask(
                taskId, validSessionId, intent, validGoal, executionTime,
                AgentTaskStatus.SCHEDULED, steps, 0, null, null, now, now
        );
        tasks.put(taskId, repository.save(task));
        return task;
    }

    @Override
    public AgentTask get(String taskId) throws IOException {
        AgentTask task = tasks.get(taskId);
        if (task != null) {
            return task;
        }
        AgentTask loaded = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        tasks.put(taskId, loaded);
        return loaded;
    }

    @Override
    public List<AgentTask> list() {
        return tasks.values().stream()
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .toList();
    }

    @Override
    public AgentTask runNow(String taskId) throws IOException {
        AgentTask task = get(taskId);
        if (task.status() == AgentTaskStatus.COMPLETED || task.status() == AgentTaskStatus.RUNNING) {
            return task;
        }
        AgentTask scheduled = task.reschedule(Instant.now(), AgentTaskStatus.SCHEDULED, Instant.now());
        save(scheduled);
        dispatch(taskId);
        return scheduled;
    }

    @Override
    public AgentTask pause(String taskId) throws IOException {
        AgentTask task = get(taskId);
        if (task.status() == AgentTaskStatus.RUNNING) {
            throw new IllegalStateException("任务正在执行当前步骤，请在步骤结束后暂停");
        }
        if (task.status() == AgentTaskStatus.COMPLETED) {
            throw new IllegalStateException("已完成任务不能暂停");
        }
        AgentTask paused = task.reschedule(task.scheduledAt(), AgentTaskStatus.PAUSED, Instant.now());
        return save(paused);
    }

    @Override
    public AgentTask resume(String taskId) throws IOException {
        AgentTask task = get(taskId);
        if (task.status() == AgentTaskStatus.COMPLETED || task.status() == AgentTaskStatus.RUNNING) {
            return task;
        }
        AgentTask resumed = task.reschedule(Instant.now(), AgentTaskStatus.SCHEDULED, Instant.now());
        save(resumed);
        dispatch(taskId);
        return resumed;
    }

    @Scheduled(fixedDelayString = "${agent.task.poll-interval-ms:1000}")
    public void runDueTasks() {
        Instant now = Instant.now();
        tasks.values().stream()
                .filter(task -> task.status() == AgentTaskStatus.SCHEDULED)
                .filter(task -> !task.scheduledAt().isAfter(now))
                .forEach(task -> dispatch(task.id()));
    }

    void executeSynchronously(String taskId) throws IOException {
        AgentTask task = get(taskId);
        if (task.status() == AgentTaskStatus.COMPLETED || task.status() == AgentTaskStatus.PAUSED) {
            return;
        }

        AgentTask current = task.withExecutionState(
                AgentTaskStatus.RUNNING, task.steps(), task.nextStepIndex(),
                task.resultMarkdown(), null, Instant.now()
        );
        save(current);

        while (current.nextStepIndex() < current.steps().size()) {
            int stepIndex = current.nextStepIndex();
            List<AgentTaskStep> runningSteps = new ArrayList<>(current.steps());
            AgentTaskStep step = runningSteps.get(stepIndex);
            runningSteps.set(stepIndex, step.withStatus(AgentStepStatus.RUNNING, null));
            current = current.withExecutionState(
                    AgentTaskStatus.RUNNING, runningSteps, stepIndex,
                    current.resultMarkdown(), null, Instant.now()
            );
            save(current);

            try {
                StepOutput output = executeStep(current, stepIndex);
                List<AgentTaskStep> completedSteps = new ArrayList<>(current.steps());
                completedSteps.set(stepIndex, step.withStatus(AgentStepStatus.COMPLETED, output.stepOutput()));
                int nextStep = stepIndex + 1;
                AgentTaskStatus status = nextStep >= completedSteps.size()
                        ? AgentTaskStatus.COMPLETED
                        : AgentTaskStatus.RUNNING;
                current = current.withExecutionState(
                        status, completedSteps, nextStep, output.resultMarkdown(), null, Instant.now()
                );
                save(current);
            } catch (Exception e) {
                List<AgentTaskStep> failedSteps = new ArrayList<>(current.steps());
                failedSteps.set(stepIndex, step.withStatus(AgentStepStatus.FAILED, e.getMessage()));
                current = current.withExecutionState(
                        AgentTaskStatus.FAILED, failedSteps, stepIndex,
                        current.resultMarkdown(), e.getMessage(), Instant.now()
                );
                save(current);
                logger.warn("长任务执行失败 taskId={}, step={}, error={}", taskId, stepIndex, e.toString());
                return;
            }
        }
    }

    private StepOutput executeStep(AgentTask task, int stepIndex) throws IOException {
        return switch (stepIndex) {
            case 0 -> new StepOutput(
                    "已识别任务类型：%s；目标长度：%d 字符".formatted(task.intent(), task.goal().length()),
                    task.resultMarkdown()
            );
            case 1 -> {
                LearningContentResult result = learningContentService.generate(
                        task.sessionId(), task.intent(), task.goal()
                );
                yield new StepOutput(
                        "结构化内容已生成，共 %d 字符".formatted(result.markdown().length()),
                        result.markdown()
                );
            }
            case 2 -> {
                if (task.resultMarkdown() == null || task.resultMarkdown().isBlank()) {
                    throw new IOException("结构化内容为空，无法整理最终成品");
                }
                yield new StepOutput(
                        "最终 Markdown 成品已整理，可交给结果校验模块继续处理",
                        task.resultMarkdown()
                );
            }
            default -> throw new IllegalStateException("未知任务步骤: " + stepIndex);
        };
    }

    private void dispatch(String taskId) {
        if (!runningTaskIds.add(taskId)) {
            return;
        }
        executor.submit(() -> {
            try {
                executeSynchronously(taskId);
            } catch (Exception e) {
                logger.error("长任务调度失败 taskId={}", taskId, e);
            } finally {
                runningTaskIds.remove(taskId);
            }
        });
    }

    private AgentTask save(AgentTask task) throws IOException {
        AgentTask saved = repository.save(task);
        tasks.put(saved.id(), saved);
        return saved;
    }

    private String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        String value = sessionId.trim();
        if (value.length() > 64 || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("sessionId 格式不正确");
        }
        return value;
    }

    private String validateGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        String value = goal.trim();
        if (value.length() > MAX_GOAL_LENGTH) {
            throw new IllegalArgumentException("goal 不能超过4000个字符");
        }
        return value;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record StepOutput(String stepOutput, String resultMarkdown) {
    }
}
