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
$javaModuleDirectories = @(
    (Join-Path $repositoryRoot 'cc-java-domain'),
    (Join-Path $repositoryRoot 'cc-java-core'),
    (Join-Path $repositoryRoot 'cc-java-model-spring-ai'),
    (Join-Path $repositoryRoot 'cc-java-tools-local'),
    (Join-Path $repositoryRoot 'cc-java-cli')
)

# Compile the real Java Headless entrypoint and pass Java arguments without a shell string.
$canReuseBuild = $false
if ((Test-Path -LiteralPath $classpathFile -PathType Leaf) -and
    (Test-Path -LiteralPath $mainClassFile -PathType Leaf)) {
    $oldestOutput = @(
        (Get-Item -LiteralPath $classpathFile).LastWriteTimeUtc,
        (Get-Item -LiteralPath $mainClassFile).LastWriteTimeUtc
    ) | Sort-Object | Select-Object -First 1
    $buildInputs = @((Get-Item -LiteralPath $rootPom))
    foreach ($moduleDirectory in $javaModuleDirectories) {
        $modulePom = Join-Path $moduleDirectory 'pom.xml'
        if (Test-Path -LiteralPath $modulePom -PathType Leaf) {
            $buildInputs += Get-Item -LiteralPath $modulePom
        }
        $sourceDirectory = Join-Path $moduleDirectory 'src\main\java'
        if (Test-Path -LiteralPath $sourceDirectory -PathType Container) {
            $buildInputs += Get-ChildItem -LiteralPath $sourceDirectory -Recurse -File -Filter '*.java'
        }
    }
    $canReuseBuild = -not ($buildInputs | Where-Object {
        $_.LastWriteTimeUtc -gt $oldestOutput
    } | Select-Object -First 1)
}

if ($SkipBuild -and $canReuseBuild) {
    [Console]::Error.WriteLine('[cc-java] Reusing existing Java build outputs.')
}
else {
    if ($SkipBuild) {
        [Console]::Error.WriteLine(
            '[cc-java] Existing Java outputs are missing or stale; rebuilding.'
        )
    }
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
