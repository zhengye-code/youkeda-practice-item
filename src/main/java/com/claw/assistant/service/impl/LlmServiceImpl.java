package com.claw.assistant.service.impl;

import com.claw.assistant.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
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
    @Value("${aliyun.dashscope.omni-model}")
    private String omniModel;


    public String describeImage(byte[] imageBytes) {
        try {
            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);

            Map<String, Object> body = Map.of("model", vlModel, "messages",
                    List.of(Map.of("role", "user", "content", List.of(
                                    Map.of("type", "text", "text", "简述图片"),
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
    public String chat(String message) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "简洁回复"),
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
