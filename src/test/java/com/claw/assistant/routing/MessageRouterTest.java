package com.claw.assistant.routing;

import com.claw.assistant.rag.KeywordRagService;
import com.claw.assistant.service.LlmService;
import com.claw.assistant.skill.StudyReviewSkill;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageRouterTest {

    @Test
    void routesSkillBeforeRag() throws Exception {
        FakeLlmService llm = new FakeLlmService();
        MessageRouter router = router(true, llm);

        RoutingResult result = router.route("请做学习复盘，并解释 RAG");

        assertEquals(RouteType.SKILL, result.routeType());
        assertTrue(result.reply().contains("学习复盘"));
        assertEquals(0, llm.ragCalls);
        assertEquals(0, llm.directCalls);
    }

    @Test
    void routesRagKeywordThroughEnhancedPromptWhenEnabled() throws Exception {
        FakeLlmService llm = new FakeLlmService();
        MessageRouter router = router(true, llm);

        RoutingResult result = router.route("RAG、Skill 和 Function Calling 有什么区别？");

        assertEquals(RouteType.RAG, result.routeType());
        assertEquals("RAG_REPLY", result.reply());
        assertEquals(1, llm.ragCalls);
        assertTrue(llm.lastSystemPrompt.contains("来源：rag-skill-notes.md"));
        assertEquals(0, llm.directCalls);
    }

    @Test
    void disablingRagMakesSameQuestionUseDirectLlm() throws Exception {
        FakeLlmService llm = new FakeLlmService();
        MessageRouter router = router(false, llm);

        RoutingResult result = router.route("RAG、Skill 和 Function Calling 有什么区别？");

        assertEquals(RouteType.DIRECT_LLM, result.routeType());
        assertEquals("DIRECT_REPLY", result.reply());
        assertEquals(0, llm.ragCalls);
        assertEquals(1, llm.directCalls);
    }

    @Test
    void ordinaryMessageUsesExistingToolAwareLlmFlow() throws Exception {
        FakeLlmService llm = new FakeLlmService();
        MessageRouter router = router(true, llm);

        RoutingResult result = router.route("北京天气怎么样？");

        assertEquals(RouteType.DIRECT_LLM, result.routeType());
        assertEquals(1, llm.directCalls);
    }

    private static MessageRouter router(boolean ragEnabled, FakeLlmService llm) throws IOException {
        KeywordRagService rag = new KeywordRagService(new PathMatchingResourcePatternResolver());
        return new MessageRouter(
                java.util.List.of(new StudyReviewSkill()),
                rag,
                llm,
                ragEnabled,
                "RAG,Skill,Function Calling,知识库,检索增强",
                3,
                "test-model"
        );
    }

    private static class FakeLlmService implements LlmService {
        private int directCalls;
        private int ragCalls;
        private String lastSystemPrompt = "";

        @Override
        public String chatWithTools(String message) {
            directCalls++;
            return "DIRECT_REPLY";
        }

        @Override
        public String chatWithSystemPrompt(String systemPrompt, String userMessage, String model) {
            ragCalls++;
            lastSystemPrompt = systemPrompt;
            return "RAG_REPLY";
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
