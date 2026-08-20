package com.claw.assistant.service.impl;

import com.claw.assistant.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Service
public class WeatherServiceImpl implements WeatherService {
    @Value("${gaode.weather.api-key}")
    private String gaoDeKey;
    @Value("${gaode.weather.base-url}")
    private String gaoDeUrl;
    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final Logger logger = LoggerFactory.getLogger(WeatherServiceImpl.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    @Override
    public String getWeather(String cityName) {
        try {
            String adcode = resolveCityToAdcode(cityName);
            if (adcode == null) {
                return String.format("抱歉，找不到城市「%s」的编码，请换个说法试试（比如用完整的城市名）", cityName);
            }

            String cityAdcode = adcode.substring(0, 4) + "00";
            logger.info("查询天气使用的市级adcode: {}", cityAdcode);

            String url = String.format("%s?city=%s&key=%s&extensions=base",
                    gaoDeUrl, cityAdcode, gaoDeKey);

            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("天气请求失败: {}, body={}", response.code(), response.body().string());
                    return "天气查询失败，请稍后再试";
                }
                String body = response.body().string();
                logger.info("高德天气接口返回: {}", body);
                return parseWeatherResponse(body);
            }
        } catch (Exception e) {
            logger.error("天气查询异常: city={}", cityName, e);
            return "天气查询出现异常，请稍后再试";
        }
    }

    private String resolveCityToAdcode(String cityName) {
        try {
            String encodedCity = URLEncoder.encode(cityName, StandardCharsets.UTF_8);
            String url = String.format("%s?address=%s&output=JSON&key=%s",
                    GEOCODE_URL, encodedCity, gaoDeKey);
            logger.info("高德地理编码请求URL: {}", url);

            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("地理编码请求失败: {}, body={}", response.code(), response.body().string());
                    return null;
                }
                String body = response.body().string();
                logger.info("高德地理编码返回: {}", body);
                return extractAdcode(body);
            }
        } catch (Exception e) {
            logger.error("城市名转adcode失败: {}", cityName, e);
            return null;
        }
    }


    private String extractAdcode(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!"1".equals(root.path("status").asText())) {
                logger.error("高德地理编码失败: {}", root.path("info").asText());
                return null;
            }
            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.size() == 0) {
                logger.error("高德地理编码无结果");
                return null;
            }
            String adcode = geocodes.get(0).path("adcode").asText();
            if (adcode.matches("\\d{6}")) {
                return adcode;
            }
            logger.error("adcode格式错误: {}", adcode);
            return null;
        } catch (Exception e) {
            logger.error("解析adcode失败", e);
            return null;
        }
    }


    private String parseWeatherResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!"1".equals(root.path("status").asText())) {
                return "天气查询失败：" + root.path("info").asText();
            }
            JsonNode lives = root.path("lives");
            if (!lives.isArray() || lives.size() == 0) {
                return "未获取到天气数据";
            }
            JsonNode live = lives.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s%s天气播报\n",
                    live.path("province").asText("未知"),
                    live.path("city").asText("未知")));
            sb.append(String.format("天气：%s\n", live.path("weather").asText("未知")));
            sb.append(String.format("气温：%s℃\n", live.path("temperature").asText("未知")));
            sb.append(String.format("风向风力：%s风 %s级\n",
                    live.path("winddirection").asText("未知"),
                    live.path("windpower").asText("未知")));
            sb.append(String.format("湿度：%s%%\n", live.path("humidity").asText("未知")));
            sb.append(String.format("更新时间：%s", live.path("reporttime").asText("未知")));
            return sb.toString();
        } catch (Exception e) {
            logger.error("解析天气响应失败", e);
            return "天气数据解析失败";
        }
    }
}