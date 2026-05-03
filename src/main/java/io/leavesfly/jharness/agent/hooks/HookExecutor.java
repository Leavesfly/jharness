package io.leavesfly.jharness.agent.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.agent.hooks.schemas.HookDefinition;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.session.permissions.PermissionDecision;
import io.leavesfly.jharness.util.JacksonUtils;
import io.leavesfly.jharness.util.UrlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Hook 执行引擎
 *
 * 支持 4 种 Hook 类型：
 * - Command: 执行 shell 命令
 * - HTTP: 发送 HTTP POST 请求
 * - Prompt: 使用 LLM 验证（简化实现）
 * - Agent: 使用 Agent 深度验证（简化实现）
 *
 * 安全策略（S-2 应对命令注入 + 递归爆炸）：
 *   1. Command Hook：payload 一律通过 stdin + 环境变量传递，禁止 $ARGUMENTS 字符串替换；
 *   2. 命令模板若出现 $ARGUMENTS、$(...) 嵌入 payload 字段的启发式模式，记录 WARN；
 *   3. HTTP Hook：URL 走 UrlSafetyValidator（SSRF 防护）+ 请求体大小上限 + 响应体大小上限；
 *   4. 递归深度：ThreadLocal 计数 + 环境变量 JHARNESS_HOOK_DEPTH 跨进程传递，
 *      超过 MAX_HOOK_DEPTH 的嵌套直接拒绝，防止 Prompt/Agent Hook 递归爆炸。
 */
public class HookExecutor {
    private static final Logger logger = LoggerFactory.getLogger(HookExecutor.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    /** Hook 递归最大深度。3 层已足够覆盖"Hook → Hook 触发 Agent → Agent 里又触发 Hook"场景。 */
    private static final int MAX_HOOK_DEPTH = 3;

    /** HTTP Hook 请求体大小上限（防 payload 爆炸）。 */
    private static final long MAX_HTTP_REQUEST_BODY_BYTES = 10L * 1024 * 1024;

    /** HTTP Hook 响应体大小上限。 */
    private static final long MAX_HTTP_RESPONSE_BODY_BYTES = 4L * 1024 * 1024;

    /**
     * Command / Subprocess Hook 的输出大小上限（2 MB）。
     * 超过后继续读取但丢弃，防止子进程海量输出打爆内存 / 日志。
     */
    private static final long MAX_COMMAND_OUTPUT_BYTES = 2L * 1024 * 1024;

    /** 当前线程的 Hook 递归深度（由 HookExecutor 内部维护）。 */
    private static final ThreadLocal<Integer> HOOK_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 启发式检测：命令模板里嵌入 $ARGUMENTS 到命令替换中的危险写法。 */
    private static final Pattern DANGEROUS_ARG_EMBED = Pattern.compile(
            "\\$\\([^)]*\\$(?:JHARNESS_ARGUMENTS|ARGUMENTS)[^)]*\\)"
                    + "|`[^`]*\\$(?:JHARNESS_ARGUMENTS|ARGUMENTS)[^`]*`");

    private final HookRegistry registry;
    private final java.nio.file.Path cwd;

    /**
     * FP-3：可选的 PermissionChecker。注入后，Command Hook 在 fork 子进程前会先走一次
     * 权限评估（按 "bash" 工具名 + 命令字符串）。未注入时保持旧行为。
     */
    private volatile PermissionChecker permissionChecker;

    public HookExecutor(HookRegistry registry, java.nio.file.Path cwd) {
        this.registry = registry;
        this.cwd = cwd;
    }

    /**
     * FP-3：注入 PermissionChecker，使 Command Hook 与前台工具共用同一套权限栅栏。
     */
    public void setPermissionChecker(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /**
     * 读取当前进程启动时由父级 HookExecutor 写入的深度，默认 0。
     */
    private static int currentDepth() {
        Integer local = HOOK_DEPTH.get();
        if (local != null && local > 0) {
            return local;
        }
        String env = System.getenv("JHARNESS_HOOK_DEPTH");
        if (env == null || env.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(env.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 执行指定事件的所有 Hook。
     *
     * 递归深度防护（S-2）：
     *   Prompt/Agent Hook 会 fork 子进程调用 JHarness 本身，子进程若又触发 Hook 会递归爆炸。
     *   本方法在入口检查 currentDepth()，若 >= MAX_HOOK_DEPTH 则拒绝所有 Hook 并返回 blocking 结果，
     *   执行过程中 HOOK_DEPTH +1，子进程会读取环境变量 JHARNESS_HOOK_DEPTH 继续累计。
     */
    public CompletableFuture<List<HookResult>> execute(HookEvent event, Map<String, Object> payload) {
        int enterDepth = currentDepth();
        if (enterDepth >= MAX_HOOK_DEPTH) {
            HookResult blocked = new HookResult(
                    "depth-guard", false, null, true,
                    "Hook 递归深度超过上限 " + MAX_HOOK_DEPTH + "，已阻止继续触发");
            logger.warn("Hook 递归深度 {} 已达上限 {}，事件 {} 被阻断", enterDepth, MAX_HOOK_DEPTH, event);
            return CompletableFuture.completedFuture(List.of(blocked));
        }

        return CompletableFuture.supplyAsync(() -> {
            logger.debug("执行 Hook 事件: {} (depth={})", event, enterDepth);

            HOOK_DEPTH.set(enterDepth + 1);
            try {
                List<HookResult> results = new ArrayList<>();

                for (Object hookDef : registry.get(event)) {
                    if (!matchesHook(hookDef, payload)) {
                        continue;
                    }

                    HookResult result;
                    if (hookDef instanceof HookDefinition.CommandHookDefinition) {
                        result = runCommandHook((HookDefinition.CommandHookDefinition) hookDef, event, payload);
                    } else if (hookDef instanceof HookDefinition.HttpHookDefinition) {
                        result = runHttpHook((HookDefinition.HttpHookDefinition) hookDef, event, payload);
                    } else if (hookDef instanceof HookDefinition.PromptHookDefinition) {
                        result = runPromptHook((HookDefinition.PromptHookDefinition) hookDef, event, payload);
                    } else if (hookDef instanceof HookDefinition.AgentHookDefinition) {
                        result = runAgentHook((HookDefinition.AgentHookDefinition) hookDef, event, payload);
                    } else {
                        continue;
                    }

                    results.add(result);
                }

                return results;
            } finally {
                HOOK_DEPTH.remove();
            }
        });
    }

    /**
     * 执行 Command Hook
     *
     * 安全策略（S-2 完整实现）：
     *   1. 命令模板在 hooks.json 中由管理员预先定义，视为可信；
     *   2. payload（含 tool_name / prompt 等 LLM 可控内容）通过三种安全通道传递，
     *      绝不拼接到命令行参数里：
     *        - 标准输入 stdin：以 JSON 字符串写入子进程，模板可用
     *          `IFS= read -r JSON <&0` 或 `jq . <<<"$(cat)"` 读取；
     *        - 环境变量 OPENHARNESS_HOOK_PAYLOAD / JHARNESS_ARGUMENTS：
     *          管理员如选择在模板里用 "$JHARNESS_ARGUMENTS" 引用，必须加双引号保护；
     *        - 环境变量 OPENHARNESS_HOOK_EVENT：事件名；
     *   3. 对模板做启发式危险模式扫描（见 DANGEROUS_ARG_EMBED），若发现
     *      $(...$ARGUMENTS...) 或 `...$ARGUMENTS...` 这类"命令替换里嵌入 payload"
     *      的写法，直接拒绝执行——这种写法一旦 payload 带 `;rm -rf ~` 仍可注入；
     *   4. 向子进程传递 JHARNESS_HOOK_DEPTH，使 Hook 链跨进程递归计数不被绕过；
     *   5. 超时后先 destroy 再 destroyForcibly，避免子进程残留；
     *   6. 输出大小上限，防止 Hook 输出爆炸打爆日志。
     */
    private HookResult runCommandHook(HookDefinition.CommandHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        String command = hook.getCommand();
        if (command == null || command.isBlank()) {
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                    "Command hook 模板为空");
        }

        // 启发式检测：若模板将 payload 嵌入到命令替换 $(...) / `...` 中，拒绝执行。
        if (DANGEROUS_ARG_EMBED.matcher(command).find()) {
            logger.warn("Hook 命令模板含有危险的 payload 嵌入模式，已阻止: {}", command);
            return new HookResult(hook.getType(), false, null, true,
                    "命令模板将 payload 嵌入到命令替换 $(...) / `...` 中，存在注入风险，已拒绝");
        }

        // FP-3：走 PermissionChecker 的命令黑名单 + 工具黑白名单。
        // 即使 Hook 是在 hooks.json 中被管理员预先定义的可信模板，也不应成为绕过
        // 运行时命令黑名单的通道（例如管理员允许 Hook 但集团策略禁用 `sudo rm`）。
        PermissionChecker checker = permissionChecker;
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
            ProcessBuilder pb;
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            if (cwd != null) {
                pb.directory(cwd.toFile());
            }

            String payloadJson = MAPPER.writeValueAsString(payload);
            Map<String, String> env = pb.environment();
            env.put("OPENHARNESS_HOOK_EVENT", event.name());
            env.put("OPENHARNESS_HOOK_PAYLOAD", payloadJson);
            // 为兼容旧 hooks.json，仍提供 JHARNESS_ARGUMENTS 环境变量；
            // 管理员在模板里必须用 "$JHARNESS_ARGUMENTS"（带双引号）引用，否则有注入风险。
            env.put("JHARNESS_ARGUMENTS", payloadJson);
            // 递归深度跨进程传递，见 execute() / currentDepth()。
            env.put("JHARNESS_HOOK_DEPTH", String.valueOf(currentDepth()));

            pb.redirectErrorStream(true);
            process = pb.start();

            // 通过 stdin 将 payload JSON 安全喂给子进程，推荐脚本从 stdin 读取。
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(payloadJson.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            } catch (IOException ignore) {
                // 子进程可能不读 stdin，写入失败可忽略；管道已关闭不影响后续读输出。
            }

            StringBuilder output = new StringBuilder();
            long totalBytes = 0L;
            final long outputLimit = MAX_COMMAND_OUTPUT_BYTES;
            boolean truncated = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    if (totalBytes + n > outputLimit) {
                        int remain = (int) (outputLimit - totalBytes);
                        if (remain > 0) {
                            output.append(buf, 0, remain);
                            totalBytes += remain;
                        }
                        truncated = true;
                        // 继续读取但丢弃，以免子进程因管道阻塞无法退出
                        //noinspection StatementWithEmptyBody
                        while (reader.read(buf) != -1) { /* drain */ }
                        break;
                    }
                    output.append(buf, 0, n);
                    totalBytes += n;
                }
            }

            boolean completed = process.waitFor(hook.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);

            if (!completed) {
                destroyProcessGracefully(process);
                return new HookResult(
                        hook.getType(), false, null,
                        hook.isBlockOnFailure(),
                        String.format("Command hook timed out after %ds", hook.getTimeoutSeconds())
                );
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String outputStr = output.toString().trim();
            if (truncated) {
                outputStr = outputStr + "\n...[输出被截断，超过 " + outputLimit + " 字节]";
            }

            return new HookResult(
                    hook.getType(), success, outputStr,
                    hook.isBlockOnFailure() && !success,
                    success ? null : (outputStr.isEmpty() ? String.format("Command hook failed with exit code %d", exitCode) : outputStr)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                destroyProcessGracefully(process);
            }
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), "Command hook interrupted");
        } catch (IOException e) {
            if (process != null && process.isAlive()) {
                destroyProcessGracefully(process);
            }
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), e.getMessage());
        }
    }

    /**
     * 优雅销毁进程：先 destroy（SIGTERM），等 3s 无响应则 destroyForcibly（SIGKILL）。
     */
    private static void destroyProcessGracefully(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * 执行 HTTP Hook
     *
     * 安全策略（S-2 / S-4 补强）：
     *   1. URL 走 UrlSafetyValidator：仅允许 http/https，拒绝回环/内网/云元数据等；
     *   2. HttpClient 配置 Redirect.NEVER，避免 302 → 内网地址绕过 URL 校验；
     *   3. 请求体大小上限 MAX_HTTP_REQUEST_BODY_BYTES，防止 payload 爆炸；
     *   4. 响应以字节读取，超过 MAX_HTTP_RESPONSE_BODY_BYTES 则截断并标记，
     *      防止恶意/异常服务端返回超大响应打爆内存。
     */
    private HookResult runHttpHook(HookDefinition.HttpHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        String safetyError = UrlSafetyValidator.validate(hook.getUrl());
        if (safetyError != null) {
            logger.warn("拒绝不安全的 Hook HTTP URL: {}, reason={}", hook.getUrl(), safetyError);
            return new HookResult(hook.getType(), false, null, true,
                    "HTTP Hook URL 不安全: " + safetyError);
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(hook.getTimeoutSeconds()))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            Map<String, Object> body = Map.of(
                    "event", event.name(),
                    "payload", payload,
                    "hook_depth", currentDepth()
            );
            byte[] bodyBytes = MAPPER.writeValueAsBytes(body);
            if (bodyBytes.length > MAX_HTTP_REQUEST_BODY_BYTES) {
                return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                        "HTTP Hook 请求体超过上限 " + MAX_HTTP_REQUEST_BODY_BYTES
                                + " 字节，实际 " + bodyBytes.length);
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(hook.getUrl()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .timeout(Duration.ofSeconds(hook.getTimeoutSeconds()))
                    .header("Content-Type", "application/json");

            for (Map.Entry<String, String> header : hook.getHeaders().entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }

            HttpResponse<byte[]> response = client.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            byte[] respBytes = response.body() == null ? new byte[0] : response.body();
            boolean truncated = false;
            if (respBytes.length > MAX_HTTP_RESPONSE_BODY_BYTES) {
                byte[] cut = new byte[(int) MAX_HTTP_RESPONSE_BODY_BYTES];
                System.arraycopy(respBytes, 0, cut, 0, cut.length);
                respBytes = cut;
                truncated = true;
            }
            String output = new String(respBytes, StandardCharsets.UTF_8);
            if (truncated) {
                output = output + "\n...[响应被截断，超过 " + MAX_HTTP_RESPONSE_BODY_BYTES + " 字节]";
            }

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;

            return new HookResult(
                    hook.getType(), success, output,
                    hook.isBlockOnFailure() && !success,
                    success ? null : String.format("HTTP hook returned %d: %s", response.statusCode(), output)
            );
        } catch (Exception e) {
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), e.getMessage());
        }
    }

    /**
     * 执行 Prompt Hook：通过子进程调用 JHarness 单次查询模式，
     * 由 LLM 根据 hook prompt + payload 判断是否允许操作。
     */
    private HookResult runPromptHook(HookDefinition.PromptHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        logger.debug("Prompt hook triggered: {}", hook.getPrompt());

        String fullPrompt = hook.getPrompt() + "\n\nPayload:\n" + payloadToString(payload)
                + "\n\nRespond with ONLY 'ALLOW' or 'DENY' followed by a brief reason.";

        return runJavaSubprocessHook(
                hook.getType(),
                fullPrompt,
                hook.getTimeoutSeconds(),
                hook.isBlockOnFailure(),
                List.of("--permission-mode", "plan"),
                "Prompt");
    }

    /**
     * 执行 Agent Hook：启动独立的 JHarness Agent 子进程进行深度验证，
     * Agent 可以使用工具进行更复杂的检查。
     */
    private HookResult runAgentHook(HookDefinition.AgentHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        logger.debug("Agent hook triggered: {}", hook.getPrompt());

        String fullPrompt = hook.getPrompt() + "\n\nContext:\n" + payloadToString(payload)
                + "\n\nPerform your analysis and respond with ALLOW or DENY followed by your reasoning.";

        return runJavaSubprocessHook(
                hook.getType(),
                fullPrompt,
                hook.getTimeoutSeconds(),
                hook.isBlockOnFailure(),
                List.of("--permission-mode", "full_auto", "--max-turns", "3"),
                "Agent");
    }

    /**
     * 公共 Java 子进程 Hook 执行逻辑，供 Prompt/Agent Hook 复用。
     *
     * 安全/稳定性要点：
     *   - 将当前 HOOK_DEPTH 写入子进程环境变量 JHARNESS_HOOK_DEPTH，
     *     子进程 HookExecutor 会读取并继续累加，超过 MAX_HOOK_DEPTH 时拒绝执行，
     *     防止 Prompt→Agent→Prompt 递归爆炸；
     *   - 使用 ProcessBuilder 的数组形式传参，避免 shell 解释；
     *   - 输出带大小上限，防止子进程打出海量内容；
     *   - 超时后 destroy + destroyForcibly。
     */
    private HookResult runJavaSubprocessHook(String hookType,
                                             String fullPrompt,
                                             int timeoutSeconds,
                                             boolean blockOnFailure,
                                             List<String> extraArgs,
                                             String label) {
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
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(true);
            // 跨进程传递深度，子进程 currentDepth() 会读到
            pb.environment().put("JHARNESS_HOOK_DEPTH", String.valueOf(currentDepth()));

            process = pb.start();

            StringBuilder output = new StringBuilder();
            long totalBytes = 0L;
            final long outputLimit = MAX_COMMAND_OUTPUT_BYTES;
            boolean truncated = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    if (totalBytes + n > outputLimit) {
                        int remain = (int) (outputLimit - totalBytes);
                        if (remain > 0) {
                            output.append(buf, 0, remain);
                            totalBytes += remain;
                        }
                        truncated = true;
                        //noinspection StatementWithEmptyBody
                        while (reader.read(buf) != -1) { /* drain */ }
                        break;
                    }
                    output.append(buf, 0, n);
                    totalBytes += n;
                }
            }

            boolean completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                destroyProcessGracefully(process);
                return new HookResult(hookType, false, null, blockOnFailure,
                        label + " hook timed out");
            }

            String result = output.toString().trim();
            if (truncated) {
                result = result + "\n...[输出被截断]";
            }
            boolean allowed = result.toUpperCase().contains("ALLOW");

            return new HookResult(hookType, allowed, result,
                    blockOnFailure && !allowed,
                    allowed ? null : label + " hook denied: " + result);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                destroyProcessGracefully(process);
            }
            return new HookResult(hookType, false, null, blockOnFailure, label + " hook interrupted");
        } catch (Exception e) {
            logger.error(label + " hook 执行失败", e);
            if (process != null && process.isAlive()) {
                destroyProcessGracefully(process);
            }
            return new HookResult(hookType, false, null, blockOnFailure, e.getMessage());
        }
    }

    /**
     * 将 payload 转换为可读字符串
     */
    private String payloadToString(Map<String, Object> payload) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    /**
     * 检查 Hook 是否匹配当前载荷
     */
    private boolean matchesHook(Object hookDef, Map<String, Object> payload) {
        String matcher = null;
        
        if (hookDef instanceof HookDefinition.CommandHookDefinition) {
            matcher = ((HookDefinition.CommandHookDefinition) hookDef).getMatcher();
        } else if (hookDef instanceof HookDefinition.HttpHookDefinition) {
            matcher = ((HookDefinition.HttpHookDefinition) hookDef).getMatcher();
        } else if (hookDef instanceof HookDefinition.PromptHookDefinition) {
            matcher = ((HookDefinition.PromptHookDefinition) hookDef).getMatcher();
        } else if (hookDef instanceof HookDefinition.AgentHookDefinition) {
            matcher = ((HookDefinition.AgentHookDefinition) hookDef).getMatcher();
        }

        if (matcher == null || matcher.isEmpty()) {
            return true;
        }

        String subject = "";
        if (payload.containsKey("tool_name")) {
            subject = String.valueOf(payload.get("tool_name"));
        } else if (payload.containsKey("prompt")) {
            subject = String.valueOf(payload.get("prompt"));
        } else if (payload.containsKey("event")) {
            subject = String.valueOf(payload.get("event"));
        }

        return fnmatch(subject, matcher);
    }

    /**
     * 简单的 fnmatch 实现（支持 * 和 ? 通配符）。
     *
     * 实现说明：之前版本用 {@code Pattern.quote(pattern).replace("\\E*\\Q", …)}，
     * 对 "*"、"?abc"、"abc*" 等模式无法正确替换（Pattern.quote 只在字符串两端加 \Q\E，
     * 中间没有 \E*\Q 子串）。此处改为手动扫描 pattern，遇到通配符转成正则，
     * 其它字符用 {@code Pattern.quote} 逐段包裹，保证兼容所有边界情况。
     */
    private boolean fnmatch(String str, String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.matches(regex.toString(), str);
    }
}
