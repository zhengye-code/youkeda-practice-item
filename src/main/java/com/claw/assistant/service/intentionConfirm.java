package com.claw.assistant.service;

import java.io.IOException;

public interface intentionConfirm {

    String jsonSchema = """
            {"intentions":[{"type":"schedule","skill":"","parameters":""}]}
            """;

    String confirm(String userInput) throws IOException;
}
