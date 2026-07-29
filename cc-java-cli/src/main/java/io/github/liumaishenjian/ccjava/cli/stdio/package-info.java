/**
 * 提供 Java Headless Runtime 与终端 Client 之间的内部 stdio v0 适配。
 *
 * <p>本包只负责 UTF-8 NDJSON 的边界校验、命令顺序、事件串行化、背压和连接生命周期。
 * 它不定义 Agent 决策，不执行 Tool，也不把 Jackson 类型泄漏到 Domain/Core。
 * S02 Spike 期间该协议没有跨版本兼容承诺。</p>
 *
 * @since 0.1.0
 */
package io.github.liumaishenjian.ccjava.cli.stdio;
