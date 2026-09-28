param(
    [Parameter(Mandatory = $true)][string]$JdkDirectory,
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$RunName = ('components-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateRange(1, 20)][int]$Forks = 3,
    [string]$ClasspathFile,
    [string]$Payloads = '.*',
    [string]$Operations = '.*',
    [ValidateRange(1, 8388608)][int]$AttachmentBytes = 262144
)

$ErrorActionPreference = 'Stop'
$auditRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$auditOutput = Join-Path $auditRoot "target/mail-send-performance/$RunName"
if (Test-Path -LiteralPath $auditOutput) { throw "Run directory already exists: $auditOutput" }
$null = New-Item -ItemType Directory -Path $auditOutput
$auditJava = Join-Path $JdkDirectory 'bin/java.exe'
if (-not $ClasspathFile) { $ClasspathFile = Join-Path $auditRoot 'modules/simple-java-mail/target/mail-send-audit-classpath.txt' }
$auditClasspath = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $ClasspathFile).Path).Trim()
$auditLogConfiguration = ([Uri](Join-Path $auditRoot 'tools/performance/quiet-log4j2.xml')).AbsoluteUri
$payloadCount = @('small', 'memory', 'file', 'exact', 'dkim' | Where-Object { $_ -cmatch "^(?:$Payloads)$" }).Count
$operationCount = @('render-and-protect', 'content-inspection', 'size-measurement', 'mime-output-to-counter' |
    Where-Object { $_ -cmatch "^(?:$Operations)$" }).Count
if ($payloadCount -eq 0 -or $operationCount -eq 0) { throw 'No component measurements selected' }
$metadata = [ordered]@{
    sourceCommit = (& git -C $auditRoot rev-parse HEAD)
    classpathFile = $ClasspathFile
    payloads = $Payloads; operations = $Operations
    inspectionSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $auditRoot `
        'modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusContentNegotiation.java')).Hash
    java = (& $auditJava -version 2>&1 | Out-String).Trim()
    forks = $Forks; samples = 3; warmupMillis = 300; sampleMillis = 500; attachmentBytes = $AttachmentBytes
    heap = '-Xms512m -Xmx512m -XX:+UseG1GC'; preparedMimeReused = $true
}
[IO.File]::WriteAllText((Join-Path $auditOutput 'environment.json'), ($metadata | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
for ($fork = 1; $fork -le $Forks; $fork++) {
    Write-Host "Content component fork $fork/$Forks"
    $outputFile = Join-Path $auditOutput "fork-$fork.csv"
    & $auditJava -Xms512m -Xmx512m -XX:+UseG1GC -cp $auditClasspath "-Dlog4j2.configurationFile=$auditLogConfiguration" `
        "-Dsjm.audit.attachmentBytes=$AttachmentBytes" "-Dsjm.audit.payloads=$Payloads" "-Dsjm.audit.operations=$Operations" `
        testutil.performance.ContentCostAudit (Join-Path $auditOutput "fork-$fork") `
        > $outputFile 2> (Join-Path $auditOutput "fork-$fork-errors.log")
    if ($LASTEXITCODE -ne 0) { throw "Component audit failed; see $auditOutput/fork-$fork-errors.log" }
    $rows = @(Import-Csv -LiteralPath $outputFile)
    if ($rows.Count -ne $payloadCount * $operationCount * 3 -or (Select-String -LiteralPath $outputFile -Pattern 'ERROR|Exception|WARNING') -or
            (Select-String -LiteralPath (Join-Path $auditOutput "fork-$fork-errors.log") -Pattern 'ERROR|Exception')) {
        throw "Unexpected component output: $outputFile"
    }
}
Write-Host "Component audit artifacts: $auditOutput"
