package com.claw.assistant.service;

import com.claw.assistant.model.GenerationResult;
import com.claw.assistant.model.ValidationReport;
import com.claw.assistant.model.ValidationResult;

public interface ValidationService {
    ValidationResult validateAndFix(GenerationResult input, int retryCount, boolean lastWasTimeout);

    ValidationResult validateAndFix(GenerationResult input);

    ValidationReport validateKnowledge(GenerationResult input);

    ValidationReport validateSchedule(GenerationResult input);

    ValidationReport validateLogic(GenerationResult input);

    ValidationReport llmOnlyValidation(GenerationResult input, String systemInstruction);

    String regenerateWithFeedback(GenerationResult input, String feedback);

    String callLlm(String prompt) throws Exception;

    ValidationReport parseValidationResponse(String llmResponse);
}
