# 修复任务

给 `src/Calculator.java` 增加 `divide(int, int)`：

1. 返回整数除法结果；
2. 除数为 0 时抛出带明确消息的 `IllegalArgumentException`；
3. 在同一文件的 `--self-test` 中增加正常除法和零除数测试。

验收条件：

1. `java src/Calculator.java --self-test` 输出 `ACCEPTANCE_OK`；
2. 只允许修改 `src/Calculator.java`；
3. 不得修改 `DO_NOT_EDIT.txt`；
4. 使用 `git_diff` 检查最终变更。
