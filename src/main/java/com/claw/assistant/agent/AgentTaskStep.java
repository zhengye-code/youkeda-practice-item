package com.claw.assistant.agent;

public record AgentTaskStep(int index, String name, AgentStepStatus status, String output) {
    public AgentTaskStep withStatus(AgentStepStatus newStatus, String newOutput) {
        return new AgentTaskStep(index, name, newStatus, newOutput);
    }
}
