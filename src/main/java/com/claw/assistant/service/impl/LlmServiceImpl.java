package com.claw.assistant.service.impl;

import org.json.JSONObject;
import org.json.JSONArray;
import com.claw.assistant.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class LlmServiceImpl implements LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmServiceImpl.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    @Value("${aliyun.dashscope.api-key}")
    private String apiKey;
    @Value("${aliyun.dashscope.base-url}")
    private String baseUrl;
    @Value("${aliyun.dashscope.model}")
    private String model;
    @Value("${aliyun.dashscope.vl-model}")
    private String vlModel;

    public String describeImage(byte[] imageBytes) {
        try {
            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);

            Map<String, Object> body = Map.of("model", vlModel, "messages",
                    List.of(Map.of("role", "user", "content", List.of(
                                    Map.of("type", "text", "text", "以幽默风趣的语言谈论图片"),
                                            Map.of("type", "image_url", "image_url",
                                                    Map.of("url", "data:image/jpeg;base64," + base64Image)
                                            )
                                    )
                            )
                    ), "stream", false
            );

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("VL调用状态码: {}", response.statusCode());

            if (response.statusCode() != 200) {
                logger.error("VL 调用失败: {}", response.body());
                return "（AI看图失败，状态码: " + response.statusCode() + "）";
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("（AI看图失败）");

        } catch (Exception e) {
            logger.error("调用视觉模型失败", e);
            return "（看图功能暂时不可用）";
        }
    }

    @Override
    public String chatWithSystemPrompt(String systemPrompt, String userMessage, String model) throws IOException {
        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.put(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.put(userMsg);

        JSONObject requestBodyJson = new JSONObject();
        requestBodyJson.put("model", model);
        requestBodyJson.put("messages", messages);

        JSONObject parameters = new JSONObject();
        parameters.put("max_tokens", 10);
        parameters.put("temperature", 0.0);
        requestBodyJson.put("parameters", parameters);
        String requestBody = requestBodyJson.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return extractContent(response.body());
            }
            throw new IOException("LLM 调用失败: " + response.statusCode() + ", body: " + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONArray choices = root.getJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject firstChoice = choices.getJSONObject(0);
                JSONObject message = firstChoice.getJSONObject("message");
                if (message != null) {
                    return message.getString("content").trim();
                }
            }
            throw new IOException("解析 LLM 响应失败：未找到 choices[0].message.content");
        } catch (Exception e) {
            throw new RuntimeException("解析 LLM 响应失败，原始响应: " + responseBody, e);
        }
    }

    @Override
    public String chatWithWeatherContext(String userMessage, String weatherData) throws IOException {
        String systemPrompt = """
        你是一个天气助手。以下是用户查询城市的实时天气数据：
        %s
        
        请根据以上天气数据，用自然、友好的语言回答用户的问题。如果天气数据中没有用户问的信息，就如实告知。
        回答要简洁幽默不单调,不使用emoji表情,像个可爱风趣的助手。
        """.formatted(weatherData);

        return chatWithSystemPrompt(systemPrompt, userMessage, model);
    }

    @Override
    public String chat(String message) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "简洁幽默"),
                            Map.of("role", "user", "content", message)
                    ),
                    "stream", false
            );
            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("LLM 调用状态码: {}", response.statusCode());

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("（模型没有返回内容）");

        } catch (Exception e) {
            logger.error("调用百炼大模型失败", e);
            return "（AI暂时不在，稍后再试）";
        }
    }


}
