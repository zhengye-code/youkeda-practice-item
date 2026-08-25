package com.claw.assistant.service.impl;

import com.claw.assistant.model.IntentType;
import com.claw.assistant.service.BotService;
import com.claw.assistant.service.LlmService;
import com.claw.assistant.service.TtsService;
import com.claw.assistant.service.WeatherService;
import com.claw.assistant.service.intentionConfirm;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class BotServiceImpl implements BotService {
    private static final Logger logger = LoggerFactory.getLogger(BotServiceImpl.class);
    private ILinkClient iLinkClient;
    @Autowired
    private LlmService llmService;
    @Autowired
    private WeatherService weatherService;
    @Autowired
    private intentionConfirm intentionConfirm;
    /*@Autowired
    private IntentRecognizer intentRecognizer;*/
    @Autowired
    private TtsService ttsService;

    @PostConstruct
    @Override
    public void startBot() {
        logger.info("Bot starting");
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .writeTimeoutMs(35000)
                .httpMaxRetries(3)
                .retryBaseDelayMs(10000)
                .retryMaxDelayMs(10000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .channelVersion("1.0.0")
                .build();
        iLinkClient = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext loginContext) {
                        logger.info("onLoginSuccess");
                    }
                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        logger.info("onLoginFailure");
                    }
                })
                .onMessage(new  OnMessageListener() {

                    @Override
                    public void onMessages(List<WeixinMessage> list) {
                        try {
                            BotServiceImpl.this.doMessage(list);
                        }catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }).build();
        new Thread(()->{
            try {
                String qrContent = iLinkClient.executeLogin();
                logger.info(qrContent);
                System.out.println(qrContent);
                LoginContext ctx = iLinkClient.getLoginFuture().get();
                logger.info("success");
                Thread.currentThread().join();
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        },"Bot").start();
    }

    @Override
    public void doMessage(List<WeixinMessage> messages) throws IOException {
        for (WeixinMessage message : messages) {
            String userId = message.getFrom_user_id();
            logger.info("userId = {},messageType={}",userId,message.getMessage_type());
            if (message.getItem_list() != null) {
                for (MessageItem item : message.getItem_list()) {
                    if (item.getText_item() != null) {
                        String text = item.getText_item().getText();
                        logger.info("文本: {}", text);
                        //TODO: 这里可以调用意图识别服务
                        //@param intentionJson 识别结果的JSON字符串
                        try {
                            String intentionJson = intentionConfirm.confirm(text);
                            logger.info("AI意图识别JSON: {}", intentionJson);
                        } catch (Exception e) {
                            logger.error("AI意图识别失败", e);
                        }
                        try {
                            String reply = llmService.chatWithTools(text);
                            iLinkClient.sendText(userId, reply);
                            logger.info("AI回复: {}", reply);
                        } catch (Exception e) {
                            logger.error("AI回复失败", e);
                            iLinkClient.sendText(userId, "抱歉，我暂时无法回复");
                        }
                    }else if (item.getImage_item() != null) {
                        logger.info("收到图片消息");
                        handleImageMessage(userId, item.getImage_item());
                    }else if (item.getVoice_item() != null) {
                        logger.info("收到语音消息");
                        handleVoiceMessage(userId, item.getVoice_item());
                    }
                }
            }
        }
    }

    private void handleVoiceMessage(String userId, VoiceItem voiceItem) {
        try {
            String asrText = voiceItem.getText();
            if (asrText == null || asrText.isBlank()) {
                iLinkClient.sendText(userId, "没听清你说什么");
                return;
            }
            logger.info("语音: {}", asrText);
            String replyText = llmService.chat(asrText);
            logger.info("字回复: {}", replyText);
            byte[] audioBytes = ttsService.textToSpeech(replyText);
            String fileName = "reply_" + System.currentTimeMillis() + ".mp3";
            iLinkClient.sendFile(userId, audioBytes, fileName, "AI 语音回复：" + replyText);
            logger.info("语音回复已降级为文件发送成功");

        } catch (Exception e) {
            logger.error("语音处理失败", e);
            try {
                iLinkClient.sendText(userId, "语音回复有问题");
            } catch (Exception ex) {
                logger.error("文字降级失败", ex);
            }
        }
    }

    private void handleImageMessage(String userId, ImageItem imageItem) {
        try {
            logger.info("收到图片消息");
            MessageItem messageItem = new MessageItem();
            messageItem.setImage_item(imageItem);
            byte[] imageBytes = iLinkClient.downloadImageFromMessageItem(messageItem);
            if (imageBytes == null || imageBytes.length == 0) {
                iLinkClient.sendText(userId, "图片下载失败");
                return;
            }
            logger.info("图片大小: {} bytes", imageBytes.length);
            String description = llmService.describeImage(imageBytes);
            logger.info("AI描述: {}", description);
            iLinkClient.sendText(userId, description);
        }catch (Exception e) {
            logger.error("处理图片失败", e);
            try {
                iLinkClient.sendText(userId, "图片处理出错了");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @PreDestroy
    public void stopBot() {
        if (iLinkClient != null) {
            iLinkClient.close();
            logger.info("Bot close");
        }
    }


}
