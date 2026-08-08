/**
 * S09 外部 Hook Adapter。
 *
 * <p>本包只负责把 Core 的脱敏 HookInvocation 映射到受控本地进程；进程结果仍必须
 * 回到 Core HookCoordinator，由绑定的信任、超时和失败策略决定是否影响 Runtime。</p>
 */
package io.github.liumaishenjian.ccjava.cli.hooks;
