package com.claw.assistant.skill;

/**
 * 可复用业务流程。Skill 负责“遇到一类任务时按什么步骤处理”，
 * 与只执行单个动作的 Function Calling 工具区分开。
 */
public interface AssistantSkill {
    String name();

    boolean matches(String message);

    String execute(String message);
}
