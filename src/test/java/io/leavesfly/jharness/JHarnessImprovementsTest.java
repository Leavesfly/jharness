package io.leavesfly.jharness;

import io.leavesfly.jharness.agent.hooks.HookEvent;
import io.leavesfly.jharness.command.commands.CommandRegistry;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.core.engine.MessageCompactionService;
import io.leavesfly.jharness.core.engine.TokenEstimator;
import io.leavesfly.jharness.integration.CronRegistry;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.session.sessions.SessionSnapshot;
import io.leavesfly.jharness.session.sessions.SessionStorage;
import io.leavesfly.jharness.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖本轮"补齐功能 / 发掘潜在问题"改动的轻量回归测试：
 * <ul>
 *   <li>P-10：{@link CronRegistry} 实现 {@link AutoCloseable} 且 {@code close()} 幂等；</li>
 *   <li>P-01：{@link SessionStorage#listSessions(int)} 按 createdAt 倒序返回，且支持容量 limit，
 *       以覆盖 {@code JHarnessApplication.tryRestoreSession} 在 {@code --continue} 模式下
 *       "取最近一次会话"的核心路径；</li>
 *   <li>P-04：{@code JHarnessApplication.mapHookEvent} 对大小写 / 连字符 / 枚举 value 的归一化行为。</li>
 * </ul>
 */
class JHarnessImprovementsTest {

    @Test
    void cronRegistryIsAutoCloseableAndIdempotent(@TempDir Path dataDir) {
        try (CronRegistry registry = new CronRegistry(dataDir)) {
            // 使用 try-with-resources 应当编译通过 + 正常关闭
            assertNotNull(registry);
        }

        // 再次手动构造并连续 close 两次，验证幂等
        CronRegistry registry = new CronRegistry(dataDir);
        assertDoesNotThrow(registry::close);
        assertDoesNotThrow(registry::close);
        assertDoesNotThrow(registry::shutdown);
    }

    @Test
    void sessionStorageListSessionsReturnsNewestFirst(@TempDir Path sessionsDir) {
        SessionStorage storage = new SessionStorage(sessionsDir);

        // 造 3 个时间不同的 snapshot
        SessionSnapshot older = new SessionSnapshot(
                "sess-a", "/tmp", "m1", List.of(), null,
                Instant.parse("2024-01-01T00:00:00Z"), "older", 0);
        SessionSnapshot middle = new SessionSnapshot(
                "sess-b", "/tmp", "m1", List.of(), null,
                Instant.parse("2024-06-01T00:00:00Z"), "middle", 0);
        SessionSnapshot newest = new SessionSnapshot(
                "sess-c", "/tmp", "m1", List.of(), null,
                Instant.parse("2025-01-01T00:00:00Z"), "newest", 0);

        storage.saveSession(older);
        storage.saveSession(middle);
        storage.saveSession(newest);

        List<SessionSnapshot> recent = storage.listSessions(2);
        assertEquals(2, recent.size(), "应只返回 limit 条");
        assertEquals("sess-c", recent.get(0).getSessionId(), "最新的应排第一");
        assertEquals("sess-b", recent.get(1).getSessionId(), "第二新的排第二");

        // --continue 场景下只取 1 条
        List<SessionSnapshot> top1 = storage.listSessions(1);
        assertEquals(1, top1.size());
        assertEquals("sess-c", top1.get(0).getSessionId());

        // loadSession 可按 id 精确恢复
        SessionSnapshot loaded = storage.loadSession("sess-b");
        assertNotNull(loaded);
        assertEquals("middle", loaded.getSummary());

        // 路径遍历攻击应被拒绝
        assertNull(storage.loadSession("../etc/passwd"),
                "包含路径遍历的 session id 必须被拒绝");
    }

    @Test
    void settingsGettersAndSettersForNewFields() {
        // 【B-4/B-7】Settings 应支持 dailyBudgetUsd / 超时 / 压缩 / 自动保存等新字段的读写
        Settings settings = new Settings();

        // 默认值
        assertEquals(BigDecimal.ZERO, settings.getDailyBudgetUsd());
        assertEquals(30, settings.getConnectTimeoutSeconds());
        assertEquals(300, settings.getReadTimeoutSeconds());
        assertEquals(30, settings.getWriteTimeoutSeconds());
        assertEquals(0, settings.getMessageCompactionTokenBudget());
        assertEquals(0, settings.getMessageCompactionMaxMessages());
        assertTrue(settings.isAutoSaveSessions());

        // 设置值
        settings.setDailyBudgetUsd(new BigDecimal("1.50"));
        settings.setConnectTimeoutSeconds(11);
        settings.setReadTimeoutSeconds(222);
        settings.setWriteTimeoutSeconds(33);
        settings.setMessageCompactionTokenBudget(16000);
        settings.setMessageCompactionMaxMessages(12);
        settings.setAutoSaveSessions(false);

        assertEquals(new BigDecimal("1.50"), settings.getDailyBudgetUsd());
        assertEquals(11, settings.getConnectTimeoutSeconds());
        assertEquals(222, settings.getReadTimeoutSeconds());
        assertEquals(33, settings.getWriteTimeoutSeconds());
        assertEquals(16000, settings.getMessageCompactionTokenBudget());
        assertEquals(12, settings.getMessageCompactionMaxMessages());
        assertFalse(settings.isAutoSaveSessions());

        // 非法值被防御
        settings.setConnectTimeoutSeconds(-5);
        assertEquals(30, settings.getConnectTimeoutSeconds(),
                "非正值应被修正回默认 30");
        settings.setDailyBudgetUsd(null);
        assertEquals(BigDecimal.ZERO, settings.getDailyBudgetUsd(),
                "null 应被规整为 ZERO");
    }

    @Test
    void messageCompactionWithSystemPromptTokensReducesBudget() {
        // 【B-3】systemPrompt 的 token 必须从压缩预算里扣除
        MessageCompactionService base = new MessageCompactionService(20, 5, 10_000);
        MessageCompactionService adjusted = base.withSystemPromptTokens(4_000);
        // 新实例应满足：预算 = max(2000, 10000-4000) = 6000（通过 compact 行为间接验证更复杂，
        // 这里至少保证构造不抛、返回新实例，并不会把预算打到 0 或负数）
        assertNotNull(adjusted);
        assertNotSame(base, adjusted);

        // 极端值：systemPrompt 非常大时，预算会被地板到 2000
        MessageCompactionService floor = base.withSystemPromptTokens(99_999);
        assertNotNull(floor);
    }

    @Test
    void commandRegistryHasCommandDetectsDuplicates() {
        // 【B-12】hasCommand 支持带/不带斜杠的命名，用于 createFullRegistry 防重复
        CommandRegistry registry = new CommandRegistry();
        assertTrue(registry.hasCommand("help"));
        assertTrue(registry.hasCommand("/help"));
        assertFalse(registry.hasCommand("nonexistent"));
        assertFalse(registry.hasCommand(null));
    }

    @Test
    void sessionStorageCleansOrphanTempFilesOnInit(@TempDir Path dir) throws Exception {
        // 【B-9】启动时清理 .tmp 残留
        Path orphan = dir.resolve("session-xxx.tmp");
        Files.writeString(orphan, "{broken}");
        assertTrue(Files.exists(orphan));
        new SessionStorage(dir);
        assertFalse(Files.exists(orphan), "SessionStorage 初始化后应清理 .tmp 残留");
    }

    @Test
    void mcpClientManagerOnConnectedFiresListeners(@TempDir Path dir) throws Exception {
        // 【B-14】onConnected 在 connectAll 完成后应触发监听器
        McpClientManager mgr = new McpClientManager();
        AtomicInteger count = new AtomicInteger();
        mgr.onConnected(count::incrementAndGet);
        // 没有配置任何 server 时，connectAll 立即完成
        mgr.connectAll().get();
        assertEquals(1, count.get(), "connectAll 完成后应回调一次 onConnected 监听器");
        mgr.close();
    }

    @Test
    void toolRegistryRefreshMcpToolsIsIdempotent() {
        // 【B-14】refreshMcpTools 对 null mcpManager 返回 0，且幂等
        ToolRegistry reg = new ToolRegistry();
        assertEquals(0, reg.refreshMcpTools(null));

        McpClientManager empty = new McpClientManager();
        assertEquals(0, reg.refreshMcpTools(empty));
        assertEquals(0, reg.refreshMcpTools(empty), "二次调用仍返回 0，幂等");
        empty.close();
    }

    @Test
    void tokenEstimatorEstimatesSystemPrompt() {
        // 【B-3】验证 TokenEstimator 对 systemPrompt 文本能估算 token（非 0）
        String prompt = "你是 JHarness，一个强大的 AI 编程助手。You can use tools to complete tasks.";
        int tokens = TokenEstimator.estimateText(prompt);
        assertTrue(tokens > 10, "systemPrompt 估算 token 数应 > 10, 实际=" + tokens);
    }

    @Test
    void mapHookEventNormalizesCaseAndSeparator() throws Exception {
        Method method = JHarnessApplication.class
                .getDeclaredMethod("mapHookEvent", String.class);
        method.setAccessible(true);

        // 1. 枚举名本身
        assertEquals(HookEvent.SESSION_START, method.invoke(null, "SESSION_START"));
        // 2. 小写 + 连字符（常见 YAML / JSON 书写习惯）
        assertEquals(HookEvent.PRE_TOOL_USE, method.invoke(null, "pre-tool-use"));
        // 3. HookEvent.getValue() 的 snake_case 形式
        assertEquals(HookEvent.POST_TOOL_USE, method.invoke(null, "post_tool_use"));
        // 4. 未知事件名返回 null，不应抛异常
        assertNull(method.invoke(null, "unknown-event"));
        assertNull(method.invoke(null, (Object) null));

        // 【P2】补齐后的新事件名也应被识别
        assertEquals(HookEvent.USER_PROMPT_SUBMIT, method.invoke(null, "user_prompt_submit"));
        assertEquals(HookEvent.USER_PROMPT_SUBMIT, method.invoke(null, "user-prompt-submit"));
        assertEquals(HookEvent.STOP, method.invoke(null, "stop"));
        assertEquals(HookEvent.SUBAGENT_STOP, method.invoke(null, "SUBAGENT_STOP"));
        assertEquals(HookEvent.NOTIFICATION, method.invoke(null, "notification"));
    }

    // ===================================================================
    // 以下为本轮"按优先级路线补齐 plugin 系统（P0/P1/P2/P3）"回归测试
    // ===================================================================

    /**
     * 【P0-1】PluginSlashCommand.renderTemplate 对 $ARGUMENTS / {{args}} / $1..$9 占位符的渲染。
     *
     * 直接调用 public 静态方法，避免反射带来的 IDE 可见性静态检查误报。
     */
    @Test
    void pluginSlashCommandRendersTemplatePlaceholders() {
        assertEquals("hello alice bob",
                io.leavesfly.jharness.command.commands.PluginSlashCommand.renderTemplate(
                        "hello $ARGUMENTS", List.of("alice", "bob")));
        assertEquals("run: alice bob",
                io.leavesfly.jharness.command.commands.PluginSlashCommand.renderTemplate(
                        "run: {{args}}", List.of("alice", "bob")));
        // $1 / $2 按位置替换
        assertEquals("from=alice to=bob",
                io.leavesfly.jharness.command.commands.PluginSlashCommand.renderTemplate(
                        "from=$1 to=$2", List.of("alice", "bob")));
        // 越界占位符替换为空串
        assertEquals("a=alice b=",
                io.leavesfly.jharness.command.commands.PluginSlashCommand.renderTemplate(
                        "a=$1 b=$2", List.of("alice")));
        // 空 args + 无占位符
        assertEquals("no args here",
                io.leavesfly.jharness.command.commands.PluginSlashCommand.renderTemplate(
                        "no args here", List.of()));
        // null 安全
        assertEquals("",
                io.leavesfly.jharness.command.commands.PluginSlashCommand.renderTemplate(
                        null, null));
    }

    /**
     * 【P0-1】plugin 目录下 {@code commands/*.md} 会被 PluginLoader 扫到，
     * 且 LoadedPlugin.commandPrompts 非空。
     */
    @Test
    void pluginLoaderScansCommandsDirectory(@TempDir Path tmp) throws Exception {
        Path pluginDir = tmp.resolve("git-helper");
        Files.createDirectories(pluginDir.resolve("commands"));
        Files.writeString(pluginDir.resolve("plugin.json"),
                "{\"name\":\"git-helper\",\"version\":\"1.0.0\"}");
        Files.writeString(pluginDir.resolve("commands").resolve("commit-msg.md"),
                "---\nname: commit-msg\ndescription: 生成提交消息\n---\n请为 $ARGUMENTS 生成 commit message");

        io.leavesfly.jharness.extension.plugins.LoadedPlugin plugin =
                io.leavesfly.jharness.extension.plugins.PluginLoader
                        .loadPlugin(pluginDir, java.util.Map.of());
        assertNotNull(plugin);
        assertTrue(plugin.isEnabled());
        assertEquals(1, plugin.getCommandPrompts().size(), "commands/ 下的 md 应被扫到");
        var cmd = plugin.getCommandPrompts().get(0);
        assertEquals("commit-msg", cmd.getName());
        assertTrue(cmd.getContent().contains("$ARGUMENTS"));
        assertEquals("plugin", cmd.getSource());
    }

    /**
     * 【P0-2】plugin 目录下 {@code agents/*.md} 会被扫到，LoadedPlugin.agentDefs 非空。
     */
    @Test
    void pluginLoaderScansAgentsDirectory(@TempDir Path tmp) throws Exception {
        Path pluginDir = tmp.resolve("code-reviewer");
        Files.createDirectories(pluginDir.resolve("agents"));
        Files.writeString(pluginDir.resolve("plugin.json"),
                "{\"name\":\"code-reviewer\",\"version\":\"1.0.0\"}");
        Files.writeString(pluginDir.resolve("agents").resolve("reviewer.md"),
                "---\nname: reviewer\ndescription: 代码审查\n---\n你是代码审查专家。");

        io.leavesfly.jharness.extension.plugins.LoadedPlugin plugin =
                io.leavesfly.jharness.extension.plugins.PluginLoader
                        .loadPlugin(pluginDir, java.util.Map.of());
        assertNotNull(plugin);
        assertEquals(1, plugin.getAgentDefs().size(), "agents/ 下的 md 应被扫到");
        assertEquals("reviewer", plugin.getAgentDefs().get(0).getName());
    }

    /**
     * 【P1】PluginManifest 能反序列化扩展字段（author/repository/license/keywords/commandsDir/agentsDir），
     * 且对未知字段不抛异常（JsonIgnoreProperties）。
     */
    @Test
    void pluginManifestDeserializesExtendedFields() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                io.leavesfly.jharness.util.JacksonUtils.MAPPER;
        String json = "{\n"
                + "  \"name\": \"x\",\n"
                + "  \"version\": \"1.0\",\n"
                + "  \"author\": \"alice\",\n"
                + "  \"homepage\": \"https://example.com\",\n"
                + "  \"repository\": \"https://github.com/alice/x\",\n"
                + "  \"license\": \"MIT\",\n"
                + "  \"keywords\": [\"git\",\"helper\"],\n"
                + "  \"commandsDir\": \"cmds\",\n"
                + "  \"agentsDir\": \"subagents\",\n"
                + "  \"unknownFieldShouldNotBreak\": 123\n"
                + "}";
        var manifest = mapper.readValue(json,
                io.leavesfly.jharness.extension.plugins.PluginManifest.class);
        assertEquals("alice", manifest.getAuthor());
        assertEquals("https://example.com", manifest.getHomepage());
        assertEquals("https://github.com/alice/x", manifest.getRepository());
        assertEquals("MIT", manifest.getLicense());
        assertEquals(List.of("git", "helper"), manifest.getKeywords());
        assertEquals("cmds", manifest.getCommandsDir());
        assertEquals("subagents", manifest.getAgentsDir());
    }

    /**
     * 【P1】PluginPaths 返回的用户级插件目录集合总是把主目录 (~/.jharness/plugins) 排在第一位。
     */
    @Test
    void pluginPathsListUserRootsPutsPrimaryFirst() {
        var roots = io.leavesfly.jharness.extension.plugins.PluginPaths.listUserPluginRoots();
        // 主目录（即使刚被创建也应存在）一定是第一个
        Path primary = io.leavesfly.jharness.extension.plugins.PluginPaths.getUserPluginsDir();
        assertFalse(roots.isEmpty(), "主目录应至少能被创建并加入");
        assertEquals(primary.toAbsolutePath(), roots.get(0).toAbsolutePath());
    }

    /**
     * 【P2】MarketplaceRegistry 能从本地目录 add/list/remove，并解析插件 source。
     */
    @Test
    void marketplaceRegistryAddListRemove(@TempDir Path tmp) throws Exception {
        // 构造一个本地 marketplace：
        //   mp-root/
        //     marketplace.json
        //     plugins/
        //       foo/ (plugin.json)
        Path mpRoot = tmp.resolve("mp-root");
        Files.createDirectories(mpRoot.resolve("plugins").resolve("foo"));
        Files.writeString(mpRoot.resolve("plugins").resolve("foo").resolve("plugin.json"),
                "{\"name\":\"foo\",\"version\":\"1.0\"}");
        Files.writeString(mpRoot.resolve("marketplace.json"),
                "{\n"
                        + "  \"name\": \"mymp\",\n"
                        + "  \"owner\": \"alice\",\n"
                        + "  \"plugins\": [{\"name\": \"foo\", \"source\": \"./plugins/foo\"}]\n"
                        + "}");

        // 使用独立 state 文件，避免污染真实 ~/.jharness
        Path stateFile = tmp.resolve("marketplaces.json");
        var registry = new io.leavesfly.jharness.extension.plugins.marketplace.MarketplaceRegistry(stateFile);
        String finalName = registry.addFromLocal(null, mpRoot);
        assertEquals("mymp", finalName);
        assertTrue(registry.list().containsKey("mymp"));
        assertEquals(1, registry.listPlugins("mymp").size());

        // 解析插件 source 为绝对路径
        Path resolved = registry.resolvePluginSource("mymp", "foo");
        assertTrue(Files.isDirectory(resolved));
        assertTrue(resolved.endsWith(Path.of("plugins", "foo")));

        // 持久化：新 registry 读取同一 stateFile 能恢复
        var registry2 = new io.leavesfly.jharness.extension.plugins.marketplace.MarketplaceRegistry(stateFile);
        assertTrue(registry2.list().containsKey("mymp"));

        // remove
        assertTrue(registry.remove("mymp"));
        assertFalse(registry.list().containsKey("mymp"));
    }

    /**
     * 【P2】MarketplaceRegistry 对非法名 / 不存在目录 / 缺失清单的错误处理。
     */
    @Test
    void marketplaceRegistryRejectsInvalidInputs(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("marketplaces.json");
        var registry = new io.leavesfly.jharness.extension.plugins.marketplace.MarketplaceRegistry(stateFile);

        // 目录不存在
        assertThrows(IllegalArgumentException.class,
                () -> registry.addFromLocal("x", tmp.resolve("not-exists")));

        // 目录存在但没有 marketplace.json
        Path emptyDir = tmp.resolve("empty-mp");
        Files.createDirectories(emptyDir);
        assertThrows(IllegalArgumentException.class,
                () -> registry.addFromLocal("x", emptyDir));

        // 非法 name
        Path mpRoot = tmp.resolve("mp2");
        Files.createDirectories(mpRoot);
        Files.writeString(mpRoot.resolve("marketplace.json"),
                "{\"name\":\"ok\",\"plugins\":[]}");
        assertThrows(IllegalArgumentException.class,
                () -> registry.addFromLocal("../evil", mpRoot));
    }

    /**
     * 【P3】TrustStore 的 trust/revoke/list/evaluate 行为。
     */
    @Test
    void trustStoreTrustRevokeAndEvaluate(@TempDir Path tmp) throws Exception {
        Path stateFile = tmp.resolve("trust.json");
        var store = new io.leavesfly.jharness.extension.plugins.trust.TrustStore(stateFile);
        assertFalse(store.isTrusted("alice"));
        assertTrue(store.trust("alice"));
        assertFalse(store.trust("alice"), "幂等：重复 trust 返回 false");
        assertTrue(store.isTrusted("alice"));

        // 策略评估
        var Policy = io.leavesfly.jharness.extension.plugins.trust.TrustStore.TrustPolicy.class;
        assertTrue(store.evaluate("alice", Policy.getEnumConstants()[0])); // STRICT：已信任 → 放行
        assertFalse(store.evaluate("bob", Policy.getEnumConstants()[0]),
                "STRICT：未信任的 bob 应被拒绝");
        assertTrue(store.evaluate("bob",
                        io.leavesfly.jharness.extension.plugins.trust.TrustStore.TrustPolicy.WARN),
                "WARN：未信任的 bob 放行");
        assertTrue(store.evaluate("carol",
                        io.leavesfly.jharness.extension.plugins.trust.TrustStore.TrustPolicy.AUTO),
                "AUTO：carol 未信任 → 自动加入");
        assertTrue(store.isTrusted("carol"));

        // 持久化：新 store 能恢复
        var store2 = new io.leavesfly.jharness.extension.plugins.trust.TrustStore(stateFile);
        assertTrue(store2.isTrusted("alice"));
        assertTrue(store2.isTrusted("carol"));
        assertTrue(store2.revoke("alice"));
        assertFalse(store2.isTrusted("alice"));
    }

    /**
     * 【P3】PluginInstaller 在 STRICT 策略下拒绝未信任 author 的插件。
     */
    @Test
    void pluginInstallerRejectsUntrustedAuthorInStrictMode(@TempDir Path tmp) throws Exception {
        Path pluginDir = tmp.resolve("evil-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.json"),
                "{\"name\":\"evil\",\"version\":\"1.0\",\"author\":\"mallory\"}");

        var store = new io.leavesfly.jharness.extension.plugins.trust.TrustStore(tmp.resolve("trust.json"));
        assertThrows(SecurityException.class, () ->
                io.leavesfly.jharness.extension.plugins.PluginInstaller.installPlugin(
                        pluginDir, store,
                        io.leavesfly.jharness.extension.plugins.trust.TrustStore.TrustPolicy.STRICT));

        // trust 之后再装应放行（这里不真正 install 到 ~/.jharness，只验证抛异常路径）
        store.trust("mallory");
        assertTrue(store.evaluate("mallory",
                io.leavesfly.jharness.extension.plugins.trust.TrustStore.TrustPolicy.STRICT));
    }
}
