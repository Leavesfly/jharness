package io.leavesfly.jharness.app.cli;

import io.leavesfly.jharness.capability.session.SessionSnapshot;
import io.leavesfly.jharness.capability.session.SessionStorage;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * -c/--continue 与 -r/--resume 的会话恢复工具（4.8 拆分自 JHarnessApplication）。
 */
public final class SessionRestorer {

    private static final Logger logger = LoggerFactory.getLogger(SessionRestorer.class);

    private SessionRestorer() {}

    /**
     * 尝试恢复历史会话到 {@link QueryEngine}。
     * @return true 表示本次调用恢复了会话，false 表示未恢复或失败
     */
    public static boolean tryRestore(QueryEngine engine, String resumeSessionId, boolean continueSession) {
        if (resumeSessionId == null && !continueSession) {
            return false;
        }
        try {
            Path sessionsDir = Settings.getDefaultDataDir().resolve("sessions");
            SessionStorage storage = new SessionStorage(sessionsDir);
            SessionSnapshot snapshot;
            String targetId;
            if (resumeSessionId != null && !resumeSessionId.isBlank()) {
                targetId = resumeSessionId;
                snapshot = storage.loadSession(targetId);
            } else {
                List<SessionSnapshot> recent = storage.listSessions(1);
                if (recent.isEmpty()) {
                    System.out.println("提示: 暂无历史会话可继续（目录: " + sessionsDir + "）");
                    return false;
                }
                snapshot = recent.get(0);
                targetId = snapshot.getSessionId();
            }
            if (snapshot == null) {
                System.err.println("错误: 未找到会话 " + targetId + "（目录: " + sessionsDir + "）");
                return false;
            }
            List<ConversationMessage> messages = snapshot.getMessages();
            if (messages == null || messages.isEmpty()) {
                System.out.println("提示: 会话 " + targetId + " 为空，跳过恢复");
                return false;
            }
            engine.loadMessages(messages);
            System.out.printf("📂 已恢复会话 %s（消息 %d 条，模型 %s）%n",
                    targetId, messages.size(), snapshot.getModel());
            return true;
        } catch (Exception e) {
            logger.warn("恢复会话失败（忽略并继续）", e);
            System.err.println("提示: 恢复会话失败: " + e.getMessage() + "（忽略并继续）");
            return false;
        }
    }
}
