# ADR 0016: Bound asynchronous admission and drain accepted Mailer work

- Status: Accepted, recorded retrospectively
- Decision recorded: 2026-09-16; not the original decision date
- Applies to: `codex/10.0.0`
- Implementation status: Implemented, unreleased 10.0.0 behavior

## Context

A worker limit controls concurrent work, not pending work. If an SMTP server stalls, an unbounded executor queue can retain prepared emails faster than workers send them. Connection-pool limits cannot bound that queue. Applications also need shutdown to finish work the Mailer has accepted before closing the resources it still needs, including synchronous sends and sends scheduled on application-owned executors.

## Decision

Offer an opt-in waiting-capacity policy on the Mailer's owned executor. Capacity `-1` retains the unbounded default, `0` uses direct handoff, and positive capacities provide a bounded FIFO queue. `REJECT` reports a typed rejection immediately. `WAIT_FOR_CAPACITY` waits on the submitting thread for a bounded admission interval; it does not run SMTP work on that thread as a fallback. A total send budget can further shorten the wait.

Keep this queue policy independent of worker and SMTP connection counts. Ordinary email preparation precedes async admission. A simple batch occupies one executor operation and does not open its iterable during admission. Thus the queue bounds pending operations, not concurrent preparation, retained bytes or the number of messages in a lazy batch.

Preserve caller-executor policy and ownership. A caller-supplied executor cannot be combined with non-default built-in queue settings; the application configures its own capacity and overflow behavior. SJM does not insert a second capacity-permit system around shared caller executors. Queue diagnostics are available for the owned executor and are detached, content-free estimates with rejection counters, not a coherent snapshot on which to base correctness.

Track accepted Mailer operations separately from executor termination. Starting shutdown closes send admission, drains accepted operations and observer handoffs, then closes the pool registration. Repeated shutdown calls return the same future. Synchronous sends and external-executor sends participate in this tracking; application executors are never shut down or awaited as a whole. An open-connection scope remains active across application gaps until its transport and proxy cleanup finish.

Use a dedicated daemon cleanup thread to wait outside the Mailer monitor. Reject blocking `close()` from the Mailer's own operation or owned worker to prevent a direct self-wait. Graceful shutdown does not request cancellation. Inline observer work is part of draining; accepted offloaded observer callbacks are application-owned after handoff and are not joined.

## Recorded reasoning

| Evidence | What it establishes |
| --- | --- |
| [#656](https://github.com/bbottema/simple-java-mail/issues/656) | The archived support answer explicitly distinguishes thread count from queue capacity and leaves caller-provided executors under application ownership. Its caller-runs example describes an application policy, not the new built-in policy. |
| [#725](https://github.com/bbottema/simple-java-mail/issues/725), [completion comment](https://github.com/bbottema/simple-java-mail/issues/725#issuecomment-5636196495) | A busy or stalled server motivated opt-in per-Mailer backpressure, explicit rejection or bounded wait, content-free diagnostics and preservation of lazy batches and caller-owned executors. |
| [022dbc18](https://github.com/bbottema/simple-java-mail/commit/022dbc185a41d4e68fc6db75f157d478d713c3c4) | Implements queue controls and graceful owned-executor shutdown. The message specifically identifies lazy resource acquisition and restarting expired workers during direct-handoff admission. |
| [9b4d1118](https://github.com/bbottema/simple-java-mail/commit/9b4d1118b589926baff592d2d1c3d77d2ba6e050) and [lifetime catalogue](../concurrency/02-mailer-lifecycle.md) | Extends the integrated operation lifetime across cancellation/deadlines, resource cleanup and observer dispatch, including sends outside the owned executor. |

The code makes two subtler reasons explicit. Capacity waiting periodically rechecks shutdown and send controls because a queue producer must not outlive closing admission unnoticed. It also prestarts workers before and after a successful buffered offer: a worker can expire after the original rejection, otherwise leaving accepted work with no consumer. These are implementation-derived reasons supported by comments and regression cases, not separate recovered historical decisions.

## Alternatives and consequences

Keeping only the old custom-executor escape hatch would work for advanced applications, but ordinary users would have to recreate rejection diagnostics, configuration and lifecycle integration. Making queues bounded by default would change existing acceptance behavior; the accepted issue explicitly chose opt-in limits. Caller-runs overflow would apply backpressure but could unexpectedly move SMTP work onto an application thread. These comparisons explain the tradeoffs; only the opt-in direction is stated as the historical choice in #725.

Waiting only for owned executor termination would miss synchronous and caller-executor operations. Conversely, shutting down an external executor could disrupt unrelated work. Separate operation accounting allows the Mailer to drain the work it owns without claiming ownership of the scheduler.

Draining is not instantaneous and has no independent forced-completion guarantee. Slow inline observers, synchronous future continuations, blocked custom code or an external executor that never executes accepted work can delay it. An offloaded callback may still run after shutdown completes. An explicit `shutdownConnectionPool().get()` inside one's own callback bypasses the `close()` guard; cross-Mailer dependency cycles are not generally detected. Connection-test/probe tasks are separate helper paths and do not automatically acquire the complete send-operation contract.

## Implementation and regression anchors

- [MailSendExecutor](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailSendExecutor.java), [MailSendOperations](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailSendOperations.java), [MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java)
- [Executor admission catalogue](../concurrency/03-executor-admission.md), [Mailer lifetime catalogue](../concurrency/02-mailer-lifecycle.md)
- [MailSendExecutorTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/MailSendExecutorTest.java), [MailSendQueueTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailSendQueueTest.java), [MailSendOperationTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/MailSendOperationTest.java)

These links identify existing verification evidence; recording this ADR did not rerun those Java tests.
