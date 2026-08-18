package functionalCalling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import services.ImageService;

/**
 * 生成图片工具（Function Calling Tool）：AI 调用 generate_image 生成图片并直接发送给用户。
 * <p>底层复用 ImageService（智谱 CogView 文生图 + SDK sendImage）。</p>
 */
public class ImageTool {
    public static final String NAME = "generate_image";
    private static final Logger log = LoggerFactory.getLogger(ImageTool.class);

    /** 定义 generate_image 工具的 JSON Schema，供 chat/completions 请求的 tools 字段使用。 */
    public static String defineTool() {
        return """
                {
                  "type": "function",
                  "function": {
                    "name": "generate_image",
                    "description": "根据文字描述生成一张图片，并直接发送给用户",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "prompt": {
                          "type": "string",
                          "description": "图片内容描述，例如：一只可爱的橘猫坐在沙发上"
                        }
                      },
                      "required": ["prompt"]
                    }
                  }
                }
                """;
    }

    /** 执行工具：生成图片并通过 SDK 发送给用户，返回给 AI 的结果 JSON。 */
    public static String execute(ILinkClient client, String userId, String argumentsJson) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode args = mapper.readTree(argumentsJson);
            String prompt = args.path("prompt").asText("").trim();
            if (prompt.isEmpty()) {
                log.warn("图片工具缺少 prompt 参数");
                return "{\"error\":\"缺少图片描述参数 prompt\"}";
            }
            String url = ImageService.generateImage(prompt);
            byte[] imageBytes = ImageService.downloadImageFromUrl(url);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("图片生成失败，URL={}，prompt={}", url, prompt);
                return "{\"error\":\"图片生成失败，请检查 CogView 配置与账号余额\"}";
            }
            // SDK：发送图片给用户
            ImageService.sendImage(client, userId, imageBytes, prompt);
            return "{\"success\":true,\"message\":\"图片已生成并发送给用户\"}";
        } catch (Exception e) {
            log.error("图片工具执行失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
