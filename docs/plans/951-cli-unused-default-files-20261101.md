# CLI reuse and unused default security files

Retired after delivery: #951 is closed/completed and Done in milestone 10.0.0. The accepted design `8bf73ab1cbc5e5737d6fc3c24b7560d4235081d9`,
source `8d8fd498476efa044e66c78d379545285071f301`, documentation/gitlink `2060bc29a05edc3a8afc45344264608293d2e658` and cleanup bookkeeping
`4ea060213c81744f1319f032ac28f1220b7cae2e` are published on `codex/10.0.0`; website `52cf2042126125bc9b7a02cf2dc35240982f5c28` was published first.
The lead reviewed the actual code/tests/companions and confirmed all eight published files against the export, remote heads/gitlink and staged preservation.
Final actual Java 17 and Java 21 each completed 50 selected cases with no failures/errors and one existing Unix-only filename skip on Windows; all 20
new cases passed. Website checks/build/rendered guidance and links passed. No Java library, builder/help, schema or public API change required additional
Java 11 execution or CLI metadata generation. No accepted implementation, documentation, review or issue work remains.

Only bounded disposal remains. Cleanup syntax is valid; the full dry run stops at the older
`.local/tls-inspection-20261030/source/simplejavamail.org/node_modules` reparse point. No deletion was attempted. The exact round scratch/retired-plan
paths are on the existing guarded allowlists with manual statements, including the relocated website export beneath the round root.

Design accepted on 1 November 2026 under [#951](https://github.com/bbottema/simple-java-mail/issues/951). The maintainer said, "Agreed to the proposed fix."
The maintainer then answered "Absolutely" to delegated implementation and completion of verification, documentation, delivery and issue closure.
This authorizes execution and delegation, reviewed selective commits/publication on `codex/10.0.0` and applicable `codex/10.0.0-content`, and completion
bookkeeping for this issue. No release, deployment or history rewrite is included. Lead review and serialized delivery are coordination checkpoints,
not further permission requests.

Baseline: main `f3a7de96849ab6e879bd39a239503b7102f75441`, website `36488451157b23ca17c2489cfe7bb4b088a0fb17`; both remote heads match.
Preserve unrelated staged/unstaged research, workflow/cleanup edits, nested website work, untracked plans/scratch and registered worktrees.
Pre-recording index copies, binary diffs and state/worktree inventories are in `.local/review-unused-defaults-20261101/preservation`.
Execution preparation is also captured under `.local/review-unused-defaults-20261101/execution-baseline`. The accepted ADR-only local commit
`8bf73ab1cbc5e5737d6fc3c24b7560d4235081d9` records the design; use it as the frozen source-export baseline so the agreed ADR text is retained.
It has not been published separately. Source behavior at that revision remains the original failing profile behavior.

## Agreed caller behavior

Configured ordinary DKIM/S/MIME material can be disabled by `--mailer:withoutEmailDefaults`. Its unused files must not be opened merely to
derive the CLI's Mailer identity, and changing those files must not replace a compatible retained Mailer. Active defaults and mandatory locked
material retain their file-read/fingerprint and validation behavior. The ordered `clearEmailDefaults` CLI adapter restores captured defaults;
Java's corresponding method is `useConfiguredEmailDefaults()`.

The primary's defaults choice does not disable independently resolved selected named-Mailer defaults. Preserve one-shot/daemon consistency,
selected/transitive route ownership, configuration-relative paths, DKIM `file:`/`base64:`/legacy interpretation, HMAC/redacted identity and
normal builder/lock enforcement. No new public API, property or security policy is intended. ADRs 0039, 0019 and 0025 govern.

## Ready implementation packet

The implementation worker owns `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliMailerProfile.java`,
only narrowly necessary CLI-local selection helpers, and a unique profile/default-selection regression class. Avoid changing the Java governance
contract: `EmailGovernanceImpl` already skips the unused reads. The worker is not alone and must preserve/adapt to others' edits. Use an owned
frozen export under `.local/review-unused-defaults-20261101/source`, no shared root targets or installed development coordinates.

The lead reviews the actual combined delta before integration/publication. Clean companion ownership will cover ADRs/index, unreleased release
history, both OpenRewrite inventories and applicable CLI/configuration website guidance. The lead retains the pre-existing dirty cleanup script
and this coordination plan. Recheck every owned path before copying, staging or committing; preserve unrelated staged state exactly.

## Impact map and required checks after approval

1. Ordinary configured material + parsed `withoutEmailDefaults` -> profile -> actual public command. Reproduce an existing unreadable key
   causing `Unable to derive Mailer identity` before SMTP; after correction the command reaches its intended connection/validation path.
2. Disabled default material rotation -> same effective profile and actual retained Mailer reuse; enabled/restored material rotation ->
   different profile and refreshed Mailer. Exercise both option orders and repeated selections through the registered CLI model.
3. Locked material remains read/enforced, including unreadable required-file failure and conflicts. Preserve independent selected named
   route defaults and the already accepted unused-named-route behavior; a primary opt-out is not a transitive policy change.
4. Cover the three existing security-file property families, explicit DKIM prefixes/inline controls, configuration-directory ownership and
   one-shot/daemon command routes. Reuse current `CliSelectedMailerProfileTest` and registry fixtures rather than inventing a second parser.

Use a focused failing behavioral regression followed by the minimal correction and relevant controls. The CLI requires Java 17+, so use actual
Java 21 locally and an available Java 17 lane where required; do not claim Java 11 execution of the CLI. Preserve Java 11 library compatibility if
shared library code changes. Use loopback/synthetic fixtures, no external email, Windows `-Dlicense.skip=true`, and process-local test-agent setup
only if necessary while retaining normal JaCoCo. Begin with the focused behavioral regression and preserve the actual failure/fix evidence internally.

Review public API/Javadocs, generated CLI help/cache inputs, configuration/locks/Spring, copying/serialization, README, website, Journal,
case studies/downloads and OpenRewrite applicability. No CLI generation is expected for unchanged builder inputs; any applicable generation
must be explicit at build time, never a runtime-regeneration repair. Distinguish unreleased profile behavior from real released-9.x migration.

## Tracking and delivery gate

Issue #951 is open, milestone 10.0.0, canonical bug/Priority-Medium labels, and In Progress on the existing Simple Java Mail 10.0.0 board.
Execution and delivery are authorized; publication/closure follow lead acceptance of the actual combined result.
Issues and commits contain behavior, usage and architectural/delivered references only, never test or verification evidence. Completion begins
with after usage, preserves the original before body, and closes/marks Done only after accepted delivery. Exact scratch/retired-plan paths are
tracked for guarded cleanup at completion; no deletion is authorized by this design approval.
