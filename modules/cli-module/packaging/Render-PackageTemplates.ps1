<#
.SYNOPSIS
Creates publishable Homebrew and Chocolatey sources for one SJM CLI release.

.DESCRIPTION
Validates the public archive coordinates, replaces every release token in the
maintained templates, and writes the package-manager-specific file layout.
It deliberately performs no publication and never downloads or starts SJM.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [Parameter(Mandatory = $true)]
    [string] $ReleaseArchiveUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string] $ReleaseArchiveSha256,

    [Parameter(Mandatory = $true)]
    [string] $WindowsArchiveUrl,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string] $WindowsArchiveSha256,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string] $PackageSourceRevision,

    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-HttpsUrl {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,

        [Parameter(Mandatory = $true)]
        [string] $Value
    )

    $parsedUrl = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $parsedUrl) -or $parsedUrl.Scheme -ne 'https') {
        throw "$Name must be an absolute HTTPS URL."
    }
}

function Write-RenderedTemplate {
    param(
        [Parameter(Mandatory = $true)]
        [string] $SourcePath,

        [Parameter(Mandatory = $true)]
        [string] $DestinationPath,

        [Parameter(Mandatory = $true)]
        [hashtable] $Tokens
    )

    $content = Get-Content -Raw -LiteralPath $SourcePath
    foreach ($tokenName in $Tokens.Keys) {
        $content = $content.Replace("@$tokenName@", $Tokens[$tokenName])
    }

    $unresolvedToken = [regex]::Match($content, '@[A-Z0-9_]+@')
    if ($unresolvedToken.Success) {
        throw "Template '$SourcePath' still contains unresolved token '$($unresolvedToken.Value)'."
    }

    $destinationParent = Split-Path -Parent $DestinationPath
    New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null
    $utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($DestinationPath, $content, $utf8WithoutBom)
}

Assert-HttpsUrl -Name 'ReleaseArchiveUrl' -Value $ReleaseArchiveUrl
Assert-HttpsUrl -Name 'WindowsArchiveUrl' -Value $WindowsArchiveUrl

$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$tokens = @{
    VERSION = $Version
    RELEASE_ARCHIVE_URL = $ReleaseArchiveUrl
    RELEASE_ARCHIVE_SHA256 = $ReleaseArchiveSha256.ToLowerInvariant()
    WINDOWS_ARCHIVE_URL = $WindowsArchiveUrl
    WINDOWS_ARCHIVE_SHA256 = $WindowsArchiveSha256.ToLowerInvariant()
    PACKAGE_SOURCE_REVISION = $PackageSourceRevision.ToLowerInvariant()
}

$templates = @(
    @{
        Source = Join-Path $PSScriptRoot 'homebrew/sjm.rb.template'
        Destination = Join-Path $resolvedOutputDirectory 'homebrew/sjm.rb'
    },
    @{
        Source = Join-Path $PSScriptRoot 'chocolatey/sjm.nuspec.template'
        Destination = Join-Path $resolvedOutputDirectory 'chocolatey/sjm.nuspec'
    },
    @{
        Source = Join-Path $PSScriptRoot 'chocolatey/tools/chocolateyinstall.ps1.template'
        Destination = Join-Path $resolvedOutputDirectory 'chocolatey/tools/chocolateyinstall.ps1'
    },
    @{
        Source = Join-Path $PSScriptRoot 'chocolatey/tools/chocolateyBeforeModify.ps1.template'
        Destination = Join-Path $resolvedOutputDirectory 'chocolatey/tools/chocolateyBeforeModify.ps1'
    },
    @{
        Source = Join-Path $PSScriptRoot 'chocolatey/tools/chocolateyuninstall.ps1.template'
        Destination = Join-Path $resolvedOutputDirectory 'chocolatey/tools/chocolateyuninstall.ps1'
    }
)

foreach ($template in $templates) {
    Write-RenderedTemplate -SourcePath $template.Source -DestinationPath $template.Destination -Tokens $tokens
}

Write-Output "Rendered package sources to '$resolvedOutputDirectory'."
