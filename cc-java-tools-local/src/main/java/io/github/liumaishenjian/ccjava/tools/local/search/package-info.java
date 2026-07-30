/**
 * 精确文本搜索后端及受控 ripgrep 进程适配器。
 *
 * <p>该包只负责把已经校验的搜索请求映射到执行引擎；Workspace 权限、Tool 生命周期、
 * 结果协议和模型循环分别由相邻 Guard、Pipeline 与 Runtime 负责。</p>
 */
package io.github.liumaishenjian.ccjava.tools.local.search;
