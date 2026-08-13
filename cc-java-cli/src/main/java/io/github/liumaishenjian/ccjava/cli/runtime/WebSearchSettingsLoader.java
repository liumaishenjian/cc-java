package io.github.liumaishenjian.ccjava.cli.runtime;

import io.github.liumaishenjian.ccjava.tools.web.WebSearchConfiguration;
import io.github.liumaishenjian.ccjava.tools.web.WebSearchProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * 从 Git 忽略的 provider 本地文件与环境变量加载 hosted MCP Web Search Provider gate。
 *
 * <p>环境优先；任何缺失、非法或未显式启用状态都安全返回 disabled。生产 endpoint 由
 * {@link WebSearchProvider} 固定，Loader 不接受任意 URI，也不记录配置值或向模型暴露 credential。</p>
 */
final class WebSearchSettingsLoader {
    private static final int MAX_FILE_BYTES = 16 * 1024;
    private WebSearchSettingsLoader() { }

    static WebSearchConfiguration load(Path repositoryRoot) {
        return load(repositoryRoot, System.getenv());
    }

    static WebSearchConfiguration load(Path repositoryRoot, Map<String, String> environment) {
        Properties properties = new Properties();
        Path file = repositoryRoot.resolve("config").resolve("provider.local.properties");
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                        || Files.size(file) > MAX_FILE_BYTES) return WebSearchConfiguration.disabled();
                try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
            }
            String enabled = value(environment, "CC_JAVA_WEB_SEARCH_ENABLED", properties, "web-search.enabled");
            if (!"true".equalsIgnoreCase(enabled)) return WebSearchConfiguration.disabled();
            WebSearchProvider provider = provider(value(
                    environment, "CC_JAVA_WEB_SEARCH_PROVIDER", properties, "web-search.provider"));
            String key = providerKey(provider, environment, properties);
            return WebSearchConfiguration.hosted(provider, Optional.ofNullable(key), Duration.ofSeconds(10));
        } catch (IOException | RuntimeException failure) {
            return WebSearchConfiguration.disabled();
        }
    }

    private static WebSearchProvider provider(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 web search provider");
        return WebSearchProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private static String providerKey(
            WebSearchProvider provider, Map<String, String> environment, Properties properties) {
        String common = environment.get("CC_JAVA_WEB_SEARCH_API_KEY");
        if (common != null) return common;
        String providerEnvironment = environment.get(provider == WebSearchProvider.EXA
                ? "EXA_API_KEY" : "PARALLEL_API_KEY");
        return providerEnvironment != null ? providerEnvironment : properties.getProperty("web-search.api-key");
    }

    private static String value(Map<String, String> env, String envName, Properties properties, String property) {
        String value = env.get(envName);
        return value != null ? value : properties.getProperty(property);
    }
}
