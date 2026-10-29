#Requires -Version 5.1
<#
.SYNOPSIS
Removes explicitly listed API review scratch directories, logs and completed plans.
.DESCRIPTION
Keep this file in the checkout's root directory.
Every target is checked before any deletion starts. Committed files, merge
conflicts, registered Git worktrees, nested repositories and reparse points block cleanup.
Newly staged scratch files in the allowlist are removed from the index after cleanup.
The script leaves its own file, Git metadata and all other .local directories and plans in place.
External scratch directories are included only by their exact paths listed below.
.EXAMPLE
& 'C:\workstation\simple-java-mail\cleanup-api-review-20261021.ps1' -WhatIf
.EXAMPLE
& 'C:\workstation\simple-java-mail\cleanup-api-review-20261021.ps1'
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\', '/')
$scratchRoot = Join-Path -Path $repositoryRoot -ChildPath '.local'
$pathComparison = [StringComparison]::OrdinalIgnoreCase
$reparseAttribute = [IO.FileAttributes]::ReparsePoint

function Invoke-RepositoryGit {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $gitOutput = @(& git -C $repositoryRoot @Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "Git inspection failed: git $($Arguments -join ' ')"
    }
    return $gitOutput
}

$gitRootOutput = @(Invoke-RepositoryGit -Arguments @('rev-parse', '--show-toplevel'))
if ($gitRootOutput.Count -ne 1) {
    throw 'Could not identify the repository root.'
}
$gitRoot = [IO.Path]::GetFullPath($gitRootOutput[0]).TrimEnd('\', '/')
if (-not $gitRoot.Equals($repositoryRoot, $pathComparison)) {
    throw "The script location does not match the Git repository root: $gitRoot"
}

foreach ($ancestorPath in @($repositoryRoot, $scratchRoot)) {
    $ancestor = Get-Item -LiteralPath $ancestorPath -Force
    if (-not $ancestor.PSIsContainer -or (($ancestor.Attributes -band $reparseAttribute) -ne 0)) {
        throw "Expected an ordinary directory without a reparse point: $ancestorPath"
    }
}

$registeredWorktrees = @(
    foreach ($line in @(Invoke-RepositoryGit -Arguments @('-c', 'core.quotePath=false', 'worktree', 'list', '--porcelain'))) {
        if ($line.StartsWith('worktree ', [StringComparison]::Ordinal)) {
            [IO.Path]::GetFullPath($line.Substring(9)).TrimEnd('\', '/')
        }
    }
)

# Populate these exact allowlists for the next review, then clear them after cleanup.
# Do not use wildcards or target the whole .local directory.
$relativeTargets = @(
    '.local/feature-review-address-import-20261029'
    '.local/feature-review-daemon-control-20261029'
    '.local/feature-review-daemon-address-delivery-20261029'
    '.local/feature-review-daemon-address-site-20261029'
    '.local/feature-review-envelope-validation-20261029'
    '.local/feature-review-pending-socks-20261029'
    '.local/feature-review-followup-delivery-20261029'
    '.local/feature-review-followup-site-20261029'
    '.local/socks-addresses-20261029'
    '.local/socks-connect-20261029'
    '.local/socks-credentials-20261029'
    '.local/socks-docs-20261029'
    '.local/socks-protocol-repairs-20261029'
    '.local/socks-combined-20261029'
    '.local/feature-review-auth-20261029'
    '.local/feature-review-tunnel-timeout-20261029'
    '.local/feature-review-retry-20261029'
    '.local/feature-review-smime-20261029'
    '.local/feature-review-dsn-20261029'
    '.local/feature-review-calendar-20261029'
    '.local/feature-review-config-inputs-20261029'
    '.local/feature-review-site-20261029'
    '.local/feature-review-delivery-20261029'
    '.local/api-review-899-site'
    # '.local/api-review-example'
    '.local/api-review-897-898-20261028'
    # Current six-fix review scratch; managed worktrees must be archived before cleanup.
    '.local/api-review-six-fixes-20261028'
    '.local/878-openpgp-signatures-20261028'
    '.local/877-openpgp-transfer-decoding-20261028'
    '.local/connection-conflict-20261024'
)
$relativeFileTargets = @(
    'docs/plans/914-imported-addresses.md'
    'docs/plans/915-daemon-control-admission.md'
    'docs/plans/review-daemon-address-import-20261029.md'
    'docs/plans/908-typed-delivery-validation.md'
    'docs/plans/909-pending-socks-setup.md'
    'docs/plans/908-909-feature-review-followup.md'
    'docs/plans/910-913-socks-protocol-repairs.md'
    'docs/plans/901-902-socks-auth.md'
    'docs/plans/907-socks-tunnel-timeout.md'
    'docs/plans/900-retry-receipt.md'
    'docs/plans/905-906-smime.md'
    'docs/plans/903-dsn-zone.md'
    'docs/plans/904-calendar-input.md'
    'docs/plans/config-loader-required-inputs-20261029.md'
    'docs/plans/feature-review-20261029.md'
    '.local/899-red.log'
    '.local/899-green.log'
    '.local/899-jdk11.log'
    '.local/899-site-check.log'
    '.local/899-site-build.log'
    '.local/899-cleanup-dryrun.log'
    '.local/899-cleanup-delivery.index'
    'docs/plans/899-imported-filenames.md'
    # '.local/api-review-example.log'
    # 'docs/plans/example-completed-plan.md'
    'docs/plans/897-898-image-attributes-cli-guide.md'
    'docs/plans/867-connection-conflict-policy.md'
)
# External directories require both the exact absolute path and its direct parent.
$externalDirectoryTargets = @(
    @{
        Path = 'C:\workstation\sjm-socks-final-20261029'
        Parent = 'C:\workstation'
    }
    @{
        Path = 'C:\Users\Benny Bottema\AppData\Local\Temp\surefire3238261096306854804'
        Parent = 'C:\Users\Benny Bottema\AppData\Local\Temp'
    }
    # #895/#896 temporary checkouts remain protected by app primary/in-use ownership.
    # Archive them through the app before allowing directory cleanup.
    @{
        Path = 'C:\Users\Benny Bottema\.codex\worktrees\review-required-mailer-20261028\simple-java-mail'
        Parent = 'C:\Users\Benny Bottema\.codex\worktrees\review-required-mailer-20261028'
    }
    @{
        Path = 'C:\Users\Benny Bottema\.codex\worktrees\review-cli-selectors-20261028\simple-java-mail'
        Parent = 'C:\Users\Benny Bottema\.codex\worktrees\review-cli-selectors-20261028'
    }
    @{
        Path = 'C:\Users\Benny Bottema\.codex\worktrees\review-884-smime-20261028\simple-java-mail'
        Parent = 'C:\Users\Benny Bottema\.codex\worktrees\review-884-smime-20261028'
    }
    @{
        Path = 'C:\Users\Benny Bottema\.codex\worktrees\review-885-htmlcid-20261028\simple-java-mail'
        Parent = 'C:\Users\Benny Bottema\.codex\worktrees\review-885-htmlcid-20261028'
    }
    @{
        Path = 'C:\Users\Benny Bottema\.codex\worktrees\review-886-dsndate-20261028\simple-java-mail'
        Parent = 'C:\Users\Benny Bottema\.codex\worktrees\review-886-dsndate-20261028'
    }
    @{
        Path = 'C:\Users\Benny Bottema\.codex\worktrees\review-887-imagecid-20261028\simple-java-mail'
        Parent = 'C:\Users\Benny Bottema\.codex\worktrees\review-887-imagecid-20261028'
    }
    @{
        Path = 'C:\Users\Benny Bottema\.codex\worktrees\review-six-fixes-20261028\simple-java-mail'
        Parent = 'C:\Users\Benny Bottema\.codex\worktrees\review-six-fixes-20261028'
    }
    # @{
    #     Path = 'C:\workstation\api-review-example'
    #     Parent = 'C:\workstation'
    # }
)

if ($relativeTargets.Count + $relativeFileTargets.Count + $externalDirectoryTargets.Count -eq 0) {
    Write-Host 'No cleanup targets configured.'
    return
}

$validatedTargets = [Collections.Generic.List[string]]::new()
$validatedFiles = [Collections.Generic.List[string]]::new()
$stagedScratchTargets = [Collections.Generic.List[hashtable]]::new()
$scratchPrefix = $scratchRoot + [IO.Path]::DirectorySeparatorChar

foreach ($relativeTarget in $relativeTargets) {
    $targetPath = [IO.Path]::GetFullPath((Join-Path -Path $repositoryRoot -ChildPath $relativeTarget))
    if (-not $targetPath.StartsWith($scratchPrefix, $pathComparison) -or
        -not (Split-Path -Path $targetPath -Parent).Equals($scratchRoot, $pathComparison)) {
        throw "Target is not a direct child of the expected scratch directory: $targetPath"
    }

    # Protect committed content, but allow newly staged scratch in this exact allowlist.
    $indexedFiles = @(Invoke-RepositoryGit -Arguments @('ls-files', '--cached', '--', $relativeTarget))
    $committedFiles = @(Invoke-RepositoryGit -Arguments @('ls-tree', '-r', '--name-only', 'HEAD', '--', $relativeTarget))
    if ($committedFiles.Count -gt 0) {
        throw "Target contains committed files; cleanup stopped: $targetPath"
    }
    if (@(Invoke-RepositoryGit -Arguments @('ls-files', '--unmerged', '--', $relativeTarget)).Count -gt 0) {
        throw "Target contains merge conflicts; cleanup stopped: $targetPath"
    }
    if ($indexedFiles.Count -gt 0) {
        $stagedScratchTargets.Add(@{ Path = $targetPath; RelativePath = $relativeTarget })
    }

    $targetPrefix = $targetPath + [IO.Path]::DirectorySeparatorChar
    foreach ($worktreePath in $registeredWorktrees) {
        if ($worktreePath.Equals($targetPath, $pathComparison) -or
            $worktreePath.StartsWith($targetPrefix, $pathComparison)) {
            throw "Target contains a registered Git worktree: $worktreePath"
        }
    }

    $target = Get-Item -LiteralPath $targetPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $target) {
        Write-Host "Already absent: $targetPath"
        continue
    }
    if (-not $target.PSIsContainer -or (($target.Attributes -band $reparseAttribute) -ne 0)) {
        throw "Target must be an ordinary directory without a reparse point: $targetPath"
    }
    if (-not $target.FullName.Equals($targetPath, $pathComparison)) {
        throw "Resolved target differs from the allowlist: $($target.FullName)"
    }

    # Walk one level at a time so no link or junction is ever traversed.
    $pendingDirectories = [Collections.Generic.Queue[string]]::new()
    $pendingDirectories.Enqueue($targetPath)
    while ($pendingDirectories.Count -gt 0) {
        $directoryPath = $pendingDirectories.Dequeue()
        foreach ($entry in @(Get-ChildItem -LiteralPath $directoryPath -Force)) {
            if (($entry.Attributes -band $reparseAttribute) -ne 0) {
                throw "Target contains a reparse point; cleanup stopped: $($entry.FullName)"
            }
            if ($entry.Name -ieq '.git') {
                throw "Target contains a nested Git repository: $($entry.FullName)"
            }
            if ($entry.PSIsContainer) {
                $pendingDirectories.Enqueue($entry.FullName)
            }
        }
    }
    $validatedTargets.Add($targetPath)
}

foreach ($externalTarget in $externalDirectoryTargets) {
    $targetPath = [IO.Path]::GetFullPath($externalTarget.Path).TrimEnd('\', '/')
    $expectedParent = [IO.Path]::GetFullPath($externalTarget.Parent).TrimEnd('\', '/')
    if (-not $targetPath.Equals($externalTarget.Path, $pathComparison) -or
        -not (Split-Path -Path $targetPath -Parent).Equals($expectedParent, $pathComparison)) {
        throw "External target differs from its exact allowed path or parent: $targetPath"
    }

    $target = Get-Item -LiteralPath $targetPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $target) {
        Write-Host "Already absent: $targetPath"
        continue
    }
    if (-not $target.PSIsContainer -or (($target.Attributes -band $reparseAttribute) -ne 0) -or
        -not $target.FullName.Equals($targetPath, $pathComparison)) {
        throw "External target must resolve to its exact ordinary directory: $targetPath"
    }

    # An external target must not be reached through a junction or be inside another repository.
    $ancestorPath = $expectedParent
    while (-not [string]::IsNullOrEmpty($ancestorPath)) {
        $ancestor = Get-Item -LiteralPath $ancestorPath -Force
        if (-not $ancestor.PSIsContainer -or (($ancestor.Attributes -band $reparseAttribute) -ne 0)) {
            throw "External target has a nonordinary ancestor: $ancestorPath"
        }
        if (Test-Path -LiteralPath (Join-Path -Path $ancestorPath -ChildPath '.git')) {
            throw "External target is inside a Git repository: $ancestorPath"
        }
        $ancestorPath = Split-Path -Path $ancestorPath -Parent
    }

    $targetPrefix = $targetPath + [IO.Path]::DirectorySeparatorChar
    foreach ($worktreePath in $registeredWorktrees) {
        if ($worktreePath.Equals($targetPath, $pathComparison) -or
            $worktreePath.StartsWith($targetPrefix, $pathComparison)) {
            throw "External target contains a registered Git worktree: $worktreePath"
        }
    }

    $pendingDirectories = [Collections.Generic.Queue[string]]::new()
    $pendingDirectories.Enqueue($targetPath)
    while ($pendingDirectories.Count -gt 0) {
        $directoryPath = $pendingDirectories.Dequeue()
        foreach ($entry in @(Get-ChildItem -LiteralPath $directoryPath -Force)) {
            if (($entry.Attributes -band $reparseAttribute) -ne 0) {
                throw "External target contains a reparse point: $($entry.FullName)"
            }
            if ($entry.Name -ieq '.git') {
                throw "External target contains a Git repository: $($entry.FullName)"
            }
            if ($entry.PSIsContainer) {
                $pendingDirectories.Enqueue($entry.FullName)
            }
        }
    }
    $validatedTargets.Add($targetPath)
}

foreach ($relativeTarget in $relativeFileTargets) {
    $targetPath = [IO.Path]::GetFullPath((Join-Path -Path $repositoryRoot -ChildPath $relativeTarget))
    $expectedParent = if ($relativeTarget.StartsWith('.local/', [StringComparison]::Ordinal)) {
        $scratchRoot
    } else {
        Join-Path -Path $repositoryRoot -ChildPath 'docs/plans'
    }
    if (-not $targetPath.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, $pathComparison) -or
        -not (Split-Path -Path $targetPath -Parent).Equals($expectedParent, $pathComparison)) {
        throw "File target is outside its expected directory: $targetPath"
    }

    # Validate each parent without following a link or entering a nested repository.
    $parentPath = $repositoryRoot
    foreach ($component in ((Split-Path -Path $relativeTarget -Parent) -split '[\\/]')) {
        $parentPath = Join-Path -Path $parentPath -ChildPath $component
        $parent = Get-Item -LiteralPath $parentPath -Force
        if (-not $parent.PSIsContainer -or (($parent.Attributes -band $reparseAttribute) -ne 0)) {
            throw "Expected an ordinary parent directory without a reparse point: $parentPath"
        }
        if (Test-Path -LiteralPath (Join-Path -Path $parentPath -ChildPath '.git')) {
            throw "File target is inside a nested Git repository: $parentPath"
        }
    }

    $indexedFiles = @(Invoke-RepositoryGit -Arguments @('ls-files', '--cached', '--', $relativeTarget))
    $committedFiles = @(Invoke-RepositoryGit -Arguments @('ls-tree', '-r', '--name-only', 'HEAD', '--', $relativeTarget))
    if ($committedFiles.Count -gt 0) {
        throw "File target is committed; cleanup stopped: $targetPath"
    }
    if (@(Invoke-RepositoryGit -Arguments @('ls-files', '--unmerged', '--', $relativeTarget)).Count -gt 0) {
        throw "File target contains merge conflicts; cleanup stopped: $targetPath"
    }
    if ($indexedFiles.Count -gt 0) {
        $stagedScratchTargets.Add(@{ Path = $targetPath; RelativePath = $relativeTarget })
    }

    $target = Get-Item -LiteralPath $targetPath -Force -ErrorAction SilentlyContinue
    if ($null -eq $target) {
        Write-Host "Already absent: $targetPath"
        continue
    }
    if ($target.PSIsContainer -or (($target.Attributes -band $reparseAttribute) -ne 0)) {
        throw "File target must be an ordinary file without a reparse point: $targetPath"
    }
    if (-not $target.FullName.Equals($targetPath, $pathComparison)) {
        throw "Resolved file differs from the allowlist: $($target.FullName)"
    }
    $validatedFiles.Add($targetPath)
}

Write-Host "Validated $($validatedTargets.Count) existing API review scratch directories and $($validatedFiles.Count) temporary files."
foreach ($targetPath in $validatedTargets) {
    if ($PSCmdlet.ShouldProcess($targetPath, 'Remove API review scratch directory recursively')) {
        Remove-Item -LiteralPath $targetPath -Recurse -Force
        Write-Host "Removed: $targetPath"
    }
}
foreach ($targetPath in $validatedFiles) {
    if ($PSCmdlet.ShouldProcess($targetPath, 'Remove API review temporary file')) {
        Remove-Item -LiteralPath $targetPath -Force
        Write-Host "Removed: $targetPath"
    }
}

foreach ($stagedTarget in $stagedScratchTargets) {
    # A declined or failed filesystem removal must not silently unstage surviving files.
    if (-not $WhatIfPreference -and (Test-Path -LiteralPath $stagedTarget.Path)) {
        continue
    }
    if ($PSCmdlet.ShouldProcess($stagedTarget.Path, 'Remove newly staged scratch entries from the Git index')) {
        $committedFiles = @(Invoke-RepositoryGit -Arguments @('ls-tree', '-r', '--name-only', 'HEAD', '--', $stagedTarget.RelativePath))
        $unmergedFiles = @(Invoke-RepositoryGit -Arguments @('ls-files', '--unmerged', '--', $stagedTarget.RelativePath))
        if ($committedFiles.Count -gt 0 -or $unmergedFiles.Count -gt 0) {
            throw "Target gained committed content or merge conflicts; index cleanup stopped: $($stagedTarget.Path)"
        }
        Invoke-RepositoryGit -Arguments @('rm', '--cached', '--ignore-unmatch', '-r', '-f', '--', $stagedTarget.RelativePath) | Out-Null
        Write-Host "Removed staged scratch entries: $($stagedTarget.Path)"
    }
}

# Deferred disposable paths for CLI review issues #875 and #876.
# Remove only these exact round-owned paths after cleanup is permitted; preserve their parents.
$deferredCliReviewDirectories = @(
    'C:\workstation\simple-java-mail\tmp\api-review-20261027-cli'
    'C:\workstation\simple-java-mail\.local\cli-review-delivery-20261027'
    'C:\workstation\simple-java-mail\simplejavamail.org\.local\validation\875-cli-literal-values'
    'C:\workstation\simple-java-mail\simplejavamail.org\.local\validation\876-cli-argument-files'
    'C:\Users\Benny Bottema\AppData\Local\Temp\sjm-website-cli-delivery-52f0a2219bc946bb84572951c47f98cc'
)
$deferredCliReviewFiles = @(
    'C:\workstation\simple-java-mail\simplejavamail.org\.local\validation\875-cli-literal-values-build.log'
    'C:\workstation\simple-java-mail\simplejavamail.org\.local\validation\876-cli-argument-files-build.log'
)

foreach ($targetPath in $deferredCliReviewDirectories) {
    if (-not (Test-Path -LiteralPath $targetPath)) { continue }
    $target = Get-Item -LiteralPath $targetPath -Force
    if (-not $target.PSIsContainer -or -not $target.FullName.Equals([IO.Path]::GetFullPath($targetPath), $pathComparison) -or
        (($target.Attributes -band $reparseAttribute) -ne 0)) {
        throw "Expected an exact ordinary CLI review directory: $targetPath"
    }
    foreach ($worktreePath in $registeredWorktrees) {
        if ($worktreePath.Equals($target.FullName, $pathComparison) -or
            $worktreePath.StartsWith($target.FullName + [IO.Path]::DirectorySeparatorChar, $pathComparison)) {
            throw "CLI review directory contains a registered worktree: $worktreePath"
        }
    }
    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($target.FullName)
    while ($pending.Count -gt 0) {
        foreach ($entry in @(Get-ChildItem -LiteralPath $pending.Dequeue() -Force)) {
            if ($entry.Name -ieq '.git' -or (($entry.Attributes -band $reparseAttribute) -ne 0)) {
                throw "CLI review directory contains Git metadata or a reparse point: $($entry.FullName)"
            }
            if ($entry.PSIsContainer) { $pending.Enqueue($entry.FullName) }
        }
    }
    if ($PSCmdlet.ShouldProcess($target.FullName, 'Remove CLI review scratch directory recursively')) {
        Remove-Item -LiteralPath $target.FullName -Recurse -Force
    }
}
foreach ($targetPath in $deferredCliReviewFiles) {
    if (-not (Test-Path -LiteralPath $targetPath)) { continue }
    $target = Get-Item -LiteralPath $targetPath -Force
    if ($target.PSIsContainer -or -not $target.FullName.Equals([IO.Path]::GetFullPath($targetPath), $pathComparison) -or
        (($target.Attributes -band $reparseAttribute) -ne 0)) {
        throw "Expected an exact ordinary CLI review file: $targetPath"
    }
    if ($PSCmdlet.ShouldProcess($target.FullName, 'Remove CLI review temporary file')) {
        Remove-Item -LiteralPath $target.FullName -Force
    }
}
