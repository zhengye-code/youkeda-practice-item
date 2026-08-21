package com.claw.assistant.service.impl;

import com.claw.assistant.service.WeatherService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class ToolExecute {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecute.class);
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private final WeatherService weatherService;

    public ToolExecute(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /**
     * 并行执行模型在同一轮返回的独立工具调用，并按原始 tool_calls 顺序回传结果。
     * 跨轮依赖仍由 LlmServiceImpl 的模型循环串行协调。
     */
    public Map<String, String> execute(JSONArray toolCalls) {
        Map<String, String> results = new LinkedHashMap<>();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return results;
        }

        List<Future<ToolExecutionResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < toolCalls.length(); index++) {
                JSONObject call = toolCalls.optJSONObject(index);
                int callIndex = index;
                futures.add(executor.submit(() -> executeSingle(call, callIndex)));
            }

            for (int index = 0; index < futures.size(); index++) {
                try {
                    ToolExecutionResult execution = futures.get(index).get();
                    results.put(execution.callId(), execution.content());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    results.put("interrupted_call_" + index, errorJson("工具执行被中断"));
                    break;
                } catch (ExecutionException exception) {
                    results.put("failed_call_" + index,
                            errorJson("工具执行失败: " + safeMessage(exception)));
                }
            }
        }
        return results;
    }

    private ToolExecutionResult executeSingle(JSONObject call, int index) {
        String callId = "invalid_call_" + index;
        String name = "";
        try {
            if (call == null) {
                throw new IllegalArgumentException("工具调用必须是 JSON 对象");
            }
            callId = call.optString("id", callId).trim();
            if (callId.isBlank()) {
                callId = "invalid_call_" + index;
            }
            JSONObject function = call.optJSONObject("function");
            if (function == null) {
                throw new IllegalArgumentException("缺少 function 对象");
            }
            name = function.optString("name", "").trim();
            if (name.isBlank()) {
                throw new IllegalArgumentException("缺少工具名称");
            }
            JSONObject args = new JSONObject(function.optString("arguments", "{}"));
            logger.info("执行工具: {} (call_id={})", name, callId);

            String result = switch (name) {
                case "get_weather" -> executeGetWeather(args);
                case "get_current_time" -> executeGetCurrentTime();
                case "calculate" -> executeCalculate(args);
                default -> errorJson("未知工具: " + name);
            };
            logger.info("工具 {} 执行完成: {}", name, result);
            return new ToolExecutionResult(callId, result);
        } catch (Exception exception) {
            logger.warn("工具 {} 执行失败: {}", name, safeMessage(exception));
            return new ToolExecutionResult(
                    callId,
                    errorJson("工具执行失败: " + safeMessage(exception))
            );
        }
    }

    private String executeGetWeather(JSONObject args) {
        String city = args.getString("city");
        String result = weatherService.getWeather(city);
        return result != null ? result : "{\"error\":\"未获取到天气信息\"}";
    }

    private String executeGetCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedTime = now.format(formatter);
        logger.info("执行工具 get_current_time, 结果: {}", formattedTime);
        return String.format("{\"time\": \"%s\"}", formattedTime);
    }

    private String executeCalculate(JSONObject args) {
        BigDecimal num1 = args.getBigDecimal("num1");
        BigDecimal num2 = args.getBigDecimal("num2");
        String op = args.getString("operation");

        BigDecimal result = switch (op) {
            case "+" -> num1.add(num2, CALCULATION_CONTEXT);
            case "-" -> num1.subtract(num2, CALCULATION_CONTEXT);
            case "*" -> num1.multiply(num2, CALCULATION_CONTEXT);
            case "/" -> {
                if (num2.compareTo(BigDecimal.ZERO) == 0) {
                    throw new IllegalArgumentException("除数不能为零");
                }
                yield num1.divide(num2, CALCULATION_CONTEXT);
            }
            default -> throw new IllegalArgumentException("不支持的运算符: " + op);
        };

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("result", normalize(result));
        return response.toString();
    }

    private static String normalize(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static String errorJson(String message) {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("error", message == null || message.isBlank() ? "工具执行失败" : message);
        return response.toString();
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record ToolExecutionResult(String callId, String content) {
    }
}
