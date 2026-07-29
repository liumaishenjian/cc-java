package io.github.liumaishenjian.ccjava.cli.stdio;

import java.time.Duration;
import java.util.List;

/**
 * 供跨进程测试启动的 Fake stdio 进程，不进入生产制品。
 */
public final class StdioProtocolFixtureMain {

    private StdioProtocolFixtureMain() {
    }

    /**
     * 启动确定性 Fake Server。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        try {
            FakeStdioCommandHandler handler = new FakeStdioCommandHandler(
                    List.of("alpha ", "beta"),
                    Duration.ofMillis(250));
            StdioProtocolServer.ExitReason reason =
                    new StdioProtocolServer(System.in, System.out, handler).run();
            if (reason == StdioProtocolServer.ExitReason.INTERNAL_ERROR) {
                System.exit(2);
            }
        } catch (Exception exception) {
            System.err.println("Fake stdio fixture failed");
            System.exit(2);
        }
    }
}
