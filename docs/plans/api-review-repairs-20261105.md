# Accepted API review repairs, 5 November 2026

Retired after delivery. #488, #704 and #961 are closed/completed and Done. Main is published at
`a4c28a8345190c20f31b1d190545c59d5f87db76`; website at `b6699d55dab4d8173a280a8a0a98799cac90aa51`.
All five task main/nested website checkout pairs are retired. The original staged patch and existing working files are preserved.
The bridge-related findings coordinate with #922; this round starts no separate bridge implementation. #752 keeps its existing
deferral while #922 owns replacement and dependency coordination.

Follow-up coordination, 5 November: the maintainer identified the existing "SJM SOCKS investigation (Angus)" session
`01a1873a-cc6a-7cd1-a0f3-8a24d0cbc41b`. Its direct-route implementation is pushed on `codex/socks-protocol-repairs` at
`02566aa256bd8460b65af14d601b1af1acc62101`. Angus PR #228 is merged at `74a8e1be20011123d5930ef1d112ad6d10490614`.
The branch's ADR 0042 retains the bridge for the ordinary Angus 2.0.5 lane. #752 and #922 now cross-link the logging-only and
empty-batch acceptance cases; this replaces this round's standalone bridge-scheduling question. Both tracking issues remain open.

Automatic approval review rejected deleting this temporary plan and `.local/api-review-20261105`, stating only "blocked by policy".
Their exact cleanup paths/statements remain in the root cleanup script. The separate evidence bundle under
`.local/api-review-20261105-evidence` is intentionally retained for maintainer inspection. The earlier broad cleanup dry run also
stopped at the pre-existing Calendar dependency junction; that unrelated guard was not bypassed. The sections below are historical
work-packet context, not outstanding implementation work.

Baseline: main `734058b7874084e72cfdb04aaf3944a86c4555b7` on `codex/10.0.0`; website
`ad01c8fa29153553f8d99962820b6a1e6656f2bf` on `codex/10.0.0-content`. Target: unreleased 10.0.0.

The maintainer accepted the proposed solutions and explicitly authorized subagents to finish their owning issues through the
review workflow. This includes implementation, verification, documentation, selective commits, publication to the existing
development branches and issue/project completion after lead acceptance. No release, force-push, unrelated cleanup or change to
the separate #922 direct-SOCKS work is authorized. Preserve all existing staged, unstaged, untracked and nested-repository work.

## Ownership and delivery

| Owner | Issue and approved behavior | Isolated verification/source checkout |
| --- | --- | --- |
| Conversion worker | #961: static combined MSG artifact conversion preserves acquisition/format failure; automatic malformed signed-child interpretation preserves readable neighbors and original resource with source-linked Problems. | `C:/workstation/sjm-review-20261105/961` |
| OpenPGP worker | #704: application passphrase-provider exceptions remain exceptions with original cause; genuine crypto failures keep existing statuses. | `C:/workstation/sjm-review-20261105/704` |
| Daemon worker | #488: require regular existing state files before permission mutation, including ordinary daemon.log; preserve wrong-type targets on refusal. | `C:/workstation/sjm-review-20261105/488` |
| Lead | Shared documentation integration, actual combined acceptance, branch/index coordination, cleanup registration and remaining design decisions. | Existing dirty checkout; never run concurrent worker builds here. |

Final combined verification and selective delivery use isolated main and nested website worktrees at
`C:/workstation/sjm-review-20261105/combined`; root is their sole owner.
Publication history is assembled in a fresh isolated main/nested website pair at `C:/workstation/sjm-review-20261105/delivery`.
Its final canonical source blobs must match the completed combined-verification snapshot before publication.

The existing #752 issue explicitly defers logging-only bridge startup until after 10.0.0. The lead has asked whether this round
overrides that scheduling decision and includes its empty-batch trigger. Do not implement or change its milestone until answered.

Workers own their issue's source, focused tests, feature ADR and issue-body addendum, plus their own isolated website checkout and
feature documentation. Root owns shared `README.md`, `RELEASE_HISTORY.md`, `docs/adr/README.md`, OpenRewrite research/implementation
documents and `cleanup-api-review-20261021.ps1`; workers supply exact recommendations for those surfaces. Coordinate changes to shared
website pages before integration. Worker checkouts must remain isolated from each other and must not reinstall project artifacts into
the shared Maven repository. Use the reactor and an optional task-owned local repository if installation is essential.

Each worker follows docs/API_REVIEW_WORKFLOW.md, API_EXPANSION_WORKFLOW.md, DEVELOPMENT.md and CODING_STYLE_GUIDE.md. First extend the
owning feature issue with dated problem/before usage, accepted behavior and ADR links, reopen it and use In Progress. Keep all execution
evidence out of GitHub issue/commit prose. Prepare the complete diff and evidence for lead review before publication or closure. After
lead acceptance, coordinate selective delivery, website first, then main, an AFTER-first completion comment, closed/completed and Done.
Root will inspect the actual combined diff, final selected trees and remote/issue state. Workers must not stop at a locally passing patch.

## Impact map and before/after contracts

### #961 combined MSG artifact conversion

Before: `EmailConverter.outlookMsgToEML(new ByteArrayInputStream(new byte[0]))` can export the blank source-only inspection Email.
After: this direct combined operation rethrows its recognized import failure, like the existing fluent artifact terminal. MSG-to-Email
still returns an inspectable source-only Email; an application may explicitly choose to export it later. Preserve static stream-closing
semantics and fluent borrowed-stream semantics. No historical Problems gate, additional source reads or public API is added.

Paths: static EmailConverter MSG-to-MIME/EML overloads -> ConfiguredEmailConverterImpl FailedOutlookInput -> artifact conversion;
compare FluentEmailConversions acquisitionFailure guard. Primary files: EmailConverter.java, ConfiguredEmailConverterImpl.java,
FluentEmailConversions.java, ArtifactConversions.java, new/extended conversion regression tests. ADR 0041 owns the clarification.

Cases: empty/malformed/missing/partly unreadable MSG; String/File/InputStream entry points; static MIME and EML targets; successful
MSG with all represented content; direct/fluent failure parity; stream close and suppressed causes; explicit Email inspection/export.

### #961 malformed independent S/MIME attachment

Before: ordinary mixed EML with readable text and `application/pkcs7-mime; smime-type=signed-data` attachment containing non-CMS bytes
throws after ordinary MIME parsing. After: keep parent text, original resource and acquired source evidence; record a child-linked
protection Problem and continue neighbors. Do not promote child failure to parent crypto facts or silently discard original content.
The accepted scope preserves explicit wrong-key/password and low-level helper exception contracts; do not generalize arbitrary runtime
or application exceptions into malformed input. Bring any incompatible boundary back to the maintainer.

Paths: MIME/EML and Outlook import -> optional attachment inspection -> signed-content extraction -> inspection assembly. Primary files:
ConfiguredEmailConverterImpl.java, SMIMESupport.java, producer-owned/core internal result contracts only if needed for typed component
failure facts, focused integration tests. ADRs 0035/0041 govern recovery, 0008/0040 govern protection facts and retained merging.

Cases: actual automatic malformed child without decrypt options; good and bad sibling attachments in both orders; source identity and
Problem links; eager/deferred reads; original bytes/resource retention; copies/serialization; unaffected valid signed attachments;
explicit wrong keys/passwords, callback failures, cancellation and Errors preserve existing exception boundaries.

### #704 deferred passphrase callback failure

Before: throwing `IllegalStateException("vault unavailable")` from `passphraseProvider` becomes generic FAILED decryption.
After: original application failure escapes supported import/conversion/protection paths while genuine wrong passphrases, missing keys,
bad ciphertext and signature failures keep established facts. Provider selection stays lazy; no getters/configuration call invokes it.

Paths: OpenPgpReceiveConfig callback -> PgpainlessOpenPgpAdapter -> OpenPgpSupport catch/recovery -> public static/fluent MIME/EML imports.
Primary files: adapter/support plus OpenPgpReceiveConfig setter Javadoc and OpenPGP regression tests. ADR 0008 feature clarification;
cross-reference ADRs 0035/0041. Cases: matching encrypted key that actually invokes provider, original exception/cause identity, runtime
exception and cancellation, successful/fallback/null callbacks, missing keys and bad crypto, borrowed arrays and cleanup, nested messages.

### #488 private regular state files

Before: `sjm daemon run` with an existing daemon.log directory applies file permissions, removing traversal bits on POSIX.
After: central ensurePrivateFile rejects nonregular existing paths before permission changes. Preserve symlinks/dangling targets and
wrong-type permissions/content; regular files retain private setup. No expanded hostile same-user replacement guarantee is introduced.

Paths: foreground/bootstrap and detached start -> paths.prepare/lock -> configureDaemonLogging -> DaemonStateSecurity; audit all callers
of ensurePrivateFile. Primary files: DaemonStateSecurity.java, DaemonServer.java if required, focused daemon tests; ADR 0019 clarification.
Cases: directory, symlink, dangling link, ordinary existing/new file; POSIX preservation and ACL control; foreground and detached routing;
lock/discovery/startup-log behavior; useful CLI refusal with no daemon resources leaked.

## Surface matrix and verification order

Workers first characterize important unchanged boundaries and run a focused failing regression on the pristine baseline. Fix and run
focused actual-entry regressions, then the relevant reactor slice on modern JDK and a real JDK 11 for library changes. CLI checks use
JDK 17+; set `-Dlicense.skip=true` on Windows. Inspect XML totals because Maven may ignore test failures. Do not describe a later-added
test as strict baseline-red evidence. Preserve original failures separately from corrected results.

For every issue reconcile public Javadocs, optional integrations, configuration/locks, copies/serialization, CLI generation/help where
applicable, README/release history, ADR/index, website feature/migration guidance, Journal/case-study/downloadable examples and
OpenRewrite fixtures/manual-review boundaries. Record updates or precise no-change rationale. Unreleased-only repairs create no
invented 9.x migration recipe. Website docs use current behavior; any migration claim must identify an affected published baseline.

After worker-level checks, root reviews the combined diff and runs appropriate combined reactor and website checks on isolated final
sources, including generated/package checks affected by final changes. Broaden only for new concerns. Required docs/render/link checks
must use final selected sources. Root independently checks selected commits, remote heads, issue completion and board state.

## Retirement

The lead registers exact worker/scratch/worktree paths and this temporary plan in the existing guarded cleanup script, preserving all
guards and WhatIf support. Worktrees require ordinary Git worktree retirement after accepted delivery; no broad parent deletion or
retry of unrelated rejected cleanup. Keep execution records in bounded scratch/CI artifacts and the direct maintainer report, not
issues, commit messages or permanent ADRs. No arbitrary deletion is authorized by registering paths.
