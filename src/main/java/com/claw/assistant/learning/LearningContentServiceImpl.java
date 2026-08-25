package com.claw.assistant.learning;

import com.claw.assistant.service.LlmService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LearningContentServiceImpl implements LearningContentService {
    private static final int MAX_SESSION_ID_LENGTH = 64;
    private static final int MAX_INPUT_LENGTH = 4_000;
    private static final int MAX_HISTORY_ITEM_LENGTH = 2_500;

    private final LlmService llmService;
    private final String model;
    private final int maxContextTurns;
    private final int maxOutputTokens;
    private final Map<ContextKey, Deque<ConversationTurn>> conversations = new ConcurrentHashMap<>();

    public LearningContentServiceImpl(
            LlmService llmService,
            @Value("${aliyun.dashscope.model}") String model,
            @Value("${learning.context.max-turns:4}") int maxContextTurns,
            @Value("${learning.output.max-tokens:1800}") int maxOutputTokens
    ) {
        this.llmService = llmService;
        this.model = model;
        this.maxContextTurns = Math.max(1, maxContextTurns);
        this.maxOutputTokens = Math.max(256, maxOutputTokens);
    }

    @Override
    public LearningContentResult generate(String sessionId, LearningTaskType type, String userInput) throws IOException {
        String validSessionId = validateSessionId(sessionId);
        if (type == null) {
            throw new IllegalArgumentException("intent 不能为空");
        }
        String validInput = validateInput(userInput);

        ContextKey key = new ContextKey(validSessionId, type);
        Deque<ConversationTurn> conversation = conversations.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        List<ConversationTurn> history;
        synchronized (conversation) {
            history = new ArrayList<>(conversation);
        }

        String prompt = buildConversationPrompt(history, validInput);
        String markdown = llmService.chatWithSystemPrompt(
                LearningPromptFactory.systemPrompt(type),
                prompt,
                model,
                maxOutputTokens,
                0.3
        );
        if (markdown == null || markdown.isBlank()) {
            throw new IOException("大模型未返回学习内容");
        }

        int contextTurns;
        synchronized (conversation) {
            conversation.addLast(new ConversationTurn(validInput, markdown.trim()));
            while (conversation.size() > maxContextTurns) {
                conversation.removeFirst();
            }
            contextTurns = conversation.size();
        }

        return new LearningContentResult(validSessionId, type, markdown.trim(), contextTurns);
    }

    @Override
    public void clearContext(String sessionId, LearningTaskType type) {
        if (type == null) {
            throw new IllegalArgumentException("intent 不能为空");
        }
        conversations.remove(new ContextKey(validateSessionId(sessionId), type));
    }

    private String buildConversationPrompt(List<ConversationTurn> history, String currentInput) {
        if (history.isEmpty()) {
            return "当前用户请求：\n" + currentInput;
        }

        StringBuilder prompt = new StringBuilder("以下是同一任务的历史对话，仅用于理解本轮追问和修改要求：\n");
        int index = 1;
        for (ConversationTurn turn : history) {
            prompt.append("\n[第").append(index++).append("轮用户]\n")
                    .append(truncate(turn.userInput()))
                    .append("\n[第").append(index - 1).append("轮助手]\n")
                    .append(truncate(turn.assistantMarkdown()))
                    .append('\n');
        }
        return prompt.append("\n[当前用户请求]\n").append(currentInput).toString();
    }

    private String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        String value = sessionId.trim();
        if (value.length() > MAX_SESSION_ID_LENGTH || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("sessionId 只能包含字母、数字、下划线和连字符，且不超过64个字符");
        }
        return value;
    }

    private String validateInput(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String value = userInput.trim();
        if (value.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("content 不能超过4000个字符");
        }
        return value;
    }

    private String truncate(String text) {
        if (text.length() <= MAX_HISTORY_ITEM_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_HISTORY_ITEM_LENGTH) + "\n（历史内容已截断）";
    }

    private record ContextKey(String sessionId, LearningTaskType type) {
    }

    private record ConversationTurn(String userInput, String assistantMarkdown) {
    }
}
