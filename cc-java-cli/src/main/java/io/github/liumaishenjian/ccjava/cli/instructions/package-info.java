/**
 * S08 指令候选的 CLI 安全适配层。
 *
 * <p>本包只发现和受控读取固定来源，验证路径、链接、容量与编码边界；指令正文不参与权限决策，
 * 也不提供 OS Sandbox。</p>
 */
package io.github.liumaishenjian.ccjava.cli.instructions;
