package com.claw.assistant.routing;

import com.claw.assistant.rag.KeywordRagService;
import com.claw.assistant.service.LlmService;
import com.claw.assistant.skill.AssistantSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class MessageRouter {
    private final List<AssistantSkill> skills;
    private final KeywordRagService ragService;
    private final LlmService llmService;
    private final boolean ragEnabled;
    private final List<String> ragKeywords;
    private final int ragTopK;
    private final String model;

    public MessageRouter(
            List<AssistantSkill> skills,
            KeywordRagService ragService,
            LlmService llmService,
            @Value("${rag.enabled:true}") boolean ragEnabled,
            @Value("${rag.keywords:RAG,Skill,Function Calling,知识库,检索增强}") String ragKeywords,
            @Value("${rag.top-k:3}") int ragTopK,
            @Value("${aliyun.dashscope.model}") String model
    ) {
        this.skills = List.copyOf(skills);
        this.ragService = ragService;
        this.llmService = llmService;
        this.ragEnabled = ragEnabled;
        this.ragKeywords = Arrays.stream(ragKeywords.split(","))
                .map(String::trim)
                .filter(keyword -> !keyword.isEmpty())
                .toList();
        this.ragTopK = Math.max(1, ragTopK);
        this.model = model;
    }

    public RoutingResult route(String message) throws IOException {
        for (AssistantSkill skill : skills) {
            if (skill.matches(message)) {
                return new RoutingResult(RouteType.SKILL, skill.execute(message));
            }
        }

        if (ragEnabled && containsRagKeyword(message)) {
            String context = ragService.buildContext(message, ragTopK);
            String systemPrompt = """
                    你是课程知识助手。只依据下面检索到的知识库片段回答。
                    如果片段不足以回答，要明确说“知识库资料不足”，不要编造。
                    回答末尾列出实际使用的来源文件名。

                    知识库片段：
                    %s
                    """.formatted(context);
            String reply = llmService.chatWithSystemPrompt(systemPrompt, message, model);
            return new RoutingResult(RouteType.RAG, reply);
        }

        return new RoutingResult(RouteType.DIRECT_LLM, llmService.chatWithTools(message));
    }

    private boolean containsRagKeyword(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return ragKeywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }
}
