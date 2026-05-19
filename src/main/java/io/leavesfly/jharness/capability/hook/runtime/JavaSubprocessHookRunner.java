package io.leavesfly.jharness.capability.hook.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.capability.hook.HookResult;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Prompt/Agent Hook 共用的 Java 子进程执行器。
 *
 * 启动独立 JHarness Java 子进程，把 fullPrompt 通过 {@code -p} 参数传入，
 * 子进程读取 {@code JHARNESS_HOOK_DEPTH} 继续累计递归深度，避免 Prompt→Agent→Prompt 爆炸。
 */
public final class JavaSubprocessHookRunner {

    private static final Logger logger = LoggerFactory.getLogger(JavaSubprocessHookRunner.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;
    private static final long MAX_COMMAND_OUTPUT_BYTES = 2L * 1024 * 1024;

    private JavaSubprocessHookRunner() {}

    public static HookResult run(String hookType,
                                  String fullPrompt,
                                  int timeoutSeconds,
                                  boolean blockOnFailure,
                                  List<String> extraArgs,
                                  String label,
                                  HookRunContext ctx) {
        Process process = null;
        try {
            String javaHome = System.getProperty("java.home");
            String classpath = System.getProperty("java.class.path");

            List<String> cmd = new ArrayList<>();
            cmd.add(javaHome + "/bin/java");
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add("io.leavesfly.jharness.JHarnessApplication");
            cmd.add("-p");
            cmd.add(fullPrompt);
            cmd.addAll(extraArgs);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (ctx.getCwd() != null) {
                pb.directory(ctx.getCwd().toFile());
            }
            pb.redirectErrorStream(true);
            pb.environment().put("JHARNESS_HOOK_DEPTH", String.valueOf(ctx.getCurrentDepth()));

            process = pb.start();

            SubprocessIo.CapturedOutput captured = SubprocessIo.readLimited(process, MAX_COMMAND_OUTPUT_BYTES);

            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                SubprocessIo.destroyGracefully(process);
                return new HookResult(hookType, false, null, blockOnFailure,
                        label + " hook timed out");
            }

            String result = captured.text.trim();
            if (captured.truncated) {
                result = result + "\n...[输出被截断]";
            }
            boolean allowed = result.toUpperCase().contains("ALLOW");

            return new HookResult(hookType, allowed, result,
                    blockOnFailure && !allowed,
                    allowed ? null : label + " hook denied: " + result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                SubprocessIo.destroyGracefully(process);
            }
            return new HookResult(hookType, false, null, blockOnFailure, label + " hook interrupted");
        } catch (Exception e) {
            logger.error(label + " hook 执行失败", e);
            if (process != null && process.isAlive()) {
                SubprocessIo.destroyGracefully(process);
            }
            return new HookResult(hookType, false, null, blockOnFailure, e.getMessage());
        }
    }

    /** 把 payload 转为可读字符串（pretty JSON），异常回退到 toString。 */
    public static String payloadToString(Map<String, Object> payload) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }
}
