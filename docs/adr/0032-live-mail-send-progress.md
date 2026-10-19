# ADR 0032: Observe live progress without holding up a send

- Status: Accepted
- Decision date: 2026-10-14
- Target: 10.0.0
- Implementation: Implemented and accepted for unreleased 10.0.0
- Tracking: [#760](https://github.com/bbottema/simple-java-mail/issues/760)

## Context and decision drivers

A final result tells an application what happened, but a dashboard cannot explain a long upload or rate-limit wait until it finishes.
Progress should answer what the send is doing now without making SMTP depend on the dashboard's speed. Exact content, acceptance evidence,
retry decisions and resource cleanup remain more important than delivering every intermediate update.

## Decision

Keep two independent registrations: `withMailSendResultObserver` for the existing final outcome and `withMailSendProgressObserver` for live snapshots.
Rename the terminal callback to `MailSendResultObserver`; retain its callback method, payload and existing dispatch contracts.
Implicit lambdas would make same-name overloads for the two unrelated callback types ambiguous, so distinct names are intentional.
Each registration can be removed independently with its corresponding `clearMailSendResultObserver()` or `clearMailSendProgressObserver()` method.
Clearing removes that callback and its dispatch choice from the builder, without altering already-built Mailers or another observation facility.

One observation UUID identifies a logical individual-email send across progress, failover, retries and final result. It is not Message-ID, ENVID,
a business deduplication key or telemetry trace context. Neither the Email nor transmitted bytes acquire this ID. Unobserved sends generate none.

Use immutable core read contracts and producer-owned internal assembly. Stages are coarse observations, not another send state machine.
The initiating Mailer owns reporting; destination selection does not replace observers. Shared scope setup is not repeated as per-email work.

Progress dispatch is lazy, per-Mailer, daemon-owned and independent of send workers. It retains one latest pending snapshot per ID, at most 1,024 pending
IDs and one callback in flight. The capacity bounds lagging-dashboard retention, not batch size or mail throughput. A 250 ms upload publication interval
provides four updates per second without a callback per buffer write. Stage changes and final upload counts bypass throttling, not coalescing.
The application-executor overload hands off from this dispatcher, so blocking/caller-runs executors cannot execute application code on SMTP threads.
Callbacks remain serialized. Rejection and RuntimeExceptions are logged/ignored; there is no retry or inline fallback.

The result is authoritative. Independent dispatch can deliver old progress after it; consumers retain terminal state by ID. FINISHED does not mean accepted.
Shutdown drains sending, not reporting: discard pending progress after active sends finish, do not await/interrupt application callbacks, and never close
a supplied executor. No monitor is held while running a callback, calling an executor or waiting for application work.

Managed Angus observes real DATA/BDAT content stream input. Counts exclude framing, dot transparency and TLS; buffered bytes do not prove server receipt.
Reuse existing serialization for total measurement; distinguish input length from canonical SMTP SIZE. No extra reads, serialization, commands or flushes.
Unsupported and caller-owned providers retain coarse progress, never fail sending just to fill in observations. Transport-local observation is cleared per invocation.

## Alternatives considered

- Inline progress: simple, but slow dashboards hold transport resources and affect deadlines and throughput.
- Require an application executor: less library resource ownership, more wiring for ordinary users, and caller-runs/blocking handoff still requires isolation.
- Unbounded event queue: retains every update by retaining unbounded application backlog.
- Reuse Message-ID: absent or shared by repeated/concurrent sends; cannot identify a logical invocation reliably.
- Promise every stage or percentage: misleading for pooled, custom and third-party transports and incompatible with bounded coalescing.
- Add OpenTelemetry to core: couples ordinary observation to one ecosystem. [ADR 0034](0034-optional-opentelemetry-integration.md) keeps the optional integration under [#762](https://github.com/bbottema/simple-java-mail/issues/762) separate.

## Consequences and boundaries

Progress is useful for UI/log feedback, not billing, audit completeness, retry safety or authoritative trace completion. A blocked callback can stall this
Mailer's progress, not its SMTP work. A callback that never returns cannot be forcibly reclaimed safely. Results retain their stronger existing boundary.
An application-supplied executor should have independent capacity; sharing it with sending workers can let slow callbacks occupy those workers.
There is no progress executor, stream wrapping or snapshot allocation when disabled. Result-only observation does not enable upload instrumentation.

Detailed supported setup and SMTP categories are specified by [ADR 0033](0033-detailed-mail-send-progress.md) and implemented under
[#761](https://github.com/bbottema/simple-java-mail/issues/761), with generation-scoped setup binding described in the
[concurrency guide](../concurrency/12-live-send-progress.md). The existing enum is extended, and setup belongs only to the email
that caused it. Isolated DNS/TCP/proxy/implicit-TLS hooks remain separate research. Raw protocol transcripts remain excluded.

## Verification obligations and relationships

The optional [OpenTelemetry integration](0034-optional-opentelemetry-integration.md) shares observation identity and authoritative send
boundaries, not this lossy dispatcher. Telemetry-only sends must not enable progress snapshots/upload counting; progress-only sends
remain independent of telemetry. The integration generates correlation IDs for telemetry-observed sends without transmitting them.

Test deterministic throttling, bounds, ordering, late updates, callback/executor failures, reentrancy, shutdown, serialization and observation isolation.
Use real loopback SMTP for bytes, final-reply waits, retries, failover and disabled-path cost. Preserve all content and result contracts.

- [ADR 0012](0012-terminal-send-observation.md): terminal publication remains distinct.
- [ADR 0026](0026-actual-send-diagnostics.md): terminal measurements and immutable reports.
- [ADR 0030](0030-safe-bounded-mail-send-retries.md): one logical outcome across attempts.
- [ADR 0031](0031-public-api-and-internal-construction.md): read contracts versus internal construction.
- [Provider-hook research](../research/mail-send-progress-hooks.md).
