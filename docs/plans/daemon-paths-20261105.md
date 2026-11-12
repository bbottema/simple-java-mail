# Consistent daemon runtime path derivation

Accepted for execution on 5 November 2026. After agreeing this is a straightforward consistency correction, the maintainer explicitly instructed: "Implement. Subagent. Complete." This authorizes implementation, verification, applicable ADR/documentation alignment, reviewed selective delivery to existing SJM/website development branches and owning issue/project completion. No release, new CLI/API setting, weaker discovery validation, forced transport change or forwarding-API repair is authorized.

Baseline: main `e458b76c7f99c3a6a27a909098c803b54869cf50` on `codex/10.0.0`; website `86ce310498495ee9cadc408bcb20f2c4351d1867` on `codex/10.0.0-content`. Recheck live heads/issues before mutation. Authentication registration and S/MIME attachment workers run independently in separate task directories. Preserve all canonical dirty files, staged research and untracked work.

## Accepted behavior

```sh
SJM_DAEMON_STATE_DIR=./sjm-state sjm daemon start
```

Keep relative state roots supported. Resolve the selected root once to a lexically normalized absolute path before deriving instance/discovery/socket identities. Use the identical selected inputs for the client and child, including the temporary-directory input used by the short Unix-socket fallback. Do not require the state directory to exist merely to normalize it, add filesystem/symlink canonicalization, or change ordinary relative-path meaning.

`DaemonPaths.forInstance` currently normalizes only the instance directory, while its long-path fallback hashes the raw configured root. `DaemonProcessLauncher` passes the normalized absolute state root to the child. With a relative/unnormalized root and a preferred Unix socket longer than the existing 96-byte threshold, parent and child consequently derive different temporary socket directories; strict discovery validation refuses the child and readiness times out. The same derivation inputs must survive launch for custom temporary roots too.

Correct input capture/derivation/launch propagation, retaining current socket-length handling, instance/user/version isolation, private-state checks, locks/logs/discovery ownership, HMAC/process identity checks, shutdown and approved IPC fallback policy. Do not accept arbitrary published endpoints or fall back to TCP because authentication/derived-path validation failed. TCP controls should remain unchanged; the regression must explicitly prove the Unix path rather than accidentally exercising TCP.

Governing record: ADR 0019, with ADR 0018 bootstrap/CLI boundaries as applicable. Record the actual accepted normalization choice in the owning record. This is an unreleased #488 daemon-feature correction; establish history and use its existing issue with a dated accepted addendum/reopening, not a new published-defect ticket.

## Ownership and coordination

Worker `daemon_path_delivery` owns isolated `C:/workstation/sjm-daemon-paths-20261105/{sjm,website}` checkouts and bounded evidence/tools beneath that task root. Likely source ownership: `DaemonPaths.java`, `DaemonProcessLauncher.java`, focused path/launcher/discovery/actual detached-start tests and ADR 0019. Inspect other bootstrap/IPC paths only as required by the correction. No shared-root builds or local-only Maven installs into shared storage.

You are not alone: preserve others' edits and accommodate concurrency. `oauth_registration_delivery` owns pool/auth/core-batch changes and ADR 0015/0039; `smime_signed_attachments_delivery` owns conversion/S-MIME processing and ADR 0008/0035/0040. Shared ADR index/release history/OpenRewrite inventories and website CLI/migration content must be integrated against current accepted heads and publication serialized through root. Coordinate heavy local Maven reactors with both workers; small focused tests can proceed, but avoid simultaneous broad fixture-heavy runs. Root owns this plan, canonical cleanup script, combined acceptance and final canonical alignment.

## Verification and propagation

Read docs/API_REVIEW_WORKFLOW.md, API_EXPANSION_WORKFLOW.md, DEVELOPMENT.md and CODING_STYLE_GUIDE.md. Resolve live issue #488, preserve existing context/labels, use milestone 10.0.0/existing board/In Progress while work remains. Public issue/commit prose must contain behavior, minimal before/after usage and ADR/commit references only; never test/build/verification evidence or execution diaries. Return evidence directly to root/maintainer and bounded retained files.

Start with an actual failing regression on pristine baseline for long relative-root detached launch; preserve raw/normalized derivation comparison and parent/child address evidence. Include a live Unix-domain-socket readiness path where supported, an equivalent normalized absolute root, `.`/`..` spelling, custom temporary base propagation, ordinary short paths/default roots, distinct state roots/instances and TCP controls. Rejection of an unrelated or tampered endpoint must remain rejection. Capture route selection, child PID lifecycle and cleanup so a passing fallback or leftover child cannot masquerade as success. If the local runtime lacks Unix support, obtain an appropriate bounded supported test environment or report the real coverage limit; do not silently skip the central behavior.

Use required CLI Java 17+ verification and packaged/child-launch controls with fresh reactor outputs. Run focused controls first, then the applicable CLI/full configuration gates and packaging checks on supported modern Java, coordinated with other broad runs. Java 11 cannot run the CLI; document why an unchanged library-only Java 11 lane is unnecessary unless the implementation crosses that boundary. Inspect current-run XML/gates rather than Maven success alone. Keep original failures distinct from corrected results. No timing changes, skipped cases or unrelated agent/dependency repair to hide failures.

Reconcile source/Javadocs/options/configuration, ADR/index, README/release history, website CLI/migration pages, Journal/case-study/downloadable examples and OpenRewrite inventories with exact no-change reasons where unaffected. No new property or help signature is planned; inspect generated-cache/package implications rather than assuming a signature search proves them. This unreleased feature correction creates no fictitious released 9.x rewrite. Compile applicable usage and inspect rendered content/anchors/links; avoid claiming browser inspection if current settings prohibit it.

## Acceptance and completion

Send root actual combined source/test/doc/website diffs, current behavior and acceptance cases, precise evidence with source/runtime provenance, surface matrix and any remaining limitation before publication. Keep independent approved work moving while awaiting lead review; raise a genuinely changed contract/scope for maintainer decision rather than expanding this repair.

After root accepts, selectively commit with owning issue suffixes/architecture bodies and no execution evidence, publish website before parent gitlink on the existing branches, complete AFTER-first issue comment/closed/completed/Done and remote-state readbacks. Incorporate concurrent accepted head advances without force pushing. Do not stop at a local patch or passing tests.

Report exact commits, issue/project state, owned processes, retained evidence and disposable paths. Stop all owned daemon/test helpers. Root coordinates canonical dirty-checkout alignment and bounded cleanup registration/retirement, preserving evidence/other tasks. Do not retry earlier policy-rejected deletions or invoke broad cleanup against unrelated registered targets.
