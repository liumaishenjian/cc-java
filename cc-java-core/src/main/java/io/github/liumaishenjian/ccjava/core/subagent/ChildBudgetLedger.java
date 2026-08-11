package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.domain.subagent.ChildBudget;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对父预算实施多维原子预留、实际结算和幂等回收。
 *
 * <p>所有维度在同一 monitor 内检查并扣减，避免兄弟任务分别通过检查后超卖。任务终态必须以
 * 实际消耗调用 {@link Reservation#settle(ChildBudget)}；只有在任务尚未启动或创建失败时，
 * {@link Reservation#close()} 才完整退还预留。</p>
 *
 * @since 0.12.0
 */
public final class ChildBudgetLedger {
    private final ChildBudget capacity;
    private long turns;
    private long tools;
    private long tokens;
    private long output;
    private long millis;

    /**
     * 创建绑定父级总预算的 ledger。
     *
     * @param total 所有子任务共享的总 ceiling
     */
    public ChildBudgetLedger(ChildBudget total) {
        capacity = Objects.requireNonNull(total, "total 不能为空");
        turns = total.modelTurns();
        tools = total.toolCalls();
        tokens = total.inputTokens();
        output = total.outputCharacters();
        millis = total.duration().toMillis();
    }

    /**
     * 原子预留全部请求维度。
     *
     * @param requested 子任务上界
     * @return 预算充足时的 reservation，否则为空且不改变任何维度
     */
    public synchronized java.util.Optional<Reservation> reserve(ChildBudget requested) {
        Objects.requireNonNull(requested, "requested 不能为空");
        long requestedMillis = requested.duration().toMillis();
        if (turns < requested.modelTurns() || tools < requested.toolCalls()
                || tokens < requested.inputTokens() || output < requested.outputCharacters()
                || millis < requestedMillis) {
            return java.util.Optional.empty();
        }
        turns -= requested.modelTurns();
        tools -= requested.toolCalls();
        tokens -= requested.inputTokens();
        output -= requested.outputCharacters();
        millis -= requestedMillis;
        return java.util.Optional.of(new Reservation(this, requested));
    }

    /**
     * 返回当前可分配预算快照，供测试和父级 remaining 绑定。
     *
     * @return 原子读取的剩余预算
     */
    public synchronized ChildBudget remaining() {
        return new ChildBudget(Math.toIntExact(turns), Math.toIntExact(tools), tokens,
                Math.toIntExact(output), Duration.ofMillis(millis));
    }

    /**
     * 返回初始容量；该值不可随 reservation 改变。
     *
     * @return 构造时冻结的总 ceiling
     */
    public ChildBudget capacity() {
        return capacity;
    }

    private synchronized void refund(long refundTurns, long refundTools, long refundTokens,
            long refundOutput, long refundMillis) {
        turns += refundTurns;
        tools += refundTools;
        tokens += refundTokens;
        output += refundOutput;
        millis += refundMillis;
        if (turns > capacity.modelTurns() || tools > capacity.toolCalls()
                || tokens > capacity.inputTokens() || output > capacity.outputCharacters()
                || millis > capacity.duration().toMillis()) {
            throw new IllegalStateException("子预算发生重复退还");
        }
    }

    /** 持有一次预留；结算或关闭至多成功一次。 */
    public static final class Reservation implements AutoCloseable {
        private final ChildBudgetLedger owner;
        private final ChildBudget reserved;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(ChildBudgetLedger owner, ChildBudget reserved) {
            this.owner = owner;
            this.reserved = reserved;
        }

        /**
         * 查询本 reservation 的 ceiling。
         *
         * @return 不可变预算上界
         */
        public ChildBudget budget() {
            return reserved;
        }

        /**
         * 保留实际消耗并只退还可证明未使用的部分。
         *
         * @param actual 实际消耗；各维度不得超过 reservation
         */
        public void settle(ChildBudget actual) {
            Objects.requireNonNull(actual, "actual 不能为空");
            if (!within(actual, reserved)) {
                throw new IllegalArgumentException("实际消耗超过子预算预留");
            }
            if (closed.compareAndSet(false, true)) {
                owner.refund(
                        reserved.modelTurns() - actual.modelTurns(),
                        reserved.toolCalls() - actual.toolCalls(),
                        reserved.inputTokens() - actual.inputTokens(),
                        reserved.outputCharacters() - actual.outputCharacters(),
                        reserved.duration().minus(actual.duration()).toMillis());
            }
        }

        /** 创建失败或尚未开始时完整退还。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.refund(reserved.modelTurns(), reserved.toolCalls(), reserved.inputTokens(),
                        reserved.outputCharacters(), reserved.duration().toMillis());
            }
        }

        private static boolean within(ChildBudget value, ChildBudget ceiling) {
            return value.modelTurns() <= ceiling.modelTurns()
                    && value.toolCalls() <= ceiling.toolCalls()
                    && value.inputTokens() <= ceiling.inputTokens()
                    && value.outputCharacters() <= ceiling.outputCharacters()
                    && value.duration().compareTo(ceiling.duration()) <= 0;
        }
    }
}
