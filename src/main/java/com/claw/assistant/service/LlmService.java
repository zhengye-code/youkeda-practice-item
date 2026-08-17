package com.claw.assistant.service;

public interface LlmService {
    String chat(String message);

    String describeImage(byte[] imageBytes);

}
