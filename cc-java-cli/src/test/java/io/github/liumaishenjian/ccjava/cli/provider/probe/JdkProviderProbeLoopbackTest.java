package io.github.liumaishenjian.ccjava.cli.provider.probe;

import com.sun.net.httpserver.HttpServer;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderCatalog;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.network.NetworkAccessDecision;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;

/** JDK loopback wire test 覆盖稳定 HTTP 映射、body ceiling、timeout 与 exactly-one-attempt。 */
class JdkProviderProbeLoopbackTest {
    private HttpServer server; private URI endpoint; private final AtomicInteger attempts=new AtomicInteger();
    private final AtomicReference<String> mode=new AtomicReference<>("success");
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/probe",exchange->{attempts.incrementAndGet(); String current=mode.get();
            if(current.equals("timeout"))try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            int status=switch(current){case "rejected"->401;case "rate"->429;case "redirect"->302;default->200;};
            byte[] body=(current.equals("invalid")?"bad":current.equals("oversize")?"x".repeat(70_000):"{\"data\":[]}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", current.equals("wrong-media")
                    ? "text/plain" : "application/json; charset=utf-8");
            try{exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});
        server.start(); endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/probe");
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void mapsWireBranchesWithoutRetryOrRedirect() {
        assertAttempt("success",Duration.ofSeconds(1),ProviderProbePort.ProbeOutcome.SUCCESS);
        assertAttempt("rejected",Duration.ofSeconds(1),ProviderProbePort.ProbeOutcome.REJECTED);
        assertAttempt("rate",Duration.ofSeconds(1),ProviderProbePort.ProbeOutcome.RATE_LIMITED);
        assertAttempt("redirect",Duration.ofSeconds(1),ProviderProbePort.ProbeOutcome.UNREACHABLE);
        assertAttempt("invalid",Duration.ofSeconds(1),ProviderProbePort.ProbeOutcome.UNREACHABLE);
        assertAttempt("wrong-media",Duration.ofSeconds(1),ProviderProbePort.ProbeOutcome.UNREACHABLE);
        assertAttempt("oversize",Duration.ofSeconds(1),ProviderProbePort.ProbeOutcome.UNREACHABLE);
        assertAttempt("timeout",Duration.ofMillis(100),ProviderProbePort.ProbeOutcome.TIMED_OUT);
    }
    private void assertAttempt(String value,Duration timeout,ProviderProbePort.ProbeOutcome expected){
        mode.set(value);int before=attempts.get();ProviderDefinition definition=new ProviderCatalog(List.of()).require("anthropic");
        try(var transport=new JdkProviderProbeTransport((request,cancel)->NetworkAccessDecision.allow(),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),ignored->endpoint)){
            char[] secret="loopback-probe-sentinel".toCharArray();
            assertThat(transport.probe(definition,definition.defaultModelId(),secret,timeout,CancellationToken.none())).isEqualTo(expected);
            java.util.Arrays.fill(secret,'\0');
        }
        assertThat(attempts.get()-before).isEqualTo(1);
    }
}