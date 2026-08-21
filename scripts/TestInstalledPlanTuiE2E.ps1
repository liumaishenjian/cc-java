[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$targetParent = [IO.Path]::GetFullPath((Join-Path $root 'target/installed-plan-e2e'))
$runName = "run-$PID-$([guid]::NewGuid().ToString('N'))"
$installedRoot = [IO.Path]::GetFullPath((Join-Path $targetParent $runName))
$release = Join-Path $root 'target/release'
$mavenWrapper = Join-Path $root 'mvnw.cmd'
$tuiRoot = Join-Path $root 'cc-java-tui'
$testClasses = Join-Path $root 'cc-java-cli/target/test-classes'
$rootPrefix = $targetParent.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $installedRoot.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) `
        -or [IO.Path]::GetFileName($installedRoot) -ne $runName) {
    throw 'Installed Plan E2E target escaped its dedicated target parent'
}

# TestBuildRelease 是本脚本的有序前置：它构建真实发行物并覆盖 manifest/JAR/TUI identity
# drift 负例。本脚本复用该已验证 release，不重复或弱化身份漂移契约。
& (Join-Path $PSScriptRoot 'TestBuildRelease.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Release self-test prerequisite failed' }
if (-not (Test-Path -LiteralPath $release -PathType Container)) { throw 'Validated release missing' }

$manifest = Get-Content -LiteralPath (Join-Path $release 'release-manifest.json') -Raw | ConvertFrom-Json
$currentCommit = (& git -C $root rev-parse HEAD).Trim()
$reportedVersion = & (Join-Path $release 'codej.cmd') --version
if ($LASTEXITCODE -ne 0 -or $manifest.build.currentCommit -ne $currentCommit `
        -or $reportedVersion -notlike "codej $($manifest.version) commit=$currentCommit source=*") {
    throw 'Validated release version does not match manifest and current commit'
}

$priorRoot = [Environment]::GetEnvironmentVariable('CODEJ_INSTALLED_E2E_ROOT', 'Process')
$priorClasses = [Environment]::GetEnvironmentVariable('CODEJ_INSTALLED_E2E_TEST_CLASSES', 'Process')
try {
    New-Item -ItemType Directory -Path $targetParent -Force | Out-Null
    if ([IO.Path]::GetFullPath((Resolve-Path -LiteralPath $targetParent).Path) -ne $targetParent `
            -or (Get-Item -LiteralPath $targetParent).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw 'Installed Plan E2E parent is ambiguous'
    }
    Copy-Item -LiteralPath $release -Destination $installedRoot -Recurse
    if ((Get-Item -LiteralPath $installedRoot).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint) `
            -or [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $installedRoot).Path) -ne $installedRoot) {
        throw 'Installed Plan E2E copy is ambiguous'
    }

    & $mavenWrapper -q -pl cc-java-cli -am test-compile -DskipTests
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $testClasses -PathType Container)) {
        throw 'Fixture test-classes compilation failed'
    }
    $fixtureClass = Join-Path $testClasses 'io/github/liumaishenjian/ccjava/cli/stdio/StdioProtocolFixtureMain.class'
    if (-not (Test-Path -LiteralPath $fixtureClass -PathType Leaf)) { throw 'Plan fixture class missing' }

    # ink-testing-library 只属于测试驱动，不注入生产启动器或发行包；安装版 app/React/Ink 仍从 copy 加载。
    $testingLibrarySource = Join-Path $tuiRoot 'node_modules/ink-testing-library'
    $testingLibraryTarget = Join-Path $installedRoot 'tui/node_modules/ink-testing-library'
    if (-not (Test-Path -LiteralPath $testingLibrarySource -PathType Container)) {
        throw 'ink-testing-library dev dependency missing; run npm ci in cc-java-tui'
    }
    Copy-Item -LiteralPath $testingLibrarySource -Destination $testingLibraryTarget -Recurse

    [Environment]::SetEnvironmentVariable('CODEJ_INSTALLED_E2E_ROOT', $installedRoot, 'Process')
    [Environment]::SetEnvironmentVariable('CODEJ_INSTALLED_E2E_TEST_CLASSES', $testClasses, 'Process')
    & npm.cmd --prefix $tuiRoot run test:installed-plan
    if ($LASTEXITCODE -ne 0) { throw 'Installed Plan TUI E2E failed' }
} finally {
    [Environment]::SetEnvironmentVariable('CODEJ_INSTALLED_E2E_ROOT', $priorRoot, 'Process')
    [Environment]::SetEnvironmentVariable('CODEJ_INSTALLED_E2E_TEST_CLASSES', $priorClasses, 'Process')

    # 递归清理前重新证明父目录与唯一 target 均未被替换成 reparse point 或逃逸路径。
    $normalizedParent = [IO.Path]::GetFullPath($targetParent)
    $normalizedTarget = [IO.Path]::GetFullPath($installedRoot)
    $cleanupPrefix = $normalizedParent.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($normalizedParent -ne $targetParent -or $normalizedTarget -ne $installedRoot `
            -or -not $normalizedTarget.StartsWith($cleanupPrefix, [StringComparison]::OrdinalIgnoreCase) `
            -or [IO.Path]::GetFileName($normalizedTarget) -ne $runName) {
        throw 'Refusing unsafe installed Plan E2E cleanup target'
    }
    if (Test-Path -LiteralPath $normalizedTarget) {
        if ((Get-Item -LiteralPath $normalizedParent).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint) `
                -or (Get-Item -LiteralPath $normalizedTarget).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint) `
                -or [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $normalizedParent).Path) -ne $normalizedParent `
                -or [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $normalizedTarget).Path) -ne $normalizedTarget) {
            throw 'Refusing ambiguous installed Plan E2E cleanup target'
        }
        $linkedEntry = Get-ChildItem -LiteralPath $normalizedTarget -Recurse -Force | Where-Object {
            $_.Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)
        } | Select-Object -First 1
        if ($null -ne $linkedEntry) { throw 'Refusing installed Plan E2E cleanup containing reparse point' }
        Remove-Item -LiteralPath $normalizedTarget -Recurse -Force -Confirm:$false
    }
}

Write-Output 'Installed Plan TUI-to-Java E2E passed.'
