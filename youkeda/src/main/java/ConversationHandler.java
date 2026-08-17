import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话处理器：维护每个用户的多轮对话历史，收到消息后基于该用户完整历史调用 AI 并回复，
 * 同时把 AI 回答也追加进历史，形成闭环多轮对话。
 */
public class ConversationHandler {
    private static final Logger log = LoggerFactory.getLogger(ConversationHandler.class);
    /** 每个用户的多轮对话历史，key=userId，value=该用户与 bot 的完整消息列表。 */
    private final Map<String, List<WeixinMessage>> chatHistories = new HashMap<>();
    /** 单用户对话历史上限，防止上下文无限增长。 */
    private static final int MAX_HISTORY = 20;

    /**
     * 处理一批来自用户的入站消息：过滤 bot 自身消息后，按用户把新消息追加到多轮对话历史，
     * 调用 ContextAnalyzer 解析该用户完整历史交给 AI 生成回答；回复后再把 AI 回答也追加进历史。
     *
     * @param client   ILinkClient，用于获取登录上下文与发送回复
     * @param messages 本次心跳拉取到的一批消息
     */
    public void handleIncomingMessage(ILinkClient client, List<WeixinMessage> messages) throws Exception {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        LoginContext ctx = client.getLoginContext();
        if (ctx == null) {
            return;
        }
        // 待触发 AI 的用户消息；bot 发出的图片消息只识别并入历史，不触发对话
        List<WeixinMessage> incoming = new ArrayList<>();
        for (WeixinMessage msg : messages) {
            if (msg == null || msg.getFrom_user_id() == null) {
                continue;
            }
            if (ctx.getBotId() != null && ctx.getBotId().equals(msg.getFrom_user_id())) {
                // AI/bot 发出的消息：若含图片，则使用 ImageService 识别并记入对应用户历史
                if (msg.getTo_user_id() != null && ImageService.hasImage(msg)) {
                    try {
                        describeBotImage(client, msg);
                        List<WeixinMessage> h = chatHistories.computeIfAbsent(
                                msg.getTo_user_id(), k -> new ArrayList<>());
                        h.add(msg);
                        trimHistory(h);
                    } catch (Exception ex) {
                        // 单条 bot 图片消息处理失败不中断整批消息
                        log.warn("处理 bot 图片消息失败: ", ex);
                    }
                }
                continue;
            }
            // 用户图片消息：使用 SDK 下载图片并让 AI 识别，把识别描述并入上下文
            try {
                enrichImageMessage(client, msg);
            } catch (Exception ex) {
                // 图片下载/识别异常已被内部隔离，此处兜底：不中断该消息进入 AI 流程
                log.warn("识别用户图片失败: ", ex);
            }
            incoming.add(msg);
        }
        if (incoming.isEmpty()) {
            return;
        }
        // 按用户分组处理：基于该用户完整历史调用 AI，并把 AI 回答（含可能生成的图片）追加进历史
        Map<String, List<WeixinMessage>> byUser = new LinkedHashMap<>();
        for (WeixinMessage msg : incoming) {
            byUser.computeIfAbsent(msg.getFrom_user_id(), k -> new ArrayList<>()).add(msg);
        }
        for (Map.Entry<String, List<WeixinMessage>> e : byUser.entrySet()) {
            String userId = e.getKey();
            List<WeixinMessage> history = chatHistories.computeIfAbsent(userId, k -> new ArrayList<>());
            history.addAll(e.getValue());
            trimHistory(history);

            // 解析该用户的完整对话历史作为 AI 上下文
            String aiContext = ContextAnalyzer.analyze(history);
            if (aiContext == null || aiContext.isEmpty()) {
                continue;
            }
            IO.println("收到用户 " + userId + " 的消息（历史共 " + history.size() + " 条），正在调用 AI ...");
            String aiReply = AskAIService.askAI(aiContext);
            if (aiReply == null || aiReply.isEmpty()) {
                aiReply = "抱歉，AI 暂时无法回复，请稍后再试。";
            }
            // 发送 AI 回复：文本直接发送；若含 [图片]xxx 标记，则生成图片发送给用户
            sendAIResponse(client, ctx, userId, aiReply, history);
            trimHistory(history);
        }
    }

    /** 将 AI 回复包装为一条 bot 发出的消息，追加进对话历史。 */
    private WeixinMessage buildBotMessage(LoginContext ctx, String userId, String text) {
        WeixinMessage botMsg = new WeixinMessage();
        botMsg.setFrom_user_id(ctx.getBotId());
        botMsg.setTo_user_id(userId);
        botMsg.setCreate_time_ms(System.currentTimeMillis());
        botMsg.setItem_list(Arrays.asList(MessageItem.text(text)));
        return botMsg;
    }

    /** 裁剪对话历史，只保留最近 MAX_HISTORY 条。 */
    private void trimHistory(List<WeixinMessage> history) {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    /**
     * 识别 AI/bot 发出的图片（使用 ImageService.recognizeImage）：
     * 用 SDK 下载图片，把识别描述作为文本项追加进该消息，
     * 使后续 AI 上下文包含 bot 曾发送的图片内容。
     */
    private void describeBotImage(ILinkClient client, WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) {
            return;
        }
        // 先收集识别描述，遍历结束后统一追加，避免迭代时修改列表触发 ConcurrentModificationException
        List<MessageItem> extraItems = new ArrayList<>();
        for (MessageItem item : msg.getItem_list()) {
            if (item == null || item.getImage_item() == null || item.getImage_item().getMedia() == null) {
                continue;
            }
            try {
                byte[] imageBytes = ImageService.downloadImageFromMessageItem(client, item);
                String mime = ImageService.guessImageMime(item.getImage_item().getUrl());
                String desc = ImageService.recognizeImage(imageBytes, mime);
                if (desc != null && !desc.isEmpty()) {
                    extraItems.add(MessageItem.text("[AI发送的图片内容] " + desc));
                }
            } catch (Exception e) {
                log.warn("识别 AI 发出的图片失败: {}", e.getMessage());
            }
        }
        msg.getItem_list().addAll(extraItems);
    }

    /**
     * 发送 AI 回复给用户：若回答含 [图片]提示词 标记，则调用文生图生成图片并用 SDK sendImage 发送；
     * 其余文本用 sendText 发送；两者都追加进对话历史。
     */
    private void sendAIResponse(ILinkClient client, LoginContext ctx, String userId, String aiReply,
            List<WeixinMessage> history) throws Exception {
        String imgPrompt = ImageService.extractImagePrompt(aiReply);
        String text = ImageService.cleanImageMarker(aiReply);
        if (imgPrompt != null) {
            try {
                String url = ImageService.generateImage(imgPrompt);
                byte[] imageBytes = ImageService.downloadImageFromUrl(url);
                if (imageBytes != null && imageBytes.length > 0) {
                    // ImageService：用 SDK 发送图片消息
                    ImageService.sendImage(client, userId, imageBytes, imgPrompt);
                    history.add(buildBotMessage(ctx, userId, "[图片] " + imgPrompt));
                    IO.println("已向用户 " + userId + " 发送 AI 生成的图片: " + imgPrompt);
                } else {
                    log.warn("AI 生成的图片为空，跳过");
                }
            } catch (Exception e) {
                log.warn("AI 生成图片失败: {}", e.getMessage());
                if (text.isEmpty()) {
                    text = "抱歉，图片生成失败：" + e.getMessage();
                }
            }
        }
        if (text != null && !text.isEmpty()) {
            client.sendText(userId, text);
            history.add(buildBotMessage(ctx, userId, text));
        }
    }

    /**
     * 使用 ImageService 处理消息中的图片：下载图片并识别，把识别结果追加进该消息。
     * 同时增加判断：若用户只发图片、不带文本（文本为空），则追加明确指令，
     * 引导 AI 基于图片内容回复，保证用户可单独发送一张图片。
     */
    private void enrichImageMessage(ILinkClient client, WeixinMessage msg) {
        if (msg == null || msg.getItem_list() == null) {
            return;
        }
        // 判断：消息是否已包含文本内容（非空）
        boolean hasText = false;
        for (MessageItem item : msg.getItem_list()) {
            if (item != null && item.getText_item() != null
                    && item.getText_item().getText() != null
                    && !item.getText_item().getText().trim().isEmpty()) {
                hasText = true;
                break;
            }
        }
        // 判断：消息是否包含图片
        boolean hasImage = false;
        // 先收集识别描述，遍历结束后统一追加，避免迭代时修改列表触发 ConcurrentModificationException
        List<MessageItem> extraItems = new ArrayList<>();
        for (MessageItem item : msg.getItem_list()) {
            if (item == null || item.getImage_item() == null || item.getImage_item().getMedia() == null) {
                continue;
            }
            hasImage = true;
            try {
                byte[] imageBytes = ImageService.downloadImageFromMessageItem(client, item);
                String mime = ImageService.guessImageMime(item.getImage_item().getUrl());
                String desc = ImageService.recognizeImage(imageBytes, mime);
                if (desc != null && !desc.isEmpty()) {
                    extraItems.add(MessageItem.text("[图片内容] " + desc));
                }
            } catch (Exception e) {
                // 单张图片下载/识别失败不中断，消息仍会进入 AI 流程（指令兜底）
                log.warn("图片下载/识别失败: ", e);
            }
        }
        // 遍历结束后统一追加识别描述
        msg.getItem_list().addAll(extraItems);
        // 判断：用户只发图片、文本为空时，追加指令引导 AI 基于图片回复
        if (hasImage && !hasText) {
            msg.getItem_list().add(
                    MessageItem.text("[用户单独发送了一张图片，请基于图片内容识别并回复用户]"));
        }
    }
}
