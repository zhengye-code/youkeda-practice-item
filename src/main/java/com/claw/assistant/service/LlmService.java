package com.claw.assistant.service;

import java.io.IOException;

public interface LlmService {
    String chatWithTools(String message);

    String chatWithWeatherContext(String userMessage, String weatherData) throws IOException;

    String chat(String message);

    String describeImage(byte[] imageBytes);

    String chatWithSystemPrompt(String systemPrompt, String userMessage, String model) throws IOException;

    default String chatWithSystemPrompt(
            String systemPrompt,
            String userMessage,
            String model,
            int maxTokens,
            double temperature
    ) throws IOException {
        return chatWithSystemPrompt(systemPrompt, userMessage, model);
    }
}
