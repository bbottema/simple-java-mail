<#
.SYNOPSIS
Runs the one-time Homebrew install and uninstall smoke test for a public release.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [Parameter(Mandatory = $true)]
    [string] $WorkingDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

foreach ($command in @('brew', 'ruby', 'git')) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "'$command' is required for the Homebrew smoke test."
    }
}

$resolvedWorkingDirectory = [System.IO.Path]::GetFullPath($WorkingDirectory)
$sourceDirectory = Join-Path $resolvedWorkingDirectory 'sources'
$daemonStateDirectory = Join-Path $resolvedWorkingDirectory 'daemon-state'
$tapName = 'simple-java-mail/sjm-smoke'

& (Join-Path $PSScriptRoot 'Prepare-PackageRelease.ps1') -Version $Version -OutputDirectory $sourceDirectory
& (Join-Path $PSScriptRoot 'Test-PackageSources.ps1') `
    -SourceDirectory $sourceDirectory `
    -PackageOutputDirectory (Join-Path $resolvedWorkingDirectory 'packages') `
    -SkipChocolatey

$tapRepository = $null
$installed = $false
try {
    & brew tap-new $tapName
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create local Homebrew tap '$tapName'."
    }
    $tapRepository = ((& brew --repository $tapName) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($tapRepository)) {
        throw "Could not resolve local Homebrew tap '$tapName'."
    }

    $formulaDirectory = Join-Path $tapRepository 'Formula'
    New-Item -ItemType Directory -Path $formulaDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceDirectory 'homebrew/sjm.rb') -Destination (Join-Path $formulaDirectory 'sjm.rb') -Force

    & brew install --build-from-source "$tapName/sjm"
    if ($LASTEXITCODE -ne 0) {
        throw 'Homebrew installation failed.'
    }
    $installed = $true

    $env:SJM_DAEMON_STATE_DIR = $daemonStateDirectory
    & sjm daemon status | Out-Null
    if ($LASTEXITCODE -ne 10) {
        throw "Package installation unexpectedly left the default daemon running (status exit $LASTEXITCODE)."
    }

    & sjm daemon --help
    if ($LASTEXITCODE -ne 0) {
        throw 'The installed sjm command did not provide daemon help.'
    }
}
finally {
    if ($installed) {
        & brew uninstall --force sjm
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'Homebrew could not cleanly uninstall the smoke-test formula.'
        }
    }
    if ($null -ne $tapRepository) {
        & brew untap --force $tapName
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Homebrew could not remove local smoke-test tap '$tapName'."
        }
    }
}

Write-Output "Homebrew smoke test passed for SJM '$Version'."
