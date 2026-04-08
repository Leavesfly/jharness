package io.leavesfly.jharness.commands;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandRegistry;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.command.commands.handlers.*;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.core.engine.stream.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NewCommandHandlersTest {

    @TempDir
    Path tempDir;

    private CommandContext createContext() {
        return new CommandContext(tempDir, null, null, null, new Settings());
    }

    private java.util.function.Consumer<StreamEvent> noOpConsumer() {
        return event -> {};
    }

    @Test
    void testIssueCommandShow() throws Exception {
        SlashCommand cmd = IssueCommandHandler.createIssueCommand();
        assertEquals("issue", cmd.getName());

        // Show with no issue file
        CommandContext ctx = createContext();
        CompletableFuture<CommandResult> future = cmd.execute(List.of("show"), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("无 issue 上下文"));
    }

    @Test
    void testIssueCommandSet() throws Exception {
        SlashCommand cmd = IssueCommandHandler.createIssueCommand();
        CommandContext ctx = createContext();

        CompletableFuture<CommandResult> future = cmd.execute(
            List.of("set", "Test Issue", "::", "This is a test issue body"),
            ctx,
            noOpConsumer()
        );
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("已保存"));

        Path issueFile = tempDir.resolve(".jharness").resolve("issue.md");
        assertTrue(Files.exists(issueFile));
        String content = Files.readString(issueFile);
        assertTrue(content.contains("Test Issue"));
        assertTrue(content.contains("This is a test issue body"));
    }

    @Test
    void testIssueCommandClear() throws Exception {
        SlashCommand cmd = IssueCommandHandler.createIssueCommand();
        CommandContext ctx = createContext();

        // Set first
        cmd.execute(List.of("set", "Issue", "::", "Body"), ctx, noOpConsumer()).get();
        
        // Then clear
        CompletableFuture<CommandResult> future = cmd.execute(List.of("clear"), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        Path issueFile = tempDir.resolve(".jharness").resolve("issue.md");
        assertFalse(Files.exists(issueFile));
    }

    @Test
    void testPrCommentsCommand() throws Exception {
        SlashCommand cmd = PrCommentsCommandHandler.createPrCommentsCommand();
        assertEquals("pr_comments", cmd.getName());

        CommandContext ctx = createContext();

        // Add a comment
        CompletableFuture<CommandResult> future = cmd.execute(
            List.of("add", "src/main.java:42", "::", "Consider using Optional here"),
            ctx,
            noOpConsumer()
        );
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("已添加"));
    }

    @Test
    void testPrivacyCommand() throws Exception {
        SlashCommand cmd = PrivacyCommandHandler.createPrivacyCommand();
        assertEquals("privacy-settings", cmd.getName());

        CommandContext ctx = createContext();
        CompletableFuture<CommandResult> future = cmd.execute(List.of(), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("隐私设置"));
        assertTrue(result.getMessage().contains("配置目录"));
    }

    @Test
    void testUpgradeCommand() throws Exception {
        SlashCommand cmd = UpgradeCommandHandler.createUpgradeCommand();
        assertEquals("upgrade", cmd.getName());

        CommandContext ctx = createContext();
        CompletableFuture<CommandResult> future = cmd.execute(List.of(), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("当前版本"));
        assertTrue(result.getMessage().contains("升级说明"));
    }

    @Test
    void testReleaseNotesCommand() throws Exception {
        SlashCommand cmd = ReleaseNotesCommandHandler.createReleaseNotesCommand();
        assertEquals("release-notes", cmd.getName());

        CommandContext ctx = createContext();
        CompletableFuture<CommandResult> future = cmd.execute(List.of(), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("Release Notes"));
    }

    @Test
    void testRateLimitCommand() throws Exception {
        SlashCommand cmd = RateLimitCommandHandler.createRateLimitCommand();
        assertEquals("rate-limit-options", cmd.getName());

        CommandContext ctx = createContext();
        CompletableFuture<CommandResult> future = cmd.execute(List.of(), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("限流选项"));
    }

    @Test
    void testKeybindingsCommand() throws Exception {
        SlashCommand cmd = KeybindingsCommandHandler.createKeybindingsCommand();
        assertEquals("keybindings", cmd.getName());

        CommandContext ctx = createContext();
        CompletableFuture<CommandResult> future = cmd.execute(List.of(), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("快捷键"));
    }

    @Test
    void testBridgeCommandShow() throws Exception {
        SlashCommand cmd = BridgeCommandHandler.createBridgeCommand();
        assertEquals("bridge", cmd.getName());

        CommandContext ctx = createContext();
        CompletableFuture<CommandResult> future = cmd.execute(List.of("show"), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("Bridge"));
    }

    @Test
    void testBridgeCommandList() throws Exception {
        SlashCommand cmd = BridgeCommandHandler.createBridgeCommand();
        CommandContext ctx = createContext();

        CompletableFuture<CommandResult> future = cmd.execute(List.of("list"), ctx, noOpConsumer());
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
    }

    @Test
    void testBridgeCommandEncode() throws Exception {
        SlashCommand cmd = BridgeCommandHandler.createBridgeCommand();
        CommandContext ctx = createContext();

        CompletableFuture<CommandResult> future = cmd.execute(
            List.of("encode", "http://localhost:8080", "token123"),
            ctx,
            noOpConsumer()
        );
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("编码"));
    }

    @Test
    void testBridgeCommandDecode() throws Exception {
        SlashCommand cmd = BridgeCommandHandler.createBridgeCommand();
        CommandContext ctx = createContext();

        // First encode
        String encoded = java.util.Base64.getEncoder()
            .encodeToString("{\"api_base_url\":\"http://localhost:8080\",\"token\":\"token123\"}".getBytes());

        CompletableFuture<CommandResult> future = cmd.execute(
            List.of("decode", encoded),
            ctx,
            noOpConsumer()
        );
        CommandResult result = future.get();

        assertTrue(!result.getMessage().startsWith("错误:"));
        assertTrue(result.getMessage().contains("解码"));
    }

    @Test
    void testCommandRegistryContainsNewCommands() {
        CommandRegistry registry = new CommandRegistry();
        
        assertTrue(registry.lookup("issue").isPresent());
        assertTrue(registry.lookup("pr_comments").isPresent());
        assertTrue(registry.lookup("privacy-settings").isPresent());
        assertTrue(registry.lookup("upgrade").isPresent());
        assertTrue(registry.lookup("release-notes").isPresent());
        assertTrue(registry.lookup("rate-limit-options").isPresent());
        assertTrue(registry.lookup("keybindings").isPresent());
        assertTrue(registry.lookup("bridge").isPresent());
    }

    @Test
    void testCommandRegistrySize() {
        CommandRegistry registry = new CommandRegistry();
        assertTrue(registry.size() >= 50, "Expected 50+ commands, got: " + registry.size());
    }
}
