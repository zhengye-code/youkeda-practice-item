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
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ToolExecute {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecute.class);
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private final WeatherService weatherService;

    public ToolExecute(WeatherService weatherService) {
        this.weatherService = weatherService;
    }
    public Map<String, String> execute(JSONArray toolCalls) {
        Map<String, String> results = new LinkedHashMap<>();

        for (int i = 0; i < toolCalls.length(); i++) {
            String callId = "invalid_call_" + i;
            String name = "";

            try {
                JSONObject call = toolCalls.getJSONObject(i);
                callId = call.optString("id", callId).trim();
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
                    case "calculate"    -> executeCalculate(args);
                    default -> errorJson("未知工具: " + name);
                };
                results.put(callId, result);
                logger.info("工具 {} 执行完成: {}", name, result);
            } catch (Exception e) {
                logger.warn("工具 {} 执行失败: {}", name, safeMessage(e));
                results.put(callId, errorJson("工具执行失败: " + safeMessage(e)));
            }
        }
        return results;
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

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
