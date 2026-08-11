package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import io.github.liumaishenjian.ccjava.domain.plugin.PluginSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** 从 ordinary directory 的固定 {@code plugin.json} 创建经 canonical tree 验证的 snapshot。 */
public final class PluginPackageLoader {
    private final PluginManifestParser manifestParser;
    private final CanonicalPluginTree tree;

    /** 创建使用生产 Manifest parser 与 canonical tree scanner 的加载器。 */
    public PluginPackageLoader() {
        this(new PluginManifestParser(), new CanonicalPluginTree());
    }

    /**
     * 创建可注入严格解析器和 tree scanner 的加载器。
     *
     * @param manifestParser strict plugin.json parser
     * @param tree canonical package tree scanner
     */
    public PluginPackageLoader(PluginManifestParser manifestParser, CanonicalPluginTree tree) {
        this.manifestParser = Objects.requireNonNull(manifestParser, "manifestParser 不能为空");
        this.tree = Objects.requireNonNull(tree, "tree 不能为空");
    }

    /**
     * 加载 ordinary directory；archive、link 和非目录一律拒绝。
     *
     * @param packageDirectory 待验证的 Plugin package 目录
     * @return 绑定 manifest 与 canonical tree identity 的 snapshot
     */
    public PluginSnapshot load(Path packageDirectory) {
        Objects.requireNonNull(packageDirectory, "packageDirectory 不能为空");
        try {
            if (!Files.isDirectory(packageDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(packageDirectory)) {
                throw new PluginBoundaryException(looksLikeArchive(packageDirectory)
                        ? PluginErrorCode.ARCHIVE_REJECTED : PluginErrorCode.PACKAGE_NOT_DIRECTORY);
            }
            Path manifest = packageDirectory.resolve("plugin.json");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(manifest)) {
                throw new PluginBoundaryException(PluginErrorCode.MANIFEST_INVALID);
            }
            long size = Files.size(manifest);
            if (size > PluginManifestParser.MAX_BYTES) {
                throw new PluginBoundaryException(PluginErrorCode.MANIFEST_TOO_LARGE);
            }
            byte[] bytes = Files.readAllBytes(manifest);
            return tree.scan(packageDirectory, manifestParser.parse(bytes));
        } catch (PluginBoundaryException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new PluginBoundaryException(PluginErrorCode.PATH_REJECTED);
        }
    }

    private static boolean looksLikeArchive(Path path) {
        String value = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".zip") || value.endsWith(".jar") || value.endsWith(".tar")
                || value.endsWith(".tgz") || value.endsWith(".gz") || value.endsWith(".7z");
    }
}
