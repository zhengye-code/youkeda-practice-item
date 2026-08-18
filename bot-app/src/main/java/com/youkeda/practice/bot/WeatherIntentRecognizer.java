package com.youkeda.practice.bot;

import java.util.Optional;

/**
 * 使用本地规则识别天气查询并提取城市，避免为了路由一次消息而额外调用大模型。
 */
public final class WeatherIntentRecognizer {

    private static final String[] WEATHER_KEYWORDS = {
            "天气预报", "天气", "气温", "温度", "下雨", "降雨", "降温", "升温",
            "多少度", "几度", "冷不冷", "热不热"
    };

    private static final String[] NOISE_PHRASES = {
            "帮我查询一下", "帮我查询", "帮我查一下", "帮我查", "查询一下", "查一下",
            "请问一下", "请问", "告诉我", "我想知道", "想知道", "看一下", "看看",
            "今天", "明天", "后天", "现在", "当前", "实时", "当地", "这里", "那边",
            "会不会", "下不下", "是否", "怎么样", "如何", "是多少", "多少", "一下", "的"
    };

    private WeatherIntentRecognizer() {
    }

    public static Optional<WeatherIntent> recognize(String text, String defaultCity) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String normalized = text.replaceAll("[\\s，。！？、,.!?]", "").trim();
        int dayOffset = normalized.contains("后天") ? 2 : normalized.contains("明天") ? 1 : 0;
        String intentText = normalized
                .replace("今天", "")
                .replace("明天", "")
                .replace("后天", "");

        String matchedKeyword = null;
        int keywordIndex = Integer.MAX_VALUE;
        for (String keyword : WEATHER_KEYWORDS) {
            int index = intentText.indexOf(keyword);
            if (index >= 0 && index < keywordIndex) {
                matchedKeyword = keyword;
                keywordIndex = index;
            }
        }
        if (matchedKeyword == null) {
            return Optional.empty();
        }

        String beforeKeyword = intentText.substring(0, keywordIndex);
        String afterKeyword = intentText.substring(keywordIndex + matchedKeyword.length());
        String city = cleanCityCandidate(beforeKeyword);
        if (city.isBlank()) {
            city = cleanCityCandidate(afterKeyword);
        }
        if (city.isBlank() && defaultCity != null) {
            city = defaultCity.trim();
        }

        return Optional.of(new WeatherIntent(city, dayOffset));
    }

    private static String cleanCityCandidate(String value) {
        String result = value;
        for (String phrase : NOISE_PHRASES) {
            result = result.replace(phrase, "");
        }
        result = result.replaceAll("[^\\p{IsHan}A-Za-z0-9·-]", "");
        return result.length() <= 20 ? result : "";
    }

    public record WeatherIntent(String city, int dayOffset) {
        public boolean hasCity() {
            return city != null && !city.isBlank();
        }
    }
}
