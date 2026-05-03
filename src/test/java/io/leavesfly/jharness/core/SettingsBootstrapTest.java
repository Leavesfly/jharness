package io.leavesfly.jharness.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link SettingsBootstrap} 的首启 seed 行为：
 * - 目标目录为空时从 classpath 写入默认 settings.json 与默认插件；
 * - 目标文件已存在时幂等，不会覆盖用户修改。
 */
class SettingsBootstrapTest {

    @Test
    void seedWritesDefaultsWhenAbsent(@TempDir Path tempDir) throws Exception {
        SettingsBootstrap.seedIfAbsent(tempDir);

        Path settingsFile = tempDir.resolve("settings.json");
        assertTrue(Files.exists(settingsFile), "应写入默认 settings.json");
        String content = Files.readString(settingsFile);
        assertTrue(content.contains("qwen3.5:4b"), "默认配置应包含 qwen3.5:4b 模型: " + content);
        assertTrue(content.contains("localhost:11434"), "默认配置应指向本地 Ollama: " + content);

        Path pluginDir = tempDir.resolve("plugins").resolve("builtin-defaults");
        assertTrue(Files.isDirectory(pluginDir), "应写入 builtin-defaults 插件目录");
        assertTrue(Files.exists(pluginDir.resolve("plugin.json")), "应包含 plugin.json");
        assertTrue(Files.isDirectory(pluginDir.resolve("skills")), "应包含 skills 子目录");
        assertTrue(Files.exists(pluginDir.resolve("hooks.json")), "应包含 hooks.json");
    }

    @Test
    void seedIsIdempotentAndDoesNotOverwriteUserEdit(@TempDir Path tempDir) throws Exception {
        // 用户"已经"修改过的 settings.json
        Path settingsFile = tempDir.resolve("settings.json");
        Files.writeString(settingsFile, "{\"model\":\"user-custom-model\"}");

        SettingsBootstrap.seedIfAbsent(tempDir);

        String content = Files.readString(settingsFile);
        assertEquals("{\"model\":\"user-custom-model\"}", content, "已存在的 settings.json 必须保留");
    }

    @Test
    void seedDoesNotOverwriteExistingPlugin(@TempDir Path tempDir) throws Exception {
        Path pluginDir = tempDir.resolve("plugins").resolve("builtin-defaults");
        Files.createDirectories(pluginDir);
        Path marker = pluginDir.resolve("USER_MARKER");
        Files.writeString(marker, "keep-me");

        SettingsBootstrap.seedIfAbsent(tempDir);

        assertTrue(Files.exists(marker), "已存在的插件目录应被跳过");
        // plugin.json 由于目录已存在而不会被写入
        assertFalse(Files.exists(pluginDir.resolve("plugin.json")),
                "目录已存在时不应向其中写入默认插件文件");
    }

    @Test
    void listDefaultPluginNamesIncludesBuiltinDefaults() {
        List<String> names = SettingsBootstrap.listDefaultPluginNames();
        assertNotNull(names);
        assertTrue(names.contains("builtin-defaults"),
                "应能从 classpath 枚举到 builtin-defaults 插件，实际: " + names);
    }
}
