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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class WeatherServiceImpl implements WeatherService {
    @Value("${gaode.weather.api-key}")
    private String gaoDeKey;
    @Value("${gaode.weather.base-url}")
    private String gaoDeUrl;
    private static final Logger logger = LoggerFactory.getLogger(WeatherServiceImpl.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    @Override
    public String getWeather(String cityName) {
        String cityCode = convertCityNameToCode(cityName);
        String url = String.format("%s?key=%s&city=%s&extensions=base",
                gaoDeUrl, gaoDeKey, cityCode);

        logger.info("请求天气: cityName={}, cityCode={}", cityName, cityCode);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                logger.error("高德API HTTP错误: code={}", response.code());
                return "天气查询失败，HTTP " + response.code();
            }

            String body = response.body().string();
            logger.info("高德天气API返回: {}", body);

            return parseWeatherResponse(body);

        } catch (Exception e) {
            logger.error("天气查询异常", e);
            return "天气查询服务暂时不可用";
        }
    }

    private String convertCityNameToCode(String cityName) {
        Map<String, String> cityMap = new HashMap<>();
        cityMap.put("北京", "110000");
        cityMap.put("上海", "310000");
        cityMap.put("广州", "440100");
        cityMap.put("深圳", "440300");
        cityMap.put("杭州", "330100");
        cityMap.put("成都", "510100");

        return cityMap.getOrDefault(cityName, "110000");
    }
    private String parseWeatherResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            String status = root.path("status").asText();
            if (!"1".equals(status)) {
                String info = root.path("info").asText();
                logger.error("高德API返回错误: status={}, info={}", status, info);
                return "天气查询失败：" + info;
            }

            JsonNode lives = root.path("lives");
            if (lives.isEmpty()) {
                return "暂无该城市的天气数据";
            }

            JsonNode live = lives.get(0);
            String province = live.path("province").asText();
            String city = live.path("city").asText();
            String weather = live.path("weather").asText();
            String temperature = live.path("temperature").asText();
            String windDir = live.path("winddirection").asText();
            String windPower = live.path("windpower").asText();
            String humidity = live.path("humidity").asText();

            return String.format(
                    "%s %s 天气\n" +
                            "天气：%s\n" +
                            "气温：%s°C\n" +
                            "风向：%s风 %s级\n" +
                            "湿度：%s%%\n" +
                            "更新时间：%s",
                    province, city, weather, temperature, windDir, windPower, humidity,
                    live.path("reporttime").asText()
            );

        } catch (Exception e) {
            logger.error("解析天气JSON失败", e);
            return "天气数据解析失败";
        }
    }
}
