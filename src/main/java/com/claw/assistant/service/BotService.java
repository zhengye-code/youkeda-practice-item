package com.claw.assistant.service;


import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.IOException;
import java.util.List;

public interface BotService {
    void startBot();
    void doMessage(List<WeixinMessage> messages) throws IOException;
}
