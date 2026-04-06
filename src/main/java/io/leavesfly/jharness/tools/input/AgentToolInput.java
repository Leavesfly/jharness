package io.leavesfly.jharness.tools.input;

/**
 * Agent 工具输入
 */
public class AgentToolInput {
    private String prompt;
    private String description;
    private String model;
    private String apiKey;
    private String mode;  // local_agent, remote_agent, in_process
    private String team;  // Optional team name

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
}
