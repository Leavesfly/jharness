package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.WebSearchToolInput;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 网络搜索工具
 */
public class WebSearchTool extends BaseTool<WebSearchToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String DUCKDUCKGO_SEARCH_URL = "https://html.duckduckgo.com/html/?q=";
    private final OkHttpClient httpClient;

    public WebSearchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() { return "web_search"; }

    @Override
    public String getDescription() { return "在网络上搜索信息。返回搜索结果标题和链接。"; }

    @Override
    public Class<WebSearchToolInput> getInputClass() { return WebSearchToolInput.class; }

    @Override
    public boolean isReadOnly(WebSearchToolInput input) { return true; }

    @Override
    public CompletableFuture<ToolResult> execute(WebSearchToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedQuery = URLEncoder.encode(input.getQuery(), StandardCharsets.UTF_8);
                String url = DUCKDUCKGO_SEARCH_URL + encodedQuery;

                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        return ToolResult.error("搜索失败: HTTP " + response.code());
                    }

                    String html = response.body().string();
                    Document doc = Jsoup.parse(html);

                    StringBuilder results = new StringBuilder();
                    int count = 0;
                    int maxResults = input.getNumResults();

                    Elements resultsElements = doc.select(".result__a");
                    Elements snippetsElements = doc.select(".result__snippet");

                    for (int i = 0; i < Math.min(resultsElements.size(), maxResults); i++) {
                        Element link = resultsElements.get(i);
                        String title = link.text();
                        String href = link.attr("href");

                        if (href.contains("uddg=")) {
                            int start = href.indexOf("uddg=") + 5;
                            int end = href.indexOf("&", start);
                            if (end == -1) end = href.length();
                            href = java.net.URLDecoder.decode(href.substring(start, end), StandardCharsets.UTF_8);
                        }

                        String snippet = i < snippetsElements.size() ? snippetsElements.get(i).text() : "";

                        results.append(count + 1).append(". ").append(title).append("\n");
                        results.append("   链接: ").append(href).append("\n");
                        if (!snippet.isEmpty()) {
                            results.append("   摘要: ").append(snippet).append("\n");
                        }
                        results.append("\n");
                        count++;
                    }

                    if (count == 0) {
                        return ToolResult.success("未找到搜索结果");
                    }

                    return ToolResult.success("找到 " + count + " 个结果:\n\n" + results.toString().trim());
                }

            } catch (Exception e) {
                logger.error("网络搜索失败", e);
                return ToolResult.error("网络搜索失败: " + e.getMessage());
            }
        });
    }
}
