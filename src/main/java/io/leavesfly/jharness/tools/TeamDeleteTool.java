package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.agent.coordinator.TeamRegistry;
import io.leavesfly.jharness.tools.input.TeamDeleteToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 团队删除工具
 */
public class TeamDeleteTool extends BaseTool<TeamDeleteToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TeamDeleteTool.class);
    private final TeamRegistry teamRegistry;

    public TeamDeleteTool(TeamRegistry teamRegistry) {
        this.teamRegistry = teamRegistry;
    }

    @Override
    public String getName() {
        return "team_delete";
    }

    @Override
    public String getDescription() {
        return "删除一个 Agent 团队";
    }

    @Override
    public Class<TeamDeleteToolInput> getInputClass() {
        return TeamDeleteToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TeamDeleteToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String name = input.getName();
                boolean deleted = teamRegistry.deleteTeam(name);
                
                if (deleted) {
                    return ToolResult.success("团队已删除: " + name);
                } else {
                    return ToolResult.error("团队不存在: " + name);
                }
            } catch (Exception e) {
                logger.error("删除团队失败", e);
                return ToolResult.error("删除团队失败: " + e.getMessage());
            }
        });
    }
}
