package io.leavesfly.jharness.outputstyles;

import io.leavesfly.jharness.config.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 输出样式加载器
 */
public class OutputStyleLoader {
    
    /**
     * 获取自定义输出样式目录
     */
    public static Path getOutputStylesDir() {
        Path path = Settings.getDefaultConfigDir().resolve("output_styles");
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            // 忽略目录创建失败
        }
        return path;
    }
    
    /**
     * 加载内置和自定义输出样式
     */
    public static List<OutputStyle> loadOutputStyles() {
        List<OutputStyle> styles = new ArrayList<>();
        
        // 加载内置样式
        styles.add(new OutputStyle("default", "Standard rich console output.", "builtin"));
        styles.add(new OutputStyle("minimal", "Very terse plain-text output.", "builtin"));
        
        // 加载用户自定义样式
        Path stylesDir = getOutputStylesDir();
        try (Stream<Path> stream = Files.list(stylesDir)) {
            stream.filter(path -> path.toString().endsWith(".md"))
                  .sorted()
                  .forEach(path -> {
                      try {
                          String content = Files.readString(path);
                          String name = path.getFileName().toString();
                          // 去除 .md 扩展名
                          if (name.endsWith(".md")) {
                              name = name.substring(0, name.length() - 3);
                          }
                          styles.add(new OutputStyle(name, content, "user"));
                      } catch (IOException e) {
                          // 忽略读取失败的文件
                      }
                  });
        } catch (IOException e) {
            // 忽略目录遍历失败
        }
        
        return styles;
    }
    
    /**
     * 按名称查找样式
     */
    public static OutputStyle findStyle(String name) {
        return loadOutputStyles().stream()
                .filter(style -> style.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
