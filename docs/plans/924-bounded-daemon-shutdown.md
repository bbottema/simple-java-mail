# Bounded daemon shutdown (#924)

Retired after delivery on 30 October 2026. Main `a8ae33db2d050158d0af223340a277aae660defc` and website `3508d393b0cca5f9c3e1821ec8b38d56838d7f67` are published on the approved 10.0.0 development branches. #924 is closed/completed and Done, classified as an enhancement. This private working plan is excluded from the commits and retained only because cleanup is blocked by the existing older reparse-point guard.

Approved design and implementation: 30 October 2026. Tracking: [#924](https://github.com/bbottema/simple-java-mail/issues/924).

The maintainer approved fixed timing (30 seconds for accepted work, 60 seconds overall), CLI-only scope and the stronger graceful-cleanup behavior, then accepted all five production-code review batches and the Linux follow-up. On 30 October 2026 the maintainer requested completion of the review workflow and classification as an enhancement. Delivery is authorized to `codex/10.0.0` and `codex/10.0.0-content`, including selective commits, website-first publication, issue completion and guarded retirement. No release, deployment or history rewriting is authorized.

## Behavior

Before: `sjm daemon stop --daemon-instance=work` can remain pending behind graceful Mailer closure after the request-worker wait ends. A client timeout does not end the daemon, so restart cannot proceed.

After: the same command stops admission, allows accepted work 30 seconds, interrupts workers and preserves uncertain results, then shares the remainder of one 60-second monotonic budget between reply/network draining and Mailer closure. An independent watchdog terminates only the actual standalone daemon process if code or cleanup cannot finish. The existing client waits for actual exit. Restart starts its replacement only after that exit.

Normal Java Mailer closure stays graceful. Existing CLI commands, synchronous execution, public Mailer contracts, protocol/version and configuration remain unchanged. Duplicate stop requests do not reset the deadline. Client disconnect cannot cancel shutdown. Unresolved work is never automatically resent. A watchdog does no logging or blocking cleanup before termination. Keep it armed through process exit. Programmatic bootstrap/test fixtures cannot halt their hosting JVM. The first shutdown origin owns termination: JVM-first preserves normal signal exit behavior, while authenticated-stop-first retains its watchdog through later JVM shutdown. Hook waiting remains bounded.

## Ownership and impact map

| Owner | Files and boundaries |
| --- | --- |
| CLI worker | `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/` shutdown coordination, server, registry and bootstrap; `modules/cli-module/src/main/java/org/simplejavamail/cli/SimpleJavaMail.java`; corresponding daemon unit/process tests and test fixtures. New helper types stay package-private except an explicitly named standalone bootstrap shim needed across packages. No core/main/provider changes. |
| Lead | ADR 0019 and cross-reference from ADR 0016/index; `modules/cli-module/DAEMON.md`; unreleased `RELEASE_HISTORY.md`; root README only if applicable; nested website CLI and migration guidance only where behavior needs correction; OpenRewrite/no-migration rationale; task plan and preservation snapshots; issue bookkeeping and combined acceptance. |
| Independent reviewer | Read-only review of the final CLI and documentation diff plus actual evidence. No shared-file edits or publication. |

There is concurrent Angus and SOCKS work and historical dirty root/website content. Preserve it. Original indexes and dirty tracked-file fingerprints are retained in `.local/daemon-shutdown-20261030/baseline-preservation.json` with index copies. Never stage broadly, reset, clean, replace dependencies globally, or touch another task's scratch/worktrees. The existing cleanup junction guard stays intact; this task does not need cleanup-script edits.

## Implementation boundaries

- Capture authenticated-stop authority at the validated request boundary, before waiting on the admission gate or doing blocking cleanup. Quiescing owns its one-time admission transition separately. A valid STOP during established-runtime FAILED/QUIESCING cleanup enforces the existing remaining budget, while invalid authentication/startup failure without STOP stays unarmed. Never share the watchdog thread with socket-deadline callbacks or command/cleanup executors.
- Preserve refusal of unadmitted original requests, accepted-command ledger ownership, duplicate observers and completed results. Fence unresolved accepted results as ambiguous after the application grace period.
- Start tracked asynchronous shutdown closure for all registered Mailers without serial blocking. Preserve close-once retirement, construction/shutdown races and per-entry failures. Keep ordinary idle retirement unchanged.
- All application/network/registry waits use remaining monotonic time. One blocked Mailer must not strand the controller or prevent other closure attempts.
- Normal discovery/lock release follows real completion. If work remains at the deadline, keep ownership until process death; stale-state recovery then acquires the instance lock and validates state as before. Do not claim STOPPED while unresolved work remains.
- Terminate the standalone daemon itself with `Runtime.halt(0)` only as a final deadline action. Embedded bootstrap and in-process test seams never perform a real halt. No client-side PID killing. Preserve existing stop success-on-exit and signal statuses.
- Use intent-revealing local helpers and existing code style from `docs/CODING_STYLE_GUIDE.md`; avoid new public API, unnecessary generic frameworks and configuration knobs.

## Verification order

1. Narrow failing regression on unchanged production code: indefinitely held actual SMTP response/read timeout zero, stop requested, server never released. Preserve bounded child-process cleanup so the regression cannot strand the test runner.
2. Deterministic coordinator/registry tests with injected short budgets, clocks and recording termination; verify 30/60 defaults, first-stop ownership, independent watchdog, no embedded halt, close-once and late construction.
3. Real child JVM controls: normal completion during grace; held pooled/non-pooled SMTP; uncooperative command and blocked Mailer cleanup; duplicate/disconnected stop; restart/same-instance recovery; TCP and supported UNIX; platform-supported signal shutdown. Include a production-timing case. Keep test-only injection out of public/system-property configuration.
4. CLI tests on JDK 17 and 21, appropriate Mailer lifetime controls, fresh standalone packaging and thin-bootstrap checks. Use isolated validation snapshots so Maven plugins cannot rewrite unrelated checkout sources. Record terminal reports and compare unrelated baseline failures honestly.
5. Website check/type/template/build and inspect affected rendered CLI/migration paragraphs. Record API/property/CLI metadata/serialization/OpenRewrite no-change rationale when applicable.
6. Review actual combined diff, source/evidence provenance, unchanged original index and unrelated dirty content. Finish with a concise local result and leave #924 open pending authorized delivery.

## Documentation matrix

ADR 0019 owns the daemon process deadline and fallback; ADR 0016 continues to own graceful Mailer lifetime. CLI operations and website guidance describe grace, overall limit, uncertainty, restart and stale-state recovery without promising a message was unsent. Unreleased release history links #924. There is no released API migration, new OpenRewrite transformation, property/Spring metadata change, CLI builder-cache regeneration or Java baseline change.

## Progress

- Issue created with bug/Priority-High, milestone 10.0.0; project status In Progress.
- CLI worker `daemon_shutdown_924` owns the implementation and isolated regression/verification snapshots under `.local/daemon-shutdown-20261030/worker`.
- ADR/index, operations, unreleased release history, website CLI/migration and OpenRewrite target-fixture guidance drafted by lead. Root README and Journal/case-study usage do not describe a conflicting daemon stop contract and need no edits. No public API/property/serialization/Spring/builder-cache change is planned.
- Website checks passed all 109 tests plus template/type checks; isolated render output contains both shutdown anchors and the cross-page link. Logs: `website-check.log` and `website-render.log` in task scratch. Both original index files still match the preservation snapshot.
- The unchanged-production JDK 21 process regression failed exactly at the 65-second daemon-exit assertion while SMTP remained held: one test, one failure, no errors. Its owned child was then terminated by fixture cleanup. The report is under `worker/source/modules/cli-module/target/surefire-reports`; `worker/red-jdk21.log` retains the full run.
- CLI implementation and companion documentation are complete locally. Review corrections cover non-starving frozen-entry close fan-out, removed/late construction ownership, actual cleanup after interrupted close, first-origin termination authority, standalone-only halt and a nonblocking bounded JVM hook.
- Broad v2 CLI21:151 cases,0 failures,0 errors,1 Windows signal skip. Affected v2 CLI17:41 cases clean with1 platform skip. Final v4 affected17:19 passed. Final v4 affected21:18 passed/1 replacement-startup failure; a diagnostic rerun and5 sequential actual restart trials then passed. Original startup cause remains unproven. Do not label the raw v4 run wholly green or the failure baseline without evidence.
- Fresh publish-cli packaging17 and packaged CLI runtime smoke17/21 passed; current classes, distribution JAR and bundled docs match the artifact manifest. Java17 ordinary Mailer lifetime19 passed; Java21 queue14 passed while5 unchanged main-only operation tests retain a documented Mockito/ByteBuddy setup limitation.
- Full records and source/artifact manifests are indexed by `.local/daemon-shutdown-20261030/verification-summary.md`. Main index stat metadata changed, but root compared all blob/mode/path entries and the original staged binary patch to the saved index: identical. Website index bytes and original dirty file contents remain intact. No index restoration is needed.
- The final validated-STOP/FAILED-cleanup edge has behavioral red/green evidence: a preaccepted authenticated connection must acquire stop authority even after listener failure, while invalid HMAC stays unarmed and retains the original failure status. The epoch is never reset. Final focused controls passed28 cases on each JDK17/21 with0 failures/errors/skips; current-byte packaging and packaged smoke17/21 passed again.
- Independent final combined review accepted the local code/documentation with no remaining actionable finding. Keep the recorded unproven earlier21restart failure, unchanged21Mockito limitation and Windows signal coverage boundary. Root verified the final source manifests, actual archive hash, unchanged original dirty contents and original staged patch.
- Implementation, local/human/independent review and authorized selective delivery are complete. The enhancement is published website-first; both remote heads and the parent gitlink match. #924 is closed/completed, milestone10.0.0, labels enhancement/Priority-High, project Done. Completion comment starts with the final after usage and contains no verification prose.
- Following all five production-code review batches, the maintainer accepted retaining the stronger graceful-cleanup behavior and authorized Linux validation with Docker/WSL under a low-disk constraint. Existing cached Java17/21 Docker images ran45 checks each with0 failures/errors/skips; Java21 used non-root UID65534. Both packaged lanes exercised native Unix sockets, stop exit0 and SIGTERM exit143. Additional active-command SIGTERM controls passed on both JDKs with ambiguous accepted outcomes. The former Unix-signal coverage gap is now exercised on Linux.
- Linux validation used read-only existing artifact/cache mounts and RAM-backed runtime scratch, with no image/dependency downloads, Maven rebuild or new repository copy. Only small records remain under `.local/daemon-shutdown-20261030/linux`; all owned containers were removed. No product source changed. The earlier Windows21 startup and Mockito limitations remain documented separately.
- Final enhancement classification is in RELEASE_HISTORY under Enhancements. The bundled-history package was refreshed offline in the existing snapshot; current packaged startup/status/stop passed17/21 and28 direct refreshed-class controls passed. Website final109+type/template checks passed. These delivery records remain private/direct-to-maintainer, not in issue or commit messages.
- Cleanup syntax is valid; -WhatIf stops at the older `.local/import-review-round-20261028/website/node_modules` reparse point. No deletion was authorized by adding allowlist entries and none occurred. Exactly this task's scratch/plan paths are added without modifying the existing guards. Original staged research patch and unrelated dirty contents survive publication unchanged, including pre-existing cleanup-script work after removing the two owned lines.
