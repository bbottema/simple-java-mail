#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$testRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$testRunner = Join-Path $testRoot 'tools/memory/run-mail-send-pressure.ps1'
$testOutput = Join-Path $testRoot ('target/memory-runner-tests/frozen-' + [Guid]::NewGuid().ToString('N'))
$testEncoding = [Text.UTF8Encoding]::new($false)

# Load only the pure fingerprint/fence functions; evaluating the runner itself would start a Maven build and workload.
$testTokens = $null
$testErrors = $null
$syntax = [Management.Automation.Language.Parser]::ParseFile($testRunner, [ref]$testTokens, [ref]$testErrors)
if ($testErrors) { throw 'The memory runner contains invalid PowerShell syntax.' }
$functionNames = @('Get-TextFingerprint', 'Get-DirectoryFingerprint', 'Get-ClasspathDirectoryFingerprints', 'Assert-ClasspathDirectoriesUnchanged',
    'Get-FrozenClassFingerprints', 'Assert-IsolatedJavaOptions')
$definitions = $syntax.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $true)
foreach ($name in $functionNames) {
    $definition = @($definitions | Where-Object { $_.Name -eq $name })
    if ($definition.Count -ne 1) { throw "Cannot load the classpath verification function: $name" }
    . ([scriptblock]::Create($definition[0].Extent.Text))
}

$optionVariables = @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
$originalOptions = @{}
foreach ($name in $optionVariables) { $originalOptions[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in $optionVariables) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    Assert-IsolatedJavaOptions
    foreach ($name in $optionVariables) {
        $injectedValue = '-Dmemory.test.private=injected-private-value'
        [Environment]::SetEnvironmentVariable($name, $injectedValue, 'Process')
        $guarded = $false
        try { Assert-IsolatedJavaOptions }
        catch { $guarded = $_.Exception.Message.Contains($name) -and -not $_.Exception.Message.Contains($injectedValue) }
        if (-not $guarded) { throw "The Java-options guard did not reject $name without exposing its value." }
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
} finally {
    foreach ($name in $optionVariables) { [Environment]::SetEnvironmentVariable($name, $originalOptions[$name], 'Process') }
}

$first = Join-Path $testOutput 'core'
$second = Join-Path $testOutput 'mailer'
$null = New-Item -ItemType Directory -Path $first, $second
[IO.File]::WriteAllText((Join-Path $first 'Fixture.class'), 'original core bytes', $testEncoding)
[IO.File]::WriteAllText((Join-Path $second 'Fixture.class'), 'original mailer bytes', $testEncoding)
$entries = @($first, $second)
$before = Get-ClasspathDirectoryFingerprints $entries
Assert-ClasspathDirectoriesUnchanged $before (Get-ClasspathDirectoryFingerprints $entries)

# The first copy is locally stable, then another build changes it while the later directory is being copied.
$firstBefore = Get-DirectoryFingerprint $first
[IO.Compression.ZipFile]::CreateFromDirectory($first, (Join-Path $testOutput 'core.jar'))
$firstAfter = Get-DirectoryFingerprint $first
if ($firstBefore -ne $firstAfter) { throw 'The deterministic test changed the first directory too early.' }
[IO.File]::WriteAllText((Join-Path $first 'Fixture.class'), 'replacement core bytes', $testEncoding)
[IO.Compression.ZipFile]::CreateFromDirectory($second, (Join-Path $testOutput 'mailer.jar'))
$rejected = $false
try { Assert-ClasspathDirectoriesUnchanged $before (Get-ClasspathDirectoryFingerprints $entries) }
catch { $rejected = $_.Exception.Message -like 'Build output changed while freezing the classpath.*' }
if (-not $rejected) { throw 'An earlier module changed after its own copy, but the complete classpath fence did not reject it.' }

# Provenance must come from the launch artifacts, even if the original reactor output changes after the snapshot was accepted.
$pressureOutput = $testOutput
$pressureRoot = $testRoot
$frozenMapping = @([ordered]@{ original = $first; effective = Join-Path $testOutput 'core.jar' })
[IO.File]::WriteAllText((Join-Path $testOutput 'frozen-classpath.json'), ($frozenMapping | ConvertTo-Json), $testEncoding)
$frozenClasses = @(Get-FrozenClassFingerprints)
$expectedHash = Get-TextFingerprint 'original core bytes'
if ($frozenClasses.Count -ne 1 -or $frozenClasses[0].sha256 -ne $expectedHash) {
    throw 'Compiled-class provenance used changed reactor output instead of the frozen JAR entry.'
}

Write-Host 'Controller checks passed: injected Java options rejected safely; frozen classpath and entry hashes verified.'
Write-Host "Small synthetic test artifacts: $testOutput"
