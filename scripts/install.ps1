[CmdletBinding()]
param(
    [string]$Version = $env:CODEJ_VERSION,
    [string]$InstallRoot = $env:CODEJ_INSTALL_ROOT,
    [string]$BinDirectory = $env:CODEJ_BIN_DIR,
    [string]$DownloadBase = $env:CODEJ_DOWNLOAD_BASE,
    [string]$ArchivePath,
    [switch]$SkipPathUpdate,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($InstallRoot)) {
    $InstallRoot = Join-Path $env:LOCALAPPDATA 'codej'
}
$InstallRoot = [IO.Path]::GetFullPath($InstallRoot)
if ([string]::IsNullOrWhiteSpace($BinDirectory)) {
    $BinDirectory = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.local\bin'
}
$binDirectory = [IO.Path]::GetFullPath($BinDirectory)
$shim = Join-Path $binDirectory 'codej.cmd'

if ($Uninstall) {
    if (Test-Path -LiteralPath $shim -PathType Leaf) {
        $owned = (Get-Content -LiteralPath $shim -Raw -ErrorAction SilentlyContinue) -like '*CODEJ_PUBLIC_INSTALL_SHIM*'
        if ($owned) { Remove-Item -LiteralPath $shim -Force }
    }
    if (Test-Path -LiteralPath $InstallRoot -PathType Container) {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }
    Write-Output 'codej uninstalled; the PATH entry was left in place because it may contain other commands.'
    return
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) "codej-install-$PID-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $archive = Join-Path $temporary 'codej-windows-x64.zip'
    $checksum = "$archive.sha256"
    if (-not [string]::IsNullOrWhiteSpace($ArchivePath)) {
        Copy-Item -LiteralPath ([IO.Path]::GetFullPath($ArchivePath)) -Destination $archive
        Copy-Item -LiteralPath "$([IO.Path]::GetFullPath($ArchivePath)).sha256" -Destination $checksum
    } else {
        if ([string]::IsNullOrWhiteSpace($DownloadBase)) {
            $DownloadBase = if ([string]::IsNullOrWhiteSpace($Version)) {
                'https://github.com/liumaishenjian/cc-java/releases/latest/download'
            } else {
                "https://github.com/liumaishenjian/cc-java/releases/download/v$Version"
            }
        }
        Invoke-WebRequest -UseBasicParsing "$DownloadBase/codej-windows-x64.zip" -OutFile $archive
        Invoke-WebRequest -UseBasicParsing "$DownloadBase/codej-windows-x64.zip.sha256" -OutFile $checksum
    }
    $line = (Get-Content -LiteralPath $checksum -Raw).Trim()
    if ($line -notmatch '^([0-9a-fA-F]{64})  codej-windows-x64\.zip$') { throw 'Invalid checksum sidecar' }
    $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    if ($actual -ne $Matches[1]) { throw 'Archive checksum mismatch' }

    $expanded = Join-Path $temporary 'expanded'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        foreach ($entry in $zip.Entries) {
            $entryTarget = [IO.Path]::GetFullPath((Join-Path $expanded $entry.FullName))
            $expandedPrefix = [IO.Path]::GetFullPath($expanded).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
            if (-not $entryTarget.StartsWith($expandedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Archive entry escaped extraction root'
            }
        }
    } finally {
        $zip.Dispose()
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $expanded
    $manifestPath = Join-Path $expanded 'release-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Release manifest missing' }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.schema -ne 'cc-java-release-manifest-v1' -or $manifest.platform -ne 'windows-x64') {
        throw 'Release manifest is incompatible'
    }
    if ($manifest.version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$') {
        throw 'Release version is invalid'
    }
    if (-not [string]::IsNullOrWhiteSpace($Version) -and $manifest.version -ne $Version) {
        throw 'Downloaded release version does not match CODEJ_VERSION'
    }

    $versions = Join-Path $InstallRoot 'versions'
    $destination = Join-Path $versions $manifest.version
    $staging = "$destination.staging-$PID"
    $backup = "$destination.rollback-$PID"
    New-Item -ItemType Directory -Path $versions -Force | Out-Null
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item -LiteralPath $expanded -Destination $staging
    if (Test-Path -LiteralPath $destination) {
        Move-Item -LiteralPath $destination -Destination $backup
    }
    try {
        Move-Item -LiteralPath $staging -Destination $destination
        Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    } catch {
        if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Recurse -Force }
        if (Test-Path -LiteralPath $backup) { Move-Item -LiteralPath $backup -Destination $destination }
        throw
    }

    $currentTemporary = Join-Path $InstallRoot "current.txt.$PID"
    Set-Content -LiteralPath $currentTemporary -Value $manifest.version -Encoding ascii -NoNewline
    Move-Item -LiteralPath $currentTemporary -Destination (Join-Path $InstallRoot 'current.txt') -Force
    New-Item -ItemType Directory -Path $binDirectory -Force | Out-Null
    $batchInstallRoot = $InstallRoot.Replace('%', '%%')
    @"
@echo off
rem CODEJ_PUBLIC_INSTALL_SHIM
setlocal
set "CODEJ_INSTALL_ROOT=$batchInstallRoot"
set /p CODEJ_VERSION=<"%CODEJ_INSTALL_ROOT%\current.txt"
call "%CODEJ_INSTALL_ROOT%\versions\%CODEJ_VERSION%\codej.cmd" %*
exit /b %ERRORLEVEL%
"@ | Set-Content -LiteralPath $shim -Encoding ascii

    if (-not $SkipPathUpdate) {
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        $entries = @($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $alreadyPresent = $false
        foreach ($entry in $entries) {
            try {
                if ([IO.Path]::GetFullPath($entry) -eq [IO.Path]::GetFullPath($binDirectory)) {
                    $alreadyPresent = $true
                    break
                }
            } catch { }
        }
        if (-not $alreadyPresent) {
            [Environment]::SetEnvironmentVariable('Path', (($entries + $binDirectory) -join ';'), 'User')
        }
    }
    Write-Output "codej $($manifest.version) installed. Open a new terminal and run: codej"
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}
