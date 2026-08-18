package com.youkeda.practice.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微信扫码登录，接收文字、图片和语音消息，并通过配置的大模型生成回复。
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
        Optional<MultimodalLlmService> multimodalService =
                MultimodalLlmService.fromEnvironment();
        Optional<WeatherService> weatherService = WeatherService.fromEnvironment();
        System.out.println("正在验证 " + llmService.getProviderName()
                + " 连接，模型=" + llmService.getModel() + "……");
        llmService.validateConnection();
        System.out.println(llmService.getProviderName() + " 连接测试通过（API Key 仅从环境变量读取）。");
        if (multimodalService.isPresent()) {
            System.out.println("图片理解已启用，视觉模型="
                    + multimodalService.get().getModel() + "。");
        } else {
            System.out.println("图片理解未配置；图片接收、保存和原图回传仍可使用。");
        }
        System.out.println(weatherService.isPresent()
                ? "天气查询已启用（高德 Web 服务 Key 仅从环境变量读取）。"
                : "天气查询未配置；设置 AMAP_WEATHER_API_KEY 后即可启用。");

        try (ILinkClient client = ILinkClient.builder().build()) {
            String qrCodeContent = client.executeLogin();
            Path qrCodePath = writeQrCode(qrCodeContent);

            System.out.println("请使用微信扫描登录二维码：");
            System.out.println(qrCodePath);
            System.out.println("二维码内容（仅供排查）：" + qrCodeContent);

            LoginContext loginContext = client.getLoginFuture().get(3, TimeUnit.MINUTES);
            System.out.println("登录成功，botId = " + loginContext.getBotId());
            System.out.println("现在请使用刚才扫码的同一个微信账号，向新出现的机器人会话发送文字、图片或语音消息。");

            while (!Thread.currentThread().isInterrupted()) {
                List<WeixinMessage> messages = client.getUpdates();
                if (messages.isEmpty()) {
                    Thread.sleep(500L);
                    continue;
                }

                for (WeixinMessage message : messages) {
                    handleMessage(client, llmService, multimodalService, weatherService, message);
                }
            }
        }
    }

    private static void handleMessage(
            ILinkClient client,
            BailianLlmService llmService,
            Optional<MultimodalLlmService> multimodalService,
            Optional<WeatherService> weatherService,
            WeixinMessage message
    ) {
        String fromUserId = message.getFrom_user_id();
        if (fromUserId == null || message.getItem_list() == null) {
            return;
        }

        for (MessageItem item : message.getItem_list()) {
            if (item.getImage_item() != null) {
                handleImage(client, multimodalService, message, item, fromUserId);
            }

            if (item.getVoice_item() != null) {
                handleVoice(client, llmService, weatherService, message, item, fromUserId);
            }

            if (item.getText_item() != null && item.getText_item().getText() != null) {
                handleText(client, llmService, weatherService,
                        fromUserId, item.getText_item().getText());
            }
        }
    }

    private static void handleText(
            ILinkClient client,
            BailianLlmService llmService,
            Optional<WeatherService> weatherService,
            String fromUserId,
            String incomingText
    ) {
        System.out.println("收到文字消息：userId=" + fromUserId + "，text=" + incomingText);

        try {
            String reply = generateReply(
                    llmService, weatherService, fromUserId, incomingText, false);
            client.sendText(fromUserId, reply);
            System.out.println("大模型回复已发送，字符数=" + reply.length());
        } catch (IOException | RuntimeException exception) {
            System.err.println("生成或发送回复失败：" + exception.getMessage());
            sendFallbackText(client, fromUserId, "抱歉，AI 回复暂时失败，请稍后再试。");
        }
    }

    private static void handleImage(
            ILinkClient client,
            Optional<MultimodalLlmService> multimodalService,
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
            Path savedImage = saveIncomingMedia(message, imageBytes, "image", extension);
            String fileName = savedImage.getFileName().toString();

            client.sendImage(
                    fromUserId,
                    imageBytes,
                    fileName,
                    "图片收发测试成功：已在本地保存并原图回传。"
            );
            System.out.println("图片已保存并回传：path=" + savedImage
                    + "，bytes=" + imageBytes.length);

            if (multimodalService.isEmpty()) {
                client.sendText(fromUserId,
                        "图片已成功接收和回传；配置视觉模型后，我还可以理解图片内容。");
                return;
            }

            try {
                String description = multimodalService.get().describeImage(
                        imageBytes,
                        imageMediaType(extension),
                        "请用简洁中文描述这张图片，并回答图片中最可能需要关注的问题。"
                );
                client.sendText(fromUserId, "图片理解结果：\n" + description);
                System.out.println("图片理解结果已发送，字符数=" + description.length());
            } catch (IOException | RuntimeException visionException) {
                System.err.println("图片已保存并回传，但视觉模型调用失败："
                        + visionException.getMessage());
                sendFallbackText(client, fromUserId,
                        "图片已保存并回传，但图片理解暂时失败，请检查视觉模型配置。");
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("下载、保存或回传图片失败：" + exception.getMessage());
            sendFallbackText(client, fromUserId, "图片已收到，但下载或回传失败，请稍后再试。");
        }
    }

    private static void handleVoice(
            ILinkClient client,
            BailianLlmService llmService,
            Optional<WeatherService> weatherService,
            WeixinMessage message,
            MessageItem item,
            String fromUserId
    ) {
        VoiceItem voiceItem = item.getVoice_item();
        System.out.println("收到语音消息：userId=" + fromUserId
                + "，messageId=" + message.getMessage_id()
                + "，playtimeMs=" + voiceItem.getPlaytime());

        try {
            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
            if (voiceBytes.length == 0) {
                throw new IOException("下载到的语音内容为空");
            }

            Path savedVoice = saveIncomingMedia(message, voiceBytes, "voice", ".silk");
            client.sendVoice(
                    fromUserId,
                    voiceBytes,
                    savedVoice.getFileName().toString(),
                    valueOrDefault(voiceItem.getPlaytime(), 0),
                    valueOrDefault(voiceItem.getSample_rate(), 16000)
            );
            System.out.println("语音已保存并回传：path=" + savedVoice
                    + "，bytes=" + voiceBytes.length);

            String transcript = voiceItem.getText();
            if (transcript == null || transcript.isBlank()) {
                client.sendText(fromUserId,
                        "语音收发测试成功，但这条消息没有携带可用的语音转写文本。原语音已保存并回传。");
                return;
            }

            String normalizedTranscript = transcript.trim();
            String reply = generateReply(
                    llmService, weatherService, fromUserId, normalizedTranscript, true);
            client.sendText(fromUserId,
                    "语音识别：" + normalizedTranscript + "\n\nAI 回复：" + reply);
            System.out.println("语音转写及 AI 回复已发送，转写字符数="
                    + normalizedTranscript.length());
        } catch (IOException | RuntimeException exception) {
            System.err.println("下载、保存或处理语音失败：" + exception.getMessage());
            sendFallbackText(client, fromUserId, "语音已收到，但处理失败，请稍后再试。");
        }
    }

    private static String generateReply(
            BailianLlmService llmService,
            Optional<WeatherService> weatherService,
            String userId,
            String userText,
            boolean fromVoice
    ) throws IOException {
        String defaultCity = System.getenv("WEATHER_DEFAULT_CITY");
        Optional<WeatherIntentRecognizer.WeatherIntent> intent =
                WeatherIntentRecognizer.recognize(userText, defaultCity);
        if (intent.isPresent()) {
            WeatherIntentRecognizer.WeatherIntent weatherIntent = intent.get();
            if (!weatherIntent.hasCity()) {
                return "你想查询哪个城市的天气？例如：北京今天天气怎么样？";
            }
            if (weatherService.isEmpty()) {
                return "我识别到你在查询天气，但天气 API 还没有配置。请先设置 AMAP_WEATHER_API_KEY。";
            }
            System.out.println("识别到天气意图：city=" + weatherIntent.city()
                    + "，dayOffset=" + weatherIntent.dayOffset());
            return weatherService.get().query(weatherIntent.city(), weatherIntent.dayOffset());
        }

        return fromVoice
                ? llmService.chatFromVoice(userId, userText)
                : llmService.chat(userId, userText);
    }

    private static Path saveIncomingMedia(
            WeixinMessage message,
            byte[] mediaBytes,
            String prefix,
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
        Path output = directory.resolve(prefix + "-" + messageId + "-"
                + UUID.randomUUID().toString().substring(0, 8) + extension);
        Files.write(output, mediaBytes);
        return output;
    }

    private static String imageMediaType(String extension) {
        return switch (extension) {
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".heic" -> "image/heic";
            default -> "image/jpeg";
        };
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
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
