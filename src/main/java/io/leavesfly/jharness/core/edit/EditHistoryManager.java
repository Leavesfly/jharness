package io.leavesfly.jharness.core.edit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件编辑历史管理器（F-P0-4）。
 *
 * 设计要点：
 * - 单例（通过 {@link #getInstance()} 获取），使得不同工具共享同一份撤销栈；
 * - 仅保留**最近 N 条**编辑记录（默认 50），防止长时间运行内存膨胀；
 * - 非持久化：进程重启后撤销栈清空，避免引入文件 IO 复杂度；
 * - 线程安全：所有公共方法均为 synchronized，符合工具并发调用场景。
 *
 * 后续若需要跨进程/跨会话撤销，可扩展为基于 {@code .jharness/edits/*.json} 的持久化版本。
 */
public class EditHistoryManager {

    private static final Logger logger = LoggerFactory.getLogger(EditHistoryManager.class);
    private static final int DEFAULT_MAX_RECORDS = 50;

    private static final EditHistoryManager INSTANCE = new EditHistoryManager(DEFAULT_MAX_RECORDS);

    public static EditHistoryManager getInstance() {
        return INSTANCE;
    }

    private final int maxRecords;
    private final List<EditRecord> records = new ArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong();

    public EditHistoryManager(int maxRecords) {
        this.maxRecords = maxRecords;
    }

    /**
     * 记录一次编辑。
     *
     * @param filePath       文件绝对路径
     * @param originalContent 编辑前的原始内容；新建文件场景传 null
     * @param newContent     编辑后的内容
     * @param toolName       触发该编辑的工具名
     * @return 新记录的 ID
     */
    public synchronized long record(String filePath, String originalContent, String newContent,
                                    String toolName) {
        long id = idGenerator.incrementAndGet();
        EditRecord record = new EditRecord(id, filePath, originalContent, newContent,
                toolName, Instant.now());
        records.add(record);
        if (records.size() > maxRecords) {
            records.remove(0);
        }
        logger.debug("记录编辑历史 id={} file={} tool={}", id, filePath, toolName);
        return id;
    }

    /**
     * 根据 ID 获取记录；找不到返回 null。
     */
    public synchronized EditRecord get(long id) {
        for (EditRecord r : records) {
            if (r.getId() == id) {
                return r;
            }
        }
        return null;
    }

    /**
     * 获取最后一次**未被撤销**的编辑记录；若无则返回 null。
     */
    public synchronized EditRecord peekLastActive() {
        for (int i = records.size() - 1; i >= 0; i--) {
            EditRecord r = records.get(i);
            if (!r.isUndone()) {
                return r;
            }
        }
        return null;
    }

    /**
     * 列出全部历史记录（最新在前）。
     */
    public synchronized List<EditRecord> listAll() {
        List<EditRecord> copy = new ArrayList<>(records);
        Collections.reverse(copy);
        return copy;
    }

    /** 清空所有历史（主要用于测试）。 */
    public synchronized void clear() {
        records.clear();
        idGenerator.set(0);
    }
}
