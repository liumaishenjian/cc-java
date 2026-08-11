# cc-java Compatibility Policy

- stable protocol v1 使用 semantic major/minor；major 不兼容，minor 只能增加协商能力。
- experimental stdio v0 至少与首个 v1 本地发行候选共存一个 release，不原地改变既有 v0 语义。
- Session canonical JSONL 保持内部事实源；稳定外部交换只使用 `cc-java-session-export-v1`。
- 配置、Plugin registry 与 Session migration 必须 staging/verify/atomic publish，失败保留 last-known-good。
- 当前 `0.1.0-SNAPSHOT` 仅为本地/CI candidate；因 LICENSE 未决，不生成公开 Release，也不声称 N/N-1 已发布 artifact 兼容。
- Windows 与 Linux launcher 支持 Java 21 app-dir；Native Image、macOS installer、公共自动更新渠道延期。
