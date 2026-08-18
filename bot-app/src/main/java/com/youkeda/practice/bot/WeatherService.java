package com.youkeda.practice.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * 通过高德 Web 服务 API 查询城市天气。
 *
 * <p>Key 只从 {@code AMAP_WEATHER_API_KEY} 环境变量读取。</p>
 */
public final class WeatherService {

    private static final String DEFAULT_BASE_URL = "https://restapi.amap.com";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;

    private WeatherService(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    public static Optional<WeatherService> fromEnvironment() {
        String apiKey = System.getenv("AMAP_WEATHER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        String normalizedKey = apiKey.replaceAll("\\s+", "");
        if (!normalizedKey.matches("[A-Za-z0-9]{16,64}")) {
            throw new IllegalStateException("AMAP_WEATHER_API_KEY 格式不正确，请重新复制完整的高德 Web 服务 Key。");
        }

        String configuredBaseUrl = System.getenv("AMAP_BASE_URL");
        String baseUrl = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? DEFAULT_BASE_URL
                : configuredBaseUrl.trim();
        return Optional.of(new WeatherService(normalizedKey, baseUrl));
    }

    public String query(String city, int dayOffset) throws IOException {
        String adcode = resolveAdcode(city);
        JsonNode forecast = requestJson(
                "/v3/weather/weatherInfo",
                "city", adcode,
                "extensions", "all",
                "output", "JSON"
        );
        ensureSuccess(forecast, "天气查询");

        JsonNode forecastNode = forecast.path("forecasts").path(0);
        JsonNode casts = forecastNode.path("casts");
        if (!casts.isArray() || casts.isEmpty()) {
            throw new IOException("高德天气接口没有返回可用的天气预报。");
        }

        int safeOffset = Math.max(0, Math.min(dayOffset, casts.size() - 1));
        JsonNode day = casts.path(safeOffset);
        String resolvedCity = textOrDefault(forecastNode, "city", city);
        String relativeDay = switch (safeOffset) {
            case 1 -> "明天";
            case 2 -> "后天";
            default -> "今天";
        };

        return String.format(
                "%s%s（%s）：白天%s，%s℃；夜间%s，%s℃。%s风%s级。数据发布时间：%s。",
                resolvedCity,
                relativeDay,
                textOrDefault(day, "date", "日期未知"),
                textOrDefault(day, "dayweather", "未知"),
                textOrDefault(day, "daytemp", "--"),
                textOrDefault(day, "nightweather", "未知"),
                textOrDefault(day, "nighttemp", "--"),
                textOrDefault(day, "daywind", "未知"),
                textOrDefault(day, "daypower", "--"),
                textOrDefault(forecastNode, "reporttime", "未知")
        );
    }

    private String resolveAdcode(String city) throws IOException {
        JsonNode geocode = requestJson(
                "/v3/geocode/geo",
                "address", city,
                "output", "JSON"
        );
        ensureSuccess(geocode, "城市识别");
        String adcode = geocode.path("geocodes").path(0).path("adcode").asText().trim();
        if (adcode.isBlank()) {
            throw new IOException("没有识别到城市“" + city + "”，请换成完整城市名，例如“杭州市”。");
        }
        return adcode;
    }

    private JsonNode requestJson(String path, String... parameters) throws IOException {
        HttpUrl base = HttpUrl.parse(baseUrl + path);
        if (base == null) {
            throw new IOException("高德天气接口地址配置不正确。");
        }

        HttpUrl.Builder url = base.newBuilder().addQueryParameter("key", apiKey);
        for (int index = 0; index + 1 < parameters.length; index += 2) {
            url.addQueryParameter(parameters[index], parameters[index + 1]);
        }

        Request request = new Request.Builder().url(url.build()).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("高德天气接口返回 HTTP " + response.code() + "。");
            }
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("高德")) {
                throw exception;
            }
            throw new IOException("连接高德天气接口失败，请检查网络后重试。");
        }
    }

    private static void ensureSuccess(JsonNode root, String operation) throws IOException {
        if (!"1".equals(root.path("status").asText())) {
            String info = root.path("info").asText("未知错误");
            String infocode = root.path("infocode").asText("未知代码");
            throw new IOException(operation + "失败：" + info + "（" + infocode + "）。");
        }
    }

    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = node.path(field).asText().trim();
        return value.isBlank() ? defaultValue : value;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
