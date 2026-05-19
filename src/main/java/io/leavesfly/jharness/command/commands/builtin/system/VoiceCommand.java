package io.leavesfly.jharness.command.commands.builtin.system;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.kernel.state.AppStateStore;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.joinArgs;

/**
 * /voice - 语音模式（show/on/off/toggle/keyterms）。
 *
 * 同时对外提供 {@link #inspectVoiceCapabilities()} 用于探测当前环境的录音器可用性。
 */
public final class VoiceCommand {

    private VoiceCommand() {}

    /** 语音能力诊断结果（available + 原因 + 选用的录音器）。 */
    public record VoiceDiagnostics(boolean available, String reason, String recorder) {
        public VoiceDiagnostics(boolean available, String reason) {
            this(available, reason, null);
        }
    }

    /**
     * 探测环境是否支持语音输入：依次检查 PATH 中的 sox / ffmpeg / arecord。
     */
    public static VoiceDiagnostics inspectVoiceCapabilities() {
        String[] recorders = {"sox", "ffmpeg", "arecord"};
        for (String rec : recorders) {
            if (findInPath(rec) != null) {
                return new VoiceDiagnostics(true, "voice shell is available", rec);
            }
        }
        return new VoiceDiagnostics(false,
                "no supported recorder found (expected sox, ffmpeg, or arecord)");
    }

    private static String findInPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, executable);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    public static SlashCommand create() {
        return cmd("voice", "语音模式", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            AppStateStore stateStore = ctx.getAppStateStore();

            VoiceDiagnostics diagnostics = inspectVoiceCapabilities();

            boolean current = settings != null && settings.isVoiceEnabled();
            if (stateStore != null) {
                current = stateStore.get().isVoiceEnabled();
            }

            String joined = joinArgs(args);
            String[] parts = joined.split("\\s+", 2);
            String subcmd = parts[0];

            if (joined.isEmpty() || "show".equals(subcmd)) {
                return CompletableFuture.completedFuture(CommandResult.success(
                        "语音模式: " + (current ? "on" : "off") + "\n"
                        + "可用: " + (diagnostics.available() ? "yes" : "no") + "\n"
                        + "原因: " + diagnostics.reason()
                        + (diagnostics.recorder() != null ? "\n录音器: " + diagnostics.recorder() : "")));
            }

            if ("keyterms".equals(subcmd)) {
                String text = parts.length > 1 ? parts[1].trim() : "";
                if (text.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /voice keyterms <文本>"));
                }
                String[] words = text.split("\\s+");
                List<String> stopWords = List.of("the", "a", "an", "is", "in", "on", "at",
                        "to", "of", "and", "or", "for", "的", "了", "在", "是", "和");
                String keyterms = Arrays.stream(words)
                        .filter(w -> w.length() > 2 && !stopWords.contains(w.toLowerCase()))
                        .distinct()
                        .limit(10)
                        .collect(Collectors.joining(", "));
                return CompletableFuture.completedFuture(
                        CommandResult.success("关键词: " + (keyterms.isEmpty() ? "(无)" : keyterms)));
            }

            boolean newValue;
            switch (subcmd) {
                case "on"     -> newValue = true;
                case "off"    -> newValue = false;
                case "toggle" -> newValue = !current;
                default -> {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /voice [show|on|off|toggle|keyterms <文本>]"));
                }
            }

            if (newValue && !diagnostics.available()) {
                return CompletableFuture.completedFuture(
                        CommandResult.error("无法开启语音模式: " + diagnostics.reason()));
            }

            if (settings != null) {
                settings.setVoiceEnabled(newValue);
                settings.save();
            }
            if (stateStore != null) {
                boolean finalNewValue = newValue;
                stateStore.set(s -> {
                    s.setVoiceEnabled(finalNewValue);
                    s.setVoiceAvailable(diagnostics.available());
                    s.setVoiceReason(diagnostics.reason());
                });
            }
            return CompletableFuture.completedFuture(
                    CommandResult.success("语音模式已" + (newValue ? "开启" : "关闭")));
        });
    }
}
