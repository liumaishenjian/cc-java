# cc-java Compatibility Policy

- stable protocol v1 使用 semantic major/minor；major 不兼容，minor 只能增加协商能力。
- experimental stdio v0 至少与首个 v1 本地发行候选共存一个 release，不原地改变既有 v0 语义。
- Session canonical JSONL 保持内部事实源；稳定外部交换只使用 `cc-java-session-export-v1`。
- 配置、Plugin registry 与 Session migration 必须 staging/verify/atomic publish，失败保留 last-known-good。
- 项目已选择 Apache-2.0，`0.1.0` 为首个公开发行候选；tag workflow 成功前不声称已发布，也不声称 N/N-1 artifact 兼容。
- Windows/Linux 平台包支持 Java 21 app-dir、编译 Ink TUI、Node 22 与用户级版本目录安装；CI 正式包携带 Java/Node runtime，本地候选可使用系统运行时。
- 更新只在 archive checksum、内部 manifest/checksum 与 staging 全部验证后原子切换 `current.txt`；旧版本保留为 LKG。Native Image、macOS、publisher signature/revocation 与后台自动更新延期。
