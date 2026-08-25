package com.claw.assistant.learning;

import com.claw.assistant.service.LlmService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningContentServiceImplTest {

    @Test
    void generatesKnowledgeSummaryWithRequiredPromptAndMarkdownResult() throws Exception {
        FakeLlmService llm = new FakeLlmService();
        LearningContentServiceImpl service = new LearningContentServiceImpl(llm, "test-model", 4, 1800);

        LearningContentResult result = service.generate(
                "student-1",
                LearningTaskType.KNOWLEDGE_SUMMARY,
                "整理半正定矩阵知识点"
        );

        assertEquals(LearningTaskType.KNOWLEDGE_SUMMARY, result.intent());
        assertEquals("# 模型生成结果", result.markdown());
        assertEquals(1, result.contextTurns());
        assertTrue(llm.lastSystemPrompt.contains("## 2. 关键概念与公式"));
        assertTrue(llm.lastSystemPrompt.contains("## 4. 易错点"));
        assertEquals(1800, llm.lastMaxTokens);
        assertEquals(0.3, llm.lastTemperature);
    }

    @Test
    void includesPreviousTurnsForFollowUpAndKeepsWindowBounded() throws Exception {
        FakeLlmService llm = new FakeLlmService();
        LearningContentServiceImpl service = new LearningContentServiceImpl(llm, "test-model", 2, 1000);

        service.generate("plan-session", LearningTaskType.STUDY_PLAN, "制定三天物理复习计划");
        service.generate("plan-session", LearningTaskType.STUDY_PLAN, "每天只能学习两小时");
        LearningContentResult third = service.generate("plan-session", LearningTaskType.STUDY_PLAN, "把力学放到第一天");

        assertTrue(llm.lastUserPrompt.contains("每天只能学习两小时"));
        assertTrue(llm.lastUserPrompt.contains("把力学放到第一天"));
        assertEquals(2, third.contextTurns());

        service.generate("plan-session", LearningTaskType.STUDY_PLAN, "再增加一次模拟测试");
        assertFalse(llm.lastUserPrompt.contains("制定三天物理复习计划"));
    }

    @Test
    void separatesContextByLearningTypeAndCanClearIt() throws Exception {
        FakeLlmService llm = new FakeLlmService();
        LearningContentServiceImpl service = new LearningContentServiceImpl(llm, "test-model", 4, 1000);

        service.generate("same-session", LearningTaskType.STUDY_PLAN, "制定学习计划");
        service.generate("same-session", LearningTaskType.WRONG_ANSWER_ANALYSIS, "分析这道错题");
        assertFalse(llm.lastUserPrompt.contains("制定学习计划"));

        service.clearContext("same-session", LearningTaskType.STUDY_PLAN);
        service.generate("same-session", LearningTaskType.STUDY_PLAN, "重新制定计划");
        assertFalse(llm.lastUserPrompt.contains("以下是同一任务的历史对话"));
    }

    @Test
    void validatesSessionAndInputBeforeCallingModel() {
        FakeLlmService llm = new FakeLlmService();
        LearningContentServiceImpl service = new LearningContentServiceImpl(llm, "test-model", 4, 1000);

        assertThrows(IllegalArgumentException.class,
                () -> service.generate("bad/session", LearningTaskType.STUDY_PLAN, "制定计划"));
        assertThrows(IllegalArgumentException.class,
                () -> service.generate("student", LearningTaskType.STUDY_PLAN, " "));
        assertThrows(IllegalArgumentException.class,
                () -> service.clearContext("student", null));
        assertEquals(0, llm.prompts.size());
    }

    @Test
    void rejectsEmptyModelResponse() {
        FakeLlmService llm = new FakeLlmService();
        llm.reply = " ";
        LearningContentServiceImpl service = new LearningContentServiceImpl(llm, "test-model", 4, 1000);

        assertThrows(IOException.class,
                () -> service.generate("student", LearningTaskType.WRONG_ANSWER_ANALYSIS, "分析错题"));
    }

    private static class FakeLlmService implements LlmService {
        private final List<String> prompts = new ArrayList<>();
        private String reply = "# 模型生成结果";
        private String lastSystemPrompt = "";
        private String lastUserPrompt = "";
        private int lastMaxTokens;
        private double lastTemperature;

        @Override
        public String chatWithSystemPrompt(String systemPrompt, String userMessage, String model) {
            return reply;
        }

        @Override
        public String chatWithSystemPrompt(
                String systemPrompt,
                String userMessage,
                String model,
                int maxTokens,
                double temperature
        ) {
            prompts.add(userMessage);
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = userMessage;
            lastMaxTokens = maxTokens;
            lastTemperature = temperature;
            return reply;
        }

        @Override
        public String chatWithTools(String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chatWithWeatherContext(String userMessage, String weatherData) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chat(String message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String describeImage(byte[] imageBytes) {
            throw new UnsupportedOperationException();
        }
    }
}
