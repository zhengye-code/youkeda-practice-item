package com.claw.assistant.learning;

import java.util.Locale;

public enum LearningTaskType {
    KNOWLEDGE_SUMMARY,
    STUDY_PLAN,
    WRONG_ANSWER_ANALYSIS;

    public static LearningTaskType fromLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("intent 不能为空");
        }

        String normalized = label.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "KNOWLEDGE_SUMMARY", "KNOWLEDGE", "资料整理", "知识点整理" -> KNOWLEDGE_SUMMARY;
            case "STUDY_PLAN", "PLAN", "学习计划", "日程规划" -> STUDY_PLAN;
            case "WRONG_ANSWER_ANALYSIS", "WRONG_ANSWER", "错题解析", "错题分析" -> WRONG_ANSWER_ANALYSIS;
            default -> throw new IllegalArgumentException("不支持的学习任务类型: " + label);
        };
    }
}
