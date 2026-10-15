param(
    [string]$AngusPath = 'C:\workstation\angus-mail-pipelining-prototype',
    [string]$JdkPath = 'C:\Program Files\Java\jdk-21',
    [string]$EvidencePath = 'C:\workstation\simple-java-mail\target\angus-pipelining-prototype\logs',
    [switch]$KeyUpdates,
    [int]$DeadlineSeconds = 30
)

# Run after the focused Maven build documented in the results report. The
# watchdog owns only this diagnostic Java process, not any other Java process.
$ErrorActionPreference = 'Stop'
$reportPath = Join-Path $AngusPath 'providers/angus-mail/target/surefire-reports/TEST-org.eclipse.angus.mail.smtp.SMTPReplyReaderTest.xml'
[xml]$report = Get-Content -LiteralPath $reportPath
$classpath = ($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
if (-not $classpath) { throw 'No test classpath found. Run the focused Maven build first.' }
$java = Join-Path $JdkPath 'bin/java.exe'
$jstack = Join-Path $JdkPath 'bin/jstack.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "JDK not found: $JdkPath" }
New-Item -ItemType Directory -Force -Path $EvidencePath | Out-Null
$runName = 'reader-{0}-{1}' -f $(if ($KeyUpdates) { 'keyupdate' } else { 'normal' }), (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$arguments = @('-ea', '-cp', ('"{0}"' -f $classpath))
if ($KeyUpdates) {
    $securityPath = Join-Path $PSScriptRoot 'keyupdate.security'
    $arguments += '"-Djava.security.properties={0}"' -f $securityPath
}
$arguments += @('org.junit.runner.JUnitCore', 'org.eclipse.angus.mail.smtp.SMTPReplyReaderTest')
$process = Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $EvidencePath "$runName.out.log") `
    -RedirectStandardError (Join-Path $EvidencePath "$runName.err.log")
try {
    if (-not $process.WaitForExit($DeadlineSeconds * 1000)) {
        & $jstack $process.Id > (Join-Path $EvidencePath "$runName.threads.txt")
        throw "Reader gate exceeded ${DeadlineSeconds}s; thread dump saved under $EvidencePath."
    }
    $process.Refresh()
    if ($process.ExitCode -ne 0) { throw "Reader gate failed; see $EvidencePath/$runName.*.log" }
    Get-Content -LiteralPath (Join-Path $EvidencePath "$runName.out.log")
} finally {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id
        $process.WaitForExit()
    }
    $process.Dispose()
}
