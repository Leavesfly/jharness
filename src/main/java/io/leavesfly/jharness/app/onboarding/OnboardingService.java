package io.leavesfly.jharness.app.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.capability.permission.PermissionMode;
import io.leavesfly.jharness.config.OnboardingMarker;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Scanner;

/**
 * Agent Onboarding 流程。
 *
 * <p>职责：当用户首次启动 JHarness 时，通过交互式向导帮助其完成基础配置：
 * <ol>
 *   <li>选择模型 Provider（本地 Ollama / 远端 OpenAI 兼容）；</li>
 *   <li>输入模型名 / Base URL / API Key；</li>
 *   <li>选择权限模式（DEFAULT / FULL_AUTO / PLAN）；</li>
 *   <li>将结果写入 {@code ~/.jharness/settings.json}，并在配置目录下创建
 *       {@code .onboarded} 标记文件防止重复触发。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>非侵入</b>：所有输出通过外部注入的 {@link PrintStream} / {@link Scanner}，
 *       便于测试与替换为 TUI；</li>
 *   <li><b>幂等</b>：是否需要触发由 {@link #shouldRun(Settings)} 判断，
 *       已存在标记文件或本地端点 + 已有 apiKey 时跳过；</li>
 *   <li><b>失败软降级</b>：保存配置失败仅记录日志，不阻断主流程（用户仍可通过环境变量启动）。</li>
 * </ul>
 */
public class OnboardingService {

    private static final Logger logger = LoggerFactory.getLogger(OnboardingService.class);
    private static final ObjectMapper PRETTY_MAPPER = JacksonUtils.PRETTY_MAPPER;
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    /**
     * 配置目录下的"已完成 onboarding"标记文件名。
     * @deprecated 请改用 {@link OnboardingMarker#FILE_NAME}（已下沉到 config 包）。
     */
    @Deprecated
    public static final String ONBOARDED_MARKER = OnboardingMarker.FILE_NAME;

    private final Path configDir;
    private final PrintStream out;
    private final Scanner in;

    /**
     * 使用默认配置目录与标准输入/输出构造（生产场景）。
     */
    public OnboardingService() {
        this(Settings.getDefaultConfigDir(), System.out, new Scanner(System.in));
    }

    /**
     * 测试友好构造器。
     *
     * @param configDir 配置目录（{@code ~/.jharness/} 或测试临时目录）
     * @param out       输出流
     * @param in        输入流封装的 Scanner
     */
    public OnboardingService(Path configDir, PrintStream out, Scanner in) {
        this.configDir = configDir;
        this.out = out;
        this.in = in;
    }

    /**
     * 判断是否需要触发 onboarding。
     *
     * <p>触发条件（必须同时满足）：
     * <ol>
     *   <li>{@code ~/.jharness/.onboarded} 标记文件不存在（即未完成过引导）；</li>
     *   <li>当前 {@link Settings} 处于"无法直接可用"的状态——即：
     *       apiKey 为空 <b>且</b> baseUrl 非本地端点。
     *       若 apiKey 已配置（来自环境变量或用户配置文件），或 baseUrl 指向本地 Ollama，
     *       则无需打扰用户，直接静默标记为已完成。</li>
     * </ol>
     *
     * <p>这样可以避免对"已经手动配置好"的用户重复弹出向导，同时仍能为
     * 首次启动且未配置任何凭据的新用户提供引导。
     */
    public boolean shouldRun(Settings settings) {
        if (configDir == null || settings == null) {
            return false;
        }
        if (OnboardingMarker.isOnboarded(configDir)) {
            return false;
        }
        boolean apiKeyMissing = settings.getApiKey() == null || settings.getApiKey().isBlank();
        boolean baseUrlLocal = OpenAiApiClient.isLocalEndpoint(settings.getBaseUrl());
        // 已经具备可用配置（有 key 或本地端点）：静默标记为已完成，避免重复打扰
        if (!apiKeyMissing || baseUrlLocal) {
            markOnboarded();
            return false;
        }
        return true;
    }

    /**
     * 执行交互式向导。返回是否成功完成。
     *
     * @param settings 当前已加载的 Settings 实例，向导会就地修改其字段
     * @return 成功完成（用户回答完毕且配置已保存）返回 true；用户选择跳过或输入流关闭返回 false
     */
    public boolean run(Settings settings) {
        printBanner();

        try {
            // Step 1: Provider 选择
            ProviderChoice choice = askProvider();
            if (choice == null) {
                // 输入流关闭/EOF：视为放弃，保持原配置且不写 marker
                out.println("⚠️ 未检测到输入，已跳过 onboarding（下次启动将再次提示）。");
                return false;
            }
            if (choice == ProviderChoice.SKIP) {
                // 用户主动跳过：保持原配置，但仍写入 marker 避免重复打扰
                markOnboarded();
                out.println("已跳过 onboarding，保留当前配置。可通过 /onboarding 重新触发。");
                return false;
            }

            // Step 2: 模型 + Base URL + API Key
            applyProviderProfile(choice, settings);

            // Step 3: 权限模式
            PermissionMode mode = askPermissionMode(settings.getPermissionMode());
            settings.setPermissionMode(mode);

            // Step 4: 保存并写入 marker
            persist(settings);

            out.println();
            out.println("✅ Onboarding 完成！配置已保存到 " + configDir.resolve("settings.json"));
            out.println("   下次启动将自动跳过本向导，可通过 /onboarding 重新触发。");
            out.println();
            return true;
        } catch (Exception e) {
            logger.warn("Onboarding 过程出错（忽略并继续）: {}", e.getMessage());
            out.println("⚠️ Onboarding 出错: " + e.getMessage() + "（已跳过，使用默认配置启动）");
            return false;
        }
    }

    /** 标记 onboarding 为已完成（不再触发自动向导）。 */
    public void markOnboarded() {
        OnboardingMarker.markOnboarded(configDir);
    }

    /** 清除 onboarding 标记，使下次启动重新触发向导。 */
    public void clearOnboardedMarker() {
        OnboardingMarker.clearMarker(configDir);
    }

    // ===== 内部辅助方法 =====

    private void printBanner() {
        out.println();
        out.println("================================================================");
        out.println("  🚀 欢迎使用 JHarness —— Java 实现的轻量级 AI 智能体框架");
        out.println("----------------------------------------------------------------");
        out.println("  这是首次启动，将通过几个问题帮你完成基础配置。");
        out.println("  随时可按 Ctrl+C 退出，已配置项会保留默认值。");
        out.println("================================================================");
    }

    private ProviderChoice askProvider() {
        out.println();
        out.println("【1/3】选择模型来源：");
        out.println("  1) 本地 Ollama（推荐，免 API Key，需先 ollama pull <model>）");
        out.println("  2) 远端 OpenAI 兼容端点（OpenAI / DeepSeek / Azure OpenAI 等）");
        out.println("  3) 跳过（保留当前配置）");
        out.print("请选择 [1]: ");
        String line = readLine();
        if (line == null) return null;
        line = line.trim();
        return switch (line) {
            case "", "1" -> ProviderChoice.OLLAMA;
            case "2" -> ProviderChoice.OPENAI;
            case "3" -> ProviderChoice.SKIP;
            default -> {
                out.println("无效选项，使用默认: 本地 Ollama");
                yield ProviderChoice.OLLAMA;
            }
        };
    }

    /**
     * 根据用户选择就地修改 {@link Settings}。
     *
     * <p>注意：项目统一通过 OpenAI 兼容协议客户端访问所有 Provider，
     * 因此 {@code provider} 字段恒为 {@code "openai"}；两个选项的真正差异在 baseUrl 与是否需要 apiKey。
     *
     * <p>apiKey 输入采用专门的 {@link #askApiKey} 方法，避免在提示符中回显敏感值。
     */
    private void applyProviderProfile(ProviderChoice choice, Settings current) {
        // 项目客户端统一为 openai 协议
        current.setProvider("openai");

        if (choice == ProviderChoice.OLLAMA) {
            String defaultBaseUrl = OpenAiApiClient.isLocalEndpoint(current.getBaseUrl())
                    ? current.getBaseUrl() : "http://localhost:11434/v1";
            current.setBaseUrl(askWithDefault("【2/3】Ollama Base URL", defaultBaseUrl));
            current.setModel(askWithDefault("        模型名（需先 ollama pull）", current.getModel()));
            // 本地端点无需 API Key；保留原 apiKey 不动（环境变量可能已注入）
            return;
        }

        // OpenAI 兼容
        String defaultBaseUrl = OpenAiApiClient.isLocalEndpoint(current.getBaseUrl())
                ? "https://api.openai.com/v1" : current.getBaseUrl();
        String defaultModel = OpenAiApiClient.isLocalEndpoint(current.getBaseUrl())
                ? "gpt-4o-mini" : current.getModel();
        current.setBaseUrl(askWithDefault("【2/3】API Base URL（如 https://api.openai.com/v1）", defaultBaseUrl));
        current.setModel(askWithDefault("        模型名（如 gpt-4o-mini / deepseek-chat）", defaultModel));

        String newKey = askApiKey(current.getApiKey());
        if (newKey != null) {
            current.setApiKey(newKey);
        }
    }

    /**
     * 询问 API Key，以脱敏方式展示当前值。
     *
     * @param currentKey 当前已配置的 apiKey，可为 null
     * @return 用户输入的新 Key；若用户回车留空则返回 {@code currentKey}（保持不变）；
     *         若输入字符串 {@code "-"} 则返回空串（显式清空）
     */
    private String askApiKey(String currentKey) {
        String hint = (currentKey == null || currentKey.isBlank())
                ? "未设置"
                : maskApiKey(currentKey);
        out.print("        API Key（回车保持当前=" + hint + "，输入 - 清空）: ");
        String line = readLine();
        if (line == null) return currentKey;
        line = line.trim();
        if (line.isEmpty()) return currentKey;
        if ("-".equals(line)) return "";
        return line;
    }

    /** 将 API Key 脱敏：保留首尾 4 个字符，中间用 *** 代替。 */
    static String maskApiKey(String key) {
        if (key == null) return "未设置";
        String s = key.trim();
        if (s.isEmpty()) return "未设置";
        if (s.length() <= 8) return "***";
        return s.substring(0, 4) + "***" + s.substring(s.length() - 4);
    }

    private PermissionMode askPermissionMode(PermissionMode current) {
        out.println();
        out.println("【3/3】选择权限模式：");
        out.println("  1) DEFAULT  - 只读自动放行，写入/执行前询问（推荐）");
        out.println("  2) FULL_AUTO - 所有操作自动放行（黑名单仍生效）");
        out.println("  3) PLAN     - 仅允许只读，阻断所有写入");
        out.print("请选择 [" + indexOf(current) + "]: ");
        String line = readLine();
        if (line == null) return current;
        line = line.trim();
        return switch (line) {
            case "1" -> PermissionMode.DEFAULT;
            case "2" -> PermissionMode.FULL_AUTO;
            case "3" -> PermissionMode.PLAN;
            case "" -> current;
            default -> {
                out.println("无效选项，保留: " + current);
                yield current;
            }
        };
    }

    private int indexOf(PermissionMode mode) {
        return switch (mode) {
            case DEFAULT -> 1;
            case FULL_AUTO -> 2;
            case PLAN -> 3;
        };
    }

    private String askWithDefault(String prompt, String defaultValue) {
        String shown = defaultValue == null || defaultValue.isBlank() ? "(空)" : defaultValue;
        out.print(prompt + " [" + shown + "]: ");
        String line = readLine();
        if (line == null) return defaultValue;
        line = line.trim();
        return line.isEmpty() ? defaultValue : line;
    }

    /**
     * 安全读取一行输入。
     *
     * <p>当 Scanner 已耗尽（hasNextLine=false）或底层流抛出
     * {@link java.util.NoSuchElementException} / {@link IllegalStateException} 时，
     * 统一返回 null，由调用方按"用户放弃"语义处理。
     */
    private String readLine() {
        try {
            if (!in.hasNextLine()) return null;
            return in.nextLine();
        } catch (java.util.NoSuchElementException | IllegalStateException e) {
            return null;
        }
    }

    /**
     * 将 Settings 持久化到 {@code ~/.jharness/settings.json}。
     *
     * <p>因为 {@link Settings#getApiKey()} 标记了 {@link com.fasterxml.jackson.annotation.JsonIgnore}，
     * 直接序列化会丢失 apiKey。这里采用「合并写入」策略：
     * <ol>
     *   <li>用 {@link #PRETTY_MAPPER} 序列化 Settings 得到 ObjectNode（不含 apiKey）；</li>
     *   <li>若 apiKey 非空，手动放入节点；空串语义为"显式清空"，则不写入 apiKey 字段；</li>
     *   <li>覆盖写入 settings.json，并创建 {@code .onboarded} 标记。</li>
     * </ol>
     *
     * <p>注意：{@link com.fasterxml.jackson.databind.ObjectMapper#valueToTree} 会按
     * 当前 mapper 的序列化配置生成 JsonNode，但 pretty-print 仅在最终
     * {@code writeValueAsString} 时由 mapper 的 INDENT_OUTPUT 控制，
     * 故使用 PRETTY_MAPPER 即可，无需再额外包一层 writer。
     */
    private void persist(Settings settings) throws IOException {
        Files.createDirectories(configDir);
        Path file = configDir.resolve("settings.json");

        ObjectNode node = PRETTY_MAPPER.valueToTree(settings);
        String apiKey = settings.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            node.put("apiKey", apiKey);
        }

        String json = PRETTY_MAPPER.writeValueAsString(node);
        Files.writeString(file, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        markOnboarded();
    }

    /** 用户在 Step 1 的选择枚举。 */
    private enum ProviderChoice {
        /** 本地 Ollama，无需 API Key。 */
        OLLAMA,
        /** 远端 OpenAI 兼容端点，需要 API Key。 */
        OPENAI,
        /** 跳过引导（保留当前配置但仍写入 marker）。 */
        SKIP
    }
}
