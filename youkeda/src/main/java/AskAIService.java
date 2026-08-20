import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.sdk.ILinkClient;

import config.Config;
import functionalCalling.ImageTool;
import functionalCalling.VoiceTool;
import functionalCalling.WeatherTool;
import kong.unirest.Unirest;

/**
 * 智谱 AI 文本问答服务：构造 chat/completions 请求体、发送请求、解析 OpenAI 兼容响应。
 * 支持 Function Calling：请求携带 tools，响应含 tool_calls 时执行工具并把结果返回给 AI 继续回答。
 * 接口地址、API Key、模型、超时等参数统一由 {@link Config} 从 config.properties 读取。
 */
public class AskAIService {
    private static final Logger log = LoggerFactory.getLogger(AskAIService.class);

    /** 调用智谱 chat/completions 对话接口，返回 AI 的完整回答。支持 AI 调用工具（查天气/生成图片/生成语音）。 */
    public static String askAI(ILinkClient client, String userId, String aiContext) {
        log.info("将发送以下消息给AI：\n" + aiContext);
        ObjectMapper mapper = new ObjectMapper();
        try {
            ArrayNode messages = mapper.createArrayNode();
            ObjectNode userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", Config.systemPrompt() + aiContext);
            messages.add(userMsg);

            // 最多两轮：第一轮可能返回 tool_calls（AI 要求调用工具），执行后第二轮返回最终回答
            for (int round = 0; round < 2; round++) {
                kong.unirest.HttpResponse<String> response = Unirest.post(Config.aiUrl())
                        .connectTimeout(Config.connectTimeoutMs())
                        .socketTimeout(Config.socketTimeoutMs())
                        .header("Authorization", "Bearer " + Config.aiToken())
                        .header("Content-Type", "application/json")
                        .body(buildBody(mapper, messages))
                        .asString();
                JsonNode root = mapper.readTree(response.getBody());
                // 检查智谱 API 返回的业务错误（如模型不存在、限流等）
                if (root.has("error")) {
                    log.error("智谱 API 返回错误，请求轮次={}: {}", round, root.path("error"));
                }
                JsonNode message = root.path("choices").path(0).path("message");
                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isArray() && toolCalls.size() > 0) {
                    // 追加 assistant 消息（含 tool_calls）
                    messages.add(message);
                    // 执行每个工具调用，把结果作为 tool 消息返回
                    for (JsonNode tc : toolCalls) {
                        String result = executeToolCall(client, userId, tc);
                        ObjectNode toolMsg = mapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", tc.path("id").asText());
                        toolMsg.put("content", result);
                        messages.add(toolMsg);
                    }
                    continue;
                }
                return message.path("content").asText("");
            }
        } catch (Exception e) {
            log.warn("调用 AI 失败: ", e);
        }
        return "";
    }

    /** 根据 tool_call 分发到对应工具执行，返回工具结果 JSON 字符串。 */
    private static String executeToolCall(ILinkClient client, String userId, JsonNode toolCall) {
        String name = toolCall.path("function").path("name").asText("");
        String args = toolCall.path("function").path("arguments").asText("{}");
        if (WeatherTool.NAME.equals(name)) {
            return WeatherTool.execute(args);
        }
        if (ImageTool.NAME.equals(name)) {
            return ImageTool.execute(client, userId, args);
        }
        if (VoiceTool.NAME.equals(name)) {
            return VoiceTool.execute(client, userId, args);
        }
        log.warn("AI 调用了未注册的工具: {}", name);
        return "{\"error\":\"unknown tool: " + name + "\"}";
    }

    /** 构造 chat/completions 请求体（JSON），携带 messages 与可调用的 tools。 */
    private static String buildBody(ObjectMapper mapper, ArrayNode messages) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", Config.aiModel());
        body.put("stream", false);
        body.put("temperature", 1);
        body.set("messages", messages);
        // 注册 AI 可调用的工具：查天气、生成图片、生成语音
        ArrayNode tools = mapper.createArrayNode();
        tools.add(mapper.readTree(WeatherTool.defineTool()));
        tools.add(mapper.readTree(ImageTool.defineTool()));
        tools.add(mapper.readTree(VoiceTool.defineTool()));
        body.set("tools", tools);
        log.info("注册AI调用的工具");
        return body.toString();
    }

    /** 解析 OpenAI 兼容的 chat/completions 响应，取 choices[0].message.content。 */
    public static String extractChatAnswer(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            if (root.has("error")) {
                log.error("AI 接口返回错误: {}", root.path("error"));
            }
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
            log.warn("解析 AI 回答失败，原始响应: {}", json, e);
            return "";
        }
    }
}
