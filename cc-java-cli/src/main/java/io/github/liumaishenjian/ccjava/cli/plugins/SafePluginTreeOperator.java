package io.github.liumaishenjian.ccjava.cli.plugins;

import io.github.liumaishenjian.ccjava.domain.plugin.PluginErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Plugin copy/cleanup/uninstall 共用的 NOFOLLOW 普通树安全边界。
 *
 * <p>每层进入前执行 reparse probe、realpath containment 与 identity recheck；先完整验证整棵树，
 * 再执行删除，因此发现 link/junction/special/竞态时不会删除任何子项，更不会进入外部目标。</p>
 *
 * @since 0.11.0
 */
final class SafePluginTreeOperator {
    private final ReparseProbe reparseProbe;
    private final DeleteObserver deleteObserver;

    SafePluginTreeOperator() {
        this(ReparseProbe.system(), ignored -> { });
    }

    SafePluginTreeOperator(ReparseProbe reparseProbe, DeleteObserver deleteObserver) {
        this.reparseProbe = Objects.requireNonNull(reparseProbe, "reparseProbe 不能为空");
        this.deleteObserver = Objects.requireNonNull(deleteObserver, "deleteObserver 不能为空");
    }

    void copy(Path source, Path destination, CopyObserver observer) throws IOException {
        Objects.requireNonNull(observer, "observer 不能为空");
        Tree tree = inspect(source);
        if (!tree.nodes.getFirst().attributes.isDirectory()) {
            throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
        }
        for (Node node : tree.nodes.stream().sorted(Comparator.comparing(Node::relative)).toList()) {
            if (node.relative.toString().isEmpty()) continue;
            Path target = destination.resolve(node.relative).normalize();
            if (!target.startsWith(destination)) throw failure(PluginErrorCode.PATH_REJECTED);
            verify(node);
            if (node.attributes.isDirectory()) {
                Files.createDirectory(target);
            } else {
                observer.beforeFile(node.path);
                try (var input = java.nio.channels.FileChannel.open(
                             node.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                     var output = java.nio.channels.FileChannel.open(
                             target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    long position = 0;
                    while (position < node.attributes.size()) {
                        long transferred = input.transferTo(position, node.attributes.size() - position, output);
                        if (transferred <= 0) throw new IOException("copy stalled");
                        position += transferred;
                    }
                    output.force(true);
                }
                verify(node);
                observer.afterFile(target);
            }
        }
    }

    void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Tree tree = inspect(root);
        var ordered = tree.nodes.stream()
                .sorted(Comparator.comparingInt((Node value) -> value.relative.toString().isEmpty()
                        ? 0 : value.relative.getNameCount()).reversed())
                .toList();
        for (Node node : ordered) verify(node);
        for (Node node : ordered) {
            if (Files.isSymbolicLink(node.path) || reparseProbe.isReparseOrJunction(node.path)) {
                throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
            }
            deleteObserver.beforeDelete(node.path);
            Files.delete(node.path);
        }
    }

    private Tree inspect(Path suppliedRoot) throws IOException {
        Path root = suppliedRoot.toAbsolutePath().normalize();
        Node rootNode = node(root, Path.of(""));
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        var nodes = new ArrayList<Node>();
        nodes.add(rootNode);
        if (rootNode.attributes.isDirectory()) walk(root, root, realRoot, nodes);
        return new Tree(nodes);
    }

    private void walk(Path root, Path directory, Path realRoot, List<Node> nodes) throws IOException {
        verifyPath(directory, realRoot);
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                Path normalized = child.toAbsolutePath().normalize();
                if (!normalized.startsWith(root)) throw failure(PluginErrorCode.PATH_REJECTED);
                verifyPath(normalized, realRoot);
                Node node = node(normalized, root.relativize(normalized));
                nodes.add(node);
                if (node.attributes.isDirectory()) walk(root, normalized, realRoot, nodes);
            }
        }
    }

    private void verifyPath(Path path, Path realRoot) throws IOException {
        if (Files.isSymbolicLink(path) || reparseProbe.isReparseOrJunction(path)) {
            throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
        }
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(realRoot)) throw failure(PluginErrorCode.PATH_REJECTED);
    }

    private Node node(Path path, Path relative) throws IOException {
        if (Files.isSymbolicLink(path) || reparseProbe.isReparseOrJunction(path)) {
            throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() && !attributes.isRegularFile()) {
            throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
        }
        return new Node(path, relative, attributes);
    }

    private void verify(Node node) throws IOException {
        if (Files.isSymbolicLink(node.path) || reparseProbe.isReparseOrJunction(node.path)) {
            throw failure(PluginErrorCode.LINK_OR_SPECIAL_FILE_REJECTED);
        }
        BasicFileAttributes current = Files.readAttributes(
                node.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        boolean fileChanged = node.attributes.isRegularFile()
                && (current.size() != node.attributes.size()
                    || !current.lastModifiedTime().equals(node.attributes.lastModifiedTime()));
        if (current.isDirectory() != node.attributes.isDirectory()
                || current.isRegularFile() != node.attributes.isRegularFile()
                || fileChanged
                || !Objects.equals(current.fileKey(), node.attributes.fileKey())) {
            throw failure(PluginErrorCode.CONTENT_CHANGED);
        }
    }

    private static PluginBoundaryException failure(PluginErrorCode code) {
        return new PluginBoundaryException(code);
    }

    private record Tree(List<Node> nodes) { }
    private record Node(Path path, Path relative, BasicFileAttributes attributes) { }

    interface CopyObserver {
        void beforeFile(Path source) throws IOException;
        default void afterFile(Path target) throws IOException { }
    }

    @FunctionalInterface
    interface DeleteObserver { void beforeDelete(Path path) throws IOException; }

    @FunctionalInterface
    interface ReparseProbe {
        boolean isReparseOrJunction(Path path) throws IOException;
        static ReparseProbe system() {
            return path -> {
                try {
                    return Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).isOther();
                } catch (UnsupportedOperationException ignored) {
                    return false;
                }
            };
        }
    }
}
