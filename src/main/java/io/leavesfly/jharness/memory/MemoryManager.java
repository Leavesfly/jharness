package io.leavesfly.jharness.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 记忆管理器
 *
 * 管理跨会话的持久化知识存储，支持分类、搜索和自动清理。
 */
public class MemoryManager {
    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path memoryDir;
    private final ObjectMapper mapper;

    public MemoryManager(Path memoryDir) {
        this.memoryDir = memoryDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            logger.error("创建记忆目录失败", e);
        }
    }

    /**
     * 添加记忆条目
     */
    public void addMemory(String project, String title, String content) {
        addMemory(project, title, content, null);
    }

    /**
     * 添加记忆条目（带分类）
     */
    public void addMemory(String project, String title, String content, String category) {
        try {
            Path projectDir = memoryDir.resolve(sanitize(project)).normalize();
            if (!projectDir.startsWith(memoryDir.normalize())) {
                logger.warn("非法的项目路径: {}", project);
                return;
            }
            Files.createDirectories(projectDir);

            MemoryEntry entry = new MemoryEntry();
            entry.setTitle(title);
            entry.setContent(content);
            entry.setCategory(category != null ? category : "general");
            entry.setCreatedAt(LocalDateTime.now());
            entry.setUpdatedAt(LocalDateTime.now());

            Path memoryFile = projectDir.resolve(sanitize(title) + ".json");
            mapper.writeValue(memoryFile.toFile(), entry);

            logger.debug("记忆已添加: {}/{}", project, title);
        } catch (IOException e) {
            logger.error("添加记忆失败", e);
        }
    }

    /**
     * 获取记忆文件列表
     */
    public List<String> listMemories(String project) {
        Path projectDir = memoryDir.resolve(sanitize(project));

        if (!Files.exists(projectDir)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(projectDir)) {
            return files.filter(f -> f.toString().endsWith(".json"))
                    .map(f -> f.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.error("列出记忆失败", e);
            return List.of();
        }
    }

    /**
     * 获取记忆详细信息
     */
    public MemoryEntry getMemoryDetails(String project, String title) {
        Path memoryFile = memoryDir.resolve(sanitize(project)).resolve(sanitize(title) + ".json");
        try {
            if (Files.exists(memoryFile)) {
                return mapper.readValue(memoryFile.toFile(), MemoryEntry.class);
            }
            return null;
        } catch (IOException e) {
            logger.error("读取记忆详情失败", e);
            return null;
        }
    }

    /**
     * 按分类搜索记忆
     */
    public List<String> searchByCategory(String project, String category) {
        Path projectDir = memoryDir.resolve(sanitize(project));
        if (!Files.exists(projectDir)) {
            return List.of();
        }

        List<String> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(projectDir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    MemoryEntry entry = mapper.readValue(f.toFile(), MemoryEntry.class);
                    if (category.equals(entry.getCategory())) {
                        results.add(entry.getTitle());
                    }
                } catch (IOException e) {
                    // skip
                }
            });
        } catch (IOException e) {
            logger.error("搜索记忆失败", e);
        }
        return results;
    }

    /**
     * 搜索记忆内容
     */
    public List<String> searchMemories(String project, String keyword) {
        Path projectDir = memoryDir.resolve(sanitize(project));
        if (!Files.exists(projectDir)) {
            return List.of();
        }

        String lowerKeyword = keyword.toLowerCase();
        List<String> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(projectDir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    MemoryEntry entry = mapper.readValue(f.toFile(), MemoryEntry.class);
                    if (entry.getTitle().toLowerCase().contains(lowerKeyword) ||
                        entry.getContent().toLowerCase().contains(lowerKeyword)) {
                        results.add(entry.getTitle());
                    }
                } catch (IOException e) {
                    // skip
                }
            });
        } catch (IOException e) {
            logger.error("搜索记忆失败", e);
        }
        return results;
    }

    /**
     * 读取记忆内容
     */
    public String readMemory(String project, String title) {
        Path memoryFile = memoryDir.resolve(sanitize(project)).resolve(sanitize(title) + ".json");
        try {
            if (Files.exists(memoryFile)) {
                MemoryEntry entry = mapper.readValue(memoryFile.toFile(), MemoryEntry.class);
                return entry.getContent();
            }
            return null;
        } catch (IOException e) {
            logger.error("读取记忆失败", e);
            return null;
        }
    }

    /**
     * 更新记忆内容
     */
    public boolean updateMemory(String project, String title, String content) {
        Path memoryFile = memoryDir.resolve(sanitize(project)).resolve(sanitize(title) + ".json");
        try {
            if (Files.exists(memoryFile)) {
                MemoryEntry entry = mapper.readValue(memoryFile.toFile(), MemoryEntry.class);
                entry.setContent(content);
                entry.setUpdatedAt(LocalDateTime.now());
                mapper.writeValue(memoryFile.toFile(), entry);
                logger.debug("记忆已更新: {}/{}", project, title);
                return true;
            }
            return false;
        } catch (IOException e) {
            logger.error("更新记忆失败", e);
            return false;
        }
    }

    /**
     * 删除记忆
     */
    public boolean removeMemory(String project, String title) {
        Path memoryFile = memoryDir.resolve(sanitize(project)).resolve(sanitize(title) + ".json");
        try {
            return Files.deleteIfExists(memoryFile);
        } catch (IOException e) {
            logger.error("删除记忆失败", e);
            return false;
        }
    }

    /**
     * 清空项目所有记忆
     */
    public void clearProjectMemories(String project) {
        Path projectDir = memoryDir.resolve(sanitize(project));
        try {
            if (Files.exists(projectDir)) {
                try (Stream<Path> files = Files.list(projectDir)) {
                    files.filter(f -> f.toString().endsWith(".json"))
                            .forEach(f -> {
                                try { Files.delete(f); } catch (IOException e) { /* ignore */ }
                            });
                }
            }
            logger.debug("项目记忆已清空: {}", project);
        } catch (IOException e) {
            logger.error("清空记忆失败", e);
        }
    }

    /**
     * 获取记忆统计信息
     */
    public MemoryStats getStats(String project) {
        Path projectDir = memoryDir.resolve(sanitize(project));
        MemoryStats stats = new MemoryStats();

        if (!Files.exists(projectDir)) {
            return stats;
        }

        try (Stream<Path> files = Files.list(projectDir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    MemoryEntry entry = mapper.readValue(f.toFile(), MemoryEntry.class);
                    stats.totalCount++;
                    stats.categories.merge(entry.getCategory(), 1, Integer::sum);
                    stats.totalSize += Files.size(f);
                } catch (IOException e) {
                    // skip
                }
            });
        } catch (IOException e) {
            logger.error("获取统计信息失败", e);
        }

        return stats;
    }

    /**
     * 清理旧记忆（超过指定天数）
     */
    public int cleanupOldMemories(String project, int daysOld) {
        Path projectDir = memoryDir.resolve(sanitize(project));
        if (!Files.exists(projectDir)) {
            return 0;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);
        int[] cleaned = {0};

        try (Stream<Path> files = Files.list(projectDir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    MemoryEntry entry = mapper.readValue(f.toFile(), MemoryEntry.class);
                    if (entry.getUpdatedAt() != null && entry.getUpdatedAt().isBefore(cutoff)) {
                        Files.delete(f);
                        cleaned[0]++;
                    }
                } catch (IOException e) {
                    // skip
                }
            });
        } catch (IOException e) {
            logger.error("清理旧记忆失败", e);
        }

        logger.info("清理了 {} 条旧记忆（超过 {} 天）", cleaned[0], daysOld);
        return cleaned[0];
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be null or blank");
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        // 防止路径遍历
        sanitized = sanitized.replace("..", "_");
        // 限制长度
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 255);
        }
        return sanitized;
    }

    /**
     * 记忆条目
     */
    public static class MemoryEntry {
        private String title;
        private String content;
        private String category;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    /**
     * 记忆统计
     */
    public static class MemoryStats {
        public int totalCount = 0;
        public long totalSize = 0;
        public java.util.Map<String, Integer> categories = new java.util.HashMap<>();

        @Override
        public String toString() {
            return String.format("MemoryStats{total=%d, size=%d bytes, categories=%s}",
                    totalCount, totalSize, categories);
        }
    }
}