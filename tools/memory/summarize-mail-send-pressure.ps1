#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$RunDirectory)

$ErrorActionPreference = 'Stop'
$summaryRoot = (Resolve-Path -LiteralPath $RunDirectory).Path
$summaryEncoding = [Text.UTF8Encoding]::new($false)
$summaryCulture = [Globalization.CultureInfo]::InvariantCulture
$summaryProfiles = @()
$summarySamples = @()

function Read-OptionalJson([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
    catch { Write-Warning "Cannot read the optional run metadata: $Path"; return $null }
}

$runManifest = Read-OptionalJson (Join-Path $summaryRoot 'manifest.json')
$runStatus = if ($null -ne $runManifest.status) { [string]$runManifest.status } else { 'unknown' }

function Format-Number([double]$Value, [string]$Format = '0.00') { return $Value.ToString($Format, $summaryCulture) }
function Escape-Html([object]$Value) { return [Net.WebUtility]::HtmlEncode([string]$Value) }

function Export-InvariantCsv([object[]]$Rows, [string]$Path, [string[]]$ExcludedProperties = @()) {
    $Rows | ForEach-Object {
        $values = [ordered]@{}
        foreach ($property in $_.PSObject.Properties) {
            if ($property.Name -in $ExcludedProperties) { continue }
            $value = $property.Value
            if ($value -is [IFormattable]) { $value = $value.ToString($null, $summaryCulture) }
            $values[$property.Name] = $value
        }
        [pscustomobject]$values
    } | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding utf8
}

function Get-HeapBytes([object]$Sample) {
    if ($null -ne $Sample.live_heap_bytes -and [string]$Sample.live_heap_bytes -ne '') { return [long]$Sample.live_heap_bytes }
    return [long]$Sample.heap_used_bytes
}

function Read-ClassHistogram([string]$Path) {
    $classes = @{}
    foreach ($line in (Get-Content -LiteralPath $Path)) {
        if ($line -match '^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)') {
            $name = $Matches[3]
            if (-not $classes.ContainsKey($name)) { $classes[$name] = @{ instances = 0L; shallowBytes = 0L } }
            $classes[$name].instances += [long]$Matches[1]
            $classes[$name].shallowBytes += [long]$Matches[2]
        }
    }
    return $classes
}

function Export-ClassGrowth([string]$Directory, [object]$First, [object]$Last) {
    $firstPath = Join-Path $Directory "heap-$($First.sample).txt"
    $lastPath = Join-Path $Directory "heap-$($Last.sample).txt"
    if (-not (Test-Path -LiteralPath $firstPath) -or -not (Test-Path -LiteralPath $lastPath)) { return @() }
    $before = Read-ClassHistogram $firstPath
    $after = Read-ClassHistogram $lastPath
    $growth = @(@($before.Keys) + @($after.Keys) | Sort-Object -Unique | ForEach-Object {
        $name = $_
        $beforeInstances = if ($before.ContainsKey($name)) { $before[$name].instances } else { 0L }
        $beforeBytes = if ($before.ContainsKey($name)) { $before[$name].shallowBytes } else { 0L }
        $afterInstances = if ($after.ContainsKey($name)) { $after[$name].instances } else { 0L }
        $afterBytes = if ($after.ContainsKey($name)) { $after[$name].shallowBytes } else { 0L }
        [pscustomobject]@{ class = $name; firstInstances = $beforeInstances; lastInstances = $afterInstances;
            instanceChange = $afterInstances - $beforeInstances; firstShallowBytes = $beforeBytes; lastShallowBytes = $afterBytes;
            shallowBytesChange = $afterBytes - $beforeBytes }
    } | Sort-Object shallowBytesChange -Descending)
    Export-InvariantCsv $growth (Join-Path $Directory 'class-growth.csv')
    return $growth
}

function New-HeapPlot([object[]]$Samples, [string]$ProfileName) {
    $width = 760; $height = 250; $left = 66; $top = 20; $plotWidth = 670; $plotHeight = 185
    $maximumCompleted = [Math]::Max(1, ($Samples | Measure-Object completed -Maximum).Maximum)
    $maximumMiB = [Math]::Max(1, ($Samples | ForEach-Object { (Get-HeapBytes $_) / 1MB } | Measure-Object -Maximum).Maximum * 1.10)
    $drained = @($Samples | Where-Object { $_.label -match 'warmup|drained|baseline' })
    $loaded = @($Samples | Where-Object { $_.label -eq 'loaded' })
    $points = @($drained | ForEach-Object {
        $horizontal = $left + [double]$_.completed / $maximumCompleted * $plotWidth
        $vertical = $top + $plotHeight - ((Get-HeapBytes $_) / 1MB / $maximumMiB * $plotHeight)
        "$(Format-Number $horizontal),$(Format-Number $vertical)"
    }) -join ' '
    $loadedPoints = @($loaded | ForEach-Object {
        $horizontal = $left + [double]$_.completed / $maximumCompleted * $plotWidth
        $vertical = $top + $plotHeight - ((Get-HeapBytes $_) / 1MB / $maximumMiB * $plotHeight)
        "$(Format-Number $horizontal),$(Format-Number $vertical)"
    }) -join ' '
    $ticks = @(0..4 | ForEach-Object {
        $value = $maximumMiB * $_ / 4
        $vertical = $top + $plotHeight - $plotHeight * $_ / 4
        '<line x1="{0}" y1="{1}" x2="{2}" y2="{1}" stroke="#e2e8f0"/><text x="58" y="{3}" text-anchor="end">{4}</text>' -f `
            $left, (Format-Number $vertical), ($left + $plotWidth), (Format-Number ($vertical + 4)), (Format-Number $value '0.0')
    }) -join "`n"
    return @"
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height" role="img" aria-label="Retained heap for $(Escape-Html $ProfileName)">
<style>text{font:12px system-ui;fill:#475569}</style>$ticks
<polyline points="$points" fill="none" stroke="#087e8b" stroke-width="2.5"/>
<polyline points="$loadedPoints" fill="none" stroke="#c17a16" stroke-width="1.5" stroke-dasharray="5 3"/>
<text x="66" y="225">0</text><text x="736" y="225" text-anchor="end">$maximumCompleted completed sends</text>
<text x="66" y="245">Java heap (MiB): teal = drained after collection; amber = loaded</text></svg>
"@
}

function New-NativePlot([object[]]$Samples) {
    $available = @($Samples | Where-Object { $null -ne $_.jvm_tracked_committed_bytes })
    if ($available.Count -eq 0) { return '' }
    $maximumCompleted = [Math]::Max(1, ($available | Measure-Object completed -Maximum).Maximum)
    $maximumMiB = [Math]::Max(1, ($available | Measure-Object jvm_tracked_committed_bytes -Maximum).Maximum / 1MB * 1.10)
    $points = @($available | ForEach-Object {
        "$(Format-Number (66 + [double]$_.completed / $maximumCompleted * 670)),$(Format-Number (205 - $_.jvm_tracked_committed_bytes / 1MB / $maximumMiB * 185))"
    }) -join ' '
    return @"
<h3>JVM tracked committed memory</h3><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 760 250" role="img" aria-label="JVM tracked committed memory, including Java heap">
<style>text{font:12px system-ui;fill:#475569}</style><line x1="66" y1="205" x2="736" y2="205" stroke="#e2e8f0"/>
<polyline points="$points" fill="none" stroke="#705ca1" stroke-width="2.5"/>
<text x="66" y="225">0</text><text x="736" y="225" text-anchor="end">$maximumCompleted completed sends</text>
<text x="66" y="245">Committed memory: 0–$(Format-Number $maximumMiB '0.0') MiB scale, includes committed Java heap</text></svg>
"@
}

foreach ($directory in (Get-ChildItem -LiteralPath $summaryRoot -Directory | Sort-Object Name)) {
    $samplePath = Join-Path $directory.FullName 'samples.csv'
    if (-not (Test-Path -LiteralPath $samplePath)) { continue }
    $samples = @(Import-Csv -LiteralPath $samplePath)
    if ($samples.Count -eq 0) { continue }
    $processResult = Read-OptionalJson (Join-Path $directory.FullName 'process-result.json')
    $workloadResult = Read-OptionalJson (Join-Path $directory.FullName 'summary.json')
    $profileMetadata = Read-OptionalJson (Join-Path $directory.FullName 'memory-profile.json')
    $profileStatus = if ($processResult.status -eq 'completed' -and $processResult.exitCode -eq 0 -and $null -ne $workloadResult) {
        'completed'
    } elseif ($processResult.status -eq 'failed') { 'failed' } else { 'partial' }
    foreach ($sample in $samples) {
        if ($null -eq $sample.heap_used_bytes -or $null -eq $sample.completed) { throw "Invalid memory sample CSV: $samplePath" }
        $nativeCommitted = $null
        $nativePath = Join-Path $directory.FullName "native-$($sample.sample).txt"
        if (Test-Path -LiteralPath $nativePath) {
            $nativeTotal = Select-String -LiteralPath $nativePath -Pattern 'Total:\s+reserved=\d+KB[^\r\n]*committed=(\d+)KB' | Select-Object -First 1
            if ($null -ne $nativeTotal) {
                $nativeCommitted = [long]$nativeTotal.Matches[0].Groups[1].Value * 1KB
            }
        }
        $sample | Add-Member -NotePropertyName jvm_tracked_committed_bytes -NotePropertyValue $nativeCommitted -Force
        $row = [ordered]@{ profile = $directory.Name }
        foreach ($property in $sample.PSObject.Properties) { $row[$property.Name] = $property.Value }
        $summarySamples += [pscustomobject]$row
    }
    $measured = @($samples | Where-Object { $_.label -eq 'drained' })
    if ($measured.Count -eq 0) { $measured = $samples }
    $first = $measured[0]; $last = $measured[-1]
    $classGrowth = @(Export-ClassGrowth $directory.FullName $first $last)
    $changeBytes = (Get-HeapBytes $last) - (Get-HeapBytes $first)
    $completedChange = [long]$last.completed - [long]$first.completed
    $afterClose = @($samples | Where-Object { $_.label -match 'close' })
    $requestedTotal = if ($null -ne $profileMetadata) { [long]$profileMetadata.messages + [long]$profileMetadata.warmup }
        elseif ($null -ne $runManifest.settings) { [long]$runManifest.settings.messages + [long]$runManifest.settings.warmup }
        else { $null }
    $profileSummary = [ordered]@{ profile = $directory.Name; runStatus = $runStatus; status = $profileStatus;
        exitCode = $processResult.exitCode; failure = $processResult.failure; operatorStopReason = $runManifest.operatorStopReason;
        observedCompleted = ($samples | ForEach-Object { [long]$_.completed } | Measure-Object -Maximum).Maximum;
        verifiedCompleted = $workloadResult.completed; requestedTotal = $requestedTotal;
        samples = $samples.Count; firstCompleted = [long]$first.completed;
        lastCompleted = [long]$last.completed; firstHeapBytes = Get-HeapBytes $first; lastHeapBytes = Get-HeapBytes $last;
        heapChangeBytes = $changeBytes; heapChangeBytesPerCompletedSend = if ($completedChange -gt 0) { $changeBytes / $completedChange } else { $null };
        maximumThreads = ($samples | ForEach-Object { [int]$_.live_threads } | Measure-Object -Maximum).Maximum;
        afterCloseHeapBytes = if ($afterClose.Count -gt 0) { Get-HeapBytes $afterClose[-1] } else { $null };
        growingClasses = @($classGrowth | Where-Object { $_.shallowBytesChange -gt 0 } | Select-Object -First 10);
        positiveControl = $directory.Name -eq 'positive-control' }
    $summaryProfiles += [pscustomobject]$profileSummary
    [IO.File]::WriteAllText((Join-Path $directory.FullName 'heap.svg'), (New-HeapPlot $samples $directory.Name), $summaryEncoding)
    [IO.File]::WriteAllText((Join-Path $directory.FullName 'native-plot.html'), (New-NativePlot $samples), $summaryEncoding)
}
if ($summaryProfiles.Count -eq 0) { throw "No samples.csv files were found below $summaryRoot" }
Export-InvariantCsv $summarySamples (Join-Path $summaryRoot 'all-samples.csv')
Export-InvariantCsv $summaryProfiles (Join-Path $summaryRoot 'summary.csv') @('growingClasses')
[IO.File]::WriteAllText((Join-Path $summaryRoot 'summary.json'), (@($summaryProfiles) | ConvertTo-Json -Depth 5), $summaryEncoding)
$cards = @($summaryProfiles | ForEach-Object {
    $profileSummary = $_
    $statusNote = "<p class='status $(Escape-Html $profileSummary.status)'>Profile status: <strong>$(Escape-Html $profileSummary.status)</strong>"
    if ($null -ne $profileSummary.exitCode) { $statusNote += "; process exit code: $(Escape-Html $profileSummary.exitCode)" }
    $statusNote += ". Last sampled completed sends: $(Escape-Html $profileSummary.observedCompleted)"
    if ($null -ne $profileSummary.requestedTotal) { $statusNote += " / $(Escape-Html $profileSummary.requestedTotal) requested (including warm-up)" }
    $statusNote += '.</p>'
    if ($profileSummary.status -ne 'completed') {
        $statusNote += '<p>These are partial measurements. This profile has no verified successful workload completion.</p>'
    }
    if ($profileSummary.failure) { $statusNote += "<p>Recorded failure: $(Escape-Html $profileSummary.failure)</p>" }
    $controlNote = if ($profileSummary.positiveControl) { '<p class="control">Positive control: this fixture deliberately retains completed Emails.</p>' } else { '' }
    $closureNote = if ($null -ne $profileSummary.afterCloseHeapBytes) {
        "<p>After resource closure: $(Format-Number ($profileSummary.afterCloseHeapBytes / 1MB)) MiB. This checkpoint is excluded from the workload change.</p>"
    } else { '' }
    $plot = [IO.File]::ReadAllText((Join-Path $summaryRoot "$($profileSummary.profile)/heap.svg"))
    $nativePlot = [IO.File]::ReadAllText((Join-Path $summaryRoot "$($profileSummary.profile)/native-plot.html"))
    $workloadLink = if (Test-Path -LiteralPath (Join-Path $summaryRoot "$($profileSummary.profile)/summary.json")) {
        "<a href='$(Escape-Html $profileSummary.profile)/summary.json'>Workload result</a>"
    } else { 'Verified workload result unavailable' }
    $nativeLink = if (Test-Path -LiteralPath (Join-Path $summaryRoot "$($profileSummary.profile)/native-diff.txt")) {
        "<a href='$(Escape-Html $profileSummary.profile)/native-diff.txt'>JVM memory category changes</a>"
    } else { 'Final JVM memory comparison unavailable' }
    $classTable = ''
    if ($profileSummary.growingClasses.Count -gt 0) {
        $classRows = @($profileSummary.growingClasses | ForEach-Object {
            "<tr><td>$(Escape-Html $_.class)</td><td>$($_.instanceChange)</td><td>$($_.shallowBytesChange)</td></tr>"
        }) -join "`n"
        $classTable = "<h3>Largest class increases</h3><p>Shallow bytes describe the objects themselves; they are not retained graph sizes.</p>" +
            "<table><thead><tr><th>Class</th><th>Instance change</th><th>Shallow-byte change</th></tr></thead><tbody>$classRows</tbody></table>" +
            "<p><a href='$(Escape-Html $profileSummary.profile)/class-growth.csv'>Complete histogram comparison</a></p>"
    }
    @"
<section><h2>$(Escape-Html $profileSummary.profile)</h2>$statusNote$controlNote$plot
<p>Measured heap change: <strong>$(Format-Number ($profileSummary.heapChangeBytes / 1MB)) MiB</strong> between
$(Escape-Html $profileSummary.firstCompleted) and $(Escape-Html $profileSummary.lastCompleted) completed sends. Maximum sampled threads: $($profileSummary.maximumThreads).</p>$closureNote
<p><a href="$(Escape-Html $profileSummary.profile)/samples.csv">Samples</a> · $workloadLink · $nativeLink</p>$nativePlot$classTable</section>
"@
}) -join "`n"
$operatorNote = if ($runManifest.operatorStopReason) { "<p>Operator stop reason: $(Escape-Html $runManifest.operatorStopReason)</p>" } else { '' }
$runFailureNote = if ($runManifest.failure) { "<p>Recorded run failure: $(Escape-Html $runManifest.failure)</p>" } else { '' }
$html = @"
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mail-send memory pressure</title><style>body{max-width:1000px;margin:40px auto;padding:0 22px;font:16px/1.55 system-ui;color:#243447;background:#f8fafc}
h1,h2{line-height:1.2}section{background:white;border:1px solid #dbe3eb;border-radius:10px;padding:20px;margin:22px 0}svg{width:100%;height:auto}
a{color:#086d82}.control,.failed,.partial{color:#963c13}.status{padding:10px;background:#f1f5f9;border-left:3px solid currentColor}
code{background:#e9eff4;padding:2px 5px}table{width:100%;border-collapse:collapse;font-size:13px}
td,th{text-align:left;padding:6px;border-bottom:1px solid #e2e8f0}td:first-child{overflow-wrap:anywhere}</style></head><body>
<h1>Mail-send memory pressure</h1><p>$(Escape-Html (Split-Path $summaryRoot -Leaf))</p>
<p class="status $(Escape-Html $runStatus)">Run status: <strong>$(Escape-Html $runStatus)</strong></p>$operatorNote$runFailureNote
<p>Each graph shows Java heap at drained checkpoints after collection, with loaded samples shown separately. Compare successive equal-work intervals after warm-up;
class loading and temporarily live work can move the baseline. These graphs do not establish the absence of leaks or measure throughput.</p>
<p><code>native-baseline.txt</code> and <code>native-diff.txt</code> cover categories tracked by the JVM. Their total committed memory includes
the Java Heap category. The separate committed-memory graph therefore overlaps Java heap; it does not measure native-only usage or process working set.
Histograms and optional JFR recordings provide object/class evidence.</p>
<p><a href="manifest.json">Environment and source manifest</a> · <a href="summary.csv">Profile summary CSV</a> · <a href="all-samples.csv">All samples CSV</a></p>
$cards</body></html>
"@
[IO.File]::WriteAllText((Join-Path $summaryRoot 'report.html'), $html, $summaryEncoding)
Write-Host "Memory summary: $(Join-Path $summaryRoot 'report.html')"
