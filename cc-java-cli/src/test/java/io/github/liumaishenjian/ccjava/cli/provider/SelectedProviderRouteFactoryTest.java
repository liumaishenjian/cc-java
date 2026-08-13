package io.github.liumaishenjian.ccjava.cli.provider;

import io.github.liumaishenjian.ccjava.cli.auth.*;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.core.ModelGateway;
import io.github.liumaishenjian.ccjava.domain.ModelRequest;
import io.github.liumaishenjian.ccjava.domain.ModelTurn;
import io.github.liumaishenjian.ccjava.domain.model.ProviderSelectionSnapshot;
import io.github.liumaishenjian.ccjava.model.springai.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** selected route 只创建单 route，并在 Run 边界冻结 selection/lease 的集成契约。 */
class SelectedProviderRouteFactoryTest {
    @TempDir Path temporary;
    @Test void runFreezesSelectionAndClosesCredentialAndGatewayExactlyOnce() throws Exception {
        Path home=Files.createDirectory(temporary.resolve("home"));
        ProviderDefinitionStore definitions=new ProviderDefinitionStore(home);
        FakeStore store=new FakeStore(); CredentialLeaseRegistry leases=new CredentialLeaseRegistry();
        AtomicInteger creates=new AtomicInteger(),calls=new AtomicInteger(),closes=new AtomicInteger();
        ProviderGatewayFactory factory=new ProviderGatewayFactory(){
            @Override public ProviderGatewayKind kind(){return ProviderGatewayKind.ANTHROPIC;}
            @Override public ModelGateway create(ProviderGatewayConfiguration ignored){creates.incrementAndGet();return new FakeGateway(calls,closes);}
        };
        ProviderGatewayFactoryRegistry registry=new ProviderGatewayFactoryRegistry(List.of(factory,
                passthrough(ProviderGatewayKind.OPENAI_COMPATIBLE,calls,closes),
                passthrough(ProviderGatewayKind.OPENROUTER,calls,closes)));
        var routes=new SelectedProviderRouteFactory(definitions,new CredentialResolver(store,Map.of("CC_TEST","route-test-secret")),leases,registry);
        String model=definitions.snapshot(CancellationToken.none()).catalog().require("anthropic").defaultModelId();
        var gateway=routes.lazyGateway(()->Optional.of(new ProviderSelectionSnapshot("anthropic","personal",model)));
        try(var run=gateway.openRun()) {
            gateway.complete(new ModelRequest(new io.github.liumaishenjian.ccjava.domain.SessionId("session"),
                    new io.github.liumaishenjian.ccjava.domain.RunId("run"),1,List.of(),List.of()));
            assertThat(leases.activeCount("anthropic","personal")).isOne();
            assertThat(creates).hasValue(1); assertThat(calls).hasValue(1);
        }
        assertThat(leases.activeCount("anthropic","personal")).isZero();
        assertThat(closes).hasValue(1);
    }
    private static ProviderGatewayFactory passthrough(ProviderGatewayKind kind,AtomicInteger calls,AtomicInteger closes){
        return new ProviderGatewayFactory(){public ProviderGatewayKind kind(){return kind;}public ModelGateway create(ProviderGatewayConfiguration c){return new FakeGateway(calls,closes);}};
    }
    private record FakeGateway(AtomicInteger calls,AtomicInteger closes) implements ModelGateway,AutoCloseable {
        public ModelTurn complete(ModelRequest request){calls.incrementAndGet();return ModelTurn.text("ok");}
        public void close(){closes.incrementAndGet();}
    }
    private static final class FakeStore implements CredentialStore {
        final AtomicInteger closed=new AtomicInteger();
        final CredentialProfile profile=new CredentialProfile("personal","anthropic",new SecretRef.Env("CC_TEST"),Instant.EPOCH,Instant.EPOCH,Optional.empty());
        public Snapshot snapshot(CancellationToken c){return new Snapshot(9,List.of(profile),Map.of("anthropic","personal"));}
        @Override public CredentialProfile saveProbe(String a,String b,CredentialProfile.ProbeRecord p,SecretRef r,CancellationToken c){throw new AssertionError();}
        public SecretMaterial readSecret(SecretRef.Store r,CancellationToken c){throw new AssertionError();}
        public boolean secretExists(SecretRef.Store r,CancellationToken c){return false;}
        public CredentialProfile saveStore(String a,String b,SecretMaterial d,boolean e,CancellationToken c){throw new AssertionError();}
        public CredentialProfile saveEnv(String a,String b,String d,boolean e,CancellationToken c){throw new AssertionError();}
        public void delete(String a,String b,long g,CancellationToken c){throw new AssertionError();}
    }
}