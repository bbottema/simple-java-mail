<#
.SYNOPSIS
Runs the one-time Chocolatey install, running-daemon upgrade, and uninstall smoke test.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')]
    [string] $Version,

    [Parameter(Mandatory = $true)]
    [string] $WorkingDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($null -eq (Get-Command choco -ErrorAction SilentlyContinue)) {
    throw 'Chocolatey is required for the Chocolatey smoke test.'
}
if ($null -ne (Get-Command sjm -ErrorAction SilentlyContinue)) {
    throw 'The Chocolatey smoke test requires a clean machine without an existing sjm command.'
}

$resolvedWorkingDirectory = [System.IO.Path]::GetFullPath($WorkingDirectory)
$releaseSources = Join-Path $resolvedWorkingDirectory 'release-sources'
$testSources = Join-Path $resolvedWorkingDirectory 'test-sources'
$packages = Join-Path $resolvedWorkingDirectory 'packages'
$daemonStateDirectory = Join-Path $resolvedWorkingDirectory 'daemon-state'
$testVersion = "$Version-test1"

& (Join-Path $PSScriptRoot 'Prepare-PackageRelease.ps1') -Version $Version -OutputDirectory $releaseSources
$metadata = Get-Content -Raw -LiteralPath (Join-Path $releaseSources 'package-release.json') | ConvertFrom-Json
& (Join-Path $PSScriptRoot 'Render-PackageTemplates.ps1') `
    -Version $testVersion `
    -ReleaseArchiveUrl $metadata.tar.url `
    -ReleaseArchiveSha256 $metadata.tar.sha256 `
    -WindowsArchiveUrl $metadata.zip.url `
    -WindowsArchiveSha256 $metadata.zip.sha256 `
    -PackageSourceRevision $metadata.tagCommit `
    -OutputDirectory $testSources

& (Join-Path $PSScriptRoot 'Test-PackageSources.ps1') -SourceDirectory $testSources -PackageOutputDirectory $packages
& (Join-Path $PSScriptRoot 'Test-PackageSources.ps1') -SourceDirectory $releaseSources -PackageOutputDirectory $packages

$sources = "$packages;https://community.chocolatey.org/api/v2/"
$installed = $false
try {
    & choco install sjm --version $testVersion --pre --source $sources --yes --no-progress --limit-output
    if ($LASTEXITCODE -ne 0) {
        throw "Chocolatey could not install local package '$testVersion'."
    }
    $installed = $true

    $env:SJM_DAEMON_STATE_DIR = $daemonStateDirectory
    & sjm daemon status | Out-Null
    if ($LASTEXITCODE -ne 10) {
        throw "Package installation unexpectedly left the default daemon running (status exit $LASTEXITCODE)."
    }

    & sjm daemon start
    if ($LASTEXITCODE -ne 0) {
        throw 'The default daemon did not start before the upgrade test.'
    }

    & choco upgrade sjm --version $Version --source $sources --yes --no-progress --limit-output
    if ($LASTEXITCODE -ne 0) {
        throw "Chocolatey could not upgrade sjm to '$Version'."
    }

    $versionOutput = (& sjm --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch [regex]::Escape($Version)) {
        throw "The upgraded sjm command did not report version '$Version': '$versionOutput'."
    }

    & sjm daemon status | Out-Null
    if ($LASTEXITCODE -ne 10) {
        throw "The package upgrade did not stop the default daemon (status exit $LASTEXITCODE)."
    }

    & choco uninstall sjm --version $Version --yes --no-progress --limit-output
    if ($LASTEXITCODE -ne 0) {
        throw "Chocolatey could not uninstall sjm '$Version'."
    }
    $installed = $false
    if ($null -ne (Get-Command sjm -ErrorAction SilentlyContinue)) {
        throw 'Chocolatey uninstall left the sjm command shim on PATH.'
    }
}
finally {
    if ($installed) {
        & choco uninstall sjm --yes --no-progress --limit-output
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'Chocolatey could not cleanly remove the smoke-test package.'
        }
    }
}

Write-Output "Chocolatey smoke test passed for SJM '$Version'."
