package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * /git 相关斜杠命令实现。
 *
 * 安全约束（应对 S-10 参数注入）：
 *   1. 仅允许 SAFE_GIT_SUBCOMMANDS 中的子命令；
 *   2. 拒绝任何 -c / --exec / --upload-pack / --receive-pack / --config-env 等
 *      允许改变 git 执行环境的危险选项；
 *   3. commit message 通过 "-m <单一参数>" 传递（ProcessBuilder 数组形式天然避开 shell），
 *      并限制长度、禁止换行注入新参数；
 *   4. 执行前 drain stdout 防止子进程因管道满阻塞。
 */
public class GitCommandHandler {

    /** 允许调用的 git 子命令白名单（第一个参数必须在此列表中）。 */
    private static final Set<String> SAFE_GIT_SUBCOMMANDS = Set.of(
            "diff", "branch", "commit", "add", "status", "log", "show"
    );

    /** 危险选项黑名单：这些选项可让 git 执行任意命令或切换配置环境。 */
    private static final Set<String> DANGEROUS_GIT_OPTIONS = Set.of(
            "-c", "--config-env", "--exec", "--exec-path",
            "--upload-pack", "--receive-pack",
            "-u", "--upload-archive"
    );

    private static final int MAX_COMMIT_MESSAGE_LENGTH = 2000;
    private static final int GIT_TIMEOUT_SECONDS = 30;

    private static SlashCommand cmd(String name, String desc, Handler h) {
        return new SimpleSlashCommand(name, desc, (args, ctx, ec) -> CompletableFuture.completedFuture(h.handle(args, ctx)));
    }

    @FunctionalInterface interface Handler { CommandResult handle(List<String> args, CommandContext ctx); }

    public static SlashCommand createDiffCommand() {
        return cmd("diff", "显示 git diff", (args, ctx) ->
                "full".equals(args.isEmpty() ? "" : args.get(0))
                        ? runGit(ctx, "diff", "HEAD")
                        : runGit(ctx, "diff", "--stat"));
    }

    public static SlashCommand createBranchCommand() {
        return cmd("branch", "分支信息", (args, ctx) -> {
            String a = args.isEmpty() ? "show" : args.get(0);
            if ("show".equals(a)) return runGit(ctx, "branch", "--show-current");
            if ("list".equals(a)) return runGit(ctx, "branch", "--format", "%(refname:short)");
            return CommandResult.success("用法: /branch [show|list]");
        });
    }

    public static SlashCommand createCommitCommand() {
        return cmd("commit", "git 提交", (args, ctx) -> {
            if (args.isEmpty()) return runGit(ctx, "status", "--short");
            String message = String.join(" ", args);
            if (message.length() > MAX_COMMIT_MESSAGE_LENGTH) {
                return CommandResult.error("commit message 超过 " + MAX_COMMIT_MESSAGE_LENGTH + " 字符");
            }
            if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
                return CommandResult.error("commit message 不允许包含换行");
            }
            runGitRaw(ctx, "add", "-A");
            return runGit(ctx, "commit", "-m", message);
        });
    }

    public static SlashCommand createFilesCommand() {
        return cmd("files", "列出文件", (args, ctx) -> {
            String pattern = args.isEmpty() ? "" : args.get(0);
            Path root = ctx.getCwd();
            try (var s = Files.walk(root, 3)) {
                var f = s.filter(p -> !p.toString().contains("/.git/")
                                && !p.toString().contains("/.venv/")
                                && !p.toString().contains("/node_modules/"))
                        .filter(Files::isRegularFile)
                        .filter(p -> pattern.isEmpty()
                                || p.toString().toLowerCase().contains(pattern.toLowerCase()))
                        .limit(50)
                        .map(p -> root.relativize(p).toString())
                        .toList();
                return f.isEmpty() ? CommandResult.success("无匹配文件")
                        : CommandResult.success(String.join("\n", f));
            } catch (Exception e) {
                return CommandResult.error("失败: " + e.getMessage());
            }
        });
    }

    /**
     * 参数级安全校验：子命令白名单 + 危险选项拦截 + 禁止换行注入。
     * 注意：所有 runGit 调用者都是本类内部传入的字面量，但仍然校验以防后续重构误用。
     */
    private static String validateGitArgs(String[] args) {
        if (args.length == 0) {
            return "git 子命令不能为空";
        }
        String sub = args[0];
        if (!SAFE_GIT_SUBCOMMANDS.contains(sub)) {
            return "不允许的 git 子命令: " + sub;
        }
        for (String a : args) {
            if (a == null) return "参数不能为 null";
            if (a.indexOf('\n') >= 0 || a.indexOf('\r') >= 0) {
                return "参数不允许包含换行";
            }
            if (DANGEROUS_GIT_OPTIONS.contains(a)) {
                return "禁止使用危险的 git 选项: " + a;
            }
            // 前缀形式（如 --upload-pack=evil.sh）也要拦截
            for (String opt : DANGEROUS_GIT_OPTIONS) {
                if (opt.startsWith("--") && a.startsWith(opt + "=")) {
                    return "禁止使用危险的 git 选项: " + a;
                }
            }
        }
        return null;
    }

    private static CommandResult runGit(CommandContext ctx, String... args) {
        String violation = validateGitArgs(args);
        if (violation != null) {
            return CommandResult.error(violation);
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("git");
            for (String a : args) pb.command().add(a);
            pb.directory(ctx.getCwd().toFile());
            pb.redirectErrorStream(true);
            p = pb.start();
            // 超时前必须持续 drain stdout，否则大量输出会阻塞 git 子进程（H-3 同源问题）
            String output = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                return CommandResult.error("git 命令超时");
            }
            String trimmed = output.trim();
            return CommandResult.success(trimmed.isEmpty() ? "(无输出)" : trimmed);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (p != null && p.isAlive()) p.destroyForcibly();
            return CommandResult.error("git 被中断");
        } catch (Exception e) {
            if (p != null && p.isAlive()) p.destroyForcibly();
            return CommandResult.error("git 失败: " + e.getMessage());
        }
    }

    private static CommandResult runGitRaw(CommandContext ctx, String... args) {
        String violation = validateGitArgs(args);
        if (violation != null) {
            return CommandResult.error(violation);
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("git");
            for (String a : args) pb.command().add(a);
            pb.directory(ctx.getCwd().toFile());
            pb.redirectErrorStream(true);
            p = pb.start();
            // 即使不需要输出，也要 drain，否则超时
            p.getInputStream().readAllBytes();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                return CommandResult.error("git 命令超时");
            }
            return CommandResult.success("ok");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (p != null && p.isAlive()) p.destroyForcibly();
            return CommandResult.error("git 被中断");
        } catch (Exception e) {
            if (p != null && p.isAlive()) p.destroyForcibly();
            return CommandResult.error("git 失败: " + e.getMessage());
        }
    }
}
