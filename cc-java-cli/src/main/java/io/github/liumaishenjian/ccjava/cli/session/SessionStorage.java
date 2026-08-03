package io.github.liumaishenjian.ccjava.cli.session;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 解析 S06 Session Store 的本机私有默认位置。
 *
 * <p>默认位置固定在用户目录下且不位于 Workspace。该值不写入普通日志、stdio/TUI 事件或
 * Session metadata；测试仍可直接向 {@link FileSessionStore} 注入临时 root。</p>
 *
 * @since 0.6.0
 */
public final class SessionStorage {

    private SessionStorage() {
    }

    /**
     * 返回当前用户的 Session Store root。
     *
     * @return 规范化绝对路径
     */
    public static Path defaultRoot() {
        String home = Objects.requireNonNull(
                System.getProperty("user.home"),
                "user.home 不能为空");
        return Path.of(home, ".cc-java", "sessions")
                .toAbsolutePath()
                .normalize();
    }
}
