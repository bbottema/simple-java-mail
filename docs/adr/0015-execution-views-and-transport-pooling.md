# ADR 0015: Explicit execution views over shared Mailer resources

- Status: Accepted, recorded retrospectively
- Decision recorded: 2026-09-16; not the original decision date
- Applies to: `codex/10.0.0`, including unreleased execution views
- Implementation status: Existing pooling architecture and implemented, unreleased 10.0.0 API

## Context

A send has two independent choices: where the operation executes, and which SMTP connection it uses. Historically, a Mailer-wide async default made the first choice invisible at the call site. A method returning a future could already have sent the message synchronously; boolean overloads exposed the setting without explaining it. Separately, creating a connection for every message repeated SMTP connection and authentication work, even when many messages went to the same server.

These concerns must remain separate. Selecting synchronous execution does not disable connection pooling, and using asynchronous execution must not require the optional batch module. A new public view must not become a second Mailer with a different executor, pool registration or shutdown obligation.

## Decision

Require `mailer.sync()` or `mailer.async()` at the operation call. Both are cached immutable views of one owning `MailerImpl`. A single synchronous send returns its `MailSubmissionReceipt`; an asynchronous send returns `MailSend<MailSubmissionReceipt>`. A caller interested only in completion may ignore the receipt. Remove the Mailer-wide async default, boolean-mode overloads and separate receipt-specific families without deprecated forwarding aliases.

Keep simple batches lazy, sequential and first-failure-stopping. Their return values describe batch completion, not an accumulated receipt history. The scoped `MailSender` remains synchronous. Views delegate to the existing preparation, admission, control, outcome and cleanup machinery, preserving receipt identity and failure behavior.

Route SMTP transport acquisition through the optional batch module when it is present; otherwise use a directly owned Session transport. The batch bridge owns pool registration, acquisition and shutdown, while the Mailer owns asynchronous scheduling. Reusable transports are exclusive leases, released after success and invalidated after failure. A batch or open-connection callback can keep one transport for its own scope; it does not expose a transport for unrestricted concurrent use.

Keep worker count, queue capacity and connection-pool capacity distinct. Cluster keys identify reusable routing groups. Different keys may have different pool defaults; Mailers sharing a key use the first configuration established for that cluster. The Mailer closes its owned executor and pool registration, while caller-provided executors remain application-owned. The drain and cancellation contracts are recorded in [ADR 0016](0016-bounded-async-admission-and-shutdown.md) and [ADR 0017](0017-deadlines-and-physical-cancellation.md).

## Recorded reasoning and evolution

| Evidence | What it establishes |
| --- | --- |
| [Issue #198, reuse discussion](https://github.com/bbottema/simple-java-mail/issues/198#issuecomment-498060264) and [#214](https://github.com/bbottema/simple-java-mail/issues/214) | Connection reuse avoids repeated setup; multiple blocking transports permit concurrency; clustering supports multiple SMTP servers. The discussion explicitly avoided a large general-purpose dependency solely for caching and investigated a dedicated pooling module. Historical benchmark figures are workload-specific and some async measurements were later corrected; they are not performance guarantees. |
| [8fd76f0d](https://github.com/bbottema/simple-java-mail/commit/8fd76f0d55206c45dd853c24bfa91cbc1f3380fe), [bf6db367](https://github.com/bbottema/simple-java-mail/commit/bf6db367586207b611e86c1ad471e569fcdacf70) | Integration of SMTP Connection Pool and its cluster configuration into the regular API. |
| [a53ce99a](https://github.com/bbottema/simple-java-mail/commit/a53ce99a85219aa7831938d89b48af04f9b04af3), [c1a7ef2e](https://github.com/bbottema/simple-java-mail/commit/c1a7ef2efc8387ae0a6fa0871007ed126488af00) | Executor ownership was recorded to avoid closing application resources or leaving owned threads alive; basic async support without batch was deliberately restored after a regression. |
| [#565 use case](https://github.com/bbottema/simple-java-mail/issues/565#issuecomment-2541400404), [resolution](https://github.com/bbottema/simple-java-mail/issues/565#issuecomment-4889806767), [bda44754](https://github.com/bbottema/simple-java-mail/commit/bda44754723e7f51480b7dc11f2e1e21d5d9b665) | Two SMTP servers needed different retained-connection limits. This changed defaults from process-wide behavior to cluster-key scope while preserving first-Mailer configuration within a cluster. |
| [#711](https://github.com/bbottema/simple-java-mail/issues/711), [implementation comment](https://github.com/bbottema/simple-java-mail/issues/711#issuecomment-5427968690), [e560795a](https://github.com/bbottema/simple-java-mail/commit/e560795a223704071da8b1c29483b52fbe1e2813) | The first 10.0.0 step added named sync/async methods while retaining the older configurable methods. Its reason was agreement between method name, return type and failure timing. |
| [#734](https://github.com/bbottema/simple-java-mail/issues/734), [completion comment](https://github.com/bbottema/simple-java-mail/issues/734#issuecomment-5652027411), [c132e07c](https://github.com/bbottema/simple-java-mail/commit/c132e07c268249ffd1897ad6e2195f7ea0f133f4) | The later accepted decision superseded that compatibility approach: require execution views, unify single-send receipts and deliberately migrate callers. Execution mode belongs to application code rather than a resource configuration default. |

The separation of execution policy from connection reuse is also visible in the current code. Treating it as a durable ownership rule is a retrospective architectural synthesis, rather than a claim that the entire rule was decided in one historical discussion.

## Alternatives and consequences

The additive named-method approach was actually implemented under #711 before #734 replaced it. Retaining it would preserve source compatibility but leave overlapping ways to select execution and request receipts. The chosen API requires recompilation and migration for 10.0.0, including removal of CLI `--mailer:async`; CLI commands use the synchronous view while daemon concurrency remains daemon-owned.

A connection per email is still the supported no-batch path. A single permanently shared transport would reduce resource bookkeeping but serialize every SMTP conversation and make routing and failure recovery harder; this is an analytical comparison, not a documented rejected proposal. Pooling adds lease, eviction and shutdown obligations, and changing a cluster key is a resource-policy decision, not simply a label change.

The views add no workers, connections or locks. Asynchronous admission can nevertheless wait for capacity, and caller-side preparation can take time; `async()` is not a promise of immediate return. Connection reuse is not a promise of final delivery, and releasing a healthy lease is not physical socket disposal.

## Implementation anchors

- [Mailer](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java), [MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java), [MailerGenericBuilderImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java)
- [TransportRunner](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportRunner.java), [BatchSupport](../../modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchSupport.java), [PoolSettings](../../modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/PoolSettings.java)
- [Accepted execution-view plan](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-3-diagnostics-and-security/04a-explicit-mailer-execution-views.md), [pool claims and leases](../concurrency/06-pool-claims-and-leases.md)
