package com.claw.assistant.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LearningTaskTypeTest {

    @Test
    void acceptsModuleOneJsonLabelsAndChineseAliases() {
        assertEquals(LearningTaskType.KNOWLEDGE_SUMMARY,
                LearningTaskType.fromLabel("KNOWLEDGE_SUMMARY"));
        assertEquals(LearningTaskType.STUDY_PLAN,
                LearningTaskType.fromLabel("学习计划"));
        assertEquals(LearningTaskType.WRONG_ANSWER_ANALYSIS,
                LearningTaskType.fromLabel("错题解析"));
    }

    @Test
    void rejectsUnsupportedIntent() {
        assertThrows(IllegalArgumentException.class,
                () -> LearningTaskType.fromLabel("CHAT"));
    }
}
