Delivered on codex/10.0.0 at 1a2a1d74a4f172780a899af364f70121646fad65 and codex/10.0.0-content at a0f5007e10f69cbb10d99c8ac7580aaf23262783. Approved issues #900 through #907 are closed/completed and Done; configuration-source maintenance has no issue. Only guarded scratch/plan cleanup remains pending because the existing cleanup script stops at an older node_modules reparse point.

# Retire stale retry receipts (#900)

Tracking: [#900](https://github.com/bbottema/simple-java-mail/issues/900). Target: unreleased 10.0.0.
The maintainer approved design, execution and delivery on 29 October 2026. Root review precedes selective commits and pushes to `codex/10.0.0`; website delivery uses `codex/10.0.0-content` where applicable.

The send-execution worker owns the diagnostics recorder, focused retry/provider fixtures and regression coverage, this plan and any necessary clarification of ADR 0011/0012/0030. Other work and the shared index remain untouched. Root owns release history, ADR index, OpenRewrite inventories, shared website pages and cleanup registration.

## Contract and impact map

Retire a prior authoritative receipt precisely at another provider invocation. Preserve the last truthful refusal during waiting and failures before another invocation. Keep intermediate history, original extension failure identity, resource ownership and replay eligibility. A later receipt-free unchecked invocation failure has unavailable final acceptance in terminal observation and telemetry and cannot trigger another automatic replay.

The public usage stays the same: configure `MailSendRetryPolicy`, send through sync/async/batch/open-connection paths, and inspect `MailSendOutcome`. No new methods, properties or migration obligations arise.

Affected execution path: `MailTransportAdapterResolver` calls its pre-invocation hook, `TransportRunner` records invocation, `MailSendDiagnosticsRecorder` retires the previous receipt, and `MailSendAttempt` constructs the authoritative outcome and telemetry report. Retry history remains separate. Replay waiting, cancellation and failed reacquisition do not cross the invocation boundary.

| Scenario | Expected behavior |
| --- | --- |
| Temporary refusal followed by RuntimeException or Error in another invocation | Original throwable, no prior terminal receipt, latest history UNKNOWN, no third invocation. |
| Retry waiting, cancellation or reacquisition failure before another invocation | Preserve the last truthful refused receipt and completed history. |
| Successful retry | Final accepted receipt is shared by caller and outcome. |
| Cleanup failure after a captured result | Retain that invocation's result. |
| Unobserved sends | Keep the shared stateless diagnostics path. |

## Surface alignment

- Implementation and tests: diagnostics recorder, retry/provider boundary fixtures, telemetry attempt boundary, existing retry/cancellation/cleanup controls.
- ADRs: clarify attempt-specific receipt authority under ADR 0011; ADR 0012/0030 retain existing outcome/retry contracts.
- Core API/Javadocs, configuration, Spring and generated CLI: no signature, setting, activation or ownership changes.
- Documentation: clarify receipt authority in `docs/concurrency/11-mail-send-retries.md`; search sending/result/progress guides, Journal, case studies and downloadable examples for any statement that a previous refusal is authoritative after another invocation. Root coordinates shared updates.
- OpenRewrite: unreleased runtime behavior correction only; no released 9.x signature migration or new recipe.

## Verification and delivery

Use `.local/feature-review-retry-20261029` for a HEAD snapshot and worker-owned overlay, with separate logs outside reactor targets. Start with a failing real-provider regression, apply the smallest correction, then run focused provider/retry/telemetry/cancellation/cleanup controls on real JDK 11 and modern JDK. Root reviews the actual combined diff before serialized delivery and issue closure. The scratch directory is disposable and must be registered in root cleanup allowlists.
