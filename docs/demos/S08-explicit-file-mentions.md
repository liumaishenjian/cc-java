# S08 显式文件引用 Demo

## 目标

证明候选 UX 与权威文件快照分离：TUI 只编辑 mention，Java 在提交边界安全读取，并把不可变附件交给 Runtime、Session 与模型映射。

## 场景

1. 在 Workspace 创建 `src/App.java` 与 `docs/design notes.md`。
2. 启动 `codej`，输入 `解释 @src/`；等待文件建议，使用方向键并按 Tab 或 Enter 接受。
3. 输入 `比较 @"docs/design notes.md"#L2-8 与 @src/App.java`，再次按 Enter 提交。
4. 在 Run 进行中输入 `补充 @src/App.java#L10-20`；该 steering 在提交时形成快照，前一 Run 终态后再启动。
5. 尝试 `@../outside.txt`、`@.git/config`、目录、二进制或超过预算的文件。

## 可观察结果

- 候选接受只替换当前光标所属 token；首次 Enter 接受候选，下一次 Enter 才提交。
- 空格或包含 `#L` 的路径自动形成双引号 mention；邮箱、`\@literal` 与 Slash-only 输入不触发文件候选。
- 合法提交的 `run.started` 保存附件路径、快照 digest、行范围和截断标记；Resume/Fork 使用相同快照，不重新读取文件。
- 非法 mention 返回固定 `FILE_MENTION_INVALID`；没有 `run.started`、模型请求、Canonical/JSONL Run 写入或部分 steering。
- `file.suggest` 只返回有界 Workspace-relative 候选，不返回绝对路径、敏感内容或文件正文。

## 自动复现

```text
.\mvnw.cmd -pl cc-java-domain,cc-java-core,cc-java-model-spring-ai,cc-java-cli -am test
npm --prefix cc-java-tui run check
```

真实 Provider 不是本 Demo 的前提；Fake Gateway 与 stdio Fixture 足以证伪协议、安全边界和 Session 语义。
