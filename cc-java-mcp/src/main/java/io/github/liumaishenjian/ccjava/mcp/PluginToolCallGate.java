package io.github.liumaishenjian.ccjava.mcp;

import java.util.concurrent.Callable;

/** contribution close 与并发远端调用之间的进程内 quiescing gate。 */
final class PluginToolCallGate implements AutoCloseable {
    private boolean accepting = true;
    private int calls;

    synchronized <T> T call(Callable<T> operation) throws Exception {
        if (!accepting) throw new IllegalStateException("Plugin contribution 已 quiescing");
        calls++;
        try {
            return operation.call();
        } finally {
            calls--;
            if (calls == 0) notifyAll();
        }
    }

    @Override
    public synchronized void close() {
        accepting = false;
        boolean interrupted = false;
        while (calls != 0) {
            try {
                wait();
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
