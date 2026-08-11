[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$release = Join-Path $root 'target/release'
& (Join-Path $PSScriptRoot 'TestBuildRelease.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Windows release smoke failed' }
& (Join-Path $release 'codej.cmd') --help | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Windows launcher smoke failed' }

$wslAvailable = $false
try { $wslAvailable = ((& wsl.exe -d Ubuntu -u root -- /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -lc 'printf ready' 2>$null) -join '') -eq 'ready' } catch { }
if (-not $wslAvailable) { throw 'WSL Ubuntu unavailable' }
$linuxRelease = (& wsl.exe -d Ubuntu -u root -- /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin wslpath -a $release).Trim()
& wsl.exe -d Ubuntu -u root -- /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -lc "chmod +x '$linuxRelease/codej' && /bin/sh -n '$linuxRelease/codej' && grep -q 'CcJavaCliMain' '$linuxRelease/codej'"
if ($LASTEXITCODE -ne 0) { throw 'WSL launcher contract smoke failed' }
& wsl.exe -d Ubuntu -u root -- /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -lc "test -f '$linuxRelease/release-manifest.json' && test -f '$linuxRelease/SHA256SUMS' && cd '$linuxRelease' && tr -d '\r' < SHA256SUMS | sha256sum -c - >/dev/null"
if ($LASTEXITCODE -ne 0) { throw 'WSL package checksum smoke failed' }

# install/upgrade/current/LKG rollback：只在 target 下操作候选副本。
$install = Join-Path $root 'target/s14-install-smoke'
Remove-Item -LiteralPath $install -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $install | Out-Null
Copy-Item -LiteralPath $release -Destination (Join-Path $install 'candidate-v1') -Recurse
Move-Item -LiteralPath (Join-Path $install 'candidate-v1') -Destination (Join-Path $install 'current')
Copy-Item -LiteralPath (Join-Path $install 'current') -Destination (Join-Path $install 'lkg') -Recurse
Copy-Item -LiteralPath $release -Destination (Join-Path $install 'upgrade-staging') -Recurse
Move-Item -LiteralPath (Join-Path $install 'current') -Destination (Join-Path $install 'rollback-old')
Move-Item -LiteralPath (Join-Path $install 'upgrade-staging') -Destination (Join-Path $install 'current')
Remove-Item -LiteralPath (Join-Path $install 'current/release-manifest.json') -Force
Remove-Item -LiteralPath (Join-Path $install 'current') -Recurse -Force
Move-Item -LiteralPath (Join-Path $install 'rollback-old') -Destination (Join-Path $install 'current')
if (-not (Test-Path -LiteralPath (Join-Path $install 'current/release-manifest.json'))) { throw 'LKG rollback failed' }

Write-Output 'S14 cross-platform smoke passed: Windows+WSL launcher/package and install/upgrade/LKG rollback.'
