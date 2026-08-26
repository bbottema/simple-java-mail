<#
.SYNOPSIS
Validates and idempotently publishes one SJM release to Chocolatey and Homebrew.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')]
    [string] $Version,

    [Parameter(Mandatory = $true)]
    [string] $WorkingDirectory,

    [string] $TapRepository = 'simple-java-mail/homebrew-tap'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock] $Command,

        [Parameter(Mandatory = $true)]
        [string] $FailureMessage
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit $LASTEXITCODE)."
    }
}

function Test-ChocolateyVersionExists {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageVersion
    )

    $packageUrl = "https://community.chocolatey.org/api/v2/Packages(Id='sjm',Version='$PackageVersion')"
    try {
        Invoke-WebRequest -Method Head -Uri $packageUrl | Out-Null
        return $true
    }
    catch {
        $statusCode = $null
        if ($null -ne $_.Exception.Response) {
            $statusCode = [int] $_.Exception.Response.StatusCode
        }
        if ($statusCode -eq 404) {
            return $false
        }
        throw "Could not check Chocolatey for sjm '$PackageVersion': $($_.Exception.Message)"
    }
}

foreach ($variableName in @('HOMEBREW_TAP_TOKEN', 'CHOCO_API_KEY')) {
    $value = [Environment]::GetEnvironmentVariable($variableName)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "CircleCI context variable '$variableName' is required for publication."
    }
}
foreach ($command in @('choco', 'ruby', 'git')) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "'$command' is required for package publication."
    }
}

$resolvedWorkingDirectory = [System.IO.Path]::GetFullPath($WorkingDirectory)
$sourceDirectory = Join-Path $resolvedWorkingDirectory 'sources'
$packageDirectory = Join-Path $resolvedWorkingDirectory 'packages'
$tapDirectory = Join-Path $resolvedWorkingDirectory 'homebrew-tap'

& (Join-Path $PSScriptRoot 'Prepare-PackageRelease.ps1') -Version $Version -OutputDirectory $sourceDirectory
& (Join-Path $PSScriptRoot 'Test-PackageSources.ps1') -SourceDirectory $sourceDirectory -PackageOutputDirectory $packageDirectory

$nupkg = Get-ChildItem -LiteralPath $packageDirectory -Filter "sjm.$Version.nupkg" -File
if (@($nupkg).Count -ne 1) {
    throw "Expected exactly one Chocolatey package named 'sjm.$Version.nupkg'."
}

$tokenBytes = [Text.Encoding]::ASCII.GetBytes("x-access-token:$env:HOMEBREW_TAP_TOKEN")
$authorization = [Convert]::ToBase64String($tokenBytes)
$gitAuthorization = "http.extraHeader=Authorization: Basic $authorization"
Invoke-CheckedCommand -FailureMessage "Could not clone '$TapRepository'" -Command {
    & git -c $gitAuthorization clone "https://github.com/$TapRepository.git" $tapDirectory
}

$formulaDirectory = Join-Path $tapDirectory 'Formula'
New-Item -ItemType Directory -Path $formulaDirectory -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceDirectory 'homebrew/sjm.rb') -Destination (Join-Path $formulaDirectory 'sjm.rb') -Force
Push-Location $tapDirectory
try {
    Invoke-CheckedCommand -FailureMessage 'Could not stage the Homebrew formula' -Command { & git add -- Formula/sjm.rb }
    & git diff --cached --quiet
    $formulaChanged = $LASTEXITCODE -eq 1
    if ($LASTEXITCODE -notin 0, 1) {
        throw 'Could not compare the rendered Homebrew formula with the tap.'
    }
    if ($formulaChanged) {
        Invoke-CheckedCommand -FailureMessage 'Could not configure the release commit author' -Command { & git config user.name 'Simple Java Mail release automation' }
        Invoke-CheckedCommand -FailureMessage 'Could not configure the release commit email' -Command { & git config user.email 'release-automation@simplejavamail.org' }
        Invoke-CheckedCommand -FailureMessage 'Could not commit the Homebrew formula' -Command { & git commit -m "Publish sjm $Version" }
    }
    Invoke-CheckedCommand -FailureMessage 'The Homebrew tap token cannot push to main' -Command {
        & git -c $gitAuthorization push --dry-run origin HEAD:main
    }
}
finally {
    Pop-Location
}

if (Test-ChocolateyVersionExists -PackageVersion $Version) {
    Write-Output "Chocolatey already contains sjm '$Version'; skipping that push."
}
else {
    $pushOutput = & choco push $nupkg.FullName --source https://push.chocolatey.org/ --api-key $env:CHOCO_API_KEY --limit-output 2>&1
    if ($LASTEXITCODE -ne 0) {
        $pushText = $pushOutput -join [Environment]::NewLine
        if ($pushText -match '(?i)(already exists|409[^\r\n]*conflict)') {
            Write-Output "Chocolatey reports that sjm '$Version' already exists; continuing the rerun."
        }
        else {
            throw "Chocolatey rejected sjm '$Version' (exit $LASTEXITCODE): $pushText"
        }
    }
    else {
        $pushOutput | Write-Output
    }
}

if ($formulaChanged) {
    Push-Location $tapDirectory
    try {
        Invoke-CheckedCommand -FailureMessage 'Could not push the Homebrew formula to main' -Command {
            & git -c $gitAuthorization push origin HEAD:main
        }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Output "The Homebrew tap already contains the sjm '$Version' formula; skipping that push."
}

Write-Output "Package publication completed for SJM '$Version'."
