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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过 OpenAI 兼容接口生成回复，当前支持 DeepSeek 和阿里云百炼。
 *
 * <p>API Key 只从环境变量读取，不写入文件，也不打印到日志。</p>
 */
public final class BailianLlmService {

    private static final String BAILIAN_DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String BAILIAN_DEFAULT_MODEL = "qwen3.6-flash";
    private static final String DEEPSEEK_DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_DEFAULT_MODEL = "deepseek-v4-flash";
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String SYSTEM_PROMPT = """
            你是一个接入微信的中文 AI 助手。请直接、友好、准确地回答用户问题。
            默认使用简洁中文；不知道的内容要明确说明，不要编造事实。
            """;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final String providerName;
    private final Map<String, Deque<ChatMessage>> histories = new ConcurrentHashMap<>();

    private BailianLlmService(String apiKey, String model, String baseUrl, String providerName) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = stripTrailingSlash(baseUrl) + "/chat/completions";
        this.providerName = providerName;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(90))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static BailianLlmService fromEnvironment() {
        String deepSeekApiKey = System.getenv("DEEPSEEK_API_KEY");
        if (deepSeekApiKey != null && !deepSeekApiKey.isBlank()) {
            return createService(
                    deepSeekApiKey,
                    environmentOrDefault("DEEPSEEK_MODEL", DEEPSEEK_DEFAULT_MODEL),
                    environmentOrDefault("DEEPSEEK_BASE_URL", DEEPSEEK_DEFAULT_BASE_URL),
                    "DeepSeek"
            );
        }

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未检测到 DEEPSEEK_API_KEY 或 DASHSCOPE_API_KEY，请先安全设置一个模型 API Key。"
            );
        }

        return createService(
                apiKey,
                environmentOrDefault("BAILIAN_MODEL", BAILIAN_DEFAULT_MODEL),
                environmentOrDefault("DASHSCOPE_BASE_URL", BAILIAN_DEFAULT_BASE_URL),
                "阿里云百炼"
        );
    }

    private static BailianLlmService createService(
            String apiKey,
            String model,
            String baseUrl,
            String providerName
    ) {
        String compactValue = apiKey.replaceAll("\\s+", "");
        int keyStart = compactValue.indexOf("sk-");
        String normalizedApiKey = keyStart >= 0 ? compactValue.substring(keyStart) : compactValue;
        if (!normalizedApiKey.startsWith("sk-")) {
            throw new IllegalStateException(providerName + " API Key 格式不正确，请重新复制完整 Key。");
        }
        return new BailianLlmService(normalizedApiKey, model, baseUrl, providerName);
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public String getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    public void validateConnection() throws IOException {
        String reply = requestCompletion(new ArrayDeque<>(), "请只回复 OK");
        if (reply.isBlank()) {
            throw new IOException(providerName + " 连接测试没有返回内容。");
        }
    }

    public String chat(String userId, String userText) throws IOException {
        if (userText == null || userText.isBlank()) {
            return "请发送一段文字，我来帮你处理。";
        }

        Deque<ChatMessage> history = histories.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            String reply = requestCompletion(history, userText.trim());
            history.addLast(new ChatMessage("user", userText.trim()));
            history.addLast(new ChatMessage("assistant", reply));
            trimHistory(history);
            return reply;
        }
    }

    private String requestCompletion(Deque<ChatMessage> history, String userText) throws IOException {
        ObjectNode requestJson = objectMapper.createObjectNode();
        requestJson.put("model", model);

        ArrayNode messages = requestJson.putArray("messages");
        addMessage(messages, "system", SYSTEM_PROMPT);
        for (ChatMessage message : history) {
            addMessage(messages, message.role(), message.content());
        }
        addMessage(messages, "user", userText);

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(requestJson), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException(
                        providerName + " 接口返回 HTTP " + response.code() + "：" + abbreviate(responseBody, 300)
                );
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IOException(providerName + " 接口没有返回可用文本。响应摘要：" + abbreviate(responseBody, 300));
            }
            return content.asText().trim();
        }
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.put("content", content);
    }

    private static void trimHistory(Deque<ChatMessage> history) {
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.removeFirst();
        }
    }

    private static String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record ChatMessage(String role, String content) {
    }
}
