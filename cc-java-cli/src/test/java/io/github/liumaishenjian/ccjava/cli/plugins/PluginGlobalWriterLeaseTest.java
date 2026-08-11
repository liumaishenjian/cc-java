package io.github.liumaishenjian.ccjava.cli.plugins;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 recovery/install/uninstall 共用 writer 的进程内并发拒绝。 */
class PluginGlobalWriterLeaseTest {
    @TempDir Path temp;

    @Test void secondWriterAndRecoveryFailClosedWhileLeaseIsHeld() throws Exception {
        try (PluginGlobalWriterLease ignored = PluginGlobalWriterLease.acquire(temp)) {
            assertThatThrownBy(() -> PluginGlobalWriterLease.acquire(temp))
                    .isInstanceOf(java.io.IOException.class);
            org.assertj.core.api.Assertions.assertThat(new PluginTransactionRecovery(temp).recover().clean())
                    .isFalse();
        }
    }
}
