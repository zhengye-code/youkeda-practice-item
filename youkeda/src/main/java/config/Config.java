package config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 应用配置：启动时从 classpath 加载 config.properties，集中管理智谱 AI 相关参数。
 * 使用方式：Config.aiUrl()、Config.aiModel()、Config.socketTimeoutMs() 等。
 * 若配置文件缺失或字段缺失，则使用代码内默认值。
 */
public class Config {
    private static final String PROPERTIES_FILE = "config.properties";
    private static final Properties props = new Properties();

    static {
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (in != null) {
                props.load(in);
            } else {
                System.err.println("未找到 " + PROPERTIES_FILE + "，将使用默认值");
            }
        } catch (IOException e) {
            System.err.println("加载 " + PROPERTIES_FILE + " 失败: " + e.getMessage());
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
            return def;
        }
    }
}

