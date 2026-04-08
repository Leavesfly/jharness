package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bridge 命令处理器
 *
 * 检查桥接帮助程序和生成桥接会话
 */
public class BridgeCommandHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, BridgeSession> sessions = new ConcurrentHashMap<>();

    public static SlashCommand createBridgeCommand() {
        return new SimpleSlashCommand("bridge", "检查桥接帮助程序和生成桥接会话", (args, ctx, ec) -> {
            try {
                CommandResult result;
                if (args.isEmpty()) {
                    result = handleShow(ctx.getCwd());
                } else {
                    String subcmd = args.get(0);
                    result = switch (subcmd) {
                        case "show" -> handleShow(ctx.getCwd());
                        case "encode" -> handleEncode(args);
                        case "decode" -> handleDecode(args);
                        case "spawn" -> handleSpawn(args, ctx.getCwd());
                        case "list" -> handleList();
                        case "output" -> handleOutput(args);
                        case "stop" -> handleStop(args);
                        default -> CommandResult.success("用法: /bridge [show|encode API_BASE_URL TOKEN|decode SECRET|spawn CMD|list|output SESSION_ID|stop SESSION_ID]");
                    };
                }
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("Bridge 命令失败: " + e.getMessage()));
            }
        });
    }

    private static CommandResult handleShow(Path cwd) {
        List<String> lines = new ArrayList<>();
        lines.add("Bridge 摘要:");
        lines.add("- 后端主机: 可用");
        lines.add("- 工作目录: " + cwd);
        lines.add("- 活跃会话数: " + sessions.size());
        lines.add("- 工具: encode, decode, spawn, list, output, stop");
        return CommandResult.success(String.join("\n", lines));
    }

    private static CommandResult handleEncode(List<String> args) {
        if (args.size() < 3) {
            return CommandResult.error("用法: /bridge encode API_BASE_URL TOKEN");
        }

        String apiUrl = args.get(1);
        String token = args.get(2);

        try {
            String secret = encodeSecret(apiUrl, token);
            return CommandResult.success("编码后的密钥:\n" + secret);
        } catch (Exception e) {
            return CommandResult.error("编码失败: " + e.getMessage());
        }
    }

    private static CommandResult handleDecode(List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /bridge decode SECRET");
        }

        String secret = args.get(1);
        try {
            String decoded = decodeSecret(secret);
            return CommandResult.success("解码后的内容:\n" + decoded);
        } catch (Exception e) {
            return CommandResult.error("解码失败: " + e.getMessage());
        }
    }

    private static CommandResult handleSpawn(List<String> args, Path cwd) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /bridge spawn COMMAND");
        }

        String command = String.join(" ", args.subList(1, args.size()));
        String sessionId = "bridge-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BridgeSession session = new BridgeSession(sessionId, command, process, cwd);
            sessions.put(sessionId, session);

            return CommandResult.success("已生成 bridge 会话 " + sessionId + " pid=" + process.pid());
        } catch (IOException e) {
            return CommandResult.error("生成会话失败: " + e.getMessage());
        }
    }

    private static CommandResult handleList() {
        if (sessions.isEmpty()) {
            return CommandResult.success("无 bridge 会话。");
        }

        List<String> lines = new ArrayList<>();
        for (BridgeSession session : sessions.values()) {
            String status = session.process.isAlive() ? "running" : "stopped";
            lines.add(session.id + " [" + status + "] pid=" + session.process.pid() + " " + session.command);
        }
        return CommandResult.success(String.join("\n", lines));
    }

    private static CommandResult handleOutput(List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /bridge output SESSION_ID");
        }

        String sessionId = args.get(1);
        BridgeSession session = sessions.get(sessionId);
        if (session == null) {
            return CommandResult.error("未找到会话: " + sessionId);
        }

        try {
            String output = new String(session.process.getInputStream().readAllBytes());
            return CommandResult.success(output.isEmpty() ? "(无输出)" : output);
        } catch (IOException e) {
            return CommandResult.error("读取输出失败: " + e.getMessage());
        }
    }

    private static CommandResult handleStop(List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /bridge stop SESSION_ID");
        }

        String sessionId = args.get(1);
        BridgeSession session = sessions.remove(sessionId);
        if (session == null) {
            return CommandResult.error("未找到会话: " + sessionId);
        }

        if (session.process.isAlive()) {
            session.process.destroy();
        }
        return CommandResult.success("已停止 bridge 会话 " + sessionId);
    }

    private static String encodeSecret(String apiUrl, String token) throws Exception {
        String json = "{\"api_base_url\":\"" + apiUrl + "\",\"token\":\"" + token + "\"}";
        return java.util.Base64.getEncoder().encodeToString(json.getBytes());
    }

    private static String decodeSecret(String secret) throws Exception {
        byte[] decoded = java.util.Base64.getDecoder().decode(secret);
        return new String(decoded);
    }

    private static class BridgeSession {
        final String id;
        final String command;
        final Process process;
        final Path cwd;

        BridgeSession(String id, String command, Process process, Path cwd) {
            this.id = id;
            this.command = command;
            this.process = process;
            this.cwd = cwd;
        }
    }
}
