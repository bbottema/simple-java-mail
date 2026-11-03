<#
.SYNOPSIS
Build the pinned merged Angus sources privately, then optionally run the SJM development lane.
.DESCRIPTION
No upstream source edits or public snapshot repository are used. Each build cleans the full
Angus provider reactor, including the source artifacts consumed by the aggregate provider.
Use -ReuseBuild to validate the recorded artifact hashes without rebuilding Angus.
#>
[CmdletBinding()]
param(
    [string] $WorkDirectory,
    [string] $RepositoryPath,
    [string] $SourceDirectory,
    [string] $AngusGitRepository,
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $MavenCommand = 'mvn',
    [switch] $ReuseBuild,
    [string[]] $MavenArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$revision = '74a8e1be20011123d5930ef1d112ad6d10490614'
$tree = '5523dd4984c6e2aeb59e90a7d64d45962a64feca'
$angusVersion = '2.1.0-SNAPSHOT'
$workspace = Split-Path -Parent $PSScriptRoot
$buildProperties = [ordered]@{
    'mail-api.version' = '2.1.5'
    'activation-api.version' = '2.1.4'
    'angus.activation.version' = '2.0.3'
    'mail.spec.version' = '2.1'
}

function Get-AbsolutePath([string] $Path) {
    if (-not [System.IO.Path]::IsPathRooted($Path)) { $Path = Join-Path $workspace $Path }
    return [System.IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\', '/'))
}

function Invoke-Git([string[]] $Arguments) {
    $output = & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git failed: $($Arguments -join ' ')" }
    return $output
}

function Invoke-PrivateMaven([string] $Directory, [string[]] $Arguments, [string] $LogPath) {
    Write-Host "Maven log: $LogPath"
    Push-Location -LiteralPath $Directory
    try {
        & $MavenCommand "-Dmaven.repo.local=$RepositoryPath" @Arguments 2>&1 | Tee-Object -FilePath $LogPath
        if ($LASTEXITCODE -ne 0) { throw "Maven failed; see $LogPath" }
    } finally { Pop-Location }
}

function Assert-ExportedSources([string] $ArchivePath, [string] $Directory) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    $expectedFiles = @{}
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $relative = $entry.FullName.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
            $file = Join-Path $Directory $relative
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing pinned source: $file" }
            $expectedFiles[$relative] = $true
            $stream = $entry.Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $expectedHash = [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
            finally { $sha.Dispose(); $stream.Dispose() }
            if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $expectedHash) {
                throw "Source differs from pinned commit; preserve it and choose a new SourceDirectory: $file"
            }
        }
        foreach ($file in Get-ChildItem -LiteralPath $Directory -Recurse -File -Force) {
            $relative = $file.FullName.Substring($Directory.Length + 1)
            if ($relative -match '(^|[\\/])target([\\/]|$)') { continue }
            if (-not $expectedFiles.ContainsKey($relative)) { throw "Unexpected file in pinned sources: $($file.FullName)" }
        }
    } finally { $archive.Dispose() }
}

if (-not $WorkDirectory) { $WorkDirectory = Join-Path $workspace '.local/socks-direct-922-20261103/merged-angus' }
$WorkDirectory = Get-AbsolutePath $WorkDirectory
if (-not $RepositoryPath) { $RepositoryPath = Join-Path $WorkDirectory 'm2' }
$RepositoryPath = Get-AbsolutePath $RepositoryPath
if ($RepositoryPath -eq (Get-AbsolutePath (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository'))) {
    throw 'Select a private RepositoryPath; the normal user Maven cache is not a development-lane cache.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Pass -JavaHome with a JDK 11 or newer installation, or set JAVA_HOME.'
}
foreach ($argument in $MavenArguments) {
    if ($argument -match '(?i)^(-D(maven\.repo\.local|angus-mail\.version|sjm\.test\.authenticatedSocksRoute|performRelease)(=|$)|deploy$|([^:]+:)*(deploy|release|publish)(:|$))') {
        throw "The development runner does not accept this dependency, repository or publishing override: $argument"
    }
}
New-Item -ItemType Directory -Path $WorkDirectory -Force | Out-Null
$markerDirectory = Join-Path $RepositoryPath '.sjm-merged-angus'
$markerPath = Join-Path $markerDirectory "$revision.json"
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
try {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
    if (-not $ReuseBuild) {
        if (-not $SourceDirectory) { $SourceDirectory = Join-Path $WorkDirectory "source-$revision" }
        $SourceDirectory = Get-AbsolutePath $SourceDirectory
        if (-not $AngusGitRepository) {
            $AngusGitRepository = Join-Path $WorkDirectory 'upstream.git'
            if (-not (Test-Path -LiteralPath $AngusGitRepository)) {
                Invoke-Git @('init', '--bare', $AngusGitRepository) | Out-Null
            }
        }
        $AngusGitRepository = Get-AbsolutePath $AngusGitRepository
        Invoke-Git @('-C', $AngusGitRepository, 'fetch', '--no-tags', 'https://github.com/eclipse-ee4j/angus-mail.git', $revision) | Out-Null
        if ((Invoke-Git @('-C', $AngusGitRepository, 'rev-parse', "$revision^{tree}")) -ne $tree) { throw 'Unexpected Angus source tree.' }
        $archivePath = Join-Path $WorkDirectory "source-$revision.zip"
        Invoke-Git @('-C', $AngusGitRepository, 'archive', '--format=zip', "--output=$archivePath", $revision) | Out-Null
        if (-not (Test-Path -LiteralPath $SourceDirectory)) {
            Expand-Archive -LiteralPath $archivePath -DestinationPath $SourceDirectory
        }
        Assert-ExportedSources $archivePath $SourceDirectory
        $upstreamArguments = @('-B', '-ntp', '-pl', 'providers/angus-mail', '-am', 'clean', 'install', '-DskipTests=true', '-Dmaven.javadoc.skip=true')
        foreach ($property in $buildProperties.GetEnumerator()) { $upstreamArguments += "-D$($property.Key)=$($property.Value)" }
        $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
        $buildLog = Join-Path $WorkDirectory "angus-build-$stamp.log"
        Invoke-PrivateMaven $SourceDirectory $upstreamArguments $buildLog
        $artifacts = @()
        foreach ($artifactId in @('all', 'angus-core', 'providers', 'imap', 'smtp', 'pop3', 'logging-mailhandler', 'angus-mail')) {
            $artifactDirectory = Join-Path $RepositoryPath "org/eclipse/angus/$artifactId/$angusVersion"
            foreach ($artifact in Get-ChildItem -LiteralPath $artifactDirectory -File) {
                if ($artifact.Extension -notin @('.jar', '.pom', '.zip')) { continue }
                $artifacts += [ordered]@{
                    path = $artifact.FullName.Substring($RepositoryPath.Length + 1)
                    sha256 = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash
                }
            }
        }
        $mainJar = "org/eclipse/angus/angus-mail/$angusVersion/angus-mail-$angusVersion.jar"
        if (-not (Test-Path -LiteralPath (Join-Path $RepositoryPath $mainJar))) { throw 'Merged Angus JAR was not installed.' }
        $provenance = [ordered]@{
            schemaVersion = 1
            upstream = 'https://github.com/eclipse-ee4j/angus-mail.git'
            revision = $revision
            tree = $tree
            version = $angusVersion
            buildProperties = $buildProperties
            archiveSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
            sourceDirectory = $SourceDirectory
            repository = $RepositoryPath
            createdUtc = [DateTime]::UtcNow.ToString('o')
            java = @(& (Join-Path $JavaHome 'bin/java.exe') -version 2>&1 | ForEach-Object { "$_" })
            mavenCommand = $MavenCommand
            mavenArguments = @("-Dmaven.repo.local=$RepositoryPath") + $upstreamArguments
            buildLog = $buildLog
            artifacts = $artifacts
        }
        New-Item -ItemType Directory -Path $markerDirectory -Force | Out-Null
        $provenance | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $markerPath -Encoding UTF8
    }
    if (-not (Test-Path -LiteralPath $markerPath)) { throw 'Build Angus first without -ReuseBuild.' }
    $record = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
    if ($record.revision -ne $revision -or $record.tree -ne $tree -or $record.version -ne $angusVersion -or $record.repository -ne $RepositoryPath) {
        throw 'Private cache provenance does not match this development lane.'
    }
    foreach ($property in $buildProperties.GetEnumerator()) {
        if ($record.buildProperties.($property.Key) -ne $property.Value) { throw "Build property mismatch: $($property.Key)" }
    }
    if (@($record.artifacts).Count -eq 0) { throw 'Private cache provenance contains no artifacts.' }
    foreach ($artifact in $record.artifacts) {
        $file = Join-Path $RepositoryPath $artifact.path
        if (-not (Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256) {
            throw "Private Angus artifact changed; rebuild the pinned reactor: $file"
        }
    }
    Write-Host "Merged Angus ready: $revision; private repository: $RepositoryPath"
    if ($MavenArguments.Count -gt 0) {
        $sjmLog = Join-Path $WorkDirectory "sjm-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')).log"
        Invoke-PrivateMaven $workspace (@('-B', '-ntp', '-Pangus-merged-development', '-Dlicense.skip=true') + $MavenArguments) $sjmLog
    }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
