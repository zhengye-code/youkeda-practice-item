package com.claw.assistant.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyReviewSkillTest {

    private final StudyReviewSkill skill = new StudyReviewSkill();

    @Test
    void matchesReviewKeywordAndRunsFixedWorkflow() {
        assertTrue(skill.matches("帮我做一次学习复盘：RAG"));
        assertFalse(skill.matches("北京天气怎么样"));
        assertTrue(skill.execute("学习复盘：RAG").contains("下一步最小行动"));
    }
}
