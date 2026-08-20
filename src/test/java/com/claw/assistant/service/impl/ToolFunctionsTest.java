package com.claw.assistant.service.impl;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ToolFunctionsTest {

    @Test
    void exposesThreeStrictJsonSchemas() {
        JSONArray tools = ToolFunctions.getTools();
        Set<String> names = new HashSet<>();

        assertEquals(3, tools.length());
        for (int index = 0; index < tools.length(); index++) {
            JSONObject function = tools.getJSONObject(index).getJSONObject("function");
            names.add(function.getString("name"));
            assertFalse(function.getJSONObject("parameters")
                    .getBoolean("additionalProperties"));
        }

        assertEquals(Set.of("get_weather", "get_current_time", "calculate"), names);
    }
}
