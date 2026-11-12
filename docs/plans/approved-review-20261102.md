Retired after delivery: findings 1–10 are complete on main f0323b1a51761430707ee18f1b823a212022ff9c (implementation dc177fcf167edf4f417a95e28c5c1319f53fb634) and website c0d6afb3e4155584875dae8692a95b686a7ac2a4. Issues #704, #726, #800, #950 and #953–#956 are closed/completed and Done. Automatic approval review rejected deletion of this round's exact scratch directory and retired plan with blocked by policy; no deletion occurred. Exact allowlist entries and manual removal statements remain in the cleanup script. The three design questions are now settled: rehearsal lifetime is delivered under #709, image validation timing under #957, and the shared SMTP/SOCKS authentication-failover policy is recorded in ADR 0029 under #922. The existing SOCKS/Angus session owns #922 implementation.

# Approved review repairs, 2 November 2026

The maintainer accepted findings 1–5 and 7–10 for delegated implementation through the complete workflow, including reviewed selective commits, publication to codex/10.0.0 and applicable website codex/10.0.0-content, and issue completion. Finding 1 requires safe binary encoding. Finding 10 carries entry timing across factory execution. The maintainer subsequently selected a facts-only correction for finding 6, approving that scoped repair while preserving existing materialization and failure behavior. Subsequent design dispositions and delivery are recorded in the retirement summary above. No release, deployment, history rewrite or unrelated cleanup is authorized.

Main baseline: 659d44f3e5d33f88db0516adaf97ecec487a2b20. Website: 4f9d7eb62c805e774d923a38f72b57b08a2074a1. Preserve unrelated staged/unstaged research, review-guide/cleanup edits, website narratives/styles/tests and untracked scratch/plans/worktrees. Exact indexes, binary patches, status, non-scratch untracked files and source archives are under .local/review-approved-20261102/preservation and its parent.

## Ownership and acceptance

| Finding / issue | Owner | Production and representative boundaries |
| --- | --- | --- |
| 1 / #704 | repair_protection | SmimeCryptoSupport: safe encoding preserves ordinary/preencoded binary bytes through sign/sign+encrypt; signatures, stable writes, caller ownership and protected children. No unsupported-binary rejection. ADR 0007/0008/0036. |
| 2 / #950 | repair_replies | MimeMessageHelper snapshots: retain matching owned preencoded bytes/marker for BASE64/QP rather than reencode; bytes/borrowed source, every reply route, explicit attachment CIDs, acquired stream closure and unrelated-content/protection laziness. ADR 0008/0036. |
| 3 / #704 | repair_protection | MimeEntitySupport/OpenPgpSupport: initial CRLF/LF empty content headers, later body blank lines, original bytes/default MIME semantics and truthful cryptographic facts. ADR 0008/0035. |
| 4 / #953 | repair_import | MimeMessageParser: narrow disposition/name recovery; usable neighbors, eager/lazy ownership, content failures, source bytes, child boundaries. Released 9.3.9 defect. ADR 0035/0036. |
| 5 / #954 | repair_import | MimeMessageParser: root plain/HTML/calendar content despite attachment disposition; child attachment/multipart behavior unchanged. Released 9.3.9 defect. ADR 0008/0035. |
| 6 / #704 | repair_smime_facts | SMIMESupport: completed native Outlook-envelope decryption remains DECRYPTED independently of materialization; existing protected fallback/body/merge/view and exceptions, signature facts, independent-child ownership, copy/serialization retained. ADR 0008/0036. |
| 7 / #726 | repair_proxy | AngusSocketFactory/Factories, Angus lifecycle/probe adapters, existing core internal MailTransportLifecycleResolver and MailerImpl owned preparation: carry inert resolved endpoint in Session-qualified synchronous context; snapshot/rebind preserves raw inline/separate port/live changes/HTTP precedence, bridge and supplied/custom factory authority. ADR 0010/0017/0038. |
| 8 / #955 | repair_proxy | ConnectionConfigurationResolver and AnonymousSocks5ServerImpl: resolved active port validation before effects; partial-start cleanup retains original/suppressed failures. Passive/inactive/overridden choices unchanged. Released bridge path in 9.3.9. ADR 0010/0038/0039. |
| 9 / #956 | repair_execution | BatchTransportExecutor: retire unstarted scheduling Error, retain Error identity, graceful draining and borrowed executor ownership. Released 9.3.9 defect. ADR 0015/0016. |
| 10 / #800 | repair_execution | notifier/attempt/diagnostic/progress recorders and internal telemetry bridge/Otel runtime: entry origins survive successful/failed factories; preserve context, admission/callback/timeout/Error contracts. ADR 0012/0026/0034. |

Workers own unique regression files and narrowly owned Javadocs. They are not alone: preserve others and adapt concurrent work. Root owns shared ADRs/index, README/release history, both OpenRewrite inventories, website/Journal/case-study/example alignment, issue/project coordination and cleanup. Workers return companion wording/applicability.

## Verification and delivery

Use red-first focused behavioral regressions, then fixes and meaningful controls. Build isolated source exports beneath this task's scratch tree, at most two Maven builds concurrently. No shared root targets or installed development artifacts. Run actual Java 11 and Java 21 library checks; CLI metadata/Javadocs on Java 17+ where applicable. Skip Windows license formatting per DEVELOPMENT.md. Loopback/offline fixtures only.

Root reviews actual combined diffs and evidence, then runs combined checks for interacting paths. Account for public API/Javadocs/CLI metadata; configuration/integrations/locks/copy/serialization; ADRs/concurrency; README/release history; website feature/migration guides, Journal/case studies/downloaded examples; both OpenRewrite inventories. Unreleased corrections do not invent 9.x transformations. Validate changed website rendering/links in an isolated selected export.

Issues belong to milestone 10.0.0 and the existing project, In Progress while work/review/delivery remains. Keep existing bodies/history and dated follow-ups. Never add verification evidence to issue or commit prose. After root acceptance, serialize semantic selective commits with issue suffixes and ADR bodies; publish website before parent gitlink, check remote heads, post after-usage completion, close delivered issues and set Done.

After completion, retire this plan and add exact owned scratch/plan paths to cleanup allowlists; validate syntax and dry run without deletion. Preserve every unrelated cleanup entry and guard.
