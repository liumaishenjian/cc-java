package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 Provider/profile/generation 管理进程内模型 route lease 与 logout fence。
 *
 * <p>fence 一旦发布就先拒绝新 lease，再取消并关闭已有 route。drain 超时或 store 删除失败时 fence
 * 保持关闭，绝不因重试重新启用 credential。</p>
 */
public final class CredentialLeaseRegistry implements AutoCloseable {
    private final Object monitor = new Object();
    private final Map<ProfileKey, ProfileState> profiles = new HashMap<>();
    private boolean closed;

    /**
     * 创建空的进程内凭据租约注册表。
     */
    public CredentialLeaseRegistry() {
    }

    /**
     * 原子获取 generation 匹配的活动 lease；已 fence 时返回 AUTH_REVOKED。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @param generation 凭据代次
     * @param resource 与本次 lease 绑定、终止时需要关闭的 route 资源
     * @return 已登记的活动 lease
     */
    public Lease acquire(String providerId, String profileId, long generation, AutoCloseable resource) {
        ProfileKey key = new ProfileKey(providerId, profileId);
        synchronized (monitor) {
            if (closed) throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            ProfileState state = profiles.computeIfAbsent(key, ignored -> new ProfileState());
            if (state.fenced) throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            if (state.activeGeneration != null && state.activeGeneration.longValue() != generation) {
                throw failure(ProviderAuthException.Code.AUTH_REVOKED);
            }
            state.activeGeneration = generation;
            Lease lease = new Lease(this, key, generation, Objects.requireNonNull(resource, "resource 不能为空"));
            state.leases.add(lease);
            return lease;
        }
    }

    /**
     * 返回不暴露 generation、run identity 或资源细节的 active 数量。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @return 当前活动 lease 数量；profile 尚未登记时返回 {@code 0}
     */
    public int activeCount(String providerId, String profileId) {
        synchronized (monitor) {
            ProfileState state = profiles.get(new ProfileKey(providerId, profileId));
            return state == null ? 0 : state.leases.size();
        }
    }

    /**
     * 发布 logout fence，取消并关闭当前 leases，并在共享 deadline 内等待 terminal callback。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @param timeout 等待全部 lease 终止的最长时限
     * @param cancellation logout drain 的取消信号
     * @return 全部 lease 已终止时为 {@code true}；{@code false} 表示 REVOKING_BLOCKED，fence 仍保留
     */
    public boolean fenceAndDrain(String providerId, String profileId, Duration timeout,
                                 CancellationToken cancellation) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        Objects.requireNonNull(cancellation, "cancellation 不能为空");
        ProfileKey key = new ProfileKey(providerId, profileId);
        List<Lease> captured;
        synchronized (monitor) {
            ProfileState state = profiles.computeIfAbsent(key, ignored -> new ProfileState());
            state.fenced = true;
            captured = List.copyOf(state.leases);
        }
        captured.forEach(Lease::requestCancellationAndClose);
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (activeCountLocked(key) > 0) {
                if (cancellation.isCancellationRequested()) return false;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    long millis = Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(), 50));
                    monitor.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 标记本地删除完成；fence 永久保留到进程关闭。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     */
    public void markRevoked(String providerId, String profileId) {
        synchronized (monitor) {
            profiles.computeIfAbsent(new ProfileKey(providerId, profileId), ignored -> new ProfileState()).fenced = true;
        }
    }

    /**
     * 查询当前进程是否已 fence profile；只用于本地 status 投影。
     *
     * @param providerId Provider 标识
     * @param profileId 凭据 profile 标识
     * @return profile 已被 fence 时为 {@code true}，否则为 {@code false}
     */
    public boolean fenced(String providerId, String profileId) {
        synchronized (monitor) {
            ProfileState state = profiles.get(new ProfileKey(providerId, profileId));
            return state != null && state.fenced;
        }
    }

    private int activeCountLocked(ProfileKey key) {
        ProfileState state = profiles.get(key);
        return state == null ? 0 : state.leases.size();
    }
    private void terminal(Lease lease) {
        synchronized (monitor) {
            ProfileState state = profiles.get(lease.key);
            if (state != null && state.leases.remove(lease)) {
                if (state.leases.isEmpty()) state.activeGeneration = null;
                monitor.notifyAll();
            }
        }
    }
    @Override public void close() {
        List<Lease> leases = new ArrayList<>();
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            profiles.values().forEach(state -> { state.fenced = true; leases.addAll(state.leases); });
        }
        leases.forEach(Lease::requestCancellationAndClose);
    }
    private static ProviderAuthException failure(ProviderAuthException.Code code) {
        return new ProviderAuthException(code, ProviderAuthException.Action.LOGIN, false);
    }
    private record ProfileKey(String providerId, String profileId) {
        private ProfileKey { Objects.requireNonNull(providerId); Objects.requireNonNull(profileId); }
    }
    private static final class ProfileState {
        private boolean fenced;
        private Long activeGeneration;
        private final List<Lease> leases = new ArrayList<>();
    }

    /** 单个 Run route 的幂等 lease；resource close 与 terminal 分开支持协作式 drain。 */
    public static final class Lease implements AutoCloseable {
        private final CredentialLeaseRegistry owner;
        private final ProfileKey key;
        private final long generation;
        private final AutoCloseable resource;
        private final AtomicBoolean resourceClosed = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile Runnable cancellation = () -> { };
        private Lease(CredentialLeaseRegistry owner, ProfileKey key, long generation, AutoCloseable resource) {
            this.owner=owner; this.key=key; this.generation=generation; this.resource=resource;
        }
        /**
         * 绑定同一 Run 的协作式取消动作；fence 与 close 竞争时仍最多调用一次资源关闭。
         *
         * @param value fence 发布时执行的协作式取消动作
         */
        public void bindCancellation(Runnable value) { cancellation=Objects.requireNonNull(value); }
        /**
         * 返回只供 generation fence 测试的 generation，不进入 surface。
         *
         * @return 当前 lease 绑定的凭据代次
         */
        public long generation() { return generation; }
        private void requestCancellationAndClose() {
            try { cancellation.run(); } catch (RuntimeException ignored) { }
            closeResource();
        }
        private void closeResource() {
            if (resourceClosed.compareAndSet(false, true)) try { resource.close(); } catch (Exception ignored) { }
        }
        /** 发布恰好一次 terminal，并清理 route resource。 */
        @Override public void close() {
            closeResource();
            if (terminal.compareAndSet(false, true)) owner.terminal(this);
        }
    }
}
