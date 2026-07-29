# S02 Java Print Demo

## 前置条件

1. JDK 21；
2. 已在 Git 忽略的 `config/provider.local.properties` 填写本机 Provider；
3. Windows PowerShell 允许本次脚本通过 `-ExecutionPolicy Bypass` 运行。

## 从仓库目录运行

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\cc-java.ps1 `
  --print "介绍一下你自己"
```

## 从任意目录运行

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "E:\Java\cc-java\cc-java.ps1" `
  --workspace "E:\Java\cc-java" `
  --timeout 30s `
  --print "介绍一下你自己"
```

脚本通过自身路径定位仓库根 POM，不依赖当前目录。成功时 stdout 只有 Assistant 文本，
退出码为 0。脚本会显式把 Windows PowerShell 5.1 的输入、输出与管道编码统一为
UTF-8，避免 Java UTF-8 文本被系统 GBK/OEM 代码页误解码。参数或本地配置错误为 2，
Runtime/Provider 失败为 1，用户取消终态为 130。

查看帮助不会读取 Provider 配置或调用模型：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "E:\Java\cc-java\cc-java.ps1" `
  --help
```

当前 `--print` 是一次性 Headless 模式，不读取仓库文件、不执行 Shell，也不提供
文件 Tool。`--workspace`、`--model`、`--timeout` 同样可以放在 `--stdio` 前；
API Key 和 Base URL 不接受命令行覆盖。

墙钟超时负例：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "E:\Java\cc-java\cc-java.ps1" `
  --workspace "E:\Java\cc-java" `
  --timeout 10ms `
  --print "请回复一句中文"
```

真实模型未在 10ms 内结束时，stderr 为 `cc-java: run timed out`，退出码为 1，
且超时后不再输出属于该 Run 的文本。
