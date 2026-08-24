package com.claw.assistant.model;

import com.claw.assistant.service.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SkillContext {
    @Autowired
    private WeatherService weatherService;

    public WeatherService getWeatherService() {
        return weatherService;
    }
}
