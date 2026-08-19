package com.claw.assistant.service.impl;

import com.claw.assistant.service.WeatherService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ToolExecute {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecute.class);

    private final WeatherService weatherService;

    public ToolExecute(WeatherService weatherService) {
        this.weatherService = weatherService;
    }
    public Map<String, String> execute(JSONArray toolCalls) {
        Map<String, String> results = new HashMap<>();

        for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject call = toolCalls.getJSONObject(i);
            String callId = call.getString("id");
            JSONObject function = call.getJSONObject("function");
            String name = function.getString("name");
            JSONObject args = new JSONObject(function.getString("arguments"));

            logger.info("执行工具: {} (call_id={})", name, callId);

            try {
                String result = switch (name) {
                    case "get_weather" -> executeGetWeather(args);
                    case "calculate"    -> executeCalculate(args);
                    default -> "{\"error\":\"未知工具: " + name + "\"}";
                };
                results.put(callId, result);
                logger.info("工具 {} 执行成功: {}", name, result);
            } catch (Exception e) {
                logger.error("工具 {} 执行失败", name, e);
                results.put(callId, "{\"error\":\"工具执行失败: " + e.getMessage() + "\"}");
            }
        }
        return results;
    }

    private String executeGetWeather(JSONObject args) {
        String city = args.getString("city");
        String result = weatherService.getWeather(city);
        return result != null ? result : "{\"error\":\"未获取到天气信息\"}";
    }

    private String executeCalculate(JSONObject args) {
        double num1 = args.getDouble("num1");
        double num2 = args.getDouble("num2");
        String op = args.getString("operation");

        double result = switch (op) {
            case "+" -> num1 + num2;
            case "-" -> num1 - num2;
            case "*" -> num1 * num2;
            case "/" -> {
                if (num2 == 0) throw new IllegalArgumentException("除数不能为零");
                yield num1 / num2;
            }
            default -> throw new IllegalArgumentException("不支持的运算符: " + op);
        };

        return String.format("{\"result\":%.4f}", result);
    }

}
