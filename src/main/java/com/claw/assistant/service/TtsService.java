package com.claw.assistant.service;

import java.io.IOException;

public interface TtsService {
    byte[] textToSpeech(String text) throws IOException;
}
