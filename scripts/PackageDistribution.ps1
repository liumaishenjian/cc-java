[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [ValidateSet('windows-x64', 'linux-x64')]
    [string]$Platform = $(if ($IsWindows) { 'windows-x64' } else { 'linux-x64' }),
    [string]$JavaRuntimeDirectory,
    [string]$NodeRuntimeDirectory,
    [switch]$PublicRelease,
    [switch]$SkipBuild,
    [switch]$SkipTuiBuild
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$distributionRoot = Join-Path $root 'target/distributions'
$appDirectory = Join-Path $root "target/release/$Platform"
New-Item -ItemType Directory -Path $distributionRoot -Force | Out-Null

$arguments = @{
    OutputDirectory = "target/release/$Platform"
    Version = $Version
    Platform = $Platform
    SkipBuild = $SkipBuild
    SkipTuiBuild = $SkipTuiBuild
    PublicRelease = $PublicRelease
}
if (-not [string]::IsNullOrWhiteSpace($JavaRuntimeDirectory)) {
    $arguments.JavaRuntimeDirectory = $JavaRuntimeDirectory
}
if (-not [string]::IsNullOrWhiteSpace($NodeRuntimeDirectory)) {
    $arguments.NodeRuntimeDirectory = $NodeRuntimeDirectory
}
& (Join-Path $PSScriptRoot 'BuildRelease.ps1') @arguments
if ($LASTEXITCODE -ne 0) { throw 'Release app-dir build failed' }

$archiveName = if ($Platform -eq 'windows-x64') { 'codej-windows-x64.zip' } else { 'codej-linux-x64.tar.gz' }
$archive = Join-Path $distributionRoot $archiveName
Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
if ($Platform -eq 'windows-x64') {
    Compress-Archive -Path (Join-Path $appDirectory '*') -DestinationPath $archive -CompressionLevel Optimal
} else {
    & tar -C $appDirectory -czf $archive .
    if ($LASTEXITCODE -ne 0) { throw 'tar archive creation failed' }
}
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $archiveName" | Set-Content -LiteralPath "$archive.sha256" -Encoding ascii
Write-Output "distribution archive: $archive"
