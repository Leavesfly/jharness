package io.leavesfly.jharness.capability.hook.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.capability.hook.HookResult;
import io.leavesfly.jharness.capability.hook.schemas.HookDefinition;
import io.leavesfly.jharness.capability.permission.PermissionChecker;
import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Command Hook 执行器：fork bash/cmd 子进程，通过 stdin + 环境变量传递 payload。
 *
 * 安全策略（沿用原 HookExecutor 实现）：
 *   - payload 一律通过 stdin + env 传递，禁止 $ARGUMENTS 字符串替换；
 *   - 启发式检测命令模板里的 $(...$ARGUMENTS...) 注入模式直接拒绝；
 *   - 若注入 PermissionChecker，则按 "bash" 工具名 + 命令字符串走一次权限评估；
 *   - 子进程环境变量带 JHARNESS_HOOK_DEPTH，跨进程累计递归深度；
 *   - 输出大小上限 2 MB，超时后 destroy + destroyForcibly。
 */
public class CommandHookRunner implements HookRunner<HookDefinition.CommandHookDefinition> {

    private static final Logger logger = LoggerFactory.getLogger(CommandHookRunner.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;
    private static final long MAX_COMMAND_OUTPUT_BYTES = 2L * 1024 * 1024;

    /** 启发式检测：命令模板里嵌入 $ARGUMENTS 到命令替换中的危险写法。 */
    private static final Pattern DANGEROUS_ARG_EMBED = Pattern.compile(
            "\\$\\([^)]*\\$(?:JHARNESS_ARGUMENTS|ARGUMENTS)[^)]*\\)"
                    + "|`[^`]*\\$(?:JHARNESS_ARGUMENTS|ARGUMENTS)[^`]*`");

    @Override
    public HookResult run(HookDefinition.CommandHookDefinition hook, HookRunContext ctx) {
        String command = hook.getCommand();
        if (command == null || command.isBlank()) {
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                    "Command hook 模板为空");
        }

        if (DANGEROUS_ARG_EMBED.matcher(command).find()) {
            logger.warn("Hook 命令模板含有危险的 payload 嵌入模式，已阻止: {}", command);
            return new HookResult(hook.getType(), false, null, true,
                    "命令模板将 payload 嵌入到命令替换 $(...) / `...` 中，存在注入风险，已拒绝");
        }

        PermissionChecker checker = ctx.getPermissionChecker();
        if (checker != null) {
            PermissionDecision decision = checker.evaluate("bash", false, null, command);
            if (decision != null && !decision.isAllowed() && !decision.isRequiresConfirmation()) {
                logger.warn("Hook 命令被权限拒绝: {}, reason={}", command, decision.getReason());
                return new HookResult(hook.getType(), false, null, true,
                        "Hook 命令被权限拒绝: " + decision.getReason());
            }
        }

        Process process = null;
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd", "/c", command)
                    : new ProcessBuilder("bash", "-c", command);

            if (ctx.getCwd() != null) {
                pb.directory(ctx.getCwd().toFile());
            }

            String payloadJson = MAPPER.writeValueAsString(ctx.getPayload());
            Map<String, String> env = pb.environment();
            env.put("OPENHARNESS_HOOK_EVENT", ctx.getEvent().name());
            env.put("OPENHARNESS_HOOK_PAYLOAD", payloadJson);
            env.put("JHARNESS_ARGUMENTS", payloadJson);
            env.put("JHARNESS_HOOK_DEPTH", String.valueOf(ctx.getCurrentDepth()));

            pb.redirectErrorStream(true);
            process = pb.start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(payloadJson.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            } catch (IOException ignore) {
                // 子进程可能不读 stdin，写入失败可忽略
            }

            SubprocessIo.CapturedOutput captured = SubprocessIo.readLimited(process, MAX_COMMAND_OUTPUT_BYTES);

            boolean completed = process.waitFor(hook.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                SubprocessIo.destroyGracefully(process);
                return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                        String.format("Command hook timed out after %ds", hook.getTimeoutSeconds()));
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String outputStr = captured.text.trim();
            if (captured.truncated) {
                outputStr = outputStr + "\n...[输出被截断，超过 " + MAX_COMMAND_OUTPUT_BYTES + " 字节]";
            }

            return new HookResult(
                    hook.getType(), success, outputStr,
                    hook.isBlockOnFailure() && !success,
                    success ? null
                            : (outputStr.isEmpty()
                                    ? String.format("Command hook failed with exit code %d", exitCode)
                                    : outputStr));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                SubprocessIo.destroyGracefully(process);
            }
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                    "Command hook interrupted");
        } catch (IOException e) {
            if (process != null && process.isAlive()) {
                SubprocessIo.destroyGracefully(process);
            }
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), e.getMessage());
        }
    }
}
