package io.github.liumaishenjian.ccjava.cli.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.liumaishenjian.ccjava.core.governance.ManagedPolicyStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 production machine trust 与测试注入路径严格分离。 */
class ManagedGovernanceTest {
    @TempDir Path temp;

    @Test void userWritableFixtureIsNotTrustedMachineSource() throws Exception {
        Files.writeString(temp.resolve("policy.v1"), "schema=1\nnetwork-denied=true\n");
        assertThat(ManagedGovernance.trustedMachineSource(temp)).isFalse();
    }

    @Test void explicitTestInjectionParsesPolicyWithoutClaimingProductionTrust() throws Exception {
        Files.writeString(temp.resolve("policy.v1"), "schema=1\nnetwork-denied=true\n");
        var governance = ManagedGovernance.loadForTesting(temp);
        assertThat(governance.policy().status()).isEqualTo(ManagedPolicyStatus.CURRENT);
        assertThat(governance.policy().value()).hasValueSatisfying(value ->
                assertThat(value.provenance().trusted()).isTrue());
        assertThat(ManagedGovernance.trustedMachineSource(temp)).isFalse();
    }
}
