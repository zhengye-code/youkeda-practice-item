package com.claw.assistant.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;


@Component
public class SimpleRagRetriever {
    private static final Logger logger = LoggerFactory.getLogger(SimpleRagRetriever.class);
    private final Map<String, String> knowledgeBase = new HashMap<>();

    @PostConstruct
    public void init() {
        knowledgeBase.put("claw", "Claw助手是我们夏令营的项目，基于微信生态开发，全栈用Java21实现，支持天气查询、计算、图片识别、语音回复等功能。");
        knowledgeBase.put("夏令营", "本次夏令营的任务是开发Claw助手，要求实现多工具协作、Skill、RAG等功能，最终完成一个智能微信Bot。");
        knowledgeBase.put("天气工具", "天气工具调用高德Web API实现，支持查询国内所有城市的实时天气，需要先通过地理编码接口获取城市adcode，再查询天气。");
        knowledgeBase.put("工具", "现有工具包括：get_weather（查天气）、calculate（计算）、get_current_time（获取时间）、describe_image（图片识别）、text_to_speech（语音合成）。");
        logger.info("极简RAG知识库初始化完成，共{}条知识", knowledgeBase.size());
    }

    public List<String> retrieve(String userText, int topK) {
        List<String> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : knowledgeBase.entrySet()) {
            if (userText.contains(entry.getKey())) {
                results.add(entry.getValue());
                logger.info("RAG命中关键词：{}，返回知识片段", entry.getKey());
                if (results.size() >= topK) break;
            }
        }
        return results;
    }
}