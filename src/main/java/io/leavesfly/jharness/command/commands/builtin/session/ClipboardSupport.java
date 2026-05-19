package io.leavesfly.jharness.command.commands.builtin.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 简易剪贴板复制（仅 macOS pbcopy）。失败属于合理降级，只在 debug 级别记录。
 */
final class ClipboardSupport {

    private static final Logger logger = LoggerFactory.getLogger(ClipboardSupport.class);

    private ClipboardSupport() {}

    static void tryCopy(String text) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pbcopy");
            Process process = pb.start();
            process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.debug("复制到剪贴板失败（可能 pbcopy 不可用）: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
