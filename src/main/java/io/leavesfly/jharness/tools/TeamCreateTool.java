package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.coordinator.TeamRecord;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.TeamCreateToolInput;
import io.leavesfly.jharness.coordinator.TeamRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 团队创建工具
 */
public class TeamCreateTool extends BaseTool<TeamCreateToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TeamCreateTool.class);
    private final TeamRegistry teamRegistry;

    public TeamCreateTool(TeamRegistry teamRegistry) {
        this.teamRegistry = teamRegistry;
    }

    @Override
    public String getName() {
        return "team_create";
    }

    @Override
    public String getDescription() {
        return "创建一个新的 Agent 团队";
    }

    @Override
    public Class<TeamCreateToolInput> getInputClass() {
        return TeamCreateToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TeamCreateToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String name = input.getName();
                String description = input.getDescription() != null ? input.getDescription() : "";

                TeamRecord team = teamRegistry.createTeam(name, description);
                return ToolResult.success("团队已创建:\n  名称: " + team.getName() + "\n  ID: " + team.getId() + "\n  描述: " + team.getDescription());
            } catch (Exception e) {
                logger.error("创建团队失败", e);
                return ToolResult.error("创建团队失败: " + e.getMessage());
            }
        });
    }
}
