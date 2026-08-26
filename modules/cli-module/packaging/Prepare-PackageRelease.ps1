<#
.SYNOPSIS
Downloads and prepares the package-manager sources for a published SJM release.

.DESCRIPTION
Requires a public final GitHub release, the exact standalone tar and ZIP assets,
matching release and Git tags, and a checkout of the tag's dereferenced commit.
The assets are downloaded and checksum-verified before the Homebrew formula and
Chocolatey source package are rendered. This script never publishes anything.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,

    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
    [string] $Repository = 'bbottema/simple-java-mail'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine).Trim()
}

function Get-RequiredReleaseAsset {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Release,

        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    $matches = @($Release.assets | Where-Object { $_.name -eq $Name })
    if ($matches.Count -ne 1) {
        throw "Release '$Version' must contain exactly one asset named '$Name'; found $($matches.Count)."
    }
    return $matches[0]
}

function Assert-AssetDigest {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Asset,

        [Parameter(Mandatory = $true)]
        [string] $CalculatedSha256
    )

    if ($Asset.PSObject.Properties.Name -contains 'digest' -and -not [string]::IsNullOrWhiteSpace([string] $Asset.digest)) {
        $expectedDigest = "sha256:$CalculatedSha256"
        if (-not $expectedDigest.Equals([string] $Asset.digest, [StringComparison]::OrdinalIgnoreCase)) {
            throw "GitHub reports digest '$($Asset.digest)' for '$($Asset.name)', but the downloaded file is '$expectedDigest'."
        }
    }
}

function Resolve-GitHubTagCommit {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RepositoryName,

        [Parameter(Mandatory = $true)]
        [string] $TagName,

        [Parameter(Mandatory = $true)]
        [hashtable] $RequestHeaders
    )

    try {
        $reference = Invoke-RestMethod -Method Get -Uri "https://api.github.com/repos/$RepositoryName/git/ref/tags/$TagName" -Headers $RequestHeaders
    }
    catch {
        throw "Could not resolve GitHub tag '$TagName' in '$RepositoryName': $($_.Exception.Message)"
    }

    $target = $reference.object
    for ($depth = 0; $depth -lt 5; $depth++) {
        if ($target.type -eq 'commit') {
            if ($target.sha -notmatch '^[0-9a-fA-F]{40}$') {
                throw "GitHub tag '$TagName' resolved to invalid commit '$($target.sha)'."
            }
            return ([string] $target.sha).ToLowerInvariant()
        }
        if ($target.type -ne 'tag' -or $target.sha -notmatch '^[0-9a-fA-F]{40}$') {
            throw "GitHub tag '$TagName' resolved to unsupported object type '$($target.type)'."
        }
        $annotatedTag = Invoke-RestMethod -Method Get -Uri "https://api.github.com/repos/$RepositoryName/git/tags/$($target.sha)" -Headers $RequestHeaders
        $target = $annotatedTag.object
    }
    throw "GitHub tag '$TagName' contains too many nested tag objects."
}

$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$downloadDirectory = Join-Path $resolvedOutputDirectory 'downloads'
New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null

$headers = @{
    Accept = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent' = 'simple-java-mail-package-release'
}
if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) {
    $headers.Authorization = "Bearer $env:GITHUB_TOKEN"
}

$releaseApiUrl = "https://api.github.com/repos/$Repository/releases/tags/$Version"
try {
    $release = Invoke-RestMethod -Method Get -Uri $releaseApiUrl -Headers $headers
}
catch {
    throw "Could not load public GitHub release '$Version' from '$Repository': $($_.Exception.Message)"
}

if ($release.tag_name -ne $Version) {
    throw "GitHub returned release tag '$($release.tag_name)' while '$Version' was requested."
}
if ([bool] $release.draft -or [bool] $release.prerelease) {
    throw "GitHub release '$Version' must be public, non-draft, and non-prerelease."
}

$tarName = "cli-module-$Version-standalone-cli.tar"
$zipName = "cli-module-$Version-standalone-cli.zip"
$tarAsset = Get-RequiredReleaseAsset -Release $release -Name $tarName
$zipAsset = Get-RequiredReleaseAsset -Release $release -Name $zipName
$expectedReleaseBaseUrl = "https://github.com/$Repository/releases/download/$Version"

foreach ($asset in @($tarAsset, $zipAsset)) {
    $expectedUrl = "$expectedReleaseBaseUrl/$($asset.name)"
    if ($asset.browser_download_url -ne $expectedUrl) {
        throw "Asset '$($asset.name)' has URL '$($asset.browser_download_url)'; expected '$expectedUrl'."
    }
}

$githubTagCommit = Resolve-GitHubTagCommit -RepositoryName $Repository -TagName $Version -RequestHeaders $headers
$tagCommit = Invoke-Git -Arguments @('rev-parse', '--verify', "$Version^{commit}")
$headCommit = Invoke-Git -Arguments @('rev-parse', '--verify', 'HEAD')
if ($tagCommit -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Tag '$Version' did not resolve to a full commit hash."
}
if ($tagCommit -ne $githubTagCommit) {
    throw "Local tag '$Version' resolves to '$tagCommit', but GitHub resolves it to '$githubTagCommit'."
}
if ($headCommit -ne $tagCommit) {
    throw "Checkout commit '$headCommit' does not match tag '$Version' at '$tagCommit'."
}
$repositoryRoot = Invoke-Git -Arguments @('-C', $PSScriptRoot, 'rev-parse', '--show-toplevel')
$packagingStatus = Invoke-Git -Arguments @('-C', $repositoryRoot, 'status', '--porcelain', '--untracked-files=all', '--', 'modules/cli-module/packaging')
if (-not [string]::IsNullOrWhiteSpace($packagingStatus)) {
    throw "The packaging sources do not exactly match tag '$Version':$([Environment]::NewLine)$packagingStatus"
}

$tarPath = Join-Path $downloadDirectory $tarName
$zipPath = Join-Path $downloadDirectory $zipName
Invoke-WebRequest -Uri $tarAsset.browser_download_url -Headers $headers -OutFile $tarPath
Invoke-WebRequest -Uri $zipAsset.browser_download_url -Headers $headers -OutFile $zipPath

$tarSha256 = (Get-FileHash -LiteralPath $tarPath -Algorithm SHA256).Hash.ToLowerInvariant()
$zipSha256 = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-AssetDigest -Asset $tarAsset -CalculatedSha256 $tarSha256
Assert-AssetDigest -Asset $zipAsset -CalculatedSha256 $zipSha256

$renderer = Join-Path $PSScriptRoot 'Render-PackageTemplates.ps1'
& $renderer `
    -Version $Version `
    -ReleaseArchiveUrl $tarAsset.browser_download_url `
    -ReleaseArchiveSha256 $tarSha256 `
    -WindowsArchiveUrl $zipAsset.browser_download_url `
    -WindowsArchiveSha256 $zipSha256 `
    -PackageSourceRevision $githubTagCommit `
    -OutputDirectory $resolvedOutputDirectory

$metadata = [ordered] @{
    version = $Version
    repository = $Repository
    tagCommit = $githubTagCommit
    tar = [ordered] @{
        name = $tarName
        url = [string] $tarAsset.browser_download_url
        sha256 = $tarSha256
        path = $tarPath
    }
    zip = [ordered] @{
        name = $zipName
        url = [string] $zipAsset.browser_download_url
        sha256 = $zipSha256
        path = $zipPath
    }
}
$metadataPath = Join-Path $resolvedOutputDirectory 'package-release.json'
$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json -Depth 4), $utf8WithoutBom)

Write-Output "Prepared package release '$Version' from commit '$githubTagCommit' in '$resolvedOutputDirectory'."
