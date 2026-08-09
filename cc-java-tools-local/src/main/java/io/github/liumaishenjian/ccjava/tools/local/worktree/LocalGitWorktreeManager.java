package io.github.liumaishenjian.ccjava.tools.local.worktree;

import io.github.liumaishenjian.ccjava.domain.worktree.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 通过 {@link ProcessBuilder} 固定 argv 管理项目私有 Git Worktree。
 *
 * <p>目标 root 必须是 canonical repository 下的 {@code .cc-java/worktrees} 且无链接组件；创建记录
 * 明确 base commit 与仓库 identity。删除前重新验证 registration、identity、无 active owner、clean、
 * 无 base 后新 commit；任何失败返回 FAILED_PRESERVED，绝不使用 {@code --force}。</p>
 * @since 0.12.0
 */
public final class LocalGitWorktreeManager implements WorktreeManager {
    private static final Duration TIMEOUT=Duration.ofSeconds(15);
    private static final int MAX_OUTPUT=64_000;
    private final Path repository;
    private final Path root;
    private final String repositoryIdentity;
    private final Map<WorktreeLeaseId, Path> paths=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<WorktreeLeaseId> active=java.util.concurrent.ConcurrentHashMap.newKeySet();

    public LocalGitWorktreeManager(Path repository) {
        try {
            this.repository=Objects.requireNonNull(repository).toRealPath();
            String top=run(this.repository, List.of("git","rev-parse","--show-toplevel")).trim();
            if (!Path.of(top).toRealPath().equals(this.repository)) throw new IllegalArgumentException("必须使用 canonical repository root");
            root = this.repository.resolve(".cc-java").resolve("worktrees").normalize();
            ensureExistingAncestorsSafe(this.repository, root);
            Files.createDirectories(root);
            ensureExistingAncestorsSafe(this.repository, root);
            String commonDirectory = run(this.repository,
                    List.of("git", "rev-parse", "--git-common-dir")).trim();
            Path commonPath = Path.of(commonDirectory);
            if (!commonPath.isAbsolute()) commonPath = this.repository.resolve(commonPath).normalize();
            repositoryIdentity = sha256(commonPath.toRealPath().toString()
                    .replace('\\', '/').toLowerCase(Locale.ROOT));
        } catch (IOException failure) { throw new IllegalArgumentException("Worktree root 初始化失败", failure); }
    }

    @Override public WorktreeLease create(String slug, String baseCommit) {
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{0,63}") || baseCommit == null || !baseCommit.matches("[0-9a-f]{40,64}"))
            throw new IllegalArgumentException("slug 或 base commit 无效");
        Path target=root.resolve(slug).normalize();
        if (!target.getParent().equals(root) || Files.exists(target)) throw new IllegalArgumentException("Worktree target 无效");
        String branch="cc-java/s12/"+slug;
        try {
            run(repository, List.of("git","worktree","add","-b",branch,target.toString(),baseCommit));
        } catch (RuntimeException failure) {
            // 创建失败不执行全局 prune；无法证明该目标无价值时保留现场供人工检查。
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    run(repository, List.of("git", "branch", "-d", branch));
                } catch (RuntimeException ignored) {
                    // 分支状态未知时保守保留，不影响无关 Worktree。
                }
            }
            throw failure;
        }
        WorktreeLease lease=new WorktreeLease(new WorktreeLeaseId("wt-"+slug), repositoryIdentity, baseCommit,
                branch, "worktrees/"+slug, WorktreeDisposition.READY);
        paths.put(lease.id(), target); return lease;
    }

    @Override public Path enter(WorktreeLease lease) {
        Path path=verifiedPath(lease);
        try { path=path.toRealPath(); ensureRegistered(path); }
        catch (IOException failure) { throw new IllegalStateException("Worktree 不可进入"); }
        active.add(lease.id()); return path;
    }

    @Override public void leave(WorktreeLease lease) { verifiedPath(lease); active.remove(lease.id()); }

    /** 释放 active owner；随后显式 keep。 */
    @Override public WorktreeLease keep(WorktreeLease lease) {
        leave(lease);
        return copy(lease, WorktreeDisposition.KEPT);
    }

    @Override public WorktreeLease removeClean(WorktreeLease lease) {
        Path path;
        try {
            path=verifiedPath(lease);
            if (active.contains(lease.id())) return copy(lease, WorktreeDisposition.FAILED_PRESERVED);
            ensureRegistered(path);
            if (!run(path, List.of("git", "status", "--porcelain=v1")).isBlank()
                    || !run(path, List.of("git", "ls-files", "--others", "--ignored", "--exclude-standard")).isBlank()) {
                return copy(lease, WorktreeDisposition.FAILED_PRESERVED);
            }
            String head=run(path,List.of("git","rev-parse","HEAD")).trim();
            if (!head.equals(lease.baseCommit())) return copy(lease, WorktreeDisposition.FAILED_PRESERVED);
            run(repository, List.of("git", "worktree", "remove", path.toString()));
            paths.remove(lease.id());
            active.remove(lease.id());
            try {
                run(repository, List.of("git", "branch", "-d", lease.branch()));
                return copy(lease, WorktreeDisposition.REMOVED);
            } catch (RuntimeException branchFailure) {
                return copy(lease, WorktreeDisposition.REMOVED_BRANCH_PRESERVED);
            }
        } catch (RuntimeException failure) {
            return copy(lease, WorktreeDisposition.FAILED_PRESERVED);
        }
    }

    private Path verifiedPath(WorktreeLease lease) {
        Objects.requireNonNull(lease);
        if (!repositoryIdentity.equals(lease.repositoryIdentity())) throw new IllegalArgumentException("Worktree identity 不匹配");
        Path path=paths.get(lease.id());
        if (path == null || !path.normalize().startsWith(root) || !lease.opaqueRoot().equals("worktrees/"+path.getFileName()))
            throw new IllegalArgumentException("Worktree lease 不匹配");
        return path;
    }
    private void ensureRegistered(Path path) {
        String listed=run(repository,List.of("git","worktree","list","--porcelain"));
        String normalized=path.toAbsolutePath().normalize().toString().replace('\\','/');
        if (Arrays.stream(listed.split("\\R")).filter(line->line.startsWith("worktree "))
                .map(line->line.substring(9).replace('\\','/')).noneMatch(normalized::equals)) throw new IllegalStateException("Worktree 未注册");
    }
    private static void ensureExistingAncestorsSafe(Path base, Path target) throws IOException {
        Path current = base;
        for (Path part : base.relativize(target)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            var attributes = Files.readAttributes(current,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !current.toRealPath().startsWith(base.toRealPath())) {
                throw new IOException("Worktree root 包含链接或 reparse point");
            }
        }
    }
    private static String run(Path cwd,List<String> argv) {
        try {
            ProcessBuilder builder=new ProcessBuilder(argv); builder.directory(cwd.toFile()); builder.redirectErrorStream(true);
            Map<String,String> env=builder.environment(); env.clear();
            copyEnv(env,"PATH"); copyEnv(env,"SystemRoot"); copyEnv(env,"WINDIR"); copyEnv(env,"TEMP"); copyEnv(env,"TMP");
            env.put("GIT_TERMINAL_PROMPT","0"); env.put("GCM_INTERACTIVE","Never"); env.put("LC_ALL","C");
            Process process = builder.start();
            var output = new java.io.ByteArrayOutputStream();
            Thread drainer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var stream = process.getInputStream()) {
                    stream.transferTo(new java.io.OutputStream() {
                        @Override
                        public synchronized void write(int value) {
                            if (output.size() <= MAX_OUTPUT) output.write(value);
                        }
                        @Override
                        public synchronized void write(byte[] bytes, int offset, int length) {
                            int remaining = Math.max(0, MAX_OUTPUT + 1 - output.size());
                            output.write(bytes, offset, Math.min(length, remaining));
                        }
                    });
                } catch (IOException ignored) {
                    // 主线程依据进程终态统一报告失败。
                }
            });
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyTree(process);
                join(drainer);
                throw new IllegalStateException("Git timeout");
            }
            join(drainer);
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_OUTPUT || process.exitValue() != 0) {
                throw new IllegalStateException("Git command failed");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException|InterruptedException failure) { if (failure instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IllegalStateException("Git command failed",failure); }
    }
    private static void destroyTree(Process process) {
        process.descendants().forEach(child -> {
            try {
                child.destroyForcibly();
            } catch (RuntimeException ignored) {
                // 尽力清理全部后代。
            }
        });
        process.destroyForcibly();
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TIMEOUT.toMillis());
        if (thread.isAlive()) {
            thread.interrupt();
            throw new IllegalStateException("Git output drain timeout");
        }
    }

    private static void copyEnv(Map<String,String> target,String key) { String value=System.getenv(key); if(value!=null) target.put(key,value); }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
    private static WorktreeLease copy(WorktreeLease lease,WorktreeDisposition disposition){return new WorktreeLease(lease.id(),lease.repositoryIdentity(),lease.baseCommit(),lease.branch(),lease.opaqueRoot(),disposition);}
}
