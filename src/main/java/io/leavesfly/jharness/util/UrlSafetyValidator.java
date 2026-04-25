package io.leavesfly.jharness.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * URL 安全校验器（SSRF 防护）
 *
 * 应对 S-3 / S-4 SSRF 漏洞，所有对用户可控 URL 发起的出站请求
 * （WebFetchTool、McpClientManager HTTP、Hook HTTP 等）都应先调用本类校验。
 *
 * 校验维度：
 *   1. 协议白名单：仅允许 http / https，禁止 file:// / ftp:// / gopher:// / jar:// 等；
 *   2. 主机名必须可解析；
 *   3. 解析后的 InetAddress 不能命中以下任一集合：
 *      - loopback     (127.0.0.0/8, ::1)
 *      - anyLocal     (0.0.0.0)
 *      - linkLocal    (169.254.0.0/16, fe80::/10)  —— 特别防 169.254.169.254 云元数据
 *      - siteLocal    (10/8, 172.16/12, 192.168/16)
 *      - multicast    (224.0.0.0/4)
 *   4. 额外防护 100.64.0.0/10（CGNAT / AWS IMDSv2 内部段）与 IPv4 映射地址。
 *
 * 注意：本校验无法杜绝 DNS rebinding（解析时是合法公网 IP、实际连接时换成内网 IP）。
 * 彻底杜绝需要在连接建立后再校验一次远端 IP（OkHttp 层用自定义 SocketFactory），
 * 那是更深层的改造，当前版本先堵住常见 SSRF 场景。
 */
public final class UrlSafetyValidator {

    /** 允许的协议。McpClientManager 的 "http"/"https" 配置也走这里。 */
    public static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private UrlSafetyValidator() {
    }

    /**
     * 校验 URL。
     *
     * @return null 表示通过；否则返回可向上游返回的错误信息
     */
    public static String validate(String url) {
        return validate(url, ALLOWED_SCHEMES);
    }

    /**
     * 校验 URL，允许调用方自定义协议白名单（例如只允许 https）。
     */
    public static String validate(String url, Set<String> allowedSchemes) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "URL 格式非法: " + e.getMessage();
        }

        String scheme = uri.getScheme();
        if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase())) {
            return "安全限制: 仅允许 " + allowedSchemes + " 协议，实际: " + scheme;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL 必须包含有效的主机名";
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                String reason = classifyPrivate(addr);
                if (reason != null) {
                    return "安全限制: 禁止访问 " + reason + " 地址 " + addr.getHostAddress();
                }
            }
        } catch (UnknownHostException e) {
            return "无法解析主机: " + host;
        }

        return null;
    }

    /**
     * 判定一个 InetAddress 是否属于受限的私有/内部地址段。
     * 返回分类名或 null（公网地址）。
     */
    private static String classifyPrivate(InetAddress addr) {
        if (addr.isLoopbackAddress()) return "回环";
        if (addr.isAnyLocalAddress()) return "通配";
        if (addr.isLinkLocalAddress()) return "链路本地";
        if (addr.isSiteLocalAddress()) return "站点本地";
        if (addr.isMulticastAddress()) return "多播";

        // 额外段：CGNAT (100.64.0.0/10) 与少量运营商保留
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 100.64.0.0/10
            if (first == 100 && (second >= 64 && second <= 127)) {
                return "CGNAT";
            }
            // 0.0.0.0/8
            if (first == 0) {
                return "保留";
            }
        }
        return null;
    }
}
