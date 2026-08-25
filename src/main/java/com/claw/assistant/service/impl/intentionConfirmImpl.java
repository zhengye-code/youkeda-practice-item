package com.claw.assistant.service.impl;

import com.claw.assistant.service.LlmService;
import com.claw.assistant.service.intentionConfirm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class intentionConfirmImpl implements intentionConfirm {

    private static final Logger logger = LoggerFactory.getLogger(intentionConfirmImpl.class);

    @Autowired
    private LlmService llmService;

    @Value("${aliyun.dashscope.classify-model}")
    private String classifyModel;

    @Override
    public String confirm(String userInput) throws IOException {
        if (userInput == null || userInput.isBlank()) {
            return "{\"intentions\":[{\"type\":\"freeTalk\",\"skill\":\"\",\"parameters\":\"\"}]}";
        }

        String systemPrompt = """
                你是一个 AI 学习助手的意图识别器。请分析用户的输入内容，识别其学习意图，并严格按以下 JSON 格式标签返回结果：
                %s

                返回要求：
                1. 只返回符合该 JSON 格式的 JSON，不要输出任何解释、注释或多余内容。
                2. intentions 数组中可包含一个或多个意图对象，支持识别多个意图。
                3. 每个意图对象中的 type、skill、parameters 三项均为必填项；若没有可填内容，则填空白字符串。
                4. type 必须且只能是枚举类 intentions 中的枚举值之一：knowledge、schedule、analyzsis、freeTalk。
                """.formatted(jsonSchema);

        String aiJson = llmService.chatWithSystemPrompt(systemPrompt, userInput, classifyModel).trim();
        logger.info("AI 意图识别 JSON: {}", aiJson);
        return aiJson;
    }
}
