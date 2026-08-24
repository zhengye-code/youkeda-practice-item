package com.claw.assistant.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 【修正版】RAG增强的LLM服务：修复了API返回格式异常导致的空指针问题
 */
@Service
public class RagEnhancedLlm {
    private static final Logger logger = LoggerFactory.getLogger(RagEnhancedLlm.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    @Value("${aliyun.dashscope.base-url}")
    private String baseUrl;
    @Value("${aliyun.dashscope.api-key}")
    private String apiKey;
    @Value("${aliyun.dashscope.model}")
    private String model;

    private final SimpleRagRetriever ragRetriever;

    public RagEnhancedLlm(SimpleRagRetriever ragRetriever) {
        this.ragRetriever = ragRetriever;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }


    public String chatWithRag(String userText) {
        try {
            List<String> contexts = ragRetriever.retrieve(userText, 3);
            String contextStr = String.join("\n", contexts);
            logger.info("RAG检索到{}条知识，准备调用LLM", contexts.size());

            String systemPrompt = "你是智能助手，请根据以下参考资料回答用户问题，不要编造信息。如果参考资料里没有相关内容，就直接说不知道。\n\n参考资料：\n" + contextStr;
            String userPrompt = userText;

            ObjectMapper mapper = new ObjectMapper();
            ArrayNode messages = mapper.createArrayNode();

            ObjectNode systemMsg = mapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);

            ObjectNode userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.set("messages", messages);

            RequestBody body = RequestBody.create(
                    mapper.writeValueAsString(requestBody),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "无响应体";
                    logger.error("百炼API请求失败，状态码：{}，响应：{}", response.code(), errorBody);
                    return "（RAG查询失败，API返回异常）";
                }
                String responseBody = response.body().string();
                logger.info("百炼API原始返回：{}", responseBody);

                JsonNode root = mapper.readTree(responseBody);
                JsonNode choicesNode = root.get("choices");
                if (choicesNode == null || !choicesNode.isArray() || choicesNode.size() == 0) {
                    return "（RAG查询失败，返回格式错误）";
                }
                return choicesNode.get(0).get("message").get("content").asText();
            }
        } catch (Exception e) {
            logger.error("RAG增强LLM调用失败", e);
            return "（RAG查询失败，稍后再试）";
        }
    }
}