package io.github.liumaishenjian.ccjava.tools.local.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandShellTest {

    @TempDir
    Path temporary;

    @Test
    void prefersMachineInstallThenFallsBackToUserPowerShellSeven() throws Exception {
        Path machineRoot = Files.createDirectories(temporary.resolve("machine"));
        Path userRoot = Files.createDirectories(temporary.resolve("user"));
        Path userPwsh = createExecutable(
                userRoot.resolve("Programs/PowerShell/7/pwsh.exe"));

        assertThat(CommandShell.findPowerShell7(
                machineRoot.toString(), userRoot.toString()))
                .isEqualTo(userPwsh);

        Path machinePwsh = createExecutable(
                machineRoot.resolve("PowerShell/7/pwsh.exe"));
        assertThat(CommandShell.findPowerShell7(
                machineRoot.toString(), userRoot.toString()))
                .isEqualTo(machinePwsh);
    }

    @Test
    void returnsNullWhenStandardInstallRootsAreMissing() {
        assertThat(CommandShell.findPowerShell7(null, " ")).isNull();
        assertThat(CommandShell.findPowerShell7(
                temporary.resolve("missing-machine").toString(),
                temporary.resolve("missing-user").toString()))
                .isNull();
    }

    private static Path createExecutable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "test");
    }
}
