param(
    [Parameter(Mandatory = $true)][string]$JdkDirectory,
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$RunName = ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateRange(1, 20)][int]$Forks = 3,
    [ValidateRange(1, 20)][int]$Samples = 3,
    [ValidateRange(1, 100000)][int]$Messages = 2000,
    [ValidateRange(1, 10000)][int]$LargeMessages = 200,
    [ValidateRange(1, 10000)][int]$Warmup = 200,
    [ValidateRange(1, 8388608)][int]$AttachmentBytes = 262144,
    [string]$Scenarios = '.*',
    [ValidateSet('baseline', 'no-size', 'no-inspection', 'neither', 'bulk-inspection', 'reference', 'discovery-reference')]
    [string[]]$Variants = @('baseline', 'no-size', 'no-inspection', 'neither'),
    [ValidatePattern('^[0-9a-f]{40}$')][string]$ReferenceCommit = '0d100a2051fe4e35c83a66e8c90d8354801adbe5',
    [switch]$PackagedClasspath,
    [string]$RuntimeClasspathFile,
    [ValidatePattern('^[a-zA-Z0-9_.]*$')][string]$DiagnosticStreamProviderClass = '',
    [switch]$Profile,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$auditRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$auditOutput = Join-Path $auditRoot "target/mail-send-performance/$RunName"
if (Test-Path -LiteralPath $auditOutput) { throw "Run directory already exists: $auditOutput" }
$null = New-Item -ItemType Directory -Path $auditOutput
$auditJava = Join-Path $JdkDirectory 'bin/java.exe'
$auditJavac = Join-Path $JdkDirectory 'bin/javac.exe'
$auditEncoding = [System.Text.UTF8Encoding]::new($false)
$auditLogConfiguration = ([Uri](Join-Path $auditRoot 'tools/performance/quiet-log4j2.xml')).AbsoluteUri
$discoverySources = @(
    'modules/core-module/src/main/java/org/simplejavamail/internal/util/JakartaMailImplementation.java',
    'modules/core-module/src/main/java/org/simplejavamail/internal/util/MailTransportLifecycleResolver.java',
    'modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/MailTransportAdapterResolver.java',
    'modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/SmtpConnectionProbe.java',
    'modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java',
    'modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageProducerHelper.java',
    'modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageParser.java'
)

function Replace-ExactlyOnce([string]$Source, [string]$Pattern, [string]$Replacement) {
    if ([regex]::Matches($Source, $Pattern).Count -ne 1) { throw "Production source no longer matches the audit overlay: $Pattern" }
    return [regex]::Replace($Source, $Pattern, $Replacement)
}

function Compile-Overlay([string]$Variant, [string]$Classpath, [string]$Lombok) {
    $overlayRoot = Join-Path $auditOutput "overlays/$Variant"
    $overlayClasses = Join-Path $overlayRoot 'classes'
    $null = New-Item -ItemType Directory -Path $overlayClasses
    $sources = @()
    $providerRoot = Join-Path $auditRoot 'modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus'
    if ($Variant -in @('no-size', 'neither')) {
        $source = [IO.File]::ReadAllText((Join-Path $providerRoot 'ManagedAngusTransport.java'))
        $source = Replace-ExactlyOnce $source 'activeMessage\.prepareMessageSize\(getServerMaximumMessageSize\(\), currentSizeSupport\.isAdvertised\(\),\s+aborted::get\);' `
            '// Audit-only ablation: no SIZE pass, declaration or measured receipt fact.'
        $path = Join-Path $overlayRoot 'ManagedAngusTransport.java'
        [IO.File]::WriteAllText($path, $source, $auditEncoding)
        $sources += $path
    }
    # Compile the inspection class for every variant with the same compiler settings and one classpath entry each.
    # Otherwise the reference alone would add a directory to repeated ServiceLoader searches.
    $source = [IO.File]::ReadAllText((Join-Path $providerRoot 'AngusContentNegotiation.java'))
    if ($Variant -in @('reference', 'bulk-inspection')) {
        $referencePath = 'modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusContentNegotiation.java'
        $source = (& git show "${ReferenceCommit}:$referencePath") -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Cannot read reference source at $ReferenceCommit" }
    }
    if ($Variant -eq 'bulk-inspection') {
        # Keep all body checks. Only replace one synchronized stream read per byte with one read per buffer.
        $source = Replace-ExactlyOnce $source 'int nextByte;\s+while \(\(nextByte = body.read\(\)\) != -1\) \{' @'
final byte[] buffer = new byte[8192];
            int count;
            while ((count = body.read(buffer)) != -1) {
                for (int index = 0; index < count; index++) {
                    final int nextByte = buffer[index] & 0xff;
'@
        $source = Replace-ExactlyOnce $source '            return builder\(\).hasRawEightBitBodyBytes\(eightBit\).build\(\);' `
            "            }`r`n            return builder().hasRawEightBitBodyBytes(eightBit).build();"
    } elseif ($Variant -in @('no-inspection', 'neither')) {
        $source = Replace-ExactlyOnce $source 'final SubmittedContent submittedContent = inspectSubmittedContent\(preparedMail\);' `
            'final SubmittedContent submittedContent = SubmittedContent.EMPTY; // Known-safe synthetic inputs only.'
    }
    $path = Join-Path $overlayRoot 'AngusContentNegotiation.java'
    [IO.File]::WriteAllText($path, $source, $auditEncoding)
    $sources += $path
    if ($Variants -contains 'discovery-reference') {
        # Matched compilation avoids comparing old, uninstrumented classes against instrumented current classes.
        # Both discovery variants retain the optimized content inspector and the same single overlay classpath entry.
        foreach ($discoverySource in $discoverySources) {
            $source = [IO.File]::ReadAllText((Join-Path $auditRoot $discoverySource))
            if ($Variant -eq 'discovery-reference') {
                $source = (& git show "${ReferenceCommit}:$discoverySource") -join "`n"
                if ($LASTEXITCODE -ne 0) { throw "Cannot read discovery reference: $discoverySource" }
            }
            $path = Join-Path $overlayRoot ([IO.Path]::GetFileName($discoverySource))
            [IO.File]::WriteAllText($path, $source, $auditEncoding)
            $sources += $path
        }
    }
    & $auditJavac --release 11 -encoding UTF-8 -cp $Classpath -processorpath $Lombok -d $overlayClasses @sources
    if ($LASTEXITCODE -ne 0) { throw "Cannot compile isolated overlay: $Variant" }
    if ($PackagedClasspath) {
        $overlayJar = Join-Path $overlayRoot 'overlay.jar'
        & (Join-Path $JdkDirectory 'bin/jar.exe') --create --file $overlayJar -C $overlayClasses .
        if ($LASTEXITCODE -ne 0) { throw "Cannot package overlay: $Variant" }
        return "$overlayJar;$Classpath"
    }
    return "$overlayClasses;$Classpath"
}

function Package-Classpath([string]$Classpath) {
    # Change packaging only, not dependencies or service resources. This is not a minimal application classpath.
    $packageRoot = Join-Path $auditOutput 'classpath-jars'
    $null = New-Item -ItemType Directory -Path $packageRoot
    $entries = @()
    foreach ($entry in ($Classpath -split ';')) {
        if (Test-Path -LiteralPath $entry -PathType Container) {
            $jarPath = Join-Path $packageRoot "entry-$($entries.Count).jar"
            & (Join-Path $JdkDirectory 'bin/jar.exe') --create --file $jarPath -C $entry .
            if ($LASTEXITCODE -ne 0) { throw "Cannot package classpath directory: $entry" }
            $entries += $jarPath
        } else {
            $entries += $entry
        }
    }
    return $entries -join ';'
}

function Select-RuntimeClasspath([string]$TestClasspath, [string]$RuntimeFile) {
    # Maven supplies production dependencies. Add only this harness and its loopback sink, not the rest of the test runtime.
    $entries = @((Join-Path $auditRoot 'modules/simple-java-mail/target/test-classes'),
        (Join-Path $auditRoot 'modules/simple-java-mail/target/classes'))
    $entries += [IO.File]::ReadAllText((Resolve-Path -LiteralPath $RuntimeFile).Path).Trim() -split ';'
    $entries += $TestClasspath -split ';' | Where-Object {
        $_ -match '[\\/](subethasmtp|guava-mini|jsr305|log4j-slf4j2-impl|log4j-api|log4j-core)[\\/].*\.jar$'
    }
    return ($entries | Select-Object -Unique) -join ';'
}

Push-Location $auditRoot
try {
    if (-not $SkipBuild) {
        $env:JAVA_HOME = $JdkDirectory
        $env:PATH = "$JdkDirectory/bin;$env:PATH"
        $env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
        & mvn -pl modules/simple-java-mail -am test '-Dtest=MailSendPerformanceHarnessTest' '-Dsurefire.failIfNoSpecifiedTests=false' `
            '-Dlicense.skip=true' '-DexcludeLiveServerTests=true' '-Djacoco.skip=true' '-Dmaven.javadoc.skip=true' `
            *> (Join-Path $auditOutput 'build.log')
        if ($LASTEXITCODE -ne 0) { throw "Harness build failed; see $auditOutput/build.log" }
    }
    $classpath = [IO.File]::ReadAllText((Join-Path $auditRoot 'modules/simple-java-mail/target/mail-send-audit-classpath.txt')).Trim()
    $lombok = ($classpath -split ';' | Where-Object { $_ -match '[\\/]lombok[\\/].*\.jar$' } | Select-Object -First 1)
    if (-not $lombok) { throw 'Lombok is absent from the reactor test classpath' }
    $sourcePath = Join-Path $auditRoot 'modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusContentNegotiation.java'
    $metadata = [ordered]@{
        sourceCommit = (& git rev-parse HEAD)
        inspectionSourceSha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
        inspectionClassSha256 = (Get-FileHash -LiteralPath (Join-Path $auditRoot `
            'modules/angus-mail-provider-module/target/classes/org/simplejavamail/internal/mailprovider/angus/AngusContentNegotiation.class')).Hash
        referenceCommit = $ReferenceCommit; packagedClasspath = [bool]$PackagedClasspath
        runtimeClasspathFile = $RuntimeClasspathFile
        diagnosticStreamProviderClass = $DiagnosticStreamProviderClass
        overlayInspectionHashes = [ordered]@{}
        overlayClassHashes = [ordered]@{}
        providerDiscoverySourceSha256 = (Get-FileHash -LiteralPath (Join-Path $auditRoot `
            'modules/core-module/src/main/java/org/simplejavamail/internal/util/MailProviderDiscovery.java')).Hash
        providerDiscoveryClassSha256 = (Get-FileHash -LiteralPath (Join-Path $auditRoot `
            'modules/core-module/target/classes/org/simplejavamail/internal/util/MailProviderDiscovery.class')).Hash
        java = (& $auditJava -version 2>&1 | Out-String).Trim()
        os = [Environment]::OSVersion.VersionString
        processors = (Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
        forks = $Forks; samples = $Samples; messages = $Messages; largeMessages = $LargeMessages
        warmup = $Warmup; attachmentBytes = $AttachmentBytes; scenarios = $Scenarios; variants = $Variants
        profile = [bool]$Profile; heap = '-Xms512m -Xmx512m -XX:+UseG1GC'
    }
    [IO.File]::WriteAllText((Join-Path $auditOutput 'environment.json'), ($metadata | ConvertTo-Json -Depth 5), $auditEncoding)
    $overlayClasspath = $classpath
    if ($RuntimeClasspathFile) { $classpath = Select-RuntimeClasspath $classpath $RuntimeClasspathFile }
    if ($PackagedClasspath) { $classpath = Package-Classpath $classpath }
    [IO.File]::WriteAllText((Join-Path $auditOutput 'classpath.txt'), $classpath, $auditEncoding)
    $classpaths = @{}
    foreach ($variant in $Variants) {
        # Compile against the complete test classpath (annotations/Lombok); execute against the selected runtime.
        $compiledClasspath = Compile-Overlay $variant $overlayClasspath $lombok
        $classpaths[$variant] = $compiledClasspath.Substring(0, $compiledClasspath.Length - $overlayClasspath.Length) + $classpath
        [IO.File]::WriteAllText((Join-Path $auditOutput "$variant-classpath.txt"), $classpaths[$variant], $auditEncoding)
        $compiledInspectionRoot = Join-Path $auditOutput "overlays/$variant/classes/org/simplejavamail/internal/mailprovider/angus"
        $metadata.overlayInspectionHashes[$variant] = @(Get-ChildItem -LiteralPath $compiledInspectionRoot -Filter 'AngusContentNegotiation*.class' |
            Sort-Object Name | ForEach-Object { [ordered]@{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash } })
        $compiledRoot = Join-Path $auditOutput "overlays/$variant/classes"
        $metadata.overlayClassHashes[$variant] = @(Get-ChildItem -LiteralPath $compiledRoot -Filter '*.class' -Recurse |
            Sort-Object FullName | ForEach-Object { [ordered]@{
                name = $_.FullName.Substring($compiledRoot.Length + 1); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash
            } })
    }
    [IO.File]::WriteAllText((Join-Path $auditOutput 'environment.json'), ($metadata | ConvertTo-Json -Depth 7), $auditEncoding)
    for ($fork = 0; $fork -lt $Forks; $fork++) {
        for ($offset = 0; $offset -lt $Variants.Count; $offset++) {
            $variant = $Variants[($fork + $offset) % $Variants.Count]
            $sampleDirectory = Join-Path $auditOutput "fork-$($fork + 1)-$variant"
            $null = New-Item -ItemType Directory -Path $sampleDirectory
            Write-Host "Audit fork $($fork + 1)/$Forks, $variant, scenarios $Scenarios"
            $arguments = @('-Xms512m', '-Xmx512m', '-XX:+UseG1GC', '-cp', $classpaths[$variant],
                "-Dlog4j2.configurationFile=$auditLogConfiguration",
                "-Dsjm.audit.output=$sampleDirectory", "-Dsjm.audit.variant=$variant", "-Dsjm.audit.scenarios=$Scenarios",
                "-Dsjm.audit.samples=$Samples", "-Dsjm.audit.messages=$Messages", "-Dsjm.audit.largeMessages=$LargeMessages",
                "-Dsjm.audit.warmup=$Warmup", "-Dsjm.audit.attachmentBytes=$AttachmentBytes",
                "-Dsjm.audit.profile=$($Profile.IsPresent.ToString().ToLowerInvariant())", 'testutil.performance.MailSendPerformanceAudit')
            if ($DiagnosticStreamProviderClass) {
                # This child-JVM-only experiment isolates repeated Jakarta stream-provider discovery; never set it in production code.
                $arguments = @("-Djakarta.mail.util.StreamProvider=$DiagnosticStreamProviderClass") + $arguments
            }
            & $auditJava @arguments *> (Join-Path $sampleDirectory 'run.log')
            if ($LASTEXITCODE -ne 0) { throw "Audit failed; see $sampleDirectory/run.log" }
            if (Select-String -LiteralPath (Join-Path $sampleDirectory 'run.log') -Pattern 'ERROR|Exception') {
                throw "Unexpected errors in audit output; see $sampleDirectory/run.log"
            }
            Get-Content -LiteralPath (Join-Path $sampleDirectory 'samples.csv') | Select-Object -Last 1
        }
    }
    Write-Host "Audit artifacts: $auditOutput"
} finally {
    Pop-Location
}
