package com.claw.assistant.model;

import com.claw.assistant.service.SkillService;
import com.claw.assistant.service.impl.SkillServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;


@Component
public class SkillRouter {
    private static final Logger logger = LoggerFactory.getLogger(SkillRouter.class);
    private final List<SkillService> skills = new ArrayList<>();

    @Autowired
    private SkillServiceImpl skillServiceImpl;

    @PostConstruct
    public void init() {
        skills.add(skillServiceImpl);
        logger.info("Skill路由器初始化完成，共{}个Skill", skills.size());
    }

    public SkillService match(String userText) {
        for (SkillService skill : skills) {
            if (skill.matches(userText)) {
                return skill;
            }
        }
        return null;
    }
}