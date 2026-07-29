param(
    [Parameter(Mandatory = $false)]
    [string]$Prompt,

    [Parameter(Mandatory = $false)]
    [string]$Workspace,

    [Parameter(Mandatory = $false)]
    [string]$Model,

    [Parameter(Mandatory = $false)]
    [string]$Timeout = '5m',

    [Parameter(Mandatory = $false)]
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $repositoryRoot 'mvnw.cmd'
$rootPom = Join-Path $repositoryRoot 'pom.xml'
$cliDirectory = Join-Path $repositoryRoot 'cc-java-cli'
$tuiDirectory = Join-Path $repositoryRoot 'cc-java-tui'
$classpathFile = Join-Path $cliDirectory 'target\stdio-spike-classpath.txt'
$mainClassFile = Join-Path $cliDirectory 'target\classes\io\github\liumaishenjian\ccjava\cli\CcJavaCliMain.class'

# Compile the real Java Headless entrypoint and pass Java arguments without a shell string.
if ($SkipBuild) {
    if (-not (Test-Path -LiteralPath $classpathFile -PathType Leaf) -or
        -not (Test-Path -LiteralPath $mainClassFile -PathType Leaf)) {
        throw 'SkipBuild requires one successful run without -SkipBuild.'
    }
    [Console]::Error.WriteLine('[cc-java] Reusing existing Java build outputs.')
}
else {
    [Console]::Error.WriteLine(
        '[cc-java] Building Java Headless; the first run may take 1-2 minutes.'
    )
    $mavenArguments = @(
        '-q',
        '--file',
        $rootPom,
        '-pl',
        'cc-java-cli',
        '-am',
        'package',
        '-DskipTests',
        'dependency:build-classpath',
        '-Dmdep.includeScope=runtime',
        '-Dmdep.outputFile=target/stdio-spike-classpath.txt'
    )
    & $mavenWrapper @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    [Console]::Error.WriteLine('[cc-java] Java Headless build completed.')
}

$dependencyClasspath = (Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8).Trim()
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$separator = [IO.Path]::PathSeparator
$mainClasses = Join-Path $cliDirectory 'target\classes'
$classpath = "$mainClasses$separator$dependencyClasspath"
$headlessMain = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain'
$workspaceRoot = if ([string]::IsNullOrWhiteSpace($Workspace)) {
    $repositoryRoot
}
else {
    [IO.Path]::GetFullPath($Workspace)
}
$childCommand = @(
    $javaExecutable,
    '-cp',
    $classpath,
    $headlessMain,
    '--workspace',
    $workspaceRoot,
    '--timeout',
    $Timeout
)
if (-not [string]::IsNullOrWhiteSpace($Model)) {
    $childCommand += @('--model', $Model)
}
$childCommand += '--stdio'
$childCommandJson = ConvertTo-Json -Compress -InputObject $childCommand
$childCommandBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($childCommandJson)
)

$env:CC_JAVA_SPIKE_COMMAND_BASE64 = $childCommandBase64
$env:CC_JAVA_REPOSITORY_ROOT = $repositoryRoot
if (-not [string]::IsNullOrWhiteSpace($Prompt)) {
    $env:CC_JAVA_SPIKE_PROMPT_BASE64 = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($Prompt)
    )
}

Push-Location $tuiDirectory
try {
    [Console]::Error.WriteLine('[cc-java] Starting React/Ink TUI and Java child process.')
    & npm.cmd --silent run dev
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
