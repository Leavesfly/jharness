package io.leavesfly.jharness;

import io.leavesfly.jharness.commands.CommandRegistry;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.permissions.PermissionChecker;
import io.leavesfly.jharness.permissions.PermissionMode;
import io.leavesfly.jharness.engine.CostTracker;
import io.leavesfly.jharness.engine.model.ConversationMessage;
import io.leavesfly.jharness.engine.model.UsageSnapshot;
import io.leavesfly.jharness.memory.MemoryManager;
import io.leavesfly.jharness.sessions.SessionSnapshot;
import io.leavesfly.jharness.sessions.SessionStorage;
import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tasks.TaskRecord;
import io.leavesfly.jharness.tasks.TaskRecord.TaskType;
import io.leavesfly.jharness.tasks.TaskStatus;
import io.leavesfly.jharness.tools.*;
import io.leavesfly.jharness.tools.input.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JHarnessComprehensiveTest {

    @TempDir
    Path tempDir;

    @Test
    void testToolRegistryWithAllTools() {
        Settings settings = new Settings();
        Path taskOutputDir = tempDir.resolve("tasks");
        BackgroundTaskManager taskManager = new BackgroundTaskManager(taskOutputDir);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new BashTool());
        registry.register(new SleepTool());
        registry.register(new TodoWriteTool());
        registry.register(new BriefTool());
        registry.register(new ConfigTool(settings));
        registry.register(new EnterPlanModeTool(settings));
        registry.register(new AgentTool(taskManager));
        registry.register(new SendMessageTool(taskManager));
        registry.register(new AskUserQuestionTool());

        assertTrue(registry.size() >= 10);
        assertTrue(registry.has("bash"));
        assertTrue(registry.has("read_file"));
        assertTrue(registry.has("agent_spawn"));
        taskManager.shutdown();
    }

    @Test
    void testCommandRegistrySize() {
        CommandRegistry registry = new CommandRegistry();
        assertTrue(registry.size() >= 40, "Expected 40+ commands, got: " + registry.size());
    }

    @Test
    void testCommandLookup() {
        CommandRegistry registry = new CommandRegistry();
        assertTrue(registry.lookup("help").isPresent());
        assertTrue(registry.lookup("resume").isPresent());
        assertTrue(registry.lookup("memory").isPresent());
        assertTrue(registry.lookup("init").isPresent());
        assertTrue(registry.lookup("/help").isPresent());
    }

    @Test
    void testPermissionCheckerModes() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertTrue(checker.evaluate("read_file", true, null, null).isAllowed());
        assertTrue(checker.evaluate("write_file", false, null, null).isRequiresConfirmation());

        checker.setMode(PermissionMode.PLAN);
        assertFalse(checker.evaluate("write_file", false, null, null).isAllowed());

        checker.setMode(PermissionMode.FULL_AUTO);
        assertTrue(checker.evaluate("bash", false, null, null).isAllowed());
    }

    @Test
    void testSettingsGetSet() {
        Settings settings = new Settings();
        settings.set("model", "test-model");
        assertEquals("test-model", settings.get("model"));
        assertNull(settings.get("nonexistent"));
    }

    @Test
    void testMemoryManager() {
        Path memoryDir = tempDir.resolve("memory");
        MemoryManager memoryManager = new MemoryManager(memoryDir);

        memoryManager.addMemory("test-project", "coding-standards", "Use camelCase for variables");
        memoryManager.addMemory("test-project", "api-endpoints", "GET /users returns list", "api");

        assertEquals(2, memoryManager.listMemories("test-project").size());

        String content = memoryManager.readMemory("test-project", "coding-standards");
        assertNotNull(content);
        assertTrue(content.contains("camelCase"));

        assertTrue(memoryManager.updateMemory("test-project", "coding-standards", "Updated content"));
        assertEquals("Updated content", memoryManager.readMemory("test-project", "coding-standards"));

        assertEquals(1, memoryManager.searchByCategory("test-project", "api").size());
        MemoryManager.MemoryStats stats = memoryManager.getStats("test-project");
        assertEquals(2, stats.totalCount);

        assertTrue(memoryManager.removeMemory("test-project", "coding-standards"));
        assertEquals(1, memoryManager.listMemories("test-project").size());
    }

    @Test
    void testSessionStorage() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        SessionStorage sessionStorage = new SessionStorage(sessionsDir);

        // Just verify that saving creates a file and we can detect it
        List<ConversationMessage> messages = List.of(
                ConversationMessage.userText("Hello"),
                ConversationMessage.assistantText("Hi there!")
        );

        SessionSnapshot snapshot = new SessionSnapshot(
                "test-123", "/test/path", "claude-3-5-sonnet", messages,
                new UsageSnapshot(100, 50, 0, 0), Instant.now(), "Test session", 2
        );

        sessionStorage.saveSession(snapshot);
        
        // Verify the session file was created
        Path sessionFile = sessionsDir.resolve("session-test-123.json");
        assertTrue(Files.exists(sessionFile), "Session file should exist: " + sessionFile);
        assertTrue(Files.size(sessionFile) > 0, "Session file should not be empty");
    }

    @Test
    void testBackgroundTaskManager() throws InterruptedException {
        Path outputDir = tempDir.resolve("tasks");
        BackgroundTaskManager taskManager = new BackgroundTaskManager(outputDir);

        try {
            TaskRecord task = taskManager.createShellTask("echo hello", "Test task", tempDir);
            assertNotNull(task);
            assertEquals(TaskStatus.RUNNING, task.getStatus());

            Thread.sleep(1000);

            TaskRecord retrieved = taskManager.getTask(task.getId());
            assertEquals(TaskStatus.COMPLETED, retrieved.getStatus());
            assertTrue(taskManager.readTaskOutput(task.getId()).contains("hello"));
        } finally {
            taskManager.shutdown();
        }
    }

    @Test
    void testTaskRecordAgentTask() {
        TaskRecord task = new TaskRecord(
                "agent-1", null, "Agent task", tempDir,
                TaskStatus.PENDING, TaskType.LOCAL_AGENT
        );
        task.setPrompt("Analyze codebase");
        task.setModel("claude-3-5-sonnet");

        assertTrue(task.isAgentTask());
        assertEquals(TaskType.LOCAL_AGENT, task.getType());
        assertEquals("Analyze codebase", task.getPrompt());
    }

    @Test
    void testCostTracker() {
        CostTracker tracker = new CostTracker();
        tracker.addUsage(new UsageSnapshot(1000, 500, 200, 100));
        assertEquals(1000, tracker.getTotalInputTokens());
        assertEquals(1, tracker.getRequestCount());

        tracker.addUsage(new UsageSnapshot(500, 300, 100, 50));
        assertEquals(1500, tracker.getTotalInputTokens());

        tracker.reset();
        assertEquals(0, tracker.getTotalInputTokens());

        tracker.addUsage(new UsageSnapshot(100, 50, 0, 0));
        UsageSnapshot snapshot = tracker.toUsageSnapshot();
        assertEquals(100, snapshot.getInputTokens());
    }

    @Test
    void testSleepTool() throws Exception {
        SleepTool tool = new SleepTool();
        SleepToolInput input = new SleepToolInput();
        input.setSeconds(1);

        long start = System.currentTimeMillis();
        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isError());
        assertTrue(elapsed >= 900);
    }

    @Test
    void testBriefTool() throws Exception {
        BriefTool tool = new BriefTool();
        BriefToolInput input = new BriefToolInput();
        input.setText("A".repeat(500));
        input.setMaxLength(50);

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null)).get();
        assertFalse(result.isError());
        assertNotNull(result.getOutput());
    }

    @Test
    void testEnterPlanModeTool() throws Exception {
        Settings settings = new Settings();
        EnterPlanModeTool tool = new EnterPlanModeTool(settings);
        ToolResult result = tool.execute(new EnterPlanModeToolInput(), new ToolExecutionContext(tempDir, null)).get();

        assertFalse(result.isError());
        assertEquals(PermissionMode.PLAN, settings.getPermissionMode());
    }

    @Test
    void testTeamRegistry() {
        io.leavesfly.jharness.coordinator.TeamRegistry teamRegistry =
                new io.leavesfly.jharness.coordinator.TeamRegistry();

        teamRegistry.createTeam("test-team", "Test team");
        teamRegistry.addAgent("test-team", "agent-1", "leader");
        assertEquals(1, teamRegistry.listTeams().size());

        assertTrue(teamRegistry.deleteTeam("test-team"));
        assertFalse(teamRegistry.deleteTeam("nonexistent"));
    }
}
