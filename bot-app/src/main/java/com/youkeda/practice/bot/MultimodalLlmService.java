package com.youkeda.practice.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 调用 OpenAI 兼容的视觉模型理解图片。
 *
 * <p>视觉模型与文字模型解耦：没有配置视觉模型时，文字和微信媒体收发仍可正常工作。</p>
 */
public final class MultimodalLlmService {

    private static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen3-vl-flash";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String endpoint;

    private MultimodalLlmService(String apiKey, String model, String baseUrl) {
        this.apiKey = normalizeApiKey(apiKey);
        this.model = model;
        this.endpoint = stripTrailingSlash(baseUrl) + "/chat/completions";
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(60))
                .build();
    }

    public static Optional<MultimodalLlmService> fromEnvironment() {
        String apiKey = firstNonBlank(
                System.getenv("VISION_API_KEY"),
                System.getenv("DASHSCOPE_API_KEY")
        );
        if (apiKey == null) {
            return Optional.empty();
        }

        String baseUrl = firstNonBlank(
                System.getenv("VISION_BASE_URL"),
                System.getenv("DASHSCOPE_BASE_URL"),
                DEFAULT_BASE_URL
        );
        String model = firstNonBlank(System.getenv("VISION_MODEL"), DEFAULT_MODEL);
        return Optional.of(new MultimodalLlmService(apiKey, model, baseUrl));
    }

    public String getModel() {
        return model;
    }

    public String describeImage(byte[] imageBytes, String mediaType, String prompt) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IOException("待识别的图片内容为空");
        }

        String dataUrl = "data:" + mediaType + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);

        ObjectNode requestJson = objectMapper.createObjectNode();
        requestJson.put("model", model);
        ArrayNode messages = requestJson.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");

        ObjectNode imageContent = content.addObject();
        imageContent.put("type", "image_url");
        imageContent.putObject("image_url").put("url", dataUrl);

        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", prompt == null || prompt.isBlank()
                ? "请用简洁中文描述这张图片，并指出你能确定的关键信息。"
                : prompt.trim());

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(requestJson), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("视觉模型接口返回 HTTP " + response.code()
                        + "：" + abbreviate(responseBody, 300));
            }

            JsonNode contentNode = objectMapper.readTree(responseBody)
                    .path("choices").path(0).path("message").path("content");
            String result = extractText(contentNode);
            if (result.isBlank()) {
                throw new IOException("视觉模型没有返回可用文本。响应摘要："
                        + abbreviate(responseBody, 300));
            }
            return result;
        }
    }

    private static String extractText(JsonNode contentNode) {
        if (contentNode.isTextual()) {
            return contentNode.asText().trim();
        }
        if (!contentNode.isArray()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode item : contentNode) {
            JsonNode text = item.path("text");
            if (text.isTextual() && !text.asText().isBlank()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(text.asText().trim());
            }
        }
        return result.toString();
    }

    private static String normalizeApiKey(String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.replaceAll("\\s+", "");
        int keyStart = normalized.indexOf("sk-");
        if (keyStart >= 0) {
            normalized = normalized.substring(keyStart);
        }
        if (normalized.isBlank()) {
            throw new IllegalStateException("视觉模型 API Key 为空");
        }
        return normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "…";
    }
}
