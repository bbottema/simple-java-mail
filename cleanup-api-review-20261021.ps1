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
# #960: unlink this task-owned dependency junction before cleanup; keep the reparse-point guard intact.
# Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\decrypted-merge-contract-20261103\repair\website-source\node_modules' -Force
$relativeTargets = @(
    # Retained review artifacts can be removed after maintainer inspection.
    '.local/api-review-20261105-evidence'
    '.local/email-source-inspection-20261103'
    '.local/decrypted-merge-contract-20261103'
    '.local/review-smime-id-contract-20261103'
    '.local/review-text-streams-20261102'
    '.local/review-selected-20261102'
    '.local/image-validation-20261102'
    '.local/rehearsal-lifetime-20261102'
    '.local/review-approved-20261102'
    '.local/review-rehearsal-wording-20261101'
    '.local/cli-metadata-workflow-20261101'
    '.local/review-html-boundaries-20261101'
    '.local/review-unused-defaults-20261101'
    '.local/review-readable-reply-20261101'
    '.local/review-cleanup-cancel-20261101'
    '.local/review-selected-20261101'
    '.local/review-wide-repairs-20261101'
    '.local/exact-eml-inspection-20261101'
    '.local/batch-cancel-boundary-20261101'
    '.local/review-accepted-20261101'
    '.local/oauth-inherited-20261101'
    '.local/review-followup-20261031'
    '.local/review-header-order-20261031'
    '.local/review-protection-20261031'
    '.local/recipient-list-930-20261031'
    '.local/review-round-20261031'
    '.local/smime-parameters-927-20261031'
    '.local/cli-help-config-928-20261031'
    '.local/header-input-929-20261031'
    '.local/key-material-atomicity-20261031'
    '.local/tls-inspection-20261030'
    '.local/daemon-shutdown-20261030'
    '.local/openpgp-jpms-20261029'
    '.local/envid-spelling-763-20261029'
    '.local/local-socks-request-validation-20261029'
    '.local/socks-capacity-921-20261029'
    '.local/content-id-comments-20261029'
    '.local/fixture-alignment-20261029'
    '.local/smime-retained-918-20261029'
    '.local/body-null-fragments-20261029'
    '.local/dsn-action-comments-20261029'
    '.local/filename-sentinel-916-20261029'
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
    # BEGIN openpgp-inspection-review-20261107 retired plan
    'docs/plans/openpgp-inspection-review-20261107.md'
    # END openpgp-inspection-review-20261107 retired plan
    # #704/#822: completed plan retained after automatic approval review refused its deletion.
    'docs/plans/smime-declaration-repairs-20261107.md'
    # #966: the retired work packet remains available for individual cleanup after inspection.
    # Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\pooled-oauth-protocol-20261105.md' -Force
    'docs/plans/pooled-oauth-protocol-20261105.md'
    # Retire these #961 specifications after delivery; keep email-inspection-roadmap-20261104.md durable.
    'docs/plans/email-source-inspection-20261103.md'
    'docs/plans/email-source-content-contract-20261104.md'
    'docs/plans/email-conversion-outcomes-20261104.md'
    'docs/plans/email-omissions-and-retention-20261104.md'
    # Retire the active merge-contract review plan before running cleanup.
    'docs/plans/decrypted-merge-contract-20261103.md'
    'docs/plans/smime-helper-id-contract-20261103.md'
    'docs/plans/text-body-streams-20261102.md'
    'docs/plans/selected-review-20261102.md'
    'docs/plans/958-reused-session-oauth2-20261102.md'
    'docs/plans/957-image-validation-20261102.md'
    'docs/plans/709-rehearsal-lifetime-20261102.md'
    'docs/plans/approved-review-20261102.md'
    'docs/plans/cli-metadata-build-cache-20261101.md'
    'docs/plans/952-html-resource-boundaries-20261101.md'
    'docs/plans/951-cli-unused-default-files-20261101.md'
    'docs/plans/950-readable-reply-20261101.md'
    'docs/plans/949-late-cancellation-cleanup-20261101.md'
    'docs/plans/selected-review-repairs-20261101.md'
    'docs/plans/wide-review-repairs-20261101.md'
    'docs/plans/exact-eml-inspection-20261101.md'
    'docs/plans/batch-cancel-boundary-20261101.md'
    'docs/plans/accepted-review-20261101.md'
    'docs/plans/oauth-inherited-20261101.md'
    'docs/plans/review-followup-20261031.md'
    'docs/plans/934-imported-header-order.md'
    'docs/plans/protection-review-20261031.md'
    'docs/plans/927-929-review-round-20261031.md'
    'docs/plans/928-cli-help-config.md'
    'docs/plans/926-key-material-atomicity.md'
    'docs/plans/925-tls-inspection.md'
    'docs/plans/924-bounded-daemon-shutdown.md'
    'docs/plans/923-openpgp-jpms.md'
    'docs/plans/763-envelope-id-preservation-20261029.md'
    'docs/plans/local-socks-request-validation.md'
    'docs/plans/921-socks-setup-capacity.md'
    'docs/plans/920-content-id-comments.md'
    'docs/plans/918-retained-smime-signatures.md'
    'docs/plans/919-body-null-fragments.md'
    'docs/plans/917-dsn-action-comments.md'
    'docs/plans/916-imported-filename-sentinel.md'
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
    # BEGIN openpgp-inspection-review-20261107 task custody
    # #704: retire nested/main checkouts through normal Git worktree removal before directory cleanup.
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-openpgp-review-20261107\declarations\sjm\simplejavamail.org'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-openpgp-review-20261107\declarations\sjm\simplejavamail.org' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-openpgp-review-20261107\declarations\sjm\simplejavamail.org'; Parent = 'C:\workstation\sjm-openpgp-review-20261107\declarations\sjm' }
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-openpgp-review-20261107\declarations\sjm'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-openpgp-review-20261107\declarations\sjm' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-openpgp-review-20261107\declarations\sjm'; Parent = 'C:\workstation\sjm-openpgp-review-20261107\declarations' }
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-openpgp-review-20261107\lazy\sjm\simplejavamail.org'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-openpgp-review-20261107\lazy\sjm\simplejavamail.org' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-openpgp-review-20261107\lazy\sjm\simplejavamail.org'; Parent = 'C:\workstation\sjm-openpgp-review-20261107\lazy\sjm' }
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-openpgp-review-20261107\lazy\sjm'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-openpgp-review-20261107\lazy\sjm' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-openpgp-review-20261107\lazy\sjm'; Parent = 'C:\workstation\sjm-openpgp-review-20261107\lazy' }
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-openpgp-review-20261107\combined\sjm\simplejavamail.org'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-openpgp-review-20261107\combined\sjm\simplejavamail.org' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-openpgp-review-20261107\combined\sjm\simplejavamail.org'; Parent = 'C:\workstation\sjm-openpgp-review-20261107\combined\sjm' }
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-openpgp-review-20261107\combined\sjm'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-openpgp-review-20261107\combined\sjm' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-openpgp-review-20261107\combined\sjm'; Parent = 'C:\workstation\sjm-openpgp-review-20261107\combined' }
    # Retain owned evidence until maintainer inspection; never clean the shared review parent wholesale.
    # Remove-Item -LiteralPath 'C:\workstation\sjm-openpgp-review-20261107\evidence' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-openpgp-review-20261107\evidence'; Parent = 'C:\workstation\sjm-openpgp-review-20261107' }
    # END openpgp-inspection-review-20261107 task custody
    # #704/#822: the attached primary checkout stays protected while this task owns it.
    # Archive it through the app after primary ownership ends; keep the registered-worktree guard.
    @{ Path = 'C:\Users\Benny Bottema\.codex\worktrees\smime-declaration-recognition-20261107\simple-java-mail'; Parent = 'C:\Users\Benny Bottema\.codex\worktrees\smime-declaration-recognition-20261107' }
    # Matching tracked website Java examples exported for relative source-context links.
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\combined\simplejavamail.org' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\combined\simplejavamail.org'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\combined' }

    # Matching tracked example sources exported for the website's optional source-context check.
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\combined\tools' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\combined\tools'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\combined' }

    # 6 November contract repairs (#488, #704, #756, #961): retire exact registered worktrees through Git first.
    # Retain task evidence until maintainer inspection; never delete the shared task parent.
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\smime\sjm'
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\smime\website'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\smime\sjm' -Recurse -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\smime\website' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\smime\sjm'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\smime' }
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\smime\website'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\smime' }
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\receipts\sjm'
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\receipts\website'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\receipts\sjm' -Recurse -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\receipts\website' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\receipts\sjm'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\receipts' }
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\receipts\website'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\receipts' }
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\daemon\sjm'
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\daemon\website'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\daemon\sjm' -Recurse -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\daemon\website' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\daemon\sjm'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\daemon' }
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\daemon\website'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\daemon' }
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\reports\sjm'
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\reports\website'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\reports\sjm' -Recurse -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\reports\website' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\reports\sjm'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\reports' }
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\reports\website'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\reports' }
    # git -C 'C:\workstation\simple-java-mail' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\combined\sjm'
    # git -C 'C:\workstation\simple-java-mail\simplejavamail.org' worktree remove 'C:\workstation\sjm-contract-repairs-20261106\combined\website'
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\combined\sjm' -Recurse -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\combined\website' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\combined\sjm'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\combined' }
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\combined\website'; Parent = 'C:\workstation\sjm-contract-repairs-20261106\combined' }
    # Remove-Item -LiteralPath 'C:\workstation\sjm-contract-repairs-20261106\evidence' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-contract-repairs-20261106\evidence'; Parent = 'C:\workstation\sjm-contract-repairs-20261106' }

    # #966: only remaining task-owned leaves; never target the parent or its retained evidence.
    # Remove-Item -LiteralPath 'C:\workstation\sjm-oauth-protocol-20261105\rehearsal-repository' -Recurse -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-oauth-protocol-20261105\tools' -Recurse -Force
    @{
        Path = 'C:\workstation\sjm-oauth-protocol-20261105\rehearsal-repository'
        Parent = 'C:\workstation\sjm-oauth-protocol-20261105'
    }
    @{
        Path = 'C:\workstation\sjm-oauth-protocol-20261105\tools'
        Parent = 'C:\workstation\sjm-oauth-protocol-20261105'
    }
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
    # #969: Session-rollback checkouts retired through Git; retain evidence for maintainer inspection.
    # Retired main checkout: C:\workstation\sjm-session-rollback-20261107\sjm
    # Retired website checkout: C:\workstation\sjm-session-rollback-20261107\sjm\simplejavamail.org
    # Retired plan: C:\workstation\sjm-session-rollback-20261107\evidence\session-rollback-plan.md
    # Remove-Item -LiteralPath 'C:\workstation\sjm-session-rollback-20261107\evidence' -Recurse -Force
    @{ Path = 'C:\workstation\sjm-session-rollback-20261107\evidence'; Parent = 'C:\workstation\sjm-session-rollback-20261107' }
    # #969/#704: completed review custody, with related #961/#971 and CLI #488.
    # All owned review website worktrees are retired; retained mains have only empty uninitialized gitlink placeholders.
    # Automatic review refused these exact plan deletions before execution. Keep originals, containing mains and branch refs.
    # Remove-Item -LiteralPath 'C:\workstation\sjm-review-20261107\session\sjm\docs\plans\session-construction-20261107.md' -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-review-20261107\smime\sjm\docs\plans\smime-outlook-20261107.md' -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-review-20261107\reading\sjm\docs\plans\import-recovery-20261107.md' -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-review-20261107\image\sjm\docs\plans\image-id-locale-20261107.md' -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-review-20261107\combined\sjm\docs\plans\review-delivery-20261107.md' -Force
    # Remove-Item -LiteralPath 'C:\workstation\sjm-review-20261107\validation\sjm\docs\plans\cli-validation-20261107.md' -Force
    @{ Path = 'C:\workstation\sjm-review-20261107\session\sjm'; Parent = 'C:\workstation\sjm-review-20261107\session' }
    @{ Path = 'C:\workstation\sjm-review-20261107\session\evidence'; Parent = 'C:\workstation\sjm-review-20261107\session' }
    @{ Path = 'C:\workstation\sjm-review-20261107\smime\sjm'; Parent = 'C:\workstation\sjm-review-20261107\smime' }
    @{ Path = 'C:\workstation\sjm-review-20261107\smime\evidence'; Parent = 'C:\workstation\sjm-review-20261107\smime' }
    @{ Path = 'C:\workstation\sjm-review-20261107\reading\sjm'; Parent = 'C:\workstation\sjm-review-20261107\reading' }
    @{ Path = 'C:\workstation\sjm-review-20261107\reading\evidence'; Parent = 'C:\workstation\sjm-review-20261107\reading' }
    @{ Path = 'C:\workstation\sjm-review-20261107\image\sjm'; Parent = 'C:\workstation\sjm-review-20261107\image' }
    @{ Path = 'C:\workstation\sjm-review-20261107\image\evidence'; Parent = 'C:\workstation\sjm-review-20261107\image' }
    @{ Path = 'C:\workstation\sjm-review-20261107\validation\sjm'; Parent = 'C:\workstation\sjm-review-20261107\validation' }
    @{ Path = 'C:\workstation\sjm-review-20261107\validation\evidence'; Parent = 'C:\workstation\sjm-review-20261107\validation' }
    @{ Path = 'C:\workstation\sjm-review-20261107\combined\sjm'; Parent = 'C:\workstation\sjm-review-20261107\combined' }
    @{ Path = 'C:\workstation\sjm-review-20261107\combined\evidence'; Parent = 'C:\workstation\sjm-review-20261107\combined' }
    @{ Path = 'C:\workstation\sjm-review-20261107\evidence'; Parent = 'C:\workstation\sjm-review-20261107' }
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

# Deferred OAuth2 review cleanup (#938) for a later manual run.
# These exact paths also remain in the guarded allowlists above.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\oauth-inherited-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\oauth-inherited-20261101', 'Remove OAuth2 review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\oauth-inherited-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\oauth-inherited-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\oauth-inherited-20261101.md', 'Remove retired OAuth2 review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\oauth-inherited-20261101.md' -Force
}

# Deferred disposal from the accepted AUTH, OpenPGP, DKIM and probe review (#939-#942).
# These exact paths also remain in the guarded allowlists above.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-accepted-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-accepted-20261101', 'Remove accepted review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-accepted-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\accepted-review-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\accepted-review-20261101.md', 'Remove retired accepted review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\accepted-review-20261101.md' -Force
}

# Deferred simple-batch source cancellation cleanup (#943).
# These exact paths also remain in the guarded allowlists above.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\batch-cancel-boundary-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\batch-cancel-boundary-20261101', 'Remove batch cancellation review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\batch-cancel-boundary-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\batch-cancel-boundary-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\batch-cancel-boundary-20261101.md', 'Remove retired batch cancellation review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\batch-cancel-boundary-20261101.md' -Force
}

# Deferred exact-EML inspection cleanup (#944).
# These exact paths also remain in the guarded allowlists above.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\exact-eml-inspection-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\exact-eml-inspection-20261101', 'Remove exact EML inspection scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\exact-eml-inspection-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\exact-eml-inspection-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\exact-eml-inspection-20261101.md', 'Remove retired exact EML inspection plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\exact-eml-inspection-20261101.md' -Force
}

# Wide review delivery is complete; these exact task-owned paths await manual disposal.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-wide-repairs-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-wide-repairs-20261101', 'Remove completed wide review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-wide-repairs-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\wide-review-repairs-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\wide-review-repairs-20261101.md', 'Remove retired wide review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\wide-review-repairs-20261101.md' -Force
}

# Selected review task-owned paths (#946, #947, #948); remove after delivery.
# Keep these exact paths in the guarded allowlists above as well.
# The website export was relocated from simplejavamail.org/.local/review-selected-20261101-website into this task's scratch directory.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-selected-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-selected-20261101', 'Remove selected review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-selected-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\selected-review-repairs-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\selected-review-repairs-20261101.md', 'Remove retired selected review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\selected-review-repairs-20261101.md' -Force
}

# Late-cancellation review task-owned paths (#949); remove after delivery.
# Keep these exact paths in the guarded allowlists above as well.
# The website export moved from simplejavamail.org/.local/949-cleanup-cancel-website into this task's scratch directory.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-cleanup-cancel-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-cleanup-cancel-20261101', 'Remove late-cancellation review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-cleanup-cancel-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\949-late-cancellation-cleanup-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\949-late-cancellation-cleanup-20261101.md', 'Remove retired late-cancellation review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\949-late-cancellation-cleanup-20261101.md' -Force
}

# Readable-reply review task-owned paths (#950); remove after delivery.
# Keep these exact paths in the guarded allowlists above as well.
# The website export moves from simplejavamail.org/.local/950-readable-reply-website into this task's scratch directory.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-readable-reply-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-readable-reply-20261101', 'Remove readable-reply review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-readable-reply-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\950-readable-reply-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\950-readable-reply-20261101.md', 'Remove retired readable-reply review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\950-readable-reply-20261101.md' -Force
}

# Unused-default-file review task-owned paths (#951); remove after delivery.
# Keep these exact paths in the guarded allowlists above as well.
# The website export moves from simplejavamail.org/.local/951-unused-defaults-website into this task's scratch directory.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-unused-defaults-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-unused-defaults-20261101', 'Remove unused-default-file review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-unused-defaults-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\951-cli-unused-default-files-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\951-cli-unused-default-files-20261101.md', 'Remove retired unused-default-file review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\951-cli-unused-default-files-20261101.md' -Force
}

# HTML resource-boundary review task-owned paths (#952); remove after delivery.
# Keep these exact paths in the guarded allowlists above as well.
# The website export moves from simplejavamail.org/.local/952-html-boundaries-website into this task's scratch directory.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-html-boundaries-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-html-boundaries-20261101', 'Remove HTML resource-boundary review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-html-boundaries-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\952-html-resource-boundaries-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\952-html-resource-boundaries-20261101.md', 'Remove retired HTML resource-boundary review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\952-html-resource-boundaries-20261101.md' -Force
}

# CLI metadata workflow scratch and retired plan; remove after delivery.
# The website archive moved from simplejavamail.org/.local/cli-metadata-workflow-20261101 into this exact scratch root.
# Persistent developer modules/cli-module/.build-cache entries are not disposable review paths.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\cli-metadata-workflow-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\cli-metadata-workflow-20261101', 'Remove CLI metadata workflow scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\cli-metadata-workflow-20261101' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\cli-metadata-build-cache-20261101.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\cli-metadata-build-cache-20261101.md', 'Remove retired CLI metadata workflow plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\cli-metadata-build-cache-20261101.md' -Force
}

# Rehearsal recipient wording review scratch; remove after delivery.
# The website export moves from simplejavamail.org/.local/rehearsal-wording-20261101 into this task's scratch directory.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-rehearsal-wording-20261101') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-rehearsal-wording-20261101', 'Remove rehearsal recipient wording scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-rehearsal-wording-20261101' -Recurse -Force
}

# Completed 2 November review; these exact paths also remain in the guarded allowlists above.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-approved-20261102') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-approved-20261102', 'Remove completed review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-approved-20261102' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\approved-review-20261102.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\approved-review-20261102.md', 'Remove retired review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\approved-review-20261102.md' -Force
}

# Completed rehearsal lifetime follow-up (#709); these paths also belong in the guarded allowlists.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\rehearsal-lifetime-20261102') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\rehearsal-lifetime-20261102', 'Remove completed rehearsal lifetime scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\rehearsal-lifetime-20261102' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\709-rehearsal-lifetime-20261102.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\709-rehearsal-lifetime-20261102.md', 'Remove retired rehearsal lifetime plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\709-rehearsal-lifetime-20261102.md' -Force
}

# Completed image-validation follow-up (#957); these exact paths also belong in the guarded allowlists.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\image-validation-20261102') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\image-validation-20261102', 'Remove completed image-validation scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\image-validation-20261102' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\957-image-validation-20261102.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\957-image-validation-20261102.md', 'Remove retired image-validation plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\957-image-validation-20261102.md' -Force
}

# Selected startup, DKIM guidance and OAuth repairs (#488, #855, #958); keep these exact paths in the guarded allowlists.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-selected-20261102') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-selected-20261102', 'Remove completed selected-review scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-selected-20261102' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\selected-review-20261102.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\selected-review-20261102.md', 'Remove retired selected-review plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\selected-review-20261102.md' -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\958-reused-session-oauth2-20261102.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\958-reused-session-oauth2-20261102.md', 'Remove retired reused-Session OAuth2 plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\958-reused-session-oauth2-20261102.md' -Force
}

# Streamed text-body import (#959); keep these exact paths in the guarded allowlists.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-text-streams-20261102') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-text-streams-20261102', 'Remove completed text-body import scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-text-streams-20261102' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\text-body-streams-20261102.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\text-body-streams-20261102.md', 'Remove retired text-body import plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\text-body-streams-20261102.md' -Force
}

# S/MIME helper ID contract; keep these exact paths in the guarded allowlists.
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\.local\review-smime-id-contract-20261103') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\.local\review-smime-id-contract-20261103', 'Remove completed S/MIME helper ID scratch recursively')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\.local\review-smime-id-contract-20261103' -Recurse -Force
}
if ((Test-Path -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\smime-helper-id-contract-20261103.md') -and
    $PSCmdlet.ShouldProcess('C:\workstation\simple-java-mail\docs\plans\smime-helper-id-contract-20261103.md', 'Remove retired S/MIME helper ID plan')) {
    Remove-Item -LiteralPath 'C:\workstation\simple-java-mail\docs\plans\smime-helper-id-contract-20261103.md' -Force
}

#region ReviewDeliveryCustody20261105
<#
Ownership and retention record for the delegated registration, signed-attachment,
daemon-path and forwarded-source changes (#488, #704, #967, #968; pool #39).

Task-owned linked checkouts retired with normal git worktree removal:
C:\workstation\sjm-oauth-registration-20261105\pool
C:\workstation\sjm-oauth-registration-20261105\sjm
C:\workstation\sjm-oauth-registration-20261105\website
C:\workstation\sjm-smime-signed-attachments-20261105\sjm
C:\workstation\sjm-smime-signed-attachments-20261105\website
C:\workstation\sjm-smime-signed-attachments-20261105\baseline
C:\workstation\sjm-daemon-paths-20261105\sjm
C:\workstation\sjm-daemon-paths-20261105\website
C:\workstation\sjm-clear-forward-20261105\sjm
C:\workstation\sjm-clear-forward-20261105\website
C:\workstation\sjm-clear-forward-20261105\closure-check

The evidence directories in all four task parents and in
C:\workstation\sjm-review-combined-20261105 remain retained. Original staged
drafts, indexes and baseline/closure fixtures are archived there. Branch refs
remain available. Task-local cache, tool and runtime leaves also remain retained.

Registered checkouts retained at the earlier automatic-removal boundary:
C:\workstation\sjm-review-combined-20261105\sjm
C:\workstation\sjm-review-combined-20261105\website
The context Junctions at tools\integration-patterns and simplejavamail.org in
that task parent remain intact, together with their targets. Do not retry their
refused removal, delete their targets indirectly, or remove the task parent.

Active Session-integration #969 and logging-only receipt #723 work, their plans,
the separate SOCKS/Angus worktrees, and older .local/manual residues retain their
own ownership. This custody record does not add an executable cleanup block.
#>
#endregion ReviewDeliveryCustody20261105

#region SessionIntegrationCustody969
<#
Session integration #969.

Task-owned linked checkouts retired with normal git worktree removal:
C:\workstation\sjm-session-integration-20261105\sjm
C:\workstation\sjm-session-integration-20261105\website
C:\workstation\sjm-session-integration-20261105\combined
C:\workstation\sjm-session-integration-20261105\combined-website
C:\workstation\sjm-session-integration-20261105\delivery
C:\workstation\sjm-session-integration-20261105\delivery-website

The Session handoff/execution plans are retired under
C:\workstation\sjm-session-integration-20261105\evidence\retired-plans.
Evidence, source snapshots and produced artifacts remain retained under
C:\workstation\sjm-session-integration-20261105\evidence.
Keep that evidence and the task parent; this record adds no executable deletion.
No completed #969 paths remain in the executable cleanup allowlists.
#>
#endregion SessionIntegrationCustody969

#region SmimeDeclarationReviewCustody20261107
<#
The #704/#822 declaration repairs and guides are delivered on codex/10.0.0
at 722269d75410655ce54cc41f4c32063d7513b8a5, with website
8bf3d251988560d424ebdf0a9f40822b0a4fafd6 published first. Both issues are closed
and their existing project items are Done.

The worker and combined nested/main checkouts were retired through normal Git
worktree removal. Their exact retired paths were:
C:\workstation\sjm-smime-declaration-20261107\recognition\sjm
C:\workstation\sjm-smime-declaration-20261107\recognition\sjm\simplejavamail.org
C:\workstation\sjm-smime-declaration-20261107\interruption\sjm
C:\workstation\sjm-smime-declaration-20261107\interruption\sjm\simplejavamail.org
C:\workstation\sjm-smime-declaration-20261107\combined\sjm
C:\workstation\sjm-smime-declaration-20261107\combined\sjm\simplejavamail.org

The retained plan is docs/plans/smime-declaration-repairs-20261107.md.
Automatic approval review rejected its deletion before execution, stating only
"blocked by policy". Do not retry that deletion through another method.
Its exact file entry remains in the guarded allowlist above.

The managed checkout listed above remains the task's attached primary, containing
the delivered sources. The app refused archive with "The task's primary checkout
cannot be removed." Keep it until that ownership ends; do not bypass that guard.

Retain .local/smime-declaration-repairs-20261107-evidence and the task parent.
The older decrypted-merge dependency junction still stops the full cleanup WhatIf
run; it was left intact. Preserve unrelated main/website dirty and staged work,
older plans, other registered worktrees and existing path/reparse-point guards.
#>
#endregion SmimeDeclarationReviewCustody20261107
