package io.leavesfly.jharness.command.commands.builtin.session;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.findLastAssistantText;
import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.joinArgs;

/**
 * /copy - 把最后一条 assistant 回复（或参数文本）写入文件并尝试复制到剪贴板。
 */
public final class CopyCommand {

    private CopyCommand() {}

    public static SlashCommand create() {
        return cmd("copy", "复制回复", (args, ctx, ec) -> {
            String trimmed = joinArgs(args);
            String text;

            if (!trimmed.isEmpty()) {
                text = trimmed;
            } else {
                QueryEngine engine = ctx.getEngine();
                if (engine == null) {
                    return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
                }
                text = findLastAssistantText(engine.getMessages());
                if (text == null) {
                    return CompletableFuture.completedFuture(CommandResult.error("没有找到可复制的回复"));
                }
            }

            Path copyPath = ctx.getCwd().resolve(".openharness/data/last_copy.txt");
            try {
                Files.createDirectories(copyPath.getParent());
                Files.writeString(copyPath, text);
            } catch (IOException e) {
                copyPath = Path.of(System.getProperty("user.home"), ".openharness/data/last_copy.txt");
                try {
                    Files.createDirectories(copyPath.getParent());
                    Files.writeString(copyPath, text);
                } catch (IOException ex) {
                    return CompletableFuture.completedFuture(CommandResult.error("写入失败: " + ex.getMessage()));
                }
            }

            ClipboardSupport.tryCopy(text);

            int previewLen = Math.min(text.length(), 100);
            String msg = String.format(
                    "已复制 (%d 字符):\n%s%s\n保存到: %s",
                    text.length(),
                    text.substring(0, previewLen),
                    text.length() > 100 ? "..." : "",
                    copyPath);
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }
}
