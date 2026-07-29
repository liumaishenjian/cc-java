param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CliArguments
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

$repositoryRoot = $PSScriptRoot
$mavenWrapper = Join-Path $repositoryRoot 'mvnw.cmd'
$rootPom = Join-Path $repositoryRoot 'pom.xml'
$cliDirectory = Join-Path $repositoryRoot 'cc-java-cli'
$classpathFile = Join-Path $cliDirectory 'target\cc-java-classpath.txt'

# Build from the root POM and launch Java with an argument array from any working directory.
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
    '-Dmdep.outputFile=target/cc-java-classpath.txt'
)
& $mavenWrapper @mavenArguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$dependencyClasspath = (Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8).Trim()
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$separator = [IO.Path]::PathSeparator
$mainClasses = Join-Path $cliDirectory 'target\classes'
$classpath = "$mainClasses$separator$dependencyClasspath"
$headlessMain = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain'

$env:CC_JAVA_REPOSITORY_ROOT = $repositoryRoot
& $javaExecutable '-cp' $classpath $headlessMain @CliArguments
exit $LASTEXITCODE
