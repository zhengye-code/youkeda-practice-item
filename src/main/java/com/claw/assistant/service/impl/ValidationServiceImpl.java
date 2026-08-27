package com.claw.assistant.service.impl;

import com.claw.assistant.model.GenerationResult;
import com.claw.assistant.model.ValidationReport;
import com.claw.assistant.model.ValidationResult;
import com.claw.assistant.service.ValidationService;
import com.claw.assistant.rag.SimpleRagRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ValidationServiceImpl implements ValidationService {

    private static final Logger logger = LoggerFactory.getLogger(ValidationService.class);
    private static final int MAX_RETRY = 2;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Value("${aliyun.dashscope.base-url}")
    private String baseUrl;
    @Value("${aliyun.dashscope.api-key}")
    private String apiKey;
    @Value("${aliyun.dashscope.model}")
    private String model;

    @Autowired
    private SimpleRagRetriever ragRetriever;

    @Override
    public ValidationResult validateAndFix(GenerationResult input, int retryCount, boolean lastWasTimeout) {
        try {
        logger.info("开始校验，意图类型：{}，重试次数：{}", input.intentType(), retryCount);

        ValidationReport report = switch (input.intentType()) {
            case "知识点整理" -> validateKnowledge(input);
            case "学习日程" -> validateSchedule(input);
            case "错题解析" -> validateLogic(input);
            default -> {
                logger.warn("未知意图类型：{}，跳过校验", input.intentType());
                yield new ValidationReport(true, List.of());
            }
        };

        logger.info("校验完成，passed={}，发现{}个问题", report.passed(), report.issues().size());
        for (String issue : report.issues()) {
            logger.info("  - 问题：{}", issue);
        }

        if (!report.passed() && retryCount < MAX_RETRY) {
            if (lastWasTimeout) {
                logger.warn("上次调用超时，跳过重试，直接返回原内容");
                return new ValidationResult(input.generatedContent(),
                        new ValidationReport(false, List.of("校验服务响应超时，请稍后重试")));
            }

            logger.info("校验未通过，触发第{}次自动重生成", retryCount + 1);
            String feedback = String.join("；", report.issues());
            String fixedContent = regenerateWithFeedback(input, feedback);

            return validateAndFix(
                    new GenerationResult(input.userQuery(), input.intentType(), fixedContent),
                    retryCount + 1,
                    false
            );
        }

        if (!report.passed()) {
            logger.warn("已达最大重试次数({})，返回最后一次生成的内容", MAX_RETRY);
        }
            return new ValidationResult(input.generatedContent(), report);
        } catch (Exception e) {
            if (isTimeout(e)) {
                logger.error("LLM调用超时（第{}次），停止重试", retryCount + 1, e);
                return new ValidationResult(input.generatedContent(),
                        new ValidationReport(false, List.of("校验服务响应超时，请稍后重试")));
            }
            logger.error("校验过程异常", e);
            return new ValidationResult(input.generatedContent(),
                    new ValidationReport(false, List.of("校验服务异常，请稍后重试")));
        }
    }

    private boolean isTimeout(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.net.SocketTimeoutException ||
                    t instanceof java.io.InterruptedIOException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    @Override
    public ValidationResult validateAndFix(GenerationResult input) {
        return validateAndFix(input, 0,false);
    }

    @Override
    public ValidationReport validateKnowledge(GenerationResult input) {
        List<String> contexts = ragRetriever.retrieve(input.userQuery(), 3);
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个严谨的大学专业课助教。请审查【AI生成内容】是否存在事实性错误。\n");
        if (!contexts.isEmpty()) {
            sb.append("\n【参考资料】（来自教材/课程大纲）：\n");
            sb.append(String.join("\n", contexts));
        } else {
            sb.append("\n（无参考资料，请基于你自己的知识判断）\n");
        }
        sb.append("\n【AI生成内容】：\n").append(input.generatedContent());
        sb.append("\n\n").append(buildOutputRule());
        try {
            String llmResponse = callLlm(sb.toString());
            return parseValidationResponse(llmResponse);
        } catch (Exception e) {
            logger.error("RAG校验调用LLM失败", e);
            return new ValidationReport(false, List.of("校验服务异常，请人工确认"));
        }
    }

    @Override
    public ValidationReport validateSchedule(GenerationResult input) {
        String prompt = """
            你是一个学习规划督导。请审查以下【学习计划】是否存在问题：
            
            【学习计划】：
            %s
            
            %s
            """.formatted(input.generatedContent(), buildOutputRule());

        try {
            String llmResponse = callLlm(prompt);
            return parseValidationResponse(llmResponse);
        } catch (Exception e) {
            logger.error("日程校验调用LLM失败", e);
            return new ValidationReport(false, List.of("日程校验服务异常"));
        }
    }

    @Override
    public ValidationReport validateLogic(GenerationResult input) {
        String instruction = """
            你是一个经验丰富的大学老师。请审查以下【错题解析】的逻辑是否正确：
            审查维度：错误原因分析是否准确、解题步骤是否完整连贯、避坑要点是否针对错误原因、最终答案是否正确。
            
            """ + buildOutputRule();
        return llmOnlyValidation(input, instruction);
    }

    private String buildOutputRule() {
        return """
            
            【输出格式-极其重要】
            你必须严格按照以下格式输出，不要输出"你好"等任何问候语，不要输出格式说明以外的解释：
            
            PASSED: false
            ISSUES:
            - 问题描述1
            - 问题描述2
            
            如果内容完全没问题，则输出：
            PASSED: true
            ISSUES: 无
            """;
    }

    @Override
    public ValidationReport llmOnlyValidation(GenerationResult input, String systemInstruction) {
        String checkPrompt = systemInstruction + "\n\n【待审查内容】：\n" + input.generatedContent();
        try {
            String llmResponse = callLlm(checkPrompt);
            return parseValidationResponse(llmResponse);
        } catch (Exception e) {
            logger.error("LLM校验失败", e);
            return new ValidationReport(false, List.of("内容校验服务异常"));
        }
    }

    @Override
    public String regenerateWithFeedback(GenerationResult input, String feedback) {
        String regeneratePrompt = """
                你之前生成的内容被审查出了以下问题：
                %s
                
                原始用户需求：%s
                
                请修正这些问题，重新生成完整的内容（保持原有的Markdown格式和结构）：
                """.formatted(feedback, input.userQuery());

        try {
            return callLlm(regeneratePrompt);
        } catch (Exception e) {
            logger.error("重生成失败", e);
            return input.generatedContent();
        }
    }

    @Override
    public String callLlm(String prompt) throws Exception {
        var messages = objectMapper.createArrayNode();
        var msg = objectMapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", prompt);
        messages.add(msg);

        var requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", messages);
        requestBody.put("temperature", 0.3);

        var body = RequestBody.create(
                objectMapper.writeValueAsString(requestBody),
                okhttp3.MediaType.parse("application/json; charset=utf-8")
        );

        var request = new Request.Builder()
                .url(baseUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无响应";
                logger.error("LLM调用失败，状态码：{}，响应：{}", response.code(), errorBody);
                throw new RuntimeException("LLM API返回异常: " + response.code());
            }
            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                throw new RuntimeException("LLM返回格式异常，无choices字段");
            }
            return choices.get(0).get("message").get("content").asText();
        }
    }

    @Override
    public ValidationReport parseValidationResponse(String llmResponse) {
        try {
            String text = llmResponse.trim();
            logger.debug("LLM原始响应前300字：{}",
                    text.length() > 300 ? text.substring(0, 300) : text);

            boolean passed = false;
            java.util.regex.Pattern passedPattern = java.util.regex.Pattern.compile(
                    "PASSED\\s*[:：]\\s*(true|false)", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher passedMatcher = passedPattern.matcher(text);
            if (passedMatcher.find()) {
                passed = "true".equalsIgnoreCase(passedMatcher.group(1));
            }

            List<String> issues = new ArrayList<>();
            if (!passed) {
                String[] lines = text.split("\\r?\\n");
                boolean inIssues = false;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("ISSUES") && trimmed.contains(":")) {
                        inIssues = true;
                        String rest = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                        if (!rest.isEmpty() && !rest.equals("无") && !rest.equals("[]")) {
                            issues.add(rest);
                        }
                        continue;
                    }
                    if (inIssues) {
                        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                            issues.add(trimmed.substring(2).trim());
                        } else if (!trimmed.isEmpty() && !trimmed.startsWith("PASSED")) {
                            if (!issues.isEmpty()) {
                                issues.set(issues.size() - 1,
                                        issues.get(issues.size() - 1) + trimmed);
                            }
                        } else if (trimmed.startsWith("PASSED")) {
                            break;
                        }
                    }
                }
            }

            if (!passed && issues.isEmpty()) {
                issues.add("内容存在需要修正的问题（AI审查未返回具体条目）");
            }
            logger.info("解析完成：passed={}，issues数量={}", passed, issues.size());
            return new ValidationReport(passed, issues);
        } catch (Exception e) {
            logger.error("解析异常", e);
            return new ValidationReport(false, List.of("校验结果解析失败"));
        }
    }


}

