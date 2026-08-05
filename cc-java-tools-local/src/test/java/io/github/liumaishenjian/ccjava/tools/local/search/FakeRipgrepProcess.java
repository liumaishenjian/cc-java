package io.github.liumaishenjian.ccjava.tools.local.search;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ripgrep 进程边界负例 Fixture；只由测试 JVM 作为独立子进程启动。
 */
public final class FakeRipgrepProcess {

    private FakeRipgrepProcess() {
    }

    /**
     * 根据系统属性模拟输出爆量、临时失败、确定性失败和带后代的长运行进程。
     *
     * @param arguments 被测客户端追加的 rg 参数；本 Fixture 不解释这些参数
     * @throws Exception 测试 Fixture 无法创建标记文件或子进程
     */
    public static void main(String[] arguments) throws Exception {
        String mode = System.getProperty("cc.java.fake.rg.mode", "success");
        writePid("cc.java.fake.rg.pid");
        if ("overflow".equals(mode)) {
            System.out.print("x".repeat(2 * 1024 * 1024 + 1));
            return;
        }
        if ("error".equals(mode)) {
            System.err.print("PRIVATE_STDERR_SENTINEL");
            System.exit(2);
        }
        if ("eagain".equals(mode)) {
            int attempt = incrementAttempt();
            if (attempt == 1) {
                System.err.print("resource temporarily unavailable (EAGAIN)");
                System.exit(2);
            }
            if (!containsSingleThread(arguments)) {
                System.err.print("retry did not reduce ripgrep threads");
                System.exit(2);
            }
            System.out.print("src/App.java:1:needle\n");
            return;
        }
        if ("eagain-always".equals(mode)) {
            int attempt = incrementAttempt();
            if (attempt > 1 && !containsSingleThread(arguments)) {
                System.err.print("retry did not reduce ripgrep threads");
                System.exit(2);
            }
            System.err.print("resource temporarily unavailable (EAGAIN)");
            System.exit(2);
        }
        if ("syntax".equals(mode)) {
            incrementAttempt();
            System.err.print("regex parse error PRIVATE_STDERR_SENTINEL");
            System.exit(2);
        }
        if ("descendant".equals(mode)) {
            Process child = new ProcessBuilder(
                    javaExecutable(),
                    "-Dcc.java.fake.rg.mode=child",
                    "-cp",
                    directClasspath(),
                    FakeRipgrepProcess.class.getName())
                    .start();
            Files.writeString(requiredPath("cc.java.fake.rg.childPid"), Long.toString(child.pid()));
            Thread.sleep(30_000);
            return;
        }
        if ("timeout".equals(mode) || "child".equals(mode)) {
            Thread.sleep(30_000);
            return;
        }
        System.out.print("src/App.java:1:needle\n");
    }

    private static void writePid(String property) throws Exception {
        String value = System.getProperty(property);
        if (value != null) {
            Files.writeString(Path.of(value), Long.toString(ProcessHandle.current().pid()));
        }
    }

    private static int incrementAttempt() throws Exception {
        Path path = requiredPath("cc.java.fake.rg.attempt");
        int previous = Files.exists(path)
                ? Integer.parseInt(Files.readString(path))
                : 0;
        int current = previous + 1;
        Files.writeString(path, Integer.toString(current));
        return current;
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " 未配置");
        }
        return Path.of(value);
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String directClasspath() {
        try {
            return Path.of(FakeRipgrepProcess.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalStateException("无法解析 Fake ripgrep test-classes", failure);
        }
    }

    private static boolean containsSingleThread(String[] arguments) {
        for (int index = 0; index < arguments.length - 1; index++) {
            if ("--threads".equals(arguments[index]) && "1".equals(arguments[index + 1])) {
                return true;
            }
        }
        return false;
    }
}
