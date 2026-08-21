package com.claw.assistant.service.impl;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecuteTest {

    private final ToolExecute toolExecute = new ToolExecute(
            city -> new JSONObject().put("city", city).put("weather", "晴").toString());

    @Test
    void chainsSecondCalculationFromFirstResult() {
        Map<String, String> first = toolExecute.execute(new JSONArray()
                .put(toolCall("call_add", "calculate",
                        new JSONObject().put("num1", 120).put("num2", 30).put("operation", "+"))));
        String firstResult = new JSONObject(first.get("call_add")).getString("result");

        Map<String, String> second = toolExecute.execute(new JSONArray()
                .put(toolCall("call_multiply", "calculate",
                        new JSONObject().put("num1", firstResult).put("num2", 2).put("operation", "*"))));

        assertEquals("150", firstResult);
        assertEquals("300", new JSONObject(second.get("call_multiply")).getString("result"));
    }

    @Test
    void keepsProcessingAfterMalformedCallAndRejectsDivisionByZero() {
        JSONArray calls = new JSONArray()
                .put(new JSONObject().put("id", "bad_json").put("function", new JSONObject()
                        .put("name", "calculate").put("arguments", "not-json")))
                .put(toolCall("divide_zero", "calculate",
                        new JSONObject().put("num1", 10).put("num2", 0).put("operation", "/")))
                .put(toolCall("time", "get_current_time", new JSONObject()));

        Map<String, String> results = toolExecute.execute(calls);

        assertEquals(3, results.size());
        assertFalse(new JSONObject(results.get("bad_json")).getBoolean("success"));
        assertTrue(new JSONObject(results.get("divide_zero")).getString("error").contains("不能为零"));
        assertTrue(new JSONObject(results.get("time")).has("time"));
    }

    @Test
    void returnsStructuredErrorForUnknownTool() {
        Map<String, String> result = toolExecute.execute(new JSONArray()
                .put(toolCall("unknown", "delete_file", new JSONObject())));

        JSONObject error = new JSONObject(result.get("unknown"));
        assertFalse(error.getBoolean("success"));
        assertTrue(error.getString("error").contains("未知工具"));
    }

    @Test
    void executesIndependentToolCallsInParallelAndPreservesOrder() {
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maximumConcurrentCalls = new AtomicInteger();
        ToolExecute parallelExecutor = new ToolExecute(city -> {
            int active = activeCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(120);
                return new JSONObject().put("city", city).toString();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("天气查询被中断", exception);
            } finally {
                activeCalls.decrementAndGet();
            }
        });

        Map<String, String> results = parallelExecutor.execute(new JSONArray()
                .put(toolCall("weather_beijing", "get_weather",
                        new JSONObject().put("city", "北京")))
                .put(toolCall("weather_shanghai", "get_weather",
                        new JSONObject().put("city", "上海"))));

        assertTrue(maximumConcurrentCalls.get() >= 2);
        assertEquals(List.of("weather_beijing", "weather_shanghai"),
                List.copyOf(results.keySet()));
    }

    private static JSONObject toolCall(
            String id,
            String name,
            JSONObject arguments
    ) {
        return new JSONObject()
                .put("id", id)
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", name)
                        .put("arguments", arguments.toString()));
    }
}
