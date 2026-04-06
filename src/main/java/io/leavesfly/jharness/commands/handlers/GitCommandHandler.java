package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SimpleSlashCommand;
import io.leavesfly.jharness.commands.SlashCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class GitCommandHandler {

    private static SlashCommand cmd(String name, String desc, Handler h) {
        return new SimpleSlashCommand(name, desc, (args, ctx, ec) -> CompletableFuture.completedFuture(h.handle(args, ctx)));
    }

    @FunctionalInterface interface Handler { CommandResult handle(List<String> args, CommandContext ctx); }

    public static SlashCommand createDiffCommand() {
        return cmd("diff", "显示 git diff", (args, ctx) -> "full".equals(args.isEmpty() ? "" : args.get(0)) ? runGit(ctx, "diff", "HEAD") : runGit(ctx, "diff", "--stat"));
    }

    public static SlashCommand createBranchCommand() {
        return cmd("branch", "分支信息", (args, ctx) -> { String a = args.isEmpty() ? "show" : args.get(0); return "show".equals(a) ? runGit(ctx, "branch", "--show-current") : "list".equals(a) ? runGit(ctx, "branch", "--format", "%(refname:short)") : CommandResult.success("用法: /branch [show|list]"); });
    }

    public static SlashCommand createCommitCommand() {
        return cmd("commit", "git 提交", (args, ctx) -> { if (args.isEmpty()) return runGit(ctx, "status", "--short"); runGitRaw(ctx, "add", "-A"); return runGit(ctx, "commit", "-m", String.join(" ", args)); });
    }

    public static SlashCommand createFilesCommand() {
        return cmd("files", "列出文件", (args, ctx) -> {
            String pattern = args.isEmpty() ? "" : args.get(0); Path root = ctx.getCwd();
            try (var s = Files.walk(root, 3)) { var f = s.filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("/.venv/") && !p.toString().contains("/node_modules/")).filter(Files::isRegularFile).filter(p -> pattern.isEmpty() || p.toString().toLowerCase().contains(pattern.toLowerCase())).limit(50).map(p -> root.relativize(p).toString()).toList(); return f.isEmpty() ? CommandResult.success("无匹配文件") : CommandResult.success(String.join("\n", f)); } catch (Exception e) { return CommandResult.error("失败: " + e.getMessage()); }
        });
    }

    private static CommandResult runGit(CommandContext ctx, String... args) { try { ProcessBuilder pb = new ProcessBuilder("git"); for (String a : args) pb.command().add(a); pb.directory(ctx.getCwd().toFile()); pb.redirectErrorStream(true); Process p = pb.start(); String o = new String(p.getInputStream().readAllBytes()); p.waitFor(); return CommandResult.success(o.trim().isEmpty() ? "(无输出)" : o.trim()); } catch (Exception e) { return CommandResult.error("git 失败: " + e.getMessage()); } }
    private static CommandResult runGitRaw(CommandContext ctx, String... args) { try { ProcessBuilder pb = new ProcessBuilder("git"); for (String a : args) pb.command().add(a); pb.directory(ctx.getCwd().toFile()); pb.start().waitFor(); return CommandResult.success("ok"); } catch (Exception e) { return CommandResult.error("git 失败: " + e.getMessage()); } }
}
