package com.claw.assistant.service.impl;

import com.claw.assistant.model.IntentType;
import com.claw.assistant.service.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntentRecognizer {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognizer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    @Value("${aliyun.dashscope.classify-model}")
    private String model;

    public IntentType recognize(String text) {
        if (text == null || text.isBlank()) {
            return IntentType.CHAT;
        }

        String systemPrompt = """
            你是一个意图分类器。根据用户消息，判断用户是否想要查询天气。
            只输出一个单词：WEATHER 或 CHAT。
            - 如果用户明确询问天气、气温、温度、是否下雨等，输出 WEATHER
            - 其他所有情况，输出 CHAT
            
            只输出单词，不要任何解释、标点或换行。
            """;

        try {

            String result = llmService.chatWithSystemPrompt(systemPrompt, text,model).trim();
            result = result.replaceAll("```json", "").replaceAll("```", "").trim().toUpperCase();
            logger.info("意图分类结果: [{}] (原文: {})", result, text);

            if ("WEATHER".equals(result)) {
                return IntentType.WEATHER;
            }
            return IntentType.CHAT;

        } catch (Exception e) {
            logger.error("意图分类失败，降级 CHAT", e);
            return IntentType.CHAT;
        }
    }
}