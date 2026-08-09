package io.github.liumaishenjian.ccjava.tools.local.command;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 在 timeout 或取消时终止命令主进程及其已观察到的后代。
 *
 * <p>Windows 先立刻强制终止已经观察到的后代，避免它们在外部清理程序启动期间继续
 * 产生副作用；随后使用结构化 {@code taskkill /T /F} 清扫整棵树，并以
 * {@link ProcessHandle} 做兜底。其他平台先处理已观察到的后代，再处理主进程。
 * 这仍是应用层进程控制，不等同于 S13 OS Sandbox 或 Windows Job Object。</p>
 *
 * @since 0.4.0
 */
public final class ProcessTreeTerminator {

    private static final Duration GRACE = Duration.ofMillis(500);

    /** 创建无状态的进程树终止器。 */
    public ProcessTreeTerminator() {
    }

    /**
     * 尽力终止主进程和调用时可观察到的全部后代，并等待一个有界宽限期。
     *
     * @param process 由受控适配器启动的主进程
     */
    public void terminate(Process process) {
        long pid = process.pid();
        List<ProcessHandle> descendants = process.descendants()
                .toList();
        if (isWindows()) {
            descendants.reversed().forEach(ProcessTreeTerminator::destroyForcibly);
            taskkill(pid);
        } else {
            descendants.forEach(ProcessTreeTerminator::destroy);
        }
        destroy(process.toHandle());
        waitForExit(process, GRACE);
        descendants.stream().filter(ProcessHandle::isAlive)
                .forEach(ProcessTreeTerminator::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
            waitForExit(process, GRACE);
        }
    }

    private static void taskkill(long pid) {
        String systemRoot = System.getenv("SystemRoot");
        Path executable = (systemRoot == null ? Path.of("C:\\Windows") : Path.of(systemRoot))
                .resolve("System32")
                .resolve("taskkill.exe");
        try {
            Process helper = new ProcessBuilder(
                    executable.toString(),
                    "/PID",
                    Long.toString(pid),
                    "/T",
                    "/F")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!helper.waitFor(2, TimeUnit.SECONDS)) {
                helper.destroyForcibly();
            }
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void waitForExit(Process process, Duration duration) {
        try {
            process.waitFor(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void destroy(ProcessHandle handle) {
        if (handle.isAlive()) {
            handle.destroy();
        }
    }

    private static void destroyForcibly(ProcessHandle handle) {
        if (handle.isAlive()) {
            handle.destroyForcibly();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
