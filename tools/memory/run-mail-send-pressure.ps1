#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JdkDirectory,
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$RunName = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateSet('sync', 'async', 'result-only', 'progress-only', 'observers', 'slow-progress', 'retry', 'cancellation', 'failover', 'churn',
        'attachment-memory', 'attachment-file', 'positive-control')]
    [string[]]$Profiles = @('async', 'observers', 'churn'),
    [ValidateRange(1, 100000000)][int]$Messages = 10000,
    [ValidateRange(0, 1000000)][int]$Warmup = 1000,
    [ValidateRange(1, 100000000)][int]$BatchSize = 1000,
    [ValidateRange(1, 64)][int]$Concurrency = 4,
    [ValidateRange(1, 4096)][int]$MaxInflight = 16,
    [ValidateRange(1, 67108864)][int]$AttachmentBytes = 5242880,
    [ValidateRange(1, 1048576)][int]$TextBytes = 4096,
    [ValidateRange(1, 30000)][int]$ObserverDelayMillis = 5,
    [ValidateRange(128, 32768)][int]$HeapMiB = 256,
    [ValidateRange(1, 1440)][int]$TimeoutMinutes = 30,
    [switch]$RecordJfr,
    [switch]$NoPooling,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$pressureRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$pressureOutput = Join-Path $pressureRoot "target/mail-send-memory/$RunName"
$pressureJava = Join-Path $JdkDirectory 'bin/java.exe'
$pressureEncoding = [Text.UTF8Encoding]::new($false)
$pressureMaximumLogBytes = 32MB
$pressureMaximumArtifactBytes = [Math]::Max(1GB, ($HeapMiB * 2MB) + 256MB)

function Write-JsonFile([string]$Path, [object]$Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), $pressureEncoding)
}

function Assert-IsolatedJavaOptions {
    foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
        if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            throw "The process variable $name contains additional Java launcher options. Unset it before starting this isolated memory run " +
                'so the selected JVM settings remain reproducible.'
        }
    }
}

function Get-TextFingerprint([string]$Value) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToHexString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))) }
    finally { $algorithm.Dispose() }
}

function Get-SourceFingerprints {
    # Capture content hashes, not source diffs or arbitrary environment variables that could contain secrets.
    $paths = & git ls-files -- modules pom.xml tools/memory
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the source revision.' }
    $paths += Get-ChildItem -LiteralPath (Join-Path $pressureRoot 'tools/memory') -File -Recurse | ForEach-Object {
        $_.FullName.Substring($pressureRoot.Length + 1).Replace('\', '/')
    }
    $paths += Get-ChildItem -LiteralPath (Join-Path $pressureRoot 'modules/simple-java-mail/src/test/java/testutil/memory') -File |
        ForEach-Object { $_.FullName.Substring($pressureRoot.Length + 1).Replace('\', '/') }
    return @($paths | Sort-Object -Unique | Where-Object { $_ -match '\.(java|xml|ps1|md)$' } | ForEach-Object {
        $path = Join-Path $pressureRoot $_
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
        }
    })
}

function Export-ReactorClasspath {
    $reportPath = Join-Path $pressureRoot ('modules/simple-java-mail/target/surefire-reports/' +
        'TEST-org.simplejavamail.mailer.internal.MailSendMemoryPressureTest.xml')
    if (-not (Test-Path -LiteralPath $reportPath)) {
        # Keep the test's package free to follow the fixture's package without relying on installed JARs.
        $reports = @(Get-ChildItem -LiteralPath (Split-Path $reportPath) -Filter 'TEST-*MailSendMemoryPressureTest.xml')
        if ($reports.Count -ne 1) { throw 'Run the reactor smoke test first; its classpath report is missing or ambiguous.' }
        $reportPath = $reports[0].FullName
    }
    [xml]$report = Get-Content -LiteralPath $reportPath -Raw
    $classpath = @($report.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' })[0].value
    if ([string]::IsNullOrWhiteSpace($classpath)) { throw "The reactor test did not export java.class.path: $reportPath" }
    foreach ($entry in ($classpath -split [IO.Path]::PathSeparator)) {
        if (-not (Test-Path -LiteralPath $entry)) { throw "Classpath entry no longer exists: $entry" }
    }
    [IO.File]::WriteAllText((Join-Path $pressureOutput 'reactor-classpath.txt'), $classpath, $pressureEncoding)
    if ($NoPooling) {
        $entries = @($classpath -split [IO.Path]::PathSeparator)
        $excluded = @($entries | Where-Object { $_ -match '[\\/]batch-module[\\/]target[\\/]classes$|[\\/]batch-module[^\\/]*\.jar$' })
        if ($excluded.Count -eq 0) { throw 'The reactor classpath has no optional batch module to exclude.' }
        $classpath = ($entries | Where-Object { $_ -notin $excluded }) -join [IO.Path]::PathSeparator
        Write-JsonFile (Join-Path $pressureOutput 'excluded-classpath-entries.json') $excluded
    }
    [IO.File]::WriteAllText((Join-Path $pressureOutput 'selected-original-classpath.txt'), $classpath, $pressureEncoding)
    return $classpath
}

function Get-DirectoryFingerprint([string]$Directory) {
    $files = @(Get-ChildItem -LiteralPath $Directory -File -Recurse | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($Directory.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
    return Get-TextFingerprint ($files -join "`n")
}

function Get-ClasspathDirectoryFingerprints([string[]]$Entries) {
    $fingerprints = [ordered]@{}
    foreach ($entry in $Entries) {
        if (Test-Path -LiteralPath $entry -PathType Container) { $fingerprints[$entry] = Get-DirectoryFingerprint $entry }
    }
    return $fingerprints
}

function Assert-ClasspathDirectoriesUnchanged([object]$Before, [object]$After) {
    foreach ($entry in @($Before.Keys) + @($After.Keys) | Select-Object -Unique) {
        if ($Before[$entry] -ne $After[$entry]) {
            throw "Build output changed while freezing the classpath. Rebuild and use a fresh run name: $entry"
        }
    }
}

function Freeze-ReactorClasspath([string]$Classpath) {
    # Concurrent sessions may rebuild target/classes during a soak. Run-local JARs preserve this launch's classes, resources and order.
    $directory = Join-Path $pressureOutput 'frozen-classpath'
    $null = New-Item -ItemType Directory -Path $directory
    $entries = @()
    $mapping = @()
    $originalEntries = @($Classpath -split [IO.Path]::PathSeparator)
    $groupBefore = Get-ClasspathDirectoryFingerprints $originalEntries
    foreach ($entry in $originalEntries) {
        if (Test-Path -LiteralPath $entry -PathType Container) {
            $frozen = Join-Path $directory ("entry-{0:D3}.jar" -f $entries.Count)
            $before = Get-DirectoryFingerprint $entry
            [IO.Compression.ZipFile]::CreateFromDirectory($entry, $frozen, [IO.Compression.CompressionLevel]::Fastest, $false)
            $after = Get-DirectoryFingerprint $entry
            if ($before -ne $after) { throw "Build output changed while freezing the classpath. Rebuild and use a fresh run name: $entry" }
            $mapping += [ordered]@{ original = $entry; effective = $frozen; directorySha256 = $before;
                jarSha256 = (Get-FileHash -LiteralPath $frozen -Algorithm SHA256).Hash }
            $entries += $frozen
        } else {
            $entries += $entry
        }
    }
    # Per-directory checks miss an earlier module being rebuilt while a later module is copied. Fence the complete reactor snapshot as well.
    $groupAfter = Get-ClasspathDirectoryFingerprints $originalEntries
    Assert-ClasspathDirectoriesUnchanged $groupBefore $groupAfter
    Write-JsonFile (Join-Path $pressureOutput 'frozen-classpath.json') $mapping
    $frozenClasspath = $entries -join [IO.Path]::PathSeparator
    [IO.File]::WriteAllText((Join-Path $pressureOutput 'classpath.txt'), $frozenClasspath, $pressureEncoding)
    return $frozenClasspath
}

function Get-FrozenClassFingerprints {
    $mapping = @(Get-Content -LiteralPath (Join-Path $pressureOutput 'frozen-classpath.json') -Raw | ConvertFrom-Json)
    foreach ($item in $mapping) {
        $archive = [IO.Compression.ZipFile]::OpenRead($item.effective)
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try {
            foreach ($entry in ($archive.Entries | Where-Object { $_.FullName.EndsWith('.class') } | Sort-Object FullName)) {
                $content = $entry.Open()
                try {
                    $original = Join-Path $item.original $entry.FullName
                    $path = if ($original.StartsWith($pressureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                        $original.Substring($pressureRoot.Length + 1)
                    } else { $original }
                    [ordered]@{ path = $path; frozenJar = $item.effective; entry = $entry.FullName;
                        sha256 = [Convert]::ToHexString($algorithm.ComputeHash($content)) }
                } finally { $content.Dispose() }
            }
        } finally {
            $algorithm.Dispose()
            $archive.Dispose()
        }
    }
}

function Start-PressureClient([string]$ProfileName, [string]$Directory, [string]$Classpath) {
    $logConfiguration = ([Uri](Join-Path $pressureRoot 'tools/performance/quiet-log4j2.xml')).AbsoluteUri
    $arguments = @("-Xms${HeapMiB}m", "-Xmx${HeapMiB}m", '-XX:+UseG1GC', '-XX:NativeMemoryTracking=summary',
        '-XX:+HeapDumpOnOutOfMemoryError', "-XX:HeapDumpPath=$Directory/client-oom.hprof", '-cp', $Classpath,
        "-Dlog4j2.configurationFile=$logConfiguration", "-Dsjm.memory.output=$Directory", "-Dsjm.memory.profile=$ProfileName",
        "-Dsjm.memory.messages=$Messages", "-Dsjm.memory.warmup=$Warmup", "-Dsjm.memory.batchSize=$BatchSize",
        "-Dsjm.memory.concurrency=$Concurrency", "-Dsjm.memory.inflight=$MaxInflight", "-Dsjm.memory.attachmentBytes=$AttachmentBytes",
        "-Dsjm.memory.textBytes=$TextBytes", "-Dsjm.memory.observerDelayMillis=$ObserverDelayMillis",
        "-Dsjm.memory.profileRecording=$($RecordJfr.IsPresent.ToString().ToLowerInvariant())", 'testutil.memory.MailSendMemoryPressure')
    Write-JsonFile (Join-Path $Directory 'launch.json') ([ordered]@{ executable = $pressureJava; arguments = $arguments })
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $pressureJava
    $start.WorkingDirectory = $pressureRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $arguments) { $start.ArgumentList.Add($argument) }
    $client = [Diagnostics.Process]::new()
    $client.StartInfo = $start
    $standardOutput = [IO.FileStream]::new((Join-Path $Directory 'run.log'), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
        [IO.FileShare]::Read, 1, [IO.FileOptions]::Asynchronous)
    $standardError = [IO.FileStream]::new((Join-Path $Directory 'error.log'), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
        [IO.FileShare]::Read, 1, [IO.FileOptions]::Asynchronous)
    $started = $false
    try {
        $started = $client.Start()
        if (-not $started) { throw 'The memory-pressure client could not be started.' }
        return [pscustomobject]@{
            process = $client
            outputStream = $standardOutput
            errorStream = $standardError
            outputCopy = $client.StandardOutput.BaseStream.CopyToAsync($standardOutput)
            errorCopy = $client.StandardError.BaseStream.CopyToAsync($standardError)
        }
    } catch {
        if ($started -and -not $client.HasExited) { $client.Kill($true); $null = $client.WaitForExit(10000) }
        $standardOutput.Dispose()
        $standardError.Dispose()
        $client.Dispose()
        throw
    }
}

function Stop-OwnedClient([object]$Client) {
    # This Process instance belongs to this launch. Never discover/kill other Java or Maven processes by name.
    if (-not $Client.process.HasExited) {
        try { $Client.process.Kill($true) }
        catch [InvalidOperationException] { if (-not $Client.process.HasExited) { throw } }
    }
    if (-not $Client.process.WaitForExit(10000)) { throw 'The launched client process did not terminate after cancellation.' }
}

function Wait-ForPressureClient([object]$Client, [string]$Directory) {
    $deadline = [DateTime]::UtcNow.AddMinutes($TimeoutMinutes)
    $nextArtifactCheck = [DateTime]::UtcNow
    while (-not $Client.process.WaitForExit(500)) {
        if ([DateTime]::UtcNow -ge $deadline) {
            throw "Profile exceeded $TimeoutMinutes minutes; its client and child servers will be stopped. See $Directory"
        }
        $logBytes = (Get-Item -LiteralPath (Join-Path $Directory 'run.log')).Length +
            (Get-Item -LiteralPath (Join-Path $Directory 'error.log')).Length
        if ($logBytes -gt $pressureMaximumLogBytes) { throw "Profile exceeded its 32 MiB log budget. See $Directory" }
        if ([DateTime]::UtcNow -ge $nextArtifactCheck) {
            $artifactBytes = (Get-ChildItem -LiteralPath $Directory -File -Recurse | Measure-Object Length -Sum).Sum
            if ($artifactBytes -gt $pressureMaximumArtifactBytes) { throw "Profile exceeded its artifact budget. See $Directory" }
            $nextArtifactCheck = [DateTime]::UtcNow.AddSeconds(5)
        }
    }
    return $Client.process.ExitCode
}

function Invoke-PressureProfile([string]$ProfileName, [string]$Classpath) {
    $directory = Join-Path $pressureOutput $ProfileName
    $null = New-Item -ItemType Directory -Path $directory
    Write-Host "Memory profile: $ProfileName; $Warmup warm-up + $Messages measured sends; heap ${HeapMiB} MiB"
    $client = $null
    $result = [ordered]@{ profile = $ProfileName; startedAtUtc = [DateTime]::UtcNow.ToString('o'); status = 'failed' }
    try {
        $client = Start-PressureClient $ProfileName $directory $Classpath
        $result.clientPid = $client.process.Id
        $exitCode = Wait-ForPressureClient $client $directory
        if ($exitCode -ne 0) { throw "Profile '$ProfileName' exited with code $exitCode. See $directory" }
        $result.status = 'completed'
        return [ordered]@{ profile = $ProfileName; exitCode = $exitCode; directory = $directory; status = 'completed' }
    } catch {
        $result.failure = $_.Exception.Message
        throw
    } finally {
        if ($null -ne $client) {
            try {
                Stop-OwnedClient $client
                $result.exitCode = $client.process.ExitCode
                $null = $client.outputCopy.GetAwaiter().GetResult()
                $null = $client.errorCopy.GetAwaiter().GetResult()
            } finally {
                $client.outputStream.Dispose()
                $client.errorStream.Dispose()
                $client.process.Dispose()
            }
        }
        $result.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
        Write-JsonFile (Join-Path $directory 'process-result.json') $result
    }
}

Assert-IsolatedJavaOptions
if (-not (Test-Path -LiteralPath $pressureJava -PathType Leaf)) { throw "Java executable not found: $pressureJava" }
if ((@($Profiles | Sort-Object -Unique)).Count -ne $Profiles.Count) { throw 'Specify each profile only once.' }
if ([Math]::Ceiling($Messages / $BatchSize) -gt 60) {
    $suggestedBatchSize = [int][Math]::Ceiling($Messages / 60)
    throw "This run would produce more than 60 diagnostic batches. Use -BatchSize $suggestedBatchSize or larger to bound retained artifacts."
}
if (Test-Path -LiteralPath $pressureOutput) { throw "Run directory already exists: $pressureOutput" }
$null = New-Item -ItemType Directory -Path $pressureOutput
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:PATH
$originalMavenOptions = $env:MAVEN_OPTS
$manifest = [ordered]@{ startedAtUtc = [DateTime]::UtcNow.ToString('o'); profiles = $Profiles; results = @(); status = 'starting' }
Push-Location $pressureRoot
try {
    if (-not $SkipBuild) {
        $env:JAVA_HOME = $JdkDirectory
        $env:PATH = "$JdkDirectory/bin;$originalPath"
        $env:MAVEN_OPTS = "$originalMavenOptions -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT".Trim()
        & mvn -pl modules/simple-java-mail -am test '-Dtest=MailSendMemoryPressureTest' '-Dsurefire.failIfNoSpecifiedTests=false' `
            '-Dlicense.skip=true' '-Djacoco.skip=true' '-Dmaven.javadoc.skip=true' '-Dspotbugs.skip=true' *> (Join-Path $pressureOutput 'build.log')
        if ($LASTEXITCODE -ne 0) { throw "Harness build failed; see $pressureOutput/build.log" }
    }
    $selectedClasspath = Export-ReactorClasspath
    $classpath = Freeze-ReactorClasspath $selectedClasspath
    $sourceFingerprints = Get-SourceFingerprints
    $dirtyDiff = (& git -c core.safecrlf=false diff --no-ext-diff --binary HEAD -- modules pom.xml tools/memory) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot fingerprint the working source changes.' }
    $manifest.sourceCommit = (& git rev-parse HEAD)
    $manifest.sourceBranch = (& git branch --show-current)
    $manifest.dirtyDiffSha256 = Get-TextFingerprint $dirtyDiff
    $manifest.sources = $sourceFingerprints
    $manifest.classpath = 'classpath.txt'
    $manifest.originalClasspath = 'selected-original-classpath.txt'
    $manifest.frozenClasspathMapping = 'frozen-classpath.json'
    $manifest.classpathEntries = @($classpath -split [IO.Path]::PathSeparator | ForEach-Object {
        if (Test-Path -LiteralPath $_ -PathType Leaf) { [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_).Hash } }
        else { [ordered]@{ path = $_; kind = 'reactor-classes' } }
    })
    $manifest.compiledClasses = @(Get-FrozenClassFingerprints)
    $manifest.java = (& $pressureJava -version 2>&1 | Out-String).Trim()
    $manifest.os = [Environment]::OSVersion.VersionString
    $manifest.logicalProcessors = [Environment]::ProcessorCount
    $manifest.powerShell = $PSVersionTable.PSVersion.ToString()
    $manifest.settings = [ordered]@{ messages = $Messages; warmup = $Warmup; batchSize = $BatchSize; concurrency = $Concurrency;
        maxInflight = $MaxInflight; attachmentBytes = $AttachmentBytes; textBytes = $TextBytes; observerDelayMillis = $ObserverDelayMillis;
        heapMiB = $HeapMiB; timeoutMinutesPerProfile = $TimeoutMinutes; jfr = [bool]$RecordJfr; skippedBuild = [bool]$SkipBuild;
        pooling = -not [bool]$NoPooling; maximumLogBytes = $pressureMaximumLogBytes; maximumArtifactBytes = $pressureMaximumArtifactBytes }
    $manifest.status = 'running'
    Write-JsonFile (Join-Path $pressureOutput 'manifest.json') $manifest
    foreach ($profileName in $Profiles) {
        $manifest.results += Invoke-PressureProfile $profileName $classpath
        Write-JsonFile (Join-Path $pressureOutput 'manifest.json') $manifest
    }
    $manifest.status = 'completed'
} catch {
    $manifest.status = 'failed'
    $manifest.failure = $_.Exception.Message
    throw
} finally {
    $manifest.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    Write-JsonFile (Join-Path $pressureOutput 'manifest.json') $manifest
    $env:JAVA_HOME = $originalJavaHome
    $env:PATH = $originalPath
    $env:MAVEN_OPTS = $originalMavenOptions
    Pop-Location
    if (Test-Path -LiteralPath (Join-Path $pressureOutput "$($Profiles[0])/samples.csv")) {
        try { & (Join-Path $PSScriptRoot 'summarize-mail-send-pressure.ps1') -RunDirectory $pressureOutput }
        catch { Write-Warning "Could not summarize the retained artifacts: $($_.Exception.Message)" }
    }
}
Write-Host "Memory artifacts and report: $pressureOutput"
