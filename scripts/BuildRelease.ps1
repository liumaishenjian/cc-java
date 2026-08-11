[CmdletBinding()]
param(
    [string]$OutputDirectory = "target/release",
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$releaseRoot = [IO.Path]::GetFullPath((Join-Path $root 'target/release'))
$out = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}

# 所有递归删除、移动和 rollback 只能发生在仓库专用 target/release 下。
$releasePrefix = $releaseRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if ($out -ne $releaseRoot -and -not $out.StartsWith($releasePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must stay under target/release'
}
if ($out -eq $releaseRoot) {
    # 默认参数历史上是 target/release；归一化后保持该目录本身。
} elseif ([IO.Path]::GetFileName($out) -in @('', '.', '..')) {
    throw 'OutputDirectory is invalid'
}

if (-not $SkipBuild) {
    & (Join-Path $root 'mvnw.cmd') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
}

$cli = Join-Path $root 'cc-java-cli/target/cc-java-cli-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $cli -PathType Leaf)) { throw 'CLI JAR missing' }
$runtimeDependencies = Join-Path $root 'cc-java-cli/target/release-dependency'
Remove-Item -LiteralPath $runtimeDependencies -Recurse -Force -ErrorAction SilentlyContinue
& (Join-Path $root 'mvnw.cmd') -q -pl cc-java-cli -am install -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Maven install failed' }
& (Join-Path $root 'mvnw.cmd') -q -pl cc-java-cli dependency:copy-dependencies `
    "-DincludeScope=runtime" "-DoutputDirectory=$runtimeDependencies"
if ($LASTEXITCODE -ne 0) { throw 'Runtime dependency collection failed' }

# 部分第三方 JAR 不携带 META-INF/maven/**/pom.properties；Maven resolver 输出作为
# 等价的确定性坐标来源。后续仍要求每个 JAR 恰好解析出一个坐标，绝不猜文件名。
$coordinateFile = Join-Path $root "target/release-runtime-coordinates-$PID.txt"
Remove-Item -LiteralPath $coordinateFile -Force -ErrorAction SilentlyContinue
& (Join-Path $root 'mvnw.cmd') -q -pl cc-java-cli dependency:list `
    "-DincludeScope=runtime" "-DoutputAbsoluteArtifactFilename=true" `
    "-DoutputFile=$coordinateFile"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $coordinateFile -PathType Leaf)) {
    throw 'Runtime dependency coordinate collection failed'
}
$resolvedCoordinates = [Collections.Generic.List[object]]::new()
foreach ($line in Get-Content -LiteralPath $coordinateFile) {
    if ($line -notmatch '^\s*(.+):(compile|runtime):([A-Za-z]:\\.+?)(?: -- module .*)?$') {
        continue
    }
    $prefix = @($Matches[1] -split ':')
    if ($prefix.Count -notin @(4, 5)) { throw 'Unexpected Maven dependency coordinate shape' }
    $coordinate = [ordered]@{ group = $prefix[0]; name = $prefix[1]; version = $prefix[-1] }
    $sourcePath = ($Matches[3] -replace "`e\[[0-9;]*m", '').Trim().Trim("'").Trim('"')
    $jarName = [IO.Path]::GetFileName($sourcePath).Trim()
    if ([string]::IsNullOrWhiteSpace($jarName)) { throw 'Resolved dependency path is invalid' }
    $existing = @($resolvedCoordinates | Where-Object { $_.jarName -ceq $jarName })
    if ($existing.Count -gt 0) {
        if ($existing.Count -ne 1 -or $existing[0].group -ne $coordinate.group `
                -or $existing[0].name -ne $coordinate.name `
                -or $existing[0].version -ne $coordinate.version) {
            throw "Ambiguous Maven coordinate for runtime JAR: $jarName"
        }
    } else {
        $resolvedCoordinates.Add([pscustomobject]@{
            jarName = $jarName
            sourcePath = $sourcePath
            sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash
            group = $coordinate.group
            name = $coordinate.name
            version = $coordinate.version
        })
    }
}
Remove-Item -LiteralPath $coordinateFile -Force
if ($resolvedCoordinates.Count -eq 0) { throw 'No runtime dependency coordinates resolved' }

$staging = "$out.staging-$PID"
$backup = "$out.rollback-$PID"
foreach ($candidate in @($staging, $backup)) {
    $full = [IO.Path]::GetFullPath($candidate)
    $generatedPrefix = $releaseRoot + '.'
    $insideRelease = $full.StartsWith($releasePrefix, [StringComparison]::OrdinalIgnoreCase)
    $generatedSibling = $full.StartsWith($generatedPrefix, [StringComparison]::OrdinalIgnoreCase)
    if (-not $insideRelease -and -not $generatedSibling) {
        throw 'Internal release path escaped target/release'
    }
    Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Path (Join-Path $staging 'app') -Force | Out-Null
Copy-Item -LiteralPath $cli -Destination (Join-Path $staging 'app/cc-java-cli.jar')
Copy-Item -Path (Join-Path $runtimeDependencies '*.jar') -Destination (Join-Path $staging 'app')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'codej-release.cmd') -Destination (Join-Path $staging 'codej.cmd')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'codej-release.sh') -Destination (Join-Path $staging 'codej')

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Read-UniqueProperty([string[]]$Lines, [string]$Name, [string]$JarName) {
    $values = @($Lines | ForEach-Object {
        if ($_ -match "^$([regex]::Escape($Name))\s*[=:]\s*(.+?)\s*$") { $Matches[1] }
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    if ($values.Count -ne 1) { throw "Missing or ambiguous $Name in Maven metadata: $JarName" }
    return $values[0]
}
function Read-JarCoordinate([IO.FileInfo]$Jar, [Collections.Generic.List[object]]$ResolverCoordinates) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Jar.FullName)
    try {
        $entries = @($archive.Entries | Where-Object {
            $_.FullName -match '^META-INF/maven/[^/]+/[^/]+/pom\.properties$'
        })
        if ($entries.Count -gt 1) { throw "Ambiguous Maven metadata entries: $($Jar.Name)" }
        if ($entries.Count -eq 1) {
            $reader = [IO.StreamReader]::new($entries[0].Open(), [Text.Encoding]::UTF8, $true)
            try { $lines = @($reader.ReadToEnd() -split "`r?`n") } finally { $reader.Dispose() }
            return [ordered]@{
                group = Read-UniqueProperty $lines 'groupId' $Jar.Name
                name = Read-UniqueProperty $lines 'artifactId' $Jar.Name
                version = Read-UniqueProperty $lines 'version' $Jar.Name
            }
        }
    } finally {
        $archive.Dispose()
    }
    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jar.FullName).Hash
    $matches = @($ResolverCoordinates | Where-Object { $_.sourceHash -eq $jarHash })
    if ($matches.Count -ne 1) {
        throw "Maven coordinate metadata missing or ambiguous for JAR: $($Jar.Name)"
    }
    return [ordered]@{
        group = $matches[0].group
        name = $matches[0].name
        version = $matches[0].version
    }
}

$componentList = [Collections.Generic.List[object]]::new()
$cliCoordinate = $null
foreach ($jar in (Get-ChildItem -LiteralPath (Join-Path $staging 'app') -Filter '*.jar' -File |
        Sort-Object Name)) {
    $coordinate = Read-JarCoordinate $jar $resolvedCoordinates
    if ($jar.Name -eq 'cc-java-cli.jar') { $cliCoordinate = $coordinate }
    $encodedGroup = [Uri]::EscapeDataString($coordinate.group)
    $encodedName = [Uri]::EscapeDataString($coordinate.name)
    $encodedVersion = [Uri]::EscapeDataString($coordinate.version)
    $componentList.Add([ordered]@{
        type = 'library'
        group = $coordinate.group
        name = $coordinate.name
        version = $coordinate.version
        purl = "pkg:maven/$encodedGroup/$encodedName@$encodedVersion"
        hashes = @([ordered]@{
            alg = 'SHA-256'
            content = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash.ToLowerInvariant()
        })
    })
}
if ($componentList.Count -eq 0 -or $null -eq $cliCoordinate) {
    throw 'Release SBOM requires non-empty components and the CLI Maven coordinate'
}
$sbom = [ordered]@{
    bomFormat = 'CycloneDX'
    specVersion = '1.6'
    serialNumber = "urn:uuid:$([guid]::NewGuid())"
    version = 1
    metadata = [ordered]@{
        component = [ordered]@{
            type = 'application'
            group = $cliCoordinate.group
            name = $cliCoordinate.name
            version = $cliCoordinate.version
            purl = "pkg:maven/$([Uri]::EscapeDataString($cliCoordinate.group))/$([Uri]::EscapeDataString($cliCoordinate.name))@$([Uri]::EscapeDataString($cliCoordinate.version))"
        }
    }
    components = @($componentList)
}
$sbom | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $staging 'sbom.cdx.json') -Encoding utf8NoBOM

$artifactFiles = Get-ChildItem -LiteralPath $staging -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS' |
    Sort-Object FullName
$manifest = [ordered]@{
    schema = 'cc-java-release-manifest-v1'
    version = '0.1.0-SNAPSHOT'
    compatibility = [ordered]@{ protocolMajors=@(0,1); sessionExportMajor=1; minimumJava=21 }
    # 当前尚未写 manifest 与 SHA256SUMS，因此最终总数需加二。
    artifacts = $artifactFiles.Count + 2
    publicReleaseAllowed = $false
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $staging 'release-manifest.json') -Encoding utf8NoBOM

# Manifest 与 SBOM 均写入后再计算 checksum；SHA256SUMS 自身不自引用。
$checksumFiles = Get-ChildItem -LiteralPath $staging -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS' |
    Sort-Object FullName
$checksums = foreach ($file in $checksumFiles) {
    $relative = [IO.Path]::GetRelativePath($staging, $file.FullName).Replace('\','/')
    "$(Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName | Select-Object -ExpandProperty Hash)  $relative"
}
$checksums | Set-Content -LiteralPath (Join-Path $staging 'SHA256SUMS') -Encoding utf8NoBOM

# 发布 staging 前立即复验每个 checksum，避免复制期间损坏进入 current candidate。
foreach ($line in Get-Content -LiteralPath (Join-Path $staging 'SHA256SUMS')) {
    if ($line -notmatch '^([0-9A-Fa-f]{64})  (.+)$') { throw 'Invalid checksum manifest' }
    $expected = $Matches[1]
    $relative = $Matches[2]
    $candidate = [IO.Path]::GetFullPath((Join-Path $staging $relative))
    $stagingPrefix = [IO.Path]::GetFullPath($staging).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($stagingPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Checksum entry escaped staging'
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidate).Hash
    if ($actual -ne $expected) { throw "Checksum mismatch: $relative" }
}

if (Test-Path -LiteralPath $out) {
    Move-Item -LiteralPath $out -Destination $backup
    try {
        Move-Item -LiteralPath $staging -Destination $out
        Remove-Item -LiteralPath $backup -Recurse -Force
    } catch {
        if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
        Move-Item -LiteralPath $backup -Destination $out
        throw
    }
} else {
    Move-Item -LiteralPath $staging -Destination $out
}
Write-Output "release candidate: $out"
