<#
.SYNOPSIS
Parses rendered package scripts and builds the Chocolatey source package.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $SourceDirectory,

    [Parameter(Mandatory = $true)]
    [string] $PackageOutputDirectory,

    [switch] $SkipHomebrew,

    [switch] $SkipChocolatey
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedSourceDirectory = [System.IO.Path]::GetFullPath($SourceDirectory)
$resolvedPackageOutputDirectory = [System.IO.Path]::GetFullPath($PackageOutputDirectory)

if (-not $SkipHomebrew) {
    $formula = Join-Path $resolvedSourceDirectory 'homebrew/sjm.rb'
    if (-not (Test-Path -LiteralPath $formula -PathType Leaf)) {
        throw "Homebrew formula '$formula' does not exist."
    }
    if ($null -eq (Get-Command ruby -ErrorAction SilentlyContinue)) {
        throw 'Ruby is required to validate the Homebrew formula.'
    }
    & ruby -c $formula
    if ($LASTEXITCODE -ne 0) {
        throw "Ruby syntax validation failed for '$formula'."
    }
}

if (-not $SkipChocolatey) {
    $chocolateyDirectory = Join-Path $resolvedSourceDirectory 'chocolatey'
    $nuspec = Join-Path $chocolateyDirectory 'sjm.nuspec'
    if (-not (Test-Path -LiteralPath $nuspec -PathType Leaf)) {
        throw "Chocolatey package definition '$nuspec' does not exist."
    }

    $powerShellFiles = @(Get-ChildItem -LiteralPath (Join-Path $chocolateyDirectory 'tools') -Filter '*.ps1' -File)
    if ($powerShellFiles.Count -eq 0) {
        throw "No Chocolatey PowerShell files were found under '$chocolateyDirectory'."
    }
    foreach ($powerShellFile in $powerShellFiles) {
        $tokens = $null
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile(
            $powerShellFile.FullName,
            [ref] $tokens,
            [ref] $parseErrors
        ) | Out-Null
        if ($parseErrors.Count -ne 0) {
            $messages = $parseErrors | ForEach-Object { $_.Message }
            throw "PowerShell syntax validation failed for '$($powerShellFile.FullName)': $($messages -join '; ')"
        }
    }

    if ($null -eq (Get-Command choco -ErrorAction SilentlyContinue)) {
        throw 'Chocolatey is required to build the package.'
    }
    New-Item -ItemType Directory -Path $resolvedPackageOutputDirectory -Force | Out-Null
    Push-Location $chocolateyDirectory
    try {
        & choco pack $nuspec --outputdirectory $resolvedPackageOutputDirectory --limit-output
        if ($LASTEXITCODE -ne 0) {
            throw "Chocolatey package validation failed for '$nuspec'."
        }
    }
    finally {
        Pop-Location
    }
}

Write-Output "Validated package sources in '$resolvedSourceDirectory'."
