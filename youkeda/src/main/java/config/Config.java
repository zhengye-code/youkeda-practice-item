package config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用配置：启动时从 classpath 加载 config.properties，集中管理智谱 AI 相关参数。
 * 使用方式：Config.aiUrl()、Config.aiModel()、Config.socketTimeoutMs() 等。
 * 若配置文件缺失或字段缺失，则使用代码内默认值。
 */
public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);
    private static final String PROPERTIES_FILE = "config.properties";
    private static final Properties props = new Properties();

    static {
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (in != null) {
                props.load(in);
            } else {
                log.warn("未找到 {}，将使用默认值", PROPERTIES_FILE);
            }
        } catch (IOException e) {
            log.warn("加载 {} 失败: ", PROPERTIES_FILE, e);
        }
    }

    /** 智谱文本对话接口地址（chat/completions）。 */
    public static String aiUrl() {
        return get("ai.url", "https://open.bigmodel.cn/api/paas/v4/chat/completions");
    }

    /** 智谱 API Key。 */
    public static String aiToken() {
        return get("ai.token", "");
    }

    /** 文本对话模型名。 */
    public static String aiModel() {
        return get("ai.model", "glm-4-flash");
    }

    /** 连接超时（毫秒）。 */
    public static int connectTimeoutMs() {
        return getInt("ai.connectTimeoutMs", 15000);
    }

    /** 读取超时（毫秒）。 */
    public static int socketTimeoutMs() {
        return getInt("ai.socketTimeoutMs", 180000);
    }

    /** 图片视觉识别接口地址（GLM-4V）。 */
    public static String visionUrl() {
        return get("ai.vision.url", "https://open.bigmodel.cn/api/paas/v4/chat/completions");
    }

    /** 图片生成接口地址（CogView 文生图）。 */
    public static String imageGenUrl() {
        return get("ai.imageGen.url", "https://open.bigmodel.cn/api/paas/v4/images/generations");
    }

    /** 语音转文本接口地址（ASR）。 */
    public static String asrUrl() {
        return get("ai.asr.url", "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions");
    }

    /** 语音转文本模型。 */
    public static String asrModel() {
        return get("ai.asr.model", "glm-asr-2512");
    }

    /** 文本转语音接口地址（TTS）。 */
    public static String ttsUrl() {
        return get("ai.tts.url", "https://open.bigmodel.cn/api/paas/v4/audio/speech");
    }

    /** 文本转语音模型。 */
    public static String ttsModel() {
        return get("ai.tts.model", "glm-tts");
    }

    /** 文本转语音音色。 */
    public static String ttsVoice() {
        return get("ai.tts.voice", "tongtong");
    }

    /** 和风天气 API Host（专属域名）。 */
    public static String qweatherHost() {
        return get("qweather.host", "https://api.qweather.com");
    }

    /** 和风天气 JWT Token。 */
    public static String qweatherToken() {
        return get("qweather.token", "");
    }

    private static String get(String key, String def) {
        String v = props.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private static int getInt(String key, int def) {
        String v = props.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 不是合法整数: {}", key, v);
            return def;
        }
    }
}

