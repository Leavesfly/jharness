package io.leavesfly.jharness.agent.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.agent.hooks.schemas.HookDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 */
public class HookExecutor {
    private static final Logger logger = LoggerFactory.getLogger(HookExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private final HookRegistry registry;
    private final java.nio.file.Path cwd;

    public HookExecutor(HookRegistry registry, java.nio.file.Path cwd) {
        this.registry = registry;
        this.cwd = cwd;
    }

    /**
     * 执行指定事件的所有 Hook
     */
    public CompletableFuture<List<HookResult>> execute(HookEvent event, Map<String, Object> payload) {
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("执行 Hook 事件: {}", event);
            
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
        });
    }

    /**
     * 执行 Command Hook
     */
    private HookResult runCommandHook(HookDefinition.CommandHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        String command = injectArguments(hook.getCommand(), payload);
        
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            if (cwd != null) {
                pb.directory(cwd.toFile());
            }

            pb.environment().put("OPENHARNESS_HOOK_EVENT", event.name());
            pb.environment().put("OPENHARNESS_HOOK_PAYLOAD", MAPPER.writeValueAsString(payload));
            // 通过环境变量安全传递参数，避免命令行注入
            pb.environment().put("JHARNESS_ARGUMENTS", MAPPER.writeValueAsString(payload));
            
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(hook.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            
            if (!completed) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return new HookResult(
                    hook.getType(), false, null,
                    hook.isBlockOnFailure(),
                    String.format("Command hook timed out after %ds", hook.getTimeoutSeconds())
                );
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String outputStr = output.toString().trim();

            return new HookResult(
                hook.getType(), success, outputStr,
                hook.isBlockOnFailure() && !success,
                success ? null : (outputStr.isEmpty() ? String.format("Command hook failed with exit code %d", exitCode) : outputStr)
            );
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), e.getMessage());
        }
    }

    /**
     * 执行 HTTP Hook
     */
    private HookResult runHttpHook(HookDefinition.HttpHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(hook.getTimeoutSeconds()))
                    .build();

            Map<String, Object> body = Map.of(
                    "event", event.name(),
                    "payload", payload
            );

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(hook.getUrl()))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(hook.getTimeoutSeconds()))
                    .header("Content-Type", "application/json");

            for (Map.Entry<String, String> header : hook.getHeaders().entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            String output = response.body();

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
     * 执行 Prompt Hook
     *
     * 通过 Shell 命令调用 JHarness 的单次查询模式，
     * 将 hook prompt + payload 作为输入，由 LLM 判断是否允许操作。
     */
    private HookResult runPromptHook(HookDefinition.PromptHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        logger.debug("Prompt hook triggered: {}", hook.getPrompt());

        String fullPrompt = hook.getPrompt() + "\n\nPayload:\n" + payloadToString(payload)
                + "\n\nRespond with ONLY 'ALLOW' or 'DENY' followed by a brief reason.";

        try {
            String javaHome = System.getProperty("java.home");
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder pb = new ProcessBuilder(
                    javaHome + "/bin/java", "-cp", classpath,
                    "io.leavesfly.jharness.JHarnessApplication",
                    "-p", fullPrompt, "--permission-mode", "plan");

            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(hook.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                        "Prompt hook timed out");
            }

            String result = output.toString().trim();
            boolean allowed = result.toUpperCase().contains("ALLOW");

            return new HookResult(hook.getType(), allowed, result,
                    hook.isBlockOnFailure() && !allowed,
                    allowed ? null : "Prompt hook denied: " + result);

        } catch (Exception e) {
            logger.error("Prompt hook 执行失败", e);
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), e.getMessage());
        }
    }

    /**
     * 执行 Agent Hook
     *
     * 启动独立的 JHarness Agent 子进程进行深度验证，
     * Agent 可以使用工具进行更复杂的检查。
     */
    private HookResult runAgentHook(HookDefinition.AgentHookDefinition hook, HookEvent event, Map<String, Object> payload) {
        logger.debug("Agent hook triggered: {}", hook.getPrompt());

        String fullPrompt = hook.getPrompt() + "\n\nContext:\n" + payloadToString(payload)
                + "\n\nPerform your analysis and respond with ALLOW or DENY followed by your reasoning.";

        try {
            String javaHome = System.getProperty("java.home");
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder pb = new ProcessBuilder(
                    javaHome + "/bin/java", "-cp", classpath,
                    "io.leavesfly.jharness.JHarnessApplication",
                    "-p", fullPrompt, "--permission-mode", "full_auto",
                    "--max-turns", "3");

            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(hook.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                        "Agent hook timed out");
            }

            String result = output.toString().trim();
            boolean allowed = result.toUpperCase().contains("ALLOW");

            return new HookResult(hook.getType(), allowed, result,
                    hook.isBlockOnFailure() && !allowed,
                    allowed ? null : "Agent hook denied: " + result);

        } catch (Exception e) {
            logger.error("Agent hook 执行失败", e);
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), e.getMessage());
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
     * 简单的 fnmatch 实现（支持 * 和 ? 通配符）
     */
    private boolean fnmatch(String str, String pattern) {
        // 使用 Pattern.quote 安全转义所有正则元字符，然后替换通配符
        String regex = Pattern.quote(pattern)
                .replace("\\E*\\Q", "\\E.*\\Q")   // * 匹配任意字符
                .replace("\\E?\\Q", "\\E.\\Q");    // ? 匹配单个字符
        return Pattern.matches(regex, str);
    }

    /**
     * 将载荷注入到模板中（替换 $ARGUMENTS 占位符）
     *
     * 安全策略：不再通过字符串替换将 JSON 嵌入命令行，
     * 而是通过环境变量 JHARNESS_ARGUMENTS 传递，彻底避免命令注入风险。
     * 模板中的 $ARGUMENTS 仍保留兼容性，但推荐改用 $JHARNESS_ARGUMENTS 环境变量。
     */
    private String injectArguments(String template, Map<String, Object> payload) {
        // 保留 $ARGUMENTS 占位符替换以兼容旧模板，但内容已通过环境变量安全传递
        // 此处仅做无害的占位符移除，实际数据通过 pb.environment() 注入
        return template.replace("$ARGUMENTS", "$JHARNESS_ARGUMENTS");
    }
}
