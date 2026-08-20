package com.claw.assistant.service.impl;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.claw.assistant.service.TtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

@Service
public class TtsServiceImpl implements TtsService {

    private static final Logger logger = LoggerFactory.getLogger(TtsServiceImpl.class);

    @Value("${aliyun.dashscope.api-key}")
    private String apiKey;

    @Value("${aliyun.dashscope.tts-model}")
    private String ttsModel;

    @Value("${aliyun.dashscope.tts-voice}")
    private String ttsVoice;

    @Override
    public byte[] textToSpeech(String text) {
        logger.info("TTS 请求: model={}, voice={}, text长度={}", ttsModel, ttsVoice, text.length());

        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(ttsModel)
                    .voice(ttsVoice)
                    .build();
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
            ByteBuffer audio = synthesizer.call(text);
            synthesizer.getDuplexApi().close(1000, "bye");
            if (audio == null) {
                throw new RuntimeException("TTS 返回空音频");
            }
            byte[] audioBytes = new byte[audio.remaining()];
            audio.get(audioBytes);
            logger.info("TTS 生成成功: {} bytes", audioBytes.length);
            return audioBytes;
        } catch (Exception e) {
            logger.error("TTS 调用失败", e);
            throw new RuntimeException("TTS 调用失败", e);
        }
    }
}