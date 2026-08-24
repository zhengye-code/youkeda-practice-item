package com.claw.assistant.service.impl;

import com.claw.assistant.model.SkillContext;
import com.claw.assistant.service.SkillService;

import com.claw.assistant.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Component
public class SkillServiceImpl implements SkillService {
    private static final Logger logger = LoggerFactory.getLogger(SkillServiceImpl.class);
    private static final String[] TRIGGER_KEYWORDS = {"跑步", "运动", "出门", "适合吗", "户外运动"};

    @Autowired
    private SkillContext skillContext;

    @Override
    public String name() {
        return "运动天气顾问";
    }

    @Override
    public String description() {
        return "用户询问天气是否适合跑步/运动时触发";
    }

    @Override
    public boolean matches(String userText) {
        for (String keyword : TRIGGER_KEYWORDS) {
            if (userText.contains(keyword)) {
                logger.info("命中Skill：{}，触发关键词：{}", name(), keyword);
                return true;
            }
        }
        return false;
    }

    @Override
    public String execute(String userText, SkillContext context) {
        logger.info("执行Skill：{}，用户输入：{}", name(), userText);
        WeatherService weatherService = context.getWeatherService();

        String city = "北京";
        if (userText.contains("上海")) city = "上海";
        else if (userText.contains("广州")) city = "广州";
        else if (userText.contains("深圳")) city = "深圳";

        String weatherResult = weatherService.getWeather(city);

        boolean isGoodForSports = weatherResult.contains("晴") || weatherResult.contains("多云");
        String advice = isGoodForSports ? "天气不错，适合户外运动哦~记得带水！" : "天气不太适合户外运动，建议室内活动~";

        return String.format("%s\n\n 运动建议：%s", weatherResult, advice);
    }
}