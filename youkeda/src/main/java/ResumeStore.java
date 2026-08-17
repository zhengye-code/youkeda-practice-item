import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK 恢复上下文（ResumeContext）的本地持久化：
 * 保存登录凭证、消息游标与会话 contextToken，供服务重启后通过 builder.resumeContext() 无缝恢复。
 */
public class ResumeStore {
    private static final Logger log = LoggerFactory.getLogger(ResumeStore.class);
    private static final String RESUME_FILE = "resume.json";

    /**
     * 使用 SDK 的 exportResumeContext() 导出当前登录态与会话上下文，并持久化到本地文件，
     * 供服务重启后通过 load() + builder.resumeContext() 无缝恢复。
     */
    public static void save(ILinkClient client) throws Exception {
        ResumeContext rc = client.exportResumeContext();
        if (rc == null || rc.getLoginContext() == null) {
            return;
        }
        LoginContext lc = rc.getLoginContext();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("botToken", lc.getBotToken());
        root.put("userId", lc.getUserId());
        root.put("botId", lc.getBotId());
        root.put("baseUrl", lc.getBaseUrl());
        if (rc.getUpdatesCursor() != null) {
            root.put("updatesCursor", rc.getUpdatesCursor());
        }
        ObjectNode convs = root.putObject("conversations");
        for (Map.Entry<String, ConversationContext> e : rc.getConversationContextMap().entrySet()) {
            ConversationContext cc = e.getValue();
            ObjectNode node = convs.putObject(e.getKey());
            if (cc.getLatestContextToken() != null) node.put("contextToken", cc.getLatestContextToken());
            if (cc.getTypingTicket() != null) node.put("typingTicket", cc.getTypingTicket());
            if (cc.getSourceMessageId() != null) node.put("sourceMessageId", cc.getSourceMessageId());
            if (cc.getSourceMessageTime() != null) node.put("sourceMessageTime", cc.getSourceMessageTime());
        }
        Files.write(Paths.get(RESUME_FILE), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        log.info("已保存 SDK 恢复上下文 -> {}", RESUME_FILE);
    }

    /** 从本地文件恢复 ResumeContext，交给 SDK 的 resumeContext() 恢复登录态与会话上下文。 */
    public static ResumeContext load() {
        File f = new File(RESUME_FILE);
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(f);
            String botToken = root.path("botToken").asText(null);
            if (botToken == null || botToken.isEmpty()) {
                return null;
            }
            LoginContext lc = new LoginContext(
                    botToken,
                    root.path("userId").asText(""),
                    root.path("botId").asText(""),
                    root.path("baseUrl").asText(""));
            String cursor = root.path("updatesCursor").asText(null);
            Map<String, ConversationContext> convs = new LinkedHashMap<>();
            JsonNode convNode = root.path("conversations");
            if (convNode != null && convNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = convNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> en = it.next();
                    String userId = en.getKey();
                    JsonNode v = en.getValue();
                    ConversationContext cc = new ConversationContext(new ContextKey(lc.getBotId(), userId));
                    String token = v.path("contextToken").asText(null);
                    if (token != null && !token.isEmpty()) {
                        Long srcId = v.path("sourceMessageId").isNumber() ? v.path("sourceMessageId").asLong() : null;
                        Long srcTime = v.path("sourceMessageTime").isNumber() ? v.path("sourceMessageTime").asLong() : null;
                        cc.updateContextToken(token, srcId, srcTime);
                    }
                    String typing = v.path("typingTicket").asText(null);
                    if (typing != null && !typing.isEmpty()) {
                        cc.setTypingTicket(typing);
                    }
                    convs.put(userId, cc);
                }
            }
            return ResumeContext.builder(lc).updatesCursor(cursor).conversationContexts(convs).build();
        } catch (Exception e) {
            log.warn("读取 SDK 恢复上下文失败: {}", e.getMessage());
            return null;
        }
    }
}
