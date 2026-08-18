package functionalCalling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.Config;
import functionalCalling.weather.WindDirection;
import kong.unirest.Unirest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 查询天气的 Function Calling 工具（供 AI 调用）。
 * <p>调用链：城市搜索（GeoAPI）→ 实时天气 + 每日预报（和风天气）。</p>
 */
public class WeatherTool {
    public static final String NAME = "get_weather";
    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    /** 定义 get_weather 工具的 JSON Schema，供 chat/completions 请求的 tools 字段使用。 */
    public static String defineTool() {
        return """
                {
                  "type": "function",
                  "function": {
                    "name": "get_weather",
                    "description": "查询指定城市的实时天气和未来天气预报，返回温度、天气现象、湿度、风向风速等信息",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "city": {
                          "type": "string",
                          "description": "城市名称，例如：北京、上海、广州、深圳"
                        },
                        "days": {
                          "type": "integer",
                          "description": "预报天数，1-7，默认 1",
                          "default": 1
                        }
                      },
                      "required": ["city"]
                    }
                  }
                }
                """;
    }

    /** 执行天气查询工具，返回给 AI 的 JSON 字符串结果。 */
    public static String execute(String argumentsJson) {
        log.info("开始调用天气查询工具");
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode args = mapper.readTree(argumentsJson);
            String city = args.path("city").asText("").trim();
            int days = Math.max(1, Math.min(7, args.path("days").asInt(1)));
            if (city.isEmpty()) {
                log.warn("天气查询缺少城市参数");
                return "{\"error\":\"缺少城市参数 city\"}";
            }
            Location loc = searchCity(city);
            if (loc == null) {
                log.warn("天气查询未找到城市: {}", city);
                return "{\"error\":\"未找到城市: " + city + "\"}";
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("city", loc.name);
            if (loc.adm1 != null) {
                result.put("adm1", loc.adm1);
            }
            result.set("now", queryCurrent(loc.lat, loc.lon));
            result.set("daily", queryDaily(loc.lat, loc.lon, days));
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("天气查询工具执行失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 和风城市搜索：返回第一个匹配城市；未找到返回 null。 */
    private static Location searchCity(String city) throws Exception {
        String body = qweatherGet("/geo/v2/city/lookup?location=" + encode(city));
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode arr = root.path("location");
        if (!arr.isArray() || arr.size() == 0) {
            log.warn("和风城市搜索无结果，城市={}，响应: {}", city, body);
            return null;
        }
        JsonNode first = arr.get(0);
        Location loc = new Location();
        loc.name = first.path("name").asText(city);
        loc.adm1 = first.path("adm1").asText(null);
        loc.lat = first.path("lat").asText();
        loc.lon = first.path("lon").asText();
        return loc;
    }

    /** 和风实时天气：返回精简后的 JSON（温度、现象、湿度、风、能见度、气压）。 */
    private static JsonNode queryCurrent(String lat, String lon) throws Exception {
        String body = qweatherGet("/weather/v1/current/" + lat + "/" + lon);
        JsonNode root = new ObjectMapper().readTree(body);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode out = mapper.createObjectNode();
        out.put("text", root.path("condition").path("text").asText(""));
        out.put("temperature", value(root, "temperature"));
        out.put("feelsLike", value(root, "feelsLike"));
        out.put("humidity", Math.round(root.path("humidity").asDouble(0) * 100) + "%");
        out.put("wind", WindDirection.zhName(
                        root.path("wind").path("direction").path("compass").asText())
                + root.path("wind").path("scale").asInt(0) + "级");
        out.put("visibility", value(root, "visibility"));
        out.put("pressure", value(root, "pressure"));
        return out;
    }

    /** 和风每日预报：返回精简后的 days 数组。 */
    private static JsonNode queryDaily(String lat, String lon, int days) throws Exception {
        String body = qweatherGet("/weather/v1/daily/" + lat + "/" + lon + "?days=" + days);
        JsonNode root = new ObjectMapper().readTree(body);
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode out = mapper.createArrayNode();
        JsonNode arr = root.path("days");
        if (arr.isArray()) {
            for (JsonNode d : arr) {
                ObjectNode day = mapper.createObjectNode();
                String start = d.path("forecastStartTime").asText("");
                day.put("date", start.length() >= 10 ? start.substring(0, 10) : start);
                day.put("tempMax", value(d, "temperatureMax"));
                day.put("tempMin", value(d, "temperatureMin"));
                day.put("daytime", d.path("daytime").path("condition").path("text").asText(""));
                day.put("nighttime", d.path("nighttime").path("condition").path("text").asText(""));
                out.add(day);
            }
        }
        return out;
    }

    /** 发送和风天气 GET 请求，返回响应体。 */
    private static String qweatherGet(String path) {
        kong.unirest.HttpResponse<String> resp = Unirest.get(Config.qweatherHost() + path)
                .connectTimeout(Config.connectTimeoutMs())
                .socketTimeout(Config.socketTimeoutMs())
                .header("Authorization", "Bearer " + Config.qweatherToken())
                .asString();
        // 和风错误响应：HTTP >=400 或 {"code":"401"...}（非 200）
        if (resp.getStatus() >= 400) {
            log.warn("和风天气 HTTP {} 请求失败，路径: {}，响应: {}", resp.getStatus(), path, resp.getBody());
        } else {
            String body = resp.getBody();
            if (body != null && body.contains("\"code\"") && !body.contains("\"code\":\"200\"")) {
                log.warn("和风天气返回错误码，路径: {}，响应: {}", path, body);
            }
        }
        return resp.getBody();
    }

    /** 从 {value, unit} 对象拼出带单位的字符串。 */
    private static String value(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.path("value").asDouble(0) + v.path("unit").asText("");
    }

    /** URL 编码。 */
    private static String encode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    /** 城市信息（和风 Location 精简）。 */
    private static final class Location {
        String name;
        String adm1;
        String lat;
        String lon;
    }
}
