package io.github.liumaishenjian.ccjava.tools.local.execution;

import io.github.liumaishenjian.ccjava.tools.local.command.ProcessTreeTerminator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 将 fixed-drive Windows Workspace 映射为 WSL 路径并双向核验真实身份。
 *
 * <p>每一层路径都拒绝 symlink/reparse point，UNC 和非 drive root 也失败关闭。映射后再由
 * Linux {@code realpath} 规范化，并通过 {@code wslpath -w} 回到同一 Windows real path，
 * 防止 Windows/WSL 两侧对同一文本路径产生不同解释。所有 helper 调用均为 fixed argv，
 * timeout 时清理完整进程树。</p>
 *
 * @since 0.13.0
 */
public final class WindowsWslPathMapper {
    private final Path wsl;
    private final String distribution;

    /** 创建绑定可信 WSL executable 与 distribution 的映射器。 */
    public WindowsWslPathMapper(Path wsl, String distribution) {
        this.wsl = Objects.requireNonNull(wsl, "wsl 不能为空");
        this.distribution = Objects.requireNonNull(distribution, "distribution 不能为空");
    }

    /**
     * 双向解析并验证路径身份。
     *
     * @param workspace Windows fixed-drive 路径
     * @return 两侧 canonical identity
     * @throws IOException 路径不安全、identity 不一致或 helper 失败时
     */
    public Mapping map(Path workspace) throws IOException {
        Path real = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
        String windowsText = real.toString();
        if (windowsText.startsWith("\\\\")
                || real.getRoot() == null
                || !real.getRoot().toString().matches("(?i)[A-Z]:\\\\")) {
            throw new IOException("仅支持 fixed drive");
        }
        rejectReparseComponents(real);
        String linux = run("wslpath", "-a", windowsText);
        String canonical = run("realpath", linux);
        String expectedPrefix = "/mnt/" + Character.toLowerCase(windowsText.charAt(0)) + "/";
        if (!canonical.startsWith(expectedPrefix)) {
            throw new IOException("WSL 映射不在 fixed drive mount");
        }
        String back = run("wslpath", "-w", canonical);
        if (!Path.of(back).toRealPath().equals(real)) {
            throw new IOException("WSL 双向身份不一致");
        }
        return new Mapping(real, canonical);
    }

    private static void rejectReparseComponents(Path real) throws IOException {
        Path cursor = real.getRoot();
        for (Path component : real.getRoot().relativize(real)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("路径含符号链接");
            }
            Path noFollow = cursor.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path followed = cursor.toRealPath();
            if (!noFollow.equals(followed)) {
                throw new IOException("路径含 reparse point");
            }
        }
    }

    private String run(String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(wsl.toString());
        command.add("--distribution");
        command.add(distribution);
        command.add("--exec");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                new ProcessTreeTerminator().terminate(process);
                throw new IOException("WSL path probe 超时");
            }
            byte[] output = process.getInputStream().readNBytes(8_193);
            if (process.exitValue() != 0 || output.length > 8_192) {
                throw new IOException("WSL path probe 失败");
            }
            return new String(output, StandardCharsets.UTF_8).trim();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            new ProcessTreeTerminator().terminate(process);
            throw new IOException("WSL path probe 中断", interrupted);
        }
    }

    /**
     * 双向验证后的路径身份。
     *
     * @param windowsCanonical Windows canonical path
     * @param linuxCanonical WSL canonical path
     */
    public record Mapping(Path windowsCanonical, String linuxCanonical) {
        public Mapping {
            windowsCanonical = Objects.requireNonNull(windowsCanonical, "windowsCanonical 不能为空");
            linuxCanonical = Objects.requireNonNull(linuxCanonical, "linuxCanonical 不能为空");
        }
    }
}
