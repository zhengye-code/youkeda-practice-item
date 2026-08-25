package com.claw.assistant.learning;

import java.io.IOException;

public interface LearningContentService {
    LearningContentResult generate(String sessionId, LearningTaskType type, String userInput) throws IOException;

    void clearContext(String sessionId, LearningTaskType type);
}
