# Late cancellation and independent cleanup failures

Retired after delivery: #949 is closed/completed and Done in milestone 10.0.0. Source `040b7f9036a55c57dbfda4b1d3b48e7fdb6c5490`,
documentation/gitlink `3ddb37d27d14dbb2705734807d5f353066635fb6` and cleanup bookkeeping `6251d3ea2d8a4a08f2863455625bf477c6c8c4f5`
are published on `codex/10.0.0`; website `1c7d39575ae6953aa57d12e150055bf9ef421231` is published on `codex/10.0.0-content`.
The lead independently confirmed final issue labels/milestone/Done status, preserved before body, after-usage completion comment and remote heads.
No accepted implementation, documentation, review or issue work remains. Only bounded disposal remains behind the older reparse-point guard below.

The maintainer accepted review finding 1 and requested delegated implementation while discussing the next finding. This continues the approved review workflow through focused verification, documentation, lead review, selective commits and publication to `codex/10.0.0` and applicable `codex/10.0.0-content`, then issue closure and Done status. No release, deployment or history rewrite is included. Tracking: [#949](https://github.com/bbottema/simple-java-mail/issues/949).

Baseline: main `bb27aa6522780f95e0624263dec85fa5f1432351`, website `ca2ed8d9eb7a637fa5d3a44fa56960775de52b60`; both remote heads match. Preserve unrelated staged/unstaged research, workflow and cleanup changes, nested website work, untracked plans/scratch and registered worktrees. Index copies, binary diffs and status/worktree inventories are in `.local/review-cleanup-cancel-20261101/preservation`.

## Accepted behavior and ownership

An observed accepted SMTP receipt remains accepted after a late stop. Expected socket-close consequences are suppressed only where the owning provider establishes its physical abort. Independent direct-provider or pooled cleanup failures remain observable with their original causes and any accepted receipt. Existing stop translation may retain a cancellation/timeout subtype; no new subtype, API, receipt state or retry rule is introduced. JVM Errors remain unwrapped; existing application/programmer-failure boundaries and primary/suppressed relationships remain unchanged. ADRs 0011, 0017 and 0026 govern.

The implementation worker owns `AcquiredMailTransport` cleanup, only narrowly necessary provider-owned abort handling if existing controls require it, a unique deterministic regression class/fixtures, and applicable clean documentation surfaces. It records the accepted clarification in ADR 0011 and reconciles the concurrency guide, ADR index, unreleased release notes, both OpenRewrite inventories and affected website guidance. It inspects README, Journal, case studies, downloadable examples, API/Javadocs, CLI/cache, Spring/config/serialization surfaces and returns either changes or reasons no update is needed. The lead owns this plan, the pre-existing dirty cleanup script and combined review/publication coordination.

The worker is not alone: preserve and accommodate all other edits. Implement and test in an owned frozen source export under `.local/review-cleanup-cancel-20261101/source`, never shared root targets or installed development coordinates. Website verification uses a selected owned export, without changing shared dependencies or introducing a junction. Integrate/commit only after lead review; delivery is serialized with any newly approved work.

## Impact map and verification order

1. Caller-supplied cooperative-only provider -> accepted submission -> blocked independent close -> late cancellation -> failure/cause plus retained accepted receipt, rather than false successful completion. Use latches and the real public async handle.
2. Equivalent timeout and no-stop paths, programmer/JVM errors, provider cleanup causes and already failed submissions. Do not alter acceptance/retry semantics or invent receipts.
3. Pooled release/disposal and retained connection scopes where this cleanup boundary is shared. Preserve primary/suppressed ordering and shared-scope receipt ownership.
4. Existing managed Angus accepted-then-aborted-QUIT control. The narrow provider-owned suppression must continue to work; do not replace it with broad stop-flag suppression or message matching.

Start with a focused failing behavioral regression, then the minimal correction and relevant controls. Execute library checks on actual Java 11 and Java 21, use `-Dlicense.skip=true` on Windows, avoid external mail and shared build outputs. Run only checks justified by the changed paths and failures; distinguish targeted coverage from full-suite claims. No CLI generation is expected for unchanged public builder inputs.

Issue #949 uses milestone 10.0.0 and the existing Simple Java Mail 10.0.0 board, In Progress during execution. Issues/commit prose contain behavior, usage, architectural decisions and delivered references only: never test or verification evidence. Report evidence directly to the maintainer and retain task-owned logs. Publish website companions before the parent gitlink; after accepted delivery add after-usage completion guidance, preserve the before issue body, close completed work and set Done.

## Delivery and cleanup

The lead accepted the production/test/documentation delta after reviewing the public regression and unchanged controls. Actual Java 11 and Java 21
each completed the 74 selected cases without failures, errors or skips. The first instrumentation-startup failure was a harness fault; a separate
behavioral baseline then exposed six suppression failures with seven unchanged controls passing. Startup agent configuration remained process-local,
with normal JaCoCo enabled and no POM/configuration change. Logs remain under the exact task scratch root and in the direct maintainer conversation.

Published source is `040b7f9036a55c57dbfda4b1d3b48e7fdb6c5490`, with shared documentation/gitlink `3ddb37d27d14dbb2705734807d5f353066635fb6`.
Website publication is `1c7d39575ae6953aa57d12e150055bf9ef421231`, published before the parent gitlink. All nine main source/documentation files
match the reviewed export. The website archive checks/build/rendered text and links passed; its skipped workbench-linkage check ran separately
against the actual checkout. No changed public signatures, builder metadata, Spring/configuration or CLI cache inputs required generation. README,
Journal, case-study and downloadable examples retain their existing applicable receipt/ownership guidance.

The website export moved from `simplejavamail.org/.local/949-cleanup-cancel-website` into `.local/review-cleanup-cancel-20261101/website` after
both paths were verified inside the workspace. The dirty cleanup script retains only this round's exact scratch/plan additions, preserving other
changes and all existing guards. Its syntax is valid; the full dry run stops at the older `.local/tls-inspection-20261030/source/simplejavamail.org/node_modules`
reparse point. No deletion was attempted.

## Other findings

Finding 2 is the next design discussion; it is not authorized for implementation. Findings 5, 8 and 9 remain unapproved. The maintainer rejected finding 6's runtime-regeneration remedy and clarified build-time packaged metadata; its architectural follow-up remains separate. Earlier findings 3, 4 and 7 are delivered under #946, #947 and #948.
