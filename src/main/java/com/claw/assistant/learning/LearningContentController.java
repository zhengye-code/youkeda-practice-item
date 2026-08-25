package com.claw.assistant.learning;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/learning/content")
public class LearningContentController {
    private final LearningContentService learningContentService;

    public LearningContentController(LearningContentService learningContentService) {
        this.learningContentService = learningContentService;
    }

    @PostMapping
    public ResponseEntity<?> generate(@RequestBody LearningContentRequest request) {
        try {
            if (request == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            LearningTaskType type = LearningTaskType.fromLabel(request.intent());
            return ResponseEntity.ok(learningContentService.generate(request.sessionId(), type, request.content()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "学习内容生成失败", "detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<?> clearContext(
            @PathVariable String sessionId,
            @RequestParam String intent
    ) {
        try {
            learningContentService.clearContext(sessionId, LearningTaskType.fromLabel(intent));
            return ResponseEntity.ok(Map.of("cleared", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
