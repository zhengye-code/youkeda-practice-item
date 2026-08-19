package com.claw.assistant.service.impl;

import org.json.JSONArray;
import org.json.JSONObject;

public class ToolFunctions {
    public static JSONArray getTools() {
        JSONArray tools = new JSONArray();

        JSONObject getWeather = new JSONObject();
        getWeather.put("type", "function");

        JSONObject weatherFunc = new JSONObject();
        weatherFunc.put("name", "get_weather");
        weatherFunc.put("description", "查询指定城市的实时天气信息，包括天气状况、气温、风向和湿度。当用户询问天气时使用此工具。");

        JSONObject weatherParams = new JSONObject();
        weatherParams.put("type", "object");

        JSONObject weatherProps = new JSONObject();

        JSONObject cityProp = new JSONObject();
        cityProp.put("type", "string");
        cityProp.put("description", "需要查询的城市名称，例如：北京、上海、广州、深圳。如果用户输入了英文名，请转换为中文。");
        weatherProps.put("city", cityProp);

        weatherParams.put("properties", weatherProps);
        weatherParams.put("required", new org.json.JSONArray().put("city"));

        weatherFunc.put("parameters", weatherParams);
        getWeather.put("function", weatherFunc);
        tools.put(getWeather);




        JSONObject calculate = new JSONObject();
        calculate.put("type", "function");

        JSONObject calcFunc = new JSONObject();
        calcFunc.put("name", "calculate");
        calcFunc.put("description", "对两个实数进行四则运算（加、减、乘、除）。当用户询问数学计算时使用此工具。");

        JSONObject calcParams = new JSONObject();
        calcParams.put("type", "object");

        JSONObject calcProps = new JSONObject();

        JSONObject num1Prop = new JSONObject();
        num1Prop.put("type", "number");
        num1Prop.put("description", "第一个操作数（计算符号前的数字）");
        calcProps.put("num1", num1Prop);

        JSONObject num2Prop = new JSONObject();
        num2Prop.put("type", "number");
        num2Prop.put("description", "第二个操作数（计算符号后的数字）");
        calcProps.put("num2", num2Prop);

        JSONObject opProp = new JSONObject();
        opProp.put("type", "string");
        opProp.put("description", "四则运算符号");
        opProp.put("enum", new org.json.JSONArray().put("+").put("-").put("*").put("/"));
        calcProps.put("operation", opProp);

        calcParams.put("properties", calcProps);
        calcParams.put("required", new org.json.JSONArray().put("num1").put("num2").put("operation"));

        calcFunc.put("parameters", calcParams);
        calculate.put("function", calcFunc);
        tools.put(calculate);

        return tools;
    }
}
