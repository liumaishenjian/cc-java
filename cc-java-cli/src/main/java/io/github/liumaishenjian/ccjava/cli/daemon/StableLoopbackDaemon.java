package io.github.liumaishenjian.ccjava.cli.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.protocol.CapabilityToken;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * stable v1 handler 的 loopback-only HTTP 传输与有界 drain 生命周期。
 *
 * <p>{@code POST /v1/message} 接收一条 stable envelope，{@code GET /v1/events} 返回下一条
 * envelope（204 表示当前为空）。每个请求都要求进程启动时生成的 Bearer token；本类型不实现
 * 远程监听、TLS、账户、多租户或第二套 Runtime。</p>
 *
 * @since 0.1.0
 */
public final class StableLoopbackDaemon implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = io.github.liumaishenjian.ccjava.protocol.StableProtocolCodec.MAX_LINE_BYTES;
    private final CapabilityToken token;
    private final StableProtocolHandler handler;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.CountDownLatch closedLatch = new java.util.concurrent.CountDownLatch(1);

    /**
     * 创建仅绑定 loopback 的 stable v1 HTTP 传输。
     *
     * @param port 监听端口；零表示由操作系统分配
     * @param token 每次 HTTP 请求必须提供的 capability token
     * @param handler 唯一 stable protocol 状态机与 Application adapter
     * @throws IOException 无法创建 loopback server 时
     */
    public StableLoopbackDaemon(int port, CapabilityToken token, StableProtocolHandler handler) throws IOException {
        this.token = Objects.requireNonNull(token, "token 不能为空");
        this.handler = Objects.requireNonNull(handler, "handler 不能为空");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32);
        server.createContext("/v1/message", this::message);
        server.createContext("/v1/events", this::events);
        server.setExecutor(executor);
    }

    /** 启动 loopback listener。 */
    public void start() {
        server.start();
    }

    /**
     * 返回操作系统实际分配的 loopback 监听端口。
     *
     * @return 监听端口
     */
    public int port() {
        return server.getAddress().getPort();
    }

    private void message(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"POST".equals(exchange.getRequestMethod())) { reply(exchange, 405, new byte[0]); return; }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length == 0 || body.length > MAX_REQUEST_BYTES) { reply(exchange, 413, new byte[0]); return; }
        try {
            handler.receive(body);
            reply(exchange, 202, new byte[0]);
            if (handler.isClosed()) {
                Thread.ofVirtual().start(this::close);
            }
        } catch (io.github.liumaishenjian.ccjava.protocol.ProtocolCodecException | RuntimeException invalid) {
            reply(exchange, 400, new byte[0]);
        }
    }

    private void events(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) { reply(exchange, 405, new byte[0]); return; }
        var output = handler.takeOutput(Duration.ofMillis(50));
        reply(exchange, output.isPresent() ? 200 : 204, output.orElse(new byte[0]));
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String value = exchange.getRequestHeaders().getFirst("Authorization");
        if (value == null || !token.matches(value.startsWith("Bearer ") ? value.substring(7) : "")) {
            reply(exchange, 401, new byte[0]);
            return false;
        }
        return true;
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body.length > 0) exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    /**
     * 等待远端 shutdown 或本地 close；用于 CLI 外层进程生命周期。
     *
     * @throws InterruptedException 当前等待线程被中断时
     */
    public void awaitClosed() throws InterruptedException { closedLatch.await(); }

    /** 停止接收、关闭连接 fence 并释放 ownership 上层持有的资源。 */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            server.stop(1);
            handler.close();
            executor.shutdownNow();
        } finally {
            closedLatch.countDown();
        }
    }
}
