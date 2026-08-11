[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$builder = Join-Path $PSScriptRoot 'BuildRelease.ps1'

& $builder -SkipBuild
if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }

$release = Join-Path $root 'target/release'
$sbomPath = Join-Path $release 'sbom.cdx.json'
$manifestPath = Join-Path $release 'release-manifest.json'
$checksumsPath = Join-Path $release 'SHA256SUMS'
$sbom = Get-Content -LiteralPath $sbomPath -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$components = @($sbom.components)
if ($components.Count -eq 0) { throw 'CycloneDX components must not be empty' }

function Assert-Coordinate([string]$Group, [string]$Name, [string]$Version) {
    $matches = @($components | Where-Object {
        $_.group -eq $Group -and $_.name -eq $Name -and $_.version -eq $Version
    })
    if ($matches.Count -ne 1) { throw "Expected exactly one coordinate: $Group`:$Name`:$Version" }
    $expectedPurl = "pkg:maven/$([Uri]::EscapeDataString($Group))/$([Uri]::EscapeDataString($Name))@$([Uri]::EscapeDataString($Version))"
    if ($matches[0].purl -ne $expectedPurl) { throw "PURL mismatch: $Group`:$Name`:$Version" }
}

Assert-Coordinate 'info.picocli' 'picocli' '4.7.7'
Assert-Coordinate 'org.springframework.ai' 'spring-ai-anthropic' '2.0.0'
Assert-Coordinate 'com.anthropic' 'anthropic-java-core' '2.40.1'
Assert-Coordinate 'io.github.liumaishenjian' 'cc-java-core' '0.1.0-SNAPSHOT'
if ($sbom.metadata.component.group -ne 'io.github.liumaishenjian' `
        -or $sbom.metadata.component.name -ne 'cc-java-cli' `
        -or $sbom.metadata.component.version -ne '0.1.0-SNAPSHOT') {
    throw 'Application Maven coordinate is incorrect'
}

$artifactFiles = @(Get-ChildItem -LiteralPath $release -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS')
$checksumLines = @(Get-Content -LiteralPath $checksumsPath)
if ($checksumLines.Count -ne $artifactFiles.Count) { throw 'Checksum coverage count mismatch' }
foreach ($line in $checksumLines) {
    if ($line -notmatch '^([0-9A-Fa-f]{64})  (.+)$') { throw 'Invalid checksum line' }
    $path = [IO.Path]::GetFullPath((Join-Path $release $Matches[2]))
    $prefix = $release.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Checksum path escaped release root'
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash -ne $Matches[1]) {
        throw "Checksum mismatch: $($Matches[2])"
    }
}
if ($manifest.artifacts -ne ($artifactFiles.Count + 1)) {
    throw 'Manifest artifact count does not include SHA256SUMS exactly once'
}
if ($manifest.publicReleaseAllowed -ne $false) { throw 'Public release must remain disabled' }

$escaped = Join-Path $root 'target/release-escape-negative'
$failedClosed = $false
try {
    & $builder -SkipBuild -OutputDirectory $escaped
} catch {
    $failedClosed = $_.Exception.Message -like '*target/release*'
}
if (-not $failedClosed) { throw 'OutputDirectory escape negative did not fail closed' }

Write-Output "S14 release self-test passed: $($components.Count) Maven components, checksums=$($checksumLines.Count)."
