param([Parameter(Mandatory = $true)][string]$RunDirectory)

$ErrorActionPreference = 'Stop'
$runRoot = (Resolve-Path -LiteralPath $RunDirectory).Path
$environment = Get-Content -Raw -LiteralPath (Join-Path $runRoot 'environment.json') | ConvertFrom-Json
if ($environment.profile) { throw 'Use JFR summaries for profiled runs; do not mix their timings into the unprofiled comparison.' }

function Median([double[]]$Values) {
    $ordered = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($ordered.Count / 2)
    if ($ordered.Count % 2 -eq 1) { return $ordered[$middle] }
    return ($ordered[$middle - 1] + $ordered[$middle]) / 2
}

$rows = @(Get-ChildItem -LiteralPath $runRoot -Directory -Filter 'fork-*' | ForEach-Object {
    $fork = [int]($_.Name -split '-')[1]
    $file = Join-Path $_.FullName 'samples.csv'
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing completed samples: $file" }
    Import-Csv -LiteralPath $file | ForEach-Object {
        $_ | Add-Member -NotePropertyName fork -NotePropertyValue $fork -PassThru
    }
})
if ($rows.Count -eq 0) { throw 'No scenarios were measured.' }
$scenarioNames = @($rows.scenario | Sort-Object -Unique)
foreach ($variant in $environment.variants) {
    foreach ($scenario in $scenarioNames) {
        for ($fork = 1; $fork -le $environment.forks; $fork++) {
            $batch = @($rows | Where-Object { $_.variant -eq $variant -and $_.scenario -eq $scenario -and $_.fork -eq $fork })
            if ($batch.Count -ne $environment.samples -or @($batch.sample | Sort-Object -Unique).Count -ne $environment.samples) {
                throw "Missing or duplicate batch: $variant, $scenario, fork $fork"
            }
        }
    }
}
$rows | Export-Csv -LiteralPath (Join-Path $runRoot 'all-samples.csv') -NoTypeInformation
$summary = @($rows | Group-Object scenario, variant | ForEach-Object {
    $group = $_.Group
    if ($group.Count -ne $environment.forks * $environment.samples) { throw "Incomplete scenario/variant: $($_.Name)" }
    $costs = @($group | ForEach-Object { [double]$_.wall_ms / [int]$_.messages })
    [pscustomobject][ordered]@{
        scenario = $group[0].scenario; variant = $group[0].variant; batches = $group.Count
        median_ms_per_email = (Median $costs)
        min_ms_per_email = ($costs | Measure-Object -Minimum).Minimum
        max_ms_per_email = ($costs | Measure-Object -Maximum).Maximum
        median_sender_cpu_ms_per_email = (Median @($group | ForEach-Object { [double]$_.sender_cpu_ms / [int]$_.messages }))
        median_fixture_cpu_ms_per_email = (Median @($group | ForEach-Object { [double]$_.fixture_cpu_ms / [int]$_.messages }))
        median_allocated_bytes_per_email = (Median @($group | ForEach-Object { [double]$_.sender_allocated_bytes / [int]$_.messages }))
        median_p95_ms = (Median @($group | ForEach-Object { [double]$_.p95_ms }))
        median_queue_ms = (Median @($group | ForEach-Object { [double]$_.mean_queue_ms }))
        median_attachment_opens_per_email = (Median @($group | ForEach-Object { [double]$_.attachment_opens / [int]$_.messages }))
        median_attachment_read_bytes_per_email = (Median @($group | ForEach-Object { [double]$_.attachment_read_bytes / [int]$_.messages }))
    }
})
$summary | Sort-Object scenario, variant | ForEach-Object {
    # CSV must stay machine-readable on hosts whose decimal separator is a comma.
    $record = [ordered]@{}
    foreach ($property in $_.PSObject.Properties) {
        $record[$property.Name] = if ($property.Value -is [IFormattable]) {
            $property.Value.ToString($null, [Globalization.CultureInfo]::InvariantCulture)
        } else { $property.Value }
    }
    [pscustomobject]$record
} | Export-Csv -LiteralPath (Join-Path $runRoot 'summary.csv') -NoTypeInformation
$summary | Sort-Object scenario, variant | Format-Table scenario, variant,
    @{n='ms/email';e={'{0:F3}' -f $_.median_ms_per_email}},
    @{n='CPU ms/email';e={'{0:F3}' -f $_.median_sender_cpu_ms_per_email}},
    @{n='allocated KiB/email';e={'{0:F1}' -f ($_.median_allocated_bytes_per_email / 1024)}} -AutoSize
