param([Parameter(Mandatory = $true)][string]$RunDirectory)

$ErrorActionPreference = 'Stop'
$runRoot = (Resolve-Path -LiteralPath $RunDirectory).Path
$environment = Get-Content -Raw -LiteralPath (Join-Path $runRoot 'environment.json') | ConvertFrom-Json

function Median([double[]]$Values) {
    $ordered = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($ordered.Count / 2)
    if ($ordered.Count % 2 -eq 1) { return $ordered[$middle] }
    return ($ordered[$middle - 1] + $ordered[$middle]) / 2
}

$rows = @(Get-ChildItem -LiteralPath $runRoot -Filter 'fork-*.csv' | ForEach-Object {
    $fork = [int]($_.BaseName -split '-')[1]
    Import-Csv -LiteralPath $_.FullName | ForEach-Object { $_ | Add-Member -NotePropertyName fork -NotePropertyValue $fork -PassThru }
})
if ($rows.Count -eq 0) { throw 'No component measurements found' }
$summary = @($rows | Group-Object payload, operation | ForEach-Object {
    $group = $_.Group
    for ($fork = 1; $fork -le $environment.forks; $fork++) {
        $samples = @($group | Where-Object { $_.fork -eq $fork })
        if ($samples.Count -ne $environment.samples -or @($samples.sample | Sort-Object -Unique).Count -ne $environment.samples) {
            throw "Missing or duplicate component samples: $($_.Name), fork $fork"
        }
    }
    [pscustomobject][ordered]@{
        payload = $group[0].payload; operation = $group[0].operation; samples = $group.Count
        median_wall_ms = (Median @($group | ForEach-Object { [double]$_.wall_ns_per_op / 1000000 }))
        min_wall_ms = ($group | ForEach-Object { [double]$_.wall_ns_per_op / 1000000 } | Measure-Object -Minimum).Minimum
        max_wall_ms = ($group | ForEach-Object { [double]$_.wall_ns_per_op / 1000000 } | Measure-Object -Maximum).Maximum
        median_cpu_ms = (Median @($group | ForEach-Object { [double]$_.cpu_ns_per_op / 1000000 }))
        median_allocated_bytes = (Median @($group | ForEach-Object { [double]$_.allocated_bytes_per_op }))
    }
})
$rows | Export-Csv -LiteralPath (Join-Path $runRoot 'all-samples.csv') -NoTypeInformation
[IO.File]::WriteAllText((Join-Path $runRoot 'summary.json'), ($summary | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$summary | Format-Table -AutoSize
