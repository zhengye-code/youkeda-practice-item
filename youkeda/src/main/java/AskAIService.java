import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.Config;
import kong.unirest.Unirest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智谱 AI 文本问答服务：构造 chat/completions 请求体、发送请求、解析 OpenAI 兼容响应并提取完整回答。
 * 接口地址、API Key、模型、超时等参数统一由 {@link Config} 从 config.properties 读取。
 */
public class AskAIService {
    private static final Logger log = LoggerFactory.getLogger(AskAIService.class);

    /** 调用智谱 chat/completions 对话接口，返回 AI 的完整回答。 */
    public static String askAI(String aiContext) {
        log.info("将发送以下消息给AI：\n" + aiContext);
        kong.unirest.HttpResponse<String> response = Unirest.post(Config.aiUrl())
                .connectTimeout(Config.connectTimeoutMs())
                .socketTimeout(Config.socketTimeoutMs())
                .header("Authorization", "Bearer " + Config.aiToken())
                .header("Content-Type", "application/json")
                .body(buildBody(aiContext))
                .asString();
        return extractChatAnswer(response.getBody());
    }

    /** 构造 chat/completions 请求体（JSON）。 */
    private static String buildBody(String aiContext) {
        return """
                {
                  "model": "%s",
                  "stream": false,
                  "temperature": 1,
                  "messages": [
                    {
                      "role": "user",
                      "content": "%s"
                    }
                  ]
                }
                """.formatted(Config.aiModel(), aiContext);
    }

    /** 解析 OpenAI 兼容的 chat/completions 响应，取 choices[0].message.content。 */
    public static String extractChatAnswer(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode c : content) {
                    sb.append(c.path("text").asText(""));
                }
                return sb.toString();
            }
            return "";
        } catch (Exception e) {
            log.warn("解析 AI 回答失败: {}", e.getMessage());
            return "";
        }
    }
}
