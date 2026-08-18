package functionalCalling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import services.VoiceService;

/**
 * 生成语音工具（Function Calling Tool）：AI 调用 generate_speech 把文本转成语音并直接发送给用户。
 * <p>底层复用 VoiceService（智谱 TTS + SDK sendVoice）。</p>
 */
public class VoiceTool {
    public static final String NAME = "generate_speech";
    private static final Logger log = LoggerFactory.getLogger(VoiceTool.class);

    /** 定义 generate_speech 工具的 JSON Schema，供 chat/completions 请求的 tools 字段使用。 */
    public static String defineTool() {
        return """
                {
                  "type": "function",
                  "function": {
                    "name": "generate_speech",
                    "description": "将文本转换为语音消息，并直接发送给用户",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "text": {
                          "type": "string",
                          "description": "要转换为语音的文本内容"
                        }
                      },
                      "required": ["text"]
                    }
                  }
                }
                """;
    }

    /** 执行工具：TTS 生成语音并通过 SDK 发送给用户，返回给 AI 的结果 JSON。 */
    public static String execute(ILinkClient client, String userId, String argumentsJson) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode args = mapper.readTree(argumentsJson);
            String text = args.path("text").asText("").trim();
            if (text.isEmpty()) {
                log.error("缺少语音文本参数");
                return "{\"error\":\"缺少语音文本参数 text\"}";
            }
            byte[] wav = VoiceService.synthesize(text);
            if (wav == null || wav.length == 0) {
                log.error("语音生成失败，请检查 TTS 配置与账号余额");
                return "{\"error\":\"语音生成失败，请检查 TTS 配置与账号余额\"}";
            }
            // 微信语音使用 SILK 编码（encodeType=6），需先将 TTS 的 wav 转码为 silk
            byte[] silk = VoiceService.wavToSilk(wav);
            if (silk == null || silk.length == 0) {
                log.error("SILK 转码失败，请安装 silk_v3_encoder 并加入 PATH");
                return "{\"error\":\"SILK 转码失败，请安装 silk_v3_encoder 并加入 PATH\"}";
            }
            // SDK：发送语音给用户（silk，时长与采样率由 VoiceService 解析）
            client.sendVoice(userId, silk, "reply.silk",
                    VoiceService.wavDurationMs(wav), VoiceService.wavSampleRate(wav));
            log.info("语音已生成并发送给用户");
            return "{\"success\":true,\"message\":\"语音已生成并发送给用户\"}";
        } catch (Exception e) {
            log.error("语音工具执行失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
