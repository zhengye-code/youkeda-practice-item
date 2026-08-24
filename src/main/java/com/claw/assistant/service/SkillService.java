package com.claw.assistant.service;

import com.claw.assistant.model.SkillContext;

public interface SkillService {
    String name();

    String description();

    boolean matches(String userText);

    String execute(String userText, SkillContext context);
}
