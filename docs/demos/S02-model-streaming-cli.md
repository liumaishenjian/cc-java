# S02 Model + Streaming CLI Demo

> Stage：S02 — Model + Streaming CLI
> Demo 类型：离线确定性回归 + 显式启用的本地 Ollama E2E + 真实 CLI 进程
> Reference Baseline：`R2026.03`
> Authorized Snapshot ID：`N/A - Not Used`
> Date：2026-07-28

## 1. 演示目标

本 Demo 验证：

- Core 接收模型文本 Delta，但仍以普通 Java 控制流拥有整个 Agent Loop；
- Spring AI Adapter 聚合文本、Tool Call、Usage 和 Finish Reason，不执行 Tool；
- 同一回合两个 Tool Call 保留不同 ID 与顺序，Result 进入下一模型回合；
- 模型流取消、Deadline、重试和输出长度都具有有界终态；
- Boot 只做 Composition Root，Picocli/JLine 只做终端边界；
- Print、non-TTY、stdout/stderr、ANSI 和退出码行为可作为真实进程复现。

## 2. 前置条件

### 2.1 离线 Demo

- JDK 21；
- 仓库 Maven Wrapper（Maven 3.9.16）；
- 首次下载依赖时可以访问 Maven Central；
- 不需要 Ollama、模型、网络调用或 API Key。

### 2.2 真实 Provider Demo

- Ollama 0.32.4 正在本机监听，默认地址为 `http://localhost:11434`；
- 使用者已经准备一个支持 Tool Calling 的本地模型；
- 用 `<LOCAL_MODEL>` 替换显式模型 Tag，并在证据记录中同时固定其 Digest；
- 不把模型 Tag、Digest、Prompt 输出或本地路径写成仓库默认值。

## 3. 离线确定性回归

```powershell
.\mvnw.cmd clean verify
```

2026-07-28 实际结果：

```text
95 tests run: 94 passed, 0 failed, 0 errors
1 opt-in Provider E2E skipped
BUILD SUCCESS
```

这是最后一次已完成的完整运行；它早于最终 JUnit BOM 构建输入修正。依用户要求停止
继续验证后，没有在最终候选上复跑。维护者复现 S02 时必须重新执行本命令，不能把上述
结果当作最终 Commit 的验证。

离线回归包含正常流、
多 Tool Call、Chunk 边界、不完整流、错误/限流 Fixture、Usage 缺失、取消、Deadline、
有界 Retry、`LENGTH`、Interactive/Print、Ctrl+C、non-TTY、终端控制序列清洗和退出码。
流安全负例还覆盖容量 2 的逐项背压队列、8 MiB UTF-8 / 128 calls 聚合上限、超限取消、
重复 Call ID、冲突 Finish Reason 和无效 Usage。

## 4. 真实 Ollama Provider E2E

```powershell
.\mvnw.cmd -pl cc-java-model-spring-ai -am test `
  '-Dtest=OllamaProviderE2ETest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dcc.java.ollama.e2e.model=<LOCAL_MODEL>'
```

若 Ollama 不在默认地址，再添加：

```text
-Dcc.java.ollama.e2e.base-url=http://localhost:11434
```

2026-07-28 实际结果：该场景两次独立运行均为 1/1 通过，首次约 97 秒、复验
110.051 秒。应观察到：

1. 至少一个文本 Delta；
2. 文本回合以 `STOP` 结束并保留 Usage；
3. 同一模型回合请求 `sum_numbers` 与 `repeat_text` 两个 Tool；
4. 两个 Call ID 不同，工具顺序保持；
5. 由测试独立构造的 Result 进入下一回合后得到最终文本；
6. 1 token 输出预算返回 `LENGTH`；
7. 第一个取消触发 Delta 后，Gateway 返回 `CANCELLED`，不继续发布 Delta。

测试只断言协议结构，不断言本地模型的固定自然语言。

## 5. 真实 Print CLI

先构建并安装当前 Reactor：

```powershell
$env:JAVA_HOME='<JDK_21_HOME>'
.\mvnw.cmd -q -DskipTests install
```

本轮实际验证使用 `C:\tmp\cc-java-jdk21\jdk-21.0.12+8`；复现者应替换为自己的 JDK 21
安装目录。

运行 Print：

```powershell
.\mvnw.cmd -q -f .\cc-java-cli\pom.xml `
  org.codehaus.mojo:exec-maven-plugin:3.6.3:java `
  '-Dexec.mainClass=io.github.liumaishenjian.ccjava.cli.CcJavaMain' `
  '-Dexec.args=--model=<LOCAL_MODEL> --timeout-seconds=180 --max-output-tokens=64 --print=只回复S02正常'
```

本次实际观察为退出码 0、stdout 单行文本、stderr 包含 `[model]` 状态，且该样本两路
都没有 ANSI ESC。自然语言和字符数不是稳定断言。独立离线 Renderer 回归验证模型
内嵌 ESC/CSI/OSC/DCS/C0/C1 会被清洗，Print 与 Interactive 共用相同安全契约。

### 5.1 环境变量等价形式

```powershell
$env:CC_JAVA_MODEL='<LOCAL_MODEL>'
$env:CC_JAVA_OLLAMA_BASE_URL='http://localhost:11434'
```

其他 S02 环境变量为 `CC_JAVA_WORKSPACE`、`CC_JAVA_MAX_OUTPUT_TOKENS`、
`CC_JAVA_TIMEOUT_SECONDS` 和 `CC_JAVA_MAX_RETRIES`。当前 Ollama 不要求
`CC_JAVA_OLLAMA_API_KEY`；该名称只保留 Secret presence 契约。CLI 值优先于环境变量。

## 6. 真实进程退出码与流分离

Maven Exec 适合手动运行；要核对真实进程退出码，先生成运行期 Classpath：

```powershell
.\mvnw.cmd -q -f .\cc-java-cli\pom.xml `
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath `
  '-Dmdep.outputFile=C:\tmp\cc-java-cli-classpath.txt' `
  '-Dmdep.outputAbsoluteArtifactFilename=true'
```

然后把 `cc-java-cli\target\classes` 与该文件内容用 Windows `;` 连接，直接启动：

```text
java -cp "<CLI_CLASSES>;<DEPENDENCY_CLASSPATH>" \
  io.github.liumaishenjian.ccjava.cli.CcJavaMain <args>
```

2026-07-28 使用 PowerShell `ProcessStartInfo` 分别重定向 stdout/stderr 后实际得到：

| 场景 | 预期与实际结果 |
| --- | --- |
| `--print` + 64 tokens | exit 0；stdout 单行；状态在 stderr；无 ANSI |
| `--print` + 32 tokens | exit 5；`LENGTH` 有界停止；无 ANSI |
| non-TTY，无 `--print` | exit 3；不等待输入；stderr 提示使用 `--print` |
| PTY 自动化工具，无 `--print` | 被正确识别为 non-TTY，exit 3 |

`--max-output-tokens=32` 是本 Demo 的负例：模型输出被截断时必须用非零退出码明确报告，
不能把部分输出伪装成成功，也不能无界续写。

## 7. 可选的真实终端检查

在真实 Windows Terminal 中运行以下命令可以人工检查行编辑、多轮、`/exit` 和 Ctrl+C：

```powershell
.\mvnw.cmd -q -f .\cc-java-cli\pom.xml `
  org.codehaus.mojo:exec-maven-plugin:3.6.3:java `
  '-Dexec.mainClass=io.github.liumaishenjian.ccjava.cli.CcJavaMain' `
  '-Dexec.args=--model=<LOCAL_MODEL> --timeout-seconds=180'
```

本轮自动化 PTY 没有提供 JLine 所需的真实 Windows Console 能力；用户随后要求停止
继续验证，并将在提交后自行验证。因此该人工检查保持 `Unknown`，不属于已经执行的
结果。离线 Fake Terminal/JLine 测试已验证项目状态迁移，但不能替代原生终端体验证据。

## 8. 事实边界

本 Demo 不能证明：

- Provider 服务端在客户端取消后立即停止全部计算；
- Provider SDK 在 Adapter 收到 `ChatResponse` 前已分配的单个巨大对象；
- 真实反向代理限流、第二 Provider 或未来 Ollama 版本兼容；
- Windows 以外平台的原生终端行为；
- 文件读取/修改、Shell、完整 Permission 或 OS Sandbox；
- 持久 Session、Context 压缩、稳定 JSONL、遥测 Export 或发行包；
- 模型自然语言答案的确定性或正确性。

持久证据见
[S02 验证证据](../evidence/S02-model-streaming-cli-2026-07-28.md)，设计理由见
[ADR-022](../adr/ADR-022-s02-provider-streaming-cli-decisions.md)，剩余差距见
[S02 差距报告](../gap-reports/S02.md)。
