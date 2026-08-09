/**
 * 定义 S11 Plugin manifest、命名空间、内容指纹、不可变快照和生命周期状态的框架无关协议。
 *
 * <p>Domain 不解析 JSON、不访问文件系统、不加载 Java 类，也不构造可信 Tool。Plugin 只表达
 * 已验证组件包的身份；fingerprint 不是签名、作者认证或 OS Sandbox。</p>
 *
 * @since 0.11.0
 */
package io.github.liumaishenjian.ccjava.domain.plugin;
