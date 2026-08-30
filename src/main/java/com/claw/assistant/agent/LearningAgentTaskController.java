package com.claw.assistant.agent;

import com.claw.assistant.learning.LearningTaskType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/tasks")
public class LearningAgentTaskController {
    private final LearningAgentTaskService taskService;

    public LearningAgentTaskController(LearningAgentTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateAgentTaskRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空"));
        }
        return call(() -> taskService.create(
                request.sessionId(),
                LearningTaskType.fromLabel(request.intent()),
                request.goal(),
                request.scheduledAt()
        ));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return call(taskService::list);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> get(@PathVariable String taskId) {
        return call(() -> taskService.get(taskId));
    }

    @PostMapping("/{taskId}/run")
    public ResponseEntity<?> runNow(@PathVariable String taskId) {
        return call(() -> taskService.runNow(taskId));
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<?> pause(@PathVariable String taskId) {
        return call(() -> taskService.pause(taskId));
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<?> resume(@PathVariable String taskId) {
        return call(() -> taskService.resume(taskId));
    }

    private ResponseEntity<?> call(ControllerCall call) {
        try {
            return ResponseEntity.ok(call.execute());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "任务存储失败", "detail", e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface ControllerCall {
        Object execute() throws IOException;
    }
}
