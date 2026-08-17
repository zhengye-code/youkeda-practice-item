import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.Config;
import kong.unirest.Unirest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * 图片服务：集中处理所有图片相关功能——
 * 下载（SDK MediaService）、识别（智谱 GLM-4V）、生成（智谱 CogView）、发送（SDK sendImage），
 * 以及图片消息判断与 AI 回复中的图片标记解析。
 */
public class ImageService {
    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    // ==================== 下载 ====================

    /** 使用 SDK 下载消息项中的图片（内部经 MediaService 下载并 AES 解密）。 */
    public static byte[] downloadImageFromMessageItem(ILinkClient client, MessageItem item) throws Exception {
        return client.downloadImageFromMessageItem(item);
    }

    /** 从 URL 下载图片字节（用于将 AI 生成的图片发送给用户）。 */
    public static byte[] downloadImageFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return new byte[0];
        }
        return Unirest.get(url).asBytes().getBody();
    }

    // ==================== 识别 ====================

    /** 识别图片内容：base64 编码后调用智谱视觉模型 GLM-4V，返回图片描述；失败时返回空字符串。 */
    public static String recognizeImage(byte[] imageBytes, String mime) {
        if (imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String body = """
                {
                  "model": "glm-4v-flash",
                  "messages": [
                    {
                      "role": "user",
                      "content": [
                        { "type": "text", "text": "请识别这张图片，并简要描述其中的内容。" },
                        { "type": "image_url", "image_url": { "url": "data:%s;base64,%s" } }
                      ]
                    }
                  ],
                  "temperature": 0.2
                }
                """.formatted(mime, base64);
        kong.unirest.HttpResponse<String> response = Unirest.post(Config.visionUrl())
                .connectTimeout(Config.connectTimeoutMs())
                .socketTimeout(Config.socketTimeoutMs())
                .header("Authorization", "Bearer " + Config.aiToken())
                .header("Content-Type", "application/json")
                .body(body)
                .asString();
        return extractOpenAiAnswer(response.getBody());
    }

    /** 解析 OpenAI 兼容的 chat/completions 响应，取 choices[0].message.content。 */
    public static String extractOpenAiAnswer(String json) {
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
            log.warn("解析 OpenAI 响应失败: {}", e.getMessage());
            return "";
        }
    }

    // ==================== 生成 ====================

    /** 文生图：根据提示词生成图片，返回生成的图片 URL；失败时返回空字符串。 */
    public static String generateImage(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.createObjectNode();
            body.put("model", "cogview-3-flash");
            body.put("prompt", prompt.trim());
            kong.unirest.HttpResponse<String> response = Unirest.post(Config.imageGenUrl())
                    .connectTimeout(Config.connectTimeoutMs())
                    .socketTimeout(Config.socketTimeoutMs())
                    .header("Authorization", "Bearer " + Config.aiToken())
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .asString();
            JsonNode root = mapper.readTree(response.getBody());
            return root.path("data").path(0).path("url").asText("");
        } catch (Exception e) {
            log.warn("AI 生成图片失败: {}", e.getMessage());
            return "";
        }
    }

    // ==================== 发送 ====================

    /** 使用 SDK 发送图片消息给用户。 */
    public static void sendImage(ILinkClient client, String userId, byte[] imageBytes, String caption)
            throws Exception {
        client.sendImage(userId, imageBytes, "ai.png", caption);
    }

    // ==================== 判断与标记解析 ====================

    /** 判断消息是否包含图片。 */
    public static boolean hasImage(WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) {
            return false;
        }
        for (MessageItem item : msg.getItem_list()) {
            if (item != null && item.getImage_item() != null && item.getImage_item().getMedia() != null) {
                return true;
            }
        }
        return false;
    }

    /** 根据图片 URL 后缀猜测 MIME 类型，默认 image/png。 */
    public static String guessImageMime(String url) {
        if (url == null) {
            return "image/png";
        }
        String lower = url.toLowerCase();
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return "image/jpeg";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".bmp")) return "image/bmp";
        return "image/png";
    }

    /** 从 AI 回答中提取 [图片]xxx 的图片提示词；没有则返回 null。 */
    public static String extractImagePrompt(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\[图片\\]\\s*([^\\n\\[\\]]+)").matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 移除 AI 回答中的 [图片]xxx 标记，返回纯文本部分。 */
    public static String cleanImageMarker(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\[图片\\]\\s*[^\\n\\[\\]]*", "").trim();
    }
}
