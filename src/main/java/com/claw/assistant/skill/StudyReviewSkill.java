package com.claw.assistant.skill;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StudyReviewSkill implements AssistantSkill {
    private static final List<String> KEYWORDS = List.of("学习复盘", "今日复盘", "复盘学习");

    @Override
    public String name() {
        return "study_review";
    }

    @Override
    public boolean matches(String message) {
        return message != null && KEYWORDS.stream().anyMatch(message::contains);
    }

    @Override
    public String execute(String message) {
        return """
                已启动“学习复盘”Skill，请按下面流程完成：
                1. 今天学了什么：用一句话写出主题和核心概念。
                2. 今天做了什么：列出代码、测试或文档等可核查产出。
                3. 是否真正跑通：记录测试结果，并区分离线测试与真实环境验证。
                4. 还有什么问题：写明证据缺口、失败原因或待确认项。
                5. 下一步最小行动：只安排一个可以立即执行的动作。

                本次复盘主题：%s
                """.formatted(message == null ? "未提供" : message.trim());
    }
}
