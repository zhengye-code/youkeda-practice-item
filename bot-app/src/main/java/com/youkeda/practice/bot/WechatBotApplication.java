package com.youkeda.practice.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微信扫码登录，接收文字和图片消息，并通过配置的大模型生成文字回复。
 */
public final class WechatBotApplication {

    private static final ZoneId CHINA_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_DIRECTORY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private WechatBotApplication() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("正在启动微信 iLink Bot……");
        BailianLlmService llmService = BailianLlmService.fromEnvironment();
        System.out.println("正在验证 " + llmService.getProviderName()
                + " 连接，模型=" + llmService.getModel() + "……");
        llmService.validateConnection();
        System.out.println(llmService.getProviderName() + " 连接测试通过（API Key 仅从环境变量读取）。");

        try (ILinkClient client = ILinkClient.builder().build()) {
            String qrCodeContent = client.executeLogin();
            Path qrCodePath = writeQrCode(qrCodeContent);

            System.out.println("请使用微信扫描登录二维码：");
            System.out.println(qrCodePath);
            System.out.println("二维码内容（仅供排查）：" + qrCodeContent);

            LoginContext loginContext = client.getLoginFuture().get(3, TimeUnit.MINUTES);
            System.out.println("登录成功，botId = " + loginContext.getBotId());
            System.out.println("现在请使用刚才扫码的同一个微信账号，向新出现的机器人会话发送文字或图片消息。");

            while (!Thread.currentThread().isInterrupted()) {
                List<WeixinMessage> messages = client.getUpdates();
                if (messages.isEmpty()) {
                    Thread.sleep(500L);
                    continue;
                }

                for (WeixinMessage message : messages) {
                    handleMessage(client, llmService, message);
                }
            }
        }
    }

    private static void handleMessage(
            ILinkClient client,
            BailianLlmService llmService,
            WeixinMessage message
    ) {
        String fromUserId = message.getFrom_user_id();
        if (fromUserId == null || message.getItem_list() == null) {
            return;
        }

        for (MessageItem item : message.getItem_list()) {
            if (item.getImage_item() != null) {
                handleImage(client, message, item, fromUserId);
            }

            if (item.getText_item() != null && item.getText_item().getText() != null) {
                handleText(client, llmService, fromUserId, item.getText_item().getText());
            }
        }
    }

    private static void handleText(
            ILinkClient client,
            BailianLlmService llmService,
            String fromUserId,
            String incomingText
    ) {
        System.out.println("收到文字消息：userId=" + fromUserId + "，text=" + incomingText);

        try {
            String reply = llmService.chat(fromUserId, incomingText);
            client.sendText(fromUserId, reply);
            System.out.println("大模型回复已发送，字符数=" + reply.length());
        } catch (IOException | RuntimeException exception) {
            System.err.println("生成或发送回复失败：" + exception.getMessage());
            sendFallbackText(client, fromUserId, "抱歉，AI 回复暂时失败，请稍后再试。");
        }
    }

    private static void handleImage(
            ILinkClient client,
            WeixinMessage message,
            MessageItem item,
            String fromUserId
    ) {
        System.out.println("收到图片消息：userId=" + fromUserId
                + "，messageId=" + message.getMessage_id());

        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            if (imageBytes.length == 0) {
                throw new IOException("下载到的图片内容为空");
            }

            String extension = detectImageExtension(imageBytes);
            Path savedImage = saveIncomingImage(message, imageBytes, extension);
            String fileName = savedImage.getFileName().toString();

            client.sendImage(
                    fromUserId,
                    imageBytes,
                    fileName,
                    "图片收发测试成功：已在本地保存并原图回传。"
            );
            System.out.println("图片已保存并回传：path=" + savedImage
                    + "，bytes=" + imageBytes.length);
        } catch (IOException | RuntimeException exception) {
            System.err.println("下载、保存或回传图片失败：" + exception.getMessage());
            sendFallbackText(client, fromUserId, "图片已收到，但下载或回传失败，请稍后再试。");
        }
    }

    private static Path saveIncomingImage(
            WeixinMessage message,
            byte[] imageBytes,
            String extension
    ) throws IOException {
        Instant messageTime = message.getCreate_time_ms() == null
                ? Instant.now()
                : Instant.ofEpochMilli(message.getCreate_time_ms());
        LocalDate date = messageTime.atZone(CHINA_TIME_ZONE).toLocalDate();

        Path directory = Paths.get(
                        "bot-app",
                        "inbox",
                        DATE_DIRECTORY_FORMAT.format(date)
                )
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(directory);

        String messageId = message.getMessage_id() == null
                ? UUID.randomUUID().toString()
                : String.valueOf(message.getMessage_id());
        Path output = directory.resolve("image-" + messageId + "-"
                + UUID.randomUUID().toString().substring(0, 8) + extension);
        Files.write(output, imageBytes);
        return output;
    }

    private static String detectImageExtension(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return ".jpg";
        }
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return ".png";
        }
        if (startsWith(bytes, 0x47, 0x49, 0x46, 0x38)) {
            return ".gif";
        }
        if (bytes.length >= 12
                && startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50) {
            return ".webp";
        }
        if (bytes.length >= 12
                && bytes[4] == 0x66
                && bytes[5] == 0x74
                && bytes[6] == 0x79
                && bytes[7] == 0x70) {
            return ".heic";
        }
        return ".img";
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static void sendFallbackText(ILinkClient client, String fromUserId, String text) {
        try {
            client.sendText(fromUserId, text);
        } catch (IOException | RuntimeException fallbackException) {
            System.err.println("发送失败提示也未成功：" + fallbackException.getMessage());
        }
    }

    private static Path writeQrCode(String content) throws Exception {
        Path output = Paths.get("bot-app", "target", "wechat-login-qr.png")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());

        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 360, 360);
        MatrixToImageWriter.writeToPath(matrix, "PNG", output);
        return output;
    }
}
