[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [switch]$AddToUserPath,
    [switch]$Uninstall,
    [switch]$RemoveUserPath,
    [switch]$SkipDependencies,
    [string]$UserHome = [Environment]::GetFolderPath('UserProfile'),
    [string]$UserPathOverride
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7) {
    throw 'InstallCodejDevCommand.ps1 requires PowerShell 7 or newer.'
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot 'CodejDevLauncher.psm1') -Force
$installationRoot = Join-Path ([IO.Path]::GetFullPath($UserHome)) '.local\bin'
$shimPath = Join-Path $installationRoot 'codej.cmd'
$metadataRoot = Join-Path ([IO.Path]::GetFullPath($UserHome)) '.cc-java'
$metadataPath = Join-Path $metadataRoot 'codej-dev-install.json'
$hasUserPathOverride = $PSBoundParameters.ContainsKey('UserPathOverride')

function Get-EffectiveUserPath {
    if ($script:hasUserPathOverride) { return $script:UserPathOverride }
    return [Environment]::GetEnvironmentVariable('Path', [EnvironmentVariableTarget]::User)
}

function Set-EffectiveUserPath {
    param([AllowEmptyString()][string]$Value)
    if ($script:hasUserPathOverride) {
        $script:UserPathOverride = $Value
        return
    }
    [Environment]::SetEnvironmentVariable('Path', $Value, [EnvironmentVariableTarget]::User)
}

function Read-InstallMetadata {
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) { return $null }
    try { return Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json }
    catch { throw "Installation metadata is invalid: $metadataPath" }
}

if ($Uninstall) {
    $metadata = Read-InstallMetadata
    if (-not $metadata) { throw 'codej development installation metadata was not found; refusing an unowned uninstall.' }
    if (-not (Test-CodejOwnedShim -Path $shimPath)) { throw "The target shim is missing or not owned by cc-java: $shimPath" }
    $expectedRepository = [IO.Path]::GetFullPath([string]$metadata.repositoryRoot)
    if ($expectedRepository -ne [IO.Path]::GetFullPath($repositoryRoot)) {
        throw 'Installation metadata belongs to another cc-java repository path.'
    }
    if ($PSCmdlet.ShouldProcess($shimPath, 'Remove cc-java development shim')) {
        Remove-Item -LiteralPath $shimPath -Force
    }
    if ($RemoveUserPath -and [bool]$metadata.pathAddedByInstaller) {
        $userPath = Get-EffectiveUserPath
        $updatedPath = Remove-CodejPathEntry -PathValue $userPath -Candidate $installationRoot
        if ($PSCmdlet.ShouldProcess('User PATH', "Remove $installationRoot")) {
            Set-EffectiveUserPath -Value $updatedPath
        }
    }
    if ($PSCmdlet.ShouldProcess($metadataPath, 'Remove codej installation metadata')) {
        Remove-Item -LiteralPath $metadataPath -Force
    }
    [Console]::Out.WriteLine('codej development command uninstalled. Source, configuration, and caches were preserved.')
    return
}

$java = Get-CodejJavaVersion
$node = Get-CodejNodeVersion
if (-not $java.Present -or -not $java.Supported) { throw "JDK 21+ is required; current: $($java.Description)" }
if (-not $node.Present -or -not $node.Supported) { throw "Node.js 22+ is required; current: $($node.Description)" }

$existingCommands = @(Get-Command codej -All -ErrorAction SilentlyContinue)
foreach ($command in $existingCommands) {
    if (-not [string]::IsNullOrWhiteSpace($command.Source)) {
        $source = [IO.Path]::GetFullPath($command.Source)
        if ((ConvertTo-CodejComparablePath -Path $source) -ne (ConvertTo-CodejComparablePath -Path $shimPath)) {
            if (-not (Test-CodejOwnedShim -Path $source)) {
                throw "Another codej command has PATH precedence or conflicts with this installation: $source"
            }
        }
    }
}
if ((Test-Path -LiteralPath $shimPath) -and -not (Test-CodejOwnedShim -Path $shimPath)) {
    throw "Refusing to overwrite a shim not owned by cc-java: $shimPath"
}

# 重装时保留“该 PATH 条目由安装器加入”的所有权事实。否则第二次安装会因为
# PATH 已包含目标目录而把标记覆盖为 false，导致安全卸载无法移除自己加入的条目。
$existingMetadata = Read-InstallMetadata
$pathPreviouslyAdded = $false
if ($existingMetadata -and (Test-CodejOwnedShim -Path $shimPath)) {
    try {
        $recordedInstallation = [IO.Path]::GetFullPath([string]$existingMetadata.installationRoot)
        $pathPreviouslyAdded = `
            (ConvertTo-CodejComparablePath -Path $recordedInstallation) -eq `
                (ConvertTo-CodejComparablePath -Path $installationRoot) -and `
            [bool]$existingMetadata.pathAddedByInstaller
    }
    catch {
        throw "Installation metadata is incomplete or invalid: $metadataPath"
    }
}

if (-not $SkipDependencies) {
    $tuiDirectory = Join-Path $repositoryRoot 'cc-java-tui'
    if ($PSCmdlet.ShouldProcess($tuiDirectory, 'Install locked TUI dependencies without lifecycle scripts')) {
        & npm.cmd --prefix $tuiDirectory ci --ignore-scripts
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
    }
}

$pathBefore = Get-EffectiveUserPath
$alreadyInPath = Test-CodejPathContains -PathValue $pathBefore -Candidate $installationRoot
$pathAdded = $pathPreviouslyAdded
if ($AddToUserPath -and -not $alreadyInPath) {
    $updatedPath = Add-CodejPathEntry -PathValue $pathBefore -Candidate $installationRoot
    if ($PSCmdlet.ShouldProcess('User PATH', "Add $installationRoot")) {
        Set-EffectiveUserPath -Value $updatedPath
        $pathAdded = $true
    }
}

if ($PSCmdlet.ShouldProcess($shimPath, 'Install cc-java development shim')) {
    $null = New-Item -ItemType Directory -Path $installationRoot -Force
    $temporary = "$shimPath.tmp.$PID"
    try {
        [IO.File]::WriteAllText($temporary, (New-CodejShimContent -RepositoryRoot $repositoryRoot), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $shimPath -Force
    }
    finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

$metadata = [ordered]@{
    schema = 1
    shimSchema = [int](Get-CodejShimSchema)
    repositoryRoot = [IO.Path]::GetFullPath($repositoryRoot)
    installationRoot = [IO.Path]::GetFullPath($installationRoot)
    pathAddedByInstaller = $pathAdded
}
if ($PSCmdlet.ShouldProcess($metadataPath, 'Write codej installation metadata')) {
    $null = New-Item -ItemType Directory -Path $metadataRoot -Force
    [IO.File]::WriteAllText($metadataPath, (($metadata | ConvertTo-Json) + "`n"), [Text.UTF8Encoding]::new($false))
}

[Console]::Out.WriteLine("codej development shim installed at: $shimPath")
if (-not $alreadyInPath -and -not $AddToUserPath) {
    [Console]::Out.WriteLine("$installationRoot is not in the user PATH. Re-run with -AddToUserPath or add it manually, then restart the terminal.")
}
elseif ($AddToUserPath -and -not $alreadyInPath) {
    [Console]::Out.WriteLine('User PATH was updated. Restart the terminal before running codej.')
}
else {
    [Console]::Out.WriteLine('Run: codej --doctor')
}
