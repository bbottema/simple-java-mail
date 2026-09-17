# ADR 0012: Observe terminal email attempts without exposing transport lifecycle

- Status: Accepted; retrospective record of implemented behavior
- Decision recorded: 2026-09-16; not the original introduction date
- Applies to: Unreleased 10.0.0, including executor-backed observer dispatch and cancellation
- Implementation baseline: `codex/10.0.0` at `a4eda9e6`
- Related decision: [ADR 0011: Submission outcomes](0011-transport-neutral-submission-outcomes.md)

## Context

Applications need one place to measure send latency and record submission outcomes across blocking, asynchronous, batch and open-connection calls. Wrapping every call and future repeats that work. Exposing provider `TransportListener`s appears attractive, but a clustered Mailer can send through another Session and Transport. Aggregating provider listeners would expose pooling and connection ownership as public observability guarantees.

The whole library attempt is also broader than SMTP submission. Governance, validation, executor admission or cancellation may fail before a transport is acquired. A receipt alone cannot explain those failures.

## Decision

Register one `MailSendObserver` on a Mailer and report one terminal `MailSendOutcome` per reached individual email attempt. Reuse the optional `MailSubmissionReceipt` for SMTP facts and preserve the exact caller-facing failure for unsuccessful attempts. Do not invent a competing submission-result hierarchy.

An outcome contains initial and effective Message-IDs, request/ready/start/completion timestamps, configured logging-only mode and whole-attempt success/failure. Preparation and scheduling failures have no receipt; unstarted work has no start timestamp. `isSuccessful()` describes completion of the configured operation, not final delivery or even observed SMTP acceptance.

Keep the public event small and terminal. Do not expose the Email, rendered content, Transport, lease, pool, proxy or executor. Do not publish a phase-event stream. Timestamps permit latency analysis without promising callbacks for every internal transition. Message-IDs are correlation hints, not unique attempt identifiers: repeated or concurrent sends can use the same Email/Message-ID, and exact EML may omit one. Applications own any stronger business correlation.

## Recorded rationale and evidence

- [#216's clustering discussion](https://github.com/bbottema/simple-java-mail/issues/216#issuecomment-546699035), 2019, identifies the original difficulty: the invoking Mailer does not necessarily determine which Session and Transport send the message. This is the historical reason provider-listener exposure is an architectural concern.
- [#712](https://github.com/bbottema/simple-java-mail/issues/712) explicitly bounds the feature to terminal per-email outcomes, reuses #710's result model, excludes content and resource lifecycle, and rejects core Micrometer/OpenTelemetry dependencies. It records cleanup-before-notification and observer-failure isolation as requirements.
- [Commit `3ba8a37c`](https://github.com/bbottema/simple-java-mail/commit/3ba8a37cabee6cf507a5c6450c2e66ddf10e3acf), 2026-08-27, introduces the observer. The [implementation comment](https://github.com/bbottema/simple-java-mail/issues/712#issuecomment-5434325577) confirms reached-email scope, receipt reuse and exact caller-facing failure.
- [Commit `9b4d1118`](https://github.com/bbottema/simple-java-mail/commit/9b4d1118b589926baff592d2d1c3d77d2ba6e050), 2026-09-11, integrates deadlines and observer dispatch while explicitly retaining cleanup-before-outcome ordering. [#726's implementation comment](https://github.com/bbottema/simple-java-mail/issues/726#issuecomment-5636193851) records the application-executor overload and cancellation-as-request boundary.

The initial #712 proposal describes inline callbacks. The later executor option is an implemented extension, not evidence that the original issue guaranteed asynchronous delivery. The precise mechanics below are verified in [MailSendAttempt](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailSendAttempt.java), [MailSendObserverNotifier](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailSendObserverNotifier.java), [MailSendOutcome](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSendOutcome.java) and [MailSendControl](../../modules/core-module/src/main/java/org/simplejavamail/internal/util/concurrent/MailSendControl.java).

## Ordering and failure isolation

Ordinary pooled sends notify after their lease has been released or invalidated. This allows an observer to start another send even with pool size one. Simple batches and open-connection scopes deliberately retain a shared transport, so each reached email is observed while that scope remains open; a per-email event does not mean the entire scope has closed. Untouched entries in a rejected or failed lazy batch produce no outcomes.

By default, callbacks run inline before the synchronous return or asynchronous completion. Ordinary preparation occurs before scheduling, so executor rejection can produce a ready but unstarted outcome. Different sends may notify concurrently, with no global ordering guarantee.

The executor-taking overload attempts handoff before send completion without awaiting callback completion. The application owns that executor. Rejection is logged as undelivered, with no retry or inline fallback. Direct, caller-runs, blocking and silent-discard executor policies retain their actual behavior. Replacing the observer replaces its executor registration too; the single-argument overload restores inline dispatch.

`MailSendAttempt` guards terminal construction with an atomic once-only boundary and captures completion before notification. This ensures a single terminal publication attempt; it does not turn an arbitrary application executor into a reliable event queue. Callback `RuntimeException`s are logged and ignored. They cannot change the send result, trigger retries or alter cleanup. The contract does not claim isolation from every possible JVM `Error`.

Observation does not arbitrate SMTP truth. Cancellation and timeout exceptions can carry the same receipt as the underlying submission failure. First-failure classification is frozen before cleanup, so a later stop cannot replace an already observed send failure. An observed acceptance is not recalled by cancellation. Observer handoff/inline work is paused out of the send deadline, including between simple-batch emails; explicit cancellation remains effective. The stop-signal thread does not run observers or complete futures.

## Alternatives and consequences

These tradeoffs are retrospective analysis unless explicitly described in #712.

- **Expose provider listeners:** couples application reporting to pooled/clustering internals and still misses preparation/admission failures. Terminal attempts preserve the useful feedback while leaving resource ownership private.
- **Publish every lifecycle phase:** makes ordering, missing phases, reused transports and concurrency part of the public API. A completed outcome provides stable facts without that expansion.
- **Make core depend on a metrics/tracing system:** narrows integration choices. Applications or optional adapters can translate the same callback to their chosen system.
- **Always create an internal event executor:** adds queues, resource lifecycle and delivery obligations to every Mailer. Inline dispatch is predictable; optional application dispatch makes the extra resource explicit.
- **Retry rejected reporting work or fall back inline:** could duplicate reporting or unexpectedly block the send thread. The selected failure behavior preserves the send contract and leaves durable reporting with the application.

Inline reporting can delay call/future completion. Offloaded reporting can arrive after completion or be discarded by application policy. Applications therefore need thread-safe observers and must arrange their own durable audit delivery if required.

## Implementation boundaries

The observer registration is Java behavior attached directly to the builder and Mailer; it does not belong in OperationalConfig, configuration files, Spring property binding, CLI generation or redacted configuration diagnostics. Validation, rehearsal, probes/connection tests, shutdown and standalone `BatchTransportExecutor` operations are not email attempts.

Ordinary cleanup failures can produce whole-attempt failure even after useful SMTP work; an optional receipt is not guaranteed for every generic cleanup error. Do not infer rejection from `isSuccessful() == false` or from a missing receipt. Likewise, a successful logging-only/custom-mailer outcome need not establish SMTP acceptance.

Existing regression landmarks are [MailSendObserverTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailSendObserverTest.java) and [MailSendObserverDispatchTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailSendObserverDispatchTest.java). This ADR records current behavior; no Java tests were rerun for the documentation pass.
