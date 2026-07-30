package io.github.liumaishenjian.ccjava.tools.local.command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * S04 固定的平台 Shell Adapter。
 *
 * <p>Windows 优先使用安装目录明确的 PowerShell 7，缺失时退回系统 Windows
 * PowerShell；其他平台固定使用 {@code /bin/sh}。模型不能选择可执行文件或启动参数，
 * 审批展示的命令正文会作为一个独立参数原样交给固定 Shell。</p>
 *
 * @since 0.4.0
 */
public enum CommandShell {

    /** Windows PowerShell 系列。 */
    WINDOWS_POWERSHELL("powershell", windowsExecutable(), windowsCharset()),

    /** POSIX /bin/sh。 */
    POSIX_SH("sh", Path.of("/bin/sh"), StandardCharsets.UTF_8);

    private final String id;
    private final Path executable;
    private final Charset outputCharset;

    CommandShell(String id, Path executable, Charset outputCharset) {
        this.id = id;
        this.executable = executable;
        this.outputCharset = outputCharset;
    }

    /**
     * 返回当前操作系统固定的 Shell。
     *
     * @return Windows PowerShell 或 POSIX sh
     */
    public static CommandShell current() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win")
                ? WINDOWS_POWERSHELL : POSIX_SH;
    }

    /**
     * 返回可安全进入审批摘要的稳定 Shell ID。
     *
     * @return {@code powershell} 或 {@code sh}
     */
    public String id() {
        return id;
    }

    /**
     * 返回固定 Shell 输出使用的字符集。
     *
     * @return 输出解码字符集
     */
    public Charset outputCharset() {
        return outputCharset;
    }

    /**
     * 构造不经过第二层字符串拼接的进程参数。
     *
     * @param command 已批准的完整命令正文
     * @return ProcessBuilder 参数
     */
    public List<String> processArguments(String command) {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add(executable.toString());
        if (this == WINDOWS_POWERSHELL) {
            arguments.add("-NoLogo");
            arguments.add("-NoProfile");
            arguments.add("-NonInteractive");
            arguments.add("-ExecutionPolicy");
            arguments.add("Bypass");
            arguments.add("-Command");
        } else {
            arguments.add("-c");
        }
        arguments.add(command);
        return List.copyOf(arguments);
    }

    private static Path windowsExecutable() {
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            Path pwsh = Path.of(programFiles, "PowerShell", "7", "pwsh.exe");
            if (Files.isRegularFile(pwsh)) {
                return pwsh;
            }
        }
        String systemRoot = System.getenv("SystemRoot");
        Path root = systemRoot == null ? Path.of("C:\\Windows") : Path.of(systemRoot);
        return root.resolve("System32")
                .resolve("WindowsPowerShell")
                .resolve("v1.0")
                .resolve("powershell.exe");
    }

    private static Charset windowsCharset() {
        if (windowsExecutable().getFileName().toString().equalsIgnoreCase("pwsh.exe")) {
            return StandardCharsets.UTF_8;
        }
        String nativeEncoding = System.getProperty("native.encoding");
        return nativeEncoding == null ? Charset.defaultCharset() : Charset.forName(nativeEncoding);
    }
}
