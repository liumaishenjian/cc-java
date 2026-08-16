[CmdletBinding()]
param(
    [string]$ArchivePath = 'target/distributions/codej-windows-x64.zip'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$archive = if ([IO.Path]::IsPathRooted($ArchivePath)) {
    [IO.Path]::GetFullPath($ArchivePath)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $ArchivePath))
}
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw 'Distribution archive missing' }

$testRoot = Join-Path $root 'target/release/install-lifecycle-test'
$installRoot = Join-Path $testRoot 'home'
$binRoot = Join-Path $testRoot 'bin'
Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    & (Join-Path $PSScriptRoot 'install.ps1') -ArchivePath $archive -InstallRoot $installRoot `
        -BinDirectory $binRoot -SkipPathUpdate
    $shim = Join-Path $binRoot 'codej.cmd'
    if (-not (Test-Path -LiteralPath $shim -PathType Leaf)) { throw 'Installed shim missing' }
    $version = & $shim --version
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^codej [0-9]+\.[0-9]+\.[0-9]+') {
        throw 'Installed version command failed'
    }
    & $shim doctor | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Installed doctor command failed' }

    $badChecksum = "$archive.sha256"
    $original = Get-Content -LiteralPath $badChecksum -Raw
    try {
        ('0' * 64) + '  codej-windows-x64.zip' | Set-Content -LiteralPath $badChecksum -Encoding ascii
        $failedClosed = $false
        try {
            & (Join-Path $PSScriptRoot 'install.ps1') -ArchivePath $archive `
                -InstallRoot (Join-Path $testRoot 'tampered') -BinDirectory $binRoot -SkipPathUpdate
        } catch {
            $failedClosed = $_.Exception.Message -like '*checksum mismatch*'
        }
        if (-not $failedClosed) { throw 'Tampered archive checksum did not fail closed' }
    } finally {
        Set-Content -LiteralPath $badChecksum -Value $original -Encoding ascii -NoNewline
    }

    & (Join-Path $PSScriptRoot 'install.ps1') -InstallRoot $installRoot -BinDirectory $binRoot `
        -SkipPathUpdate -Uninstall
    if (Test-Path -LiteralPath $installRoot) { throw 'Uninstall left installation root' }
    if (Test-Path -LiteralPath $shim) { throw 'Uninstall left owned shim' }
    Write-Output "distribution install lifecycle passed: $version"
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}
