# ADR 0026: Explain actual sends through explicit terminal measurements

- Status: Accepted
- Decision date: 2026-09-29
- Target: 10.0.0
- Implementation: Implemented and accepted for unreleased 10.0.0
- Tracking: [#750](https://github.com/bbottema/simple-java-mail/issues/750)

## Context

A slow send can be waiting for a worker, getting a connection, building protected MIME, submitting the message or cleaning up.
The existing observer timestamps cannot separate all of these. A fresh connection probe does not describe the connection that sent this email,
particularly when a cluster chooses another Session or reuses a connection.

Applications need the measurements without another wrapper around every send API. They also need to distinguish successful SMTP acceptance
from a later failure releasing that connection. Neither a new lifecycle-event stream nor a telemetry dependency is required for that explanation.

## Decision

Extend [the existing terminal outcome](0012-terminal-send-observation.md) with optional immutable `MailSendDiagnostics`.
Collect whenever an observer is registered, retaining the unobserved fast path. The original concrete outcome preserved older snapshots without diagnostics;
[ADR 0031](0031-public-api-and-internal-construction.md) supersedes that storage-format compatibility when separating read contracts from internal implementations.

Expose explicit getters for preparation, scheduling, connection acquisition, MIME preparation, submission and cleanup. Each returns one `MailSendMeasurement`
with elapsed time or a human-readable absence explanation. Its `isFailureObservedHere()` getter identifies the step where the send failed.
If submission fails and closing the connection also fails, only submission is marked. No phase enum, indexed lookup or generic attribute bag.
Keep acceptance facts and failures in [the existing receipt/outcome model](0011-transport-neutral-submission-outcomes.md).

Use monotonic differences for elapsed time. The total ends before observer handoff and includes orchestration overhead, so measurements need not sum to total.
These are elapsed boundaries, not CPU measurements or deadline accounting. Preserve the established callback, cancellation and cleanup contracts.

Acquisition includes proxy startup and obtaining a usable direct/pooled transport; it cannot portably distinguish pool waiting from connection creation.
Submission includes provider preparation, serialization and protocol work; it is not pure network time. Shared-scope setup and teardown remain outside
per-email measurements. CustomMailer is opaque and logging-only has no submission. Untouched batch entries have no event.

Capture only the selected transport's logical host and port when available, with bounded escaping and no configured fallback or guessed port.
This is not the physical peer, proxy route or proof of TLS. Default rendering omits endpoints, identifiers, exception messages and content.
Preserve the exact produced receipt across later cleanup failure and retain first-failure arbitration. The accepted 19 October API revision
also exposes it through `MailSendException.getSubmissionReceipt()`, including cleanup-only failures through `MailSendCleanupException`.
This caller-facing retention is independent of whether diagnostic observers are enabled.

### Named snapshot construction

Construction belongs to the recorder, not the application. As clarified in [ADR 0031](0031-public-api-and-internal-construction.md), use an internal
builder with named assignments and one validation/copying boundary. Do not expose `MailSendDiagnostics.builder()` or retain telescoping constructors.
Use Lombok for mechanical builder code. Keep the read API in core and recorder-specific assembly in the main implementation module, without making
the read API depend on a concrete implementation builder. Internal package visibility alone does not establish the correct module ownership.
The total and six original measurements remain required. Optional waiting measurements begin explicitly unrecorded; endpoints and histories begin absent.
The builder measures nothing, reads no configuration and supplies no invented zero-duration work. Preserve serialization within the implementation's
supported snapshot format; ADR 0031 explicitly accepts the break from the former concrete API class names. Applications receive the completed report
through their observer and inspect it rather than constructing it.

### Connection failover and executor-side preparation

[ADR 0029](0029-smtp-submission-failover.md) changes asynchronous preparation to execute after admission, on the initiating executor.
Scheduling measures admission and worker waiting; preparation measures destination-specific governance/validation, accumulating across attempted
destinations. `readyAt` means admission-ready, and `startedAt` precedes preparation. These are shallow send checks, not full MIME rehearsal.
Internal construction and the accepted snapshot-format boundary follow ADR 0031.

`getConnectionHistory()` adds immutable per-email acquisition/skip facts with explicit getters. Retain the first 63 and latest entries, reporting
omitted entries separately; this budget never limits routing. Failed acquisitions and cached skips are distinct. Logical names/endpoints are optional,
bounded and excluded from automatic report rendering. No history or endpoint reads occur without an observer. Shared-scope setup is not repeated for
every batch email. Timings and history cannot authorize replay after provider invocation.

## Alternatives considered

- **Phase enum and generic iteration:** useful for generic metrics adapters, but introduces lookup indirection for six fixed measurements.
  Explicit getters make normal application code clearer; adapters can map the six fields themselves.
- **Named getters returning only durations:** cannot distinguish measured zero from work not reached, outside scope or inapplicable.
  A small common measurement value preserves that distinction without another configuration concept.
- **Additional observer or phase events:** would expose resource lifecycle and ordering as another public contract. Extend the established terminal event instead.
- **Fine DNS/TLS/authentication/SMTP-command timings:** require provider-specific characterization and connection-generation evidence.
  Defer them; portable inclusive boundaries already answer useful questions without pretending to know more.
- **Probe before sending or inspect debug output:** adds network work or content-bearing parsing and does not reliably describe the eventual pooled connection.
- **Always collect, or add another opt-in flag:** unnecessary overhead without a consumer, or another configuration layer. Registration already expresses interest.
- **Telemetry framework integration:** keep the library neutral. Applications can translate terminal facts without adding dependencies or tracing-context promises.

## Consequences

Observers gain bounded measurements automatically. Instrumentation adds a small amount of local work only to observed attempts, without additional message
serialization, attachment reads, SMTP commands or connections. Do not promise a numerical overhead without measurement.

Failure markers identify the failed step, not the underlying cause. Shared-connection opening/closing failures can still lie outside
every per-email event. Host/port are explicit diagnostic values, not automatically logged metric labels. Applications own telemetry privacy and cardinality.

The implementation uses one attempt-local recorder passed through existing paths, not another ThreadLocal, shared Session policy or provider SPI.
Verify isolation, exact receipt/throwable identity, serialization and observed/unobserved work counts as well as successful sends.

## Related decisions

- [ADR 0034](0034-optional-opentelemetry-integration.md): optional telemetry reuses lightweight measurement
  boundaries and authoritative terminal completion without requiring a result observer or constructing public diagnostic histories.
  Unavailable timings remain absent; metrics do not imply CPU time, exact network timing or final delivery.

- [ADR 0032](0032-live-mail-send-progress.md): live progress is a separate opt-in, not a replacement for terminal measurements.
  Result-only registration does not enable upload instrumentation; progress-only registration does not build terminal diagnostic histories.
  A shared observation UUID correlates both without becoming a Message-ID or telemetry trace ID.

- [ADR 0030](0030-safe-bounded-mail-send-retries.md): explains intermediate
  refusals, delays and exhaustion through one final outcome. Retry measurements distinguish waiting from
  scheduling and connection acquisition without presenting an intermediate failure as the logical operation's terminal failure.

- [ADR 0027](0027-factory-scoped-sending-limits.md): adds the explicit `getRateLimitWait()` measurement for intentional local rate waiting.
  It is separate from scheduling and acquisition, follows the existing observation opt-in, and adds no provider timing hooks.

- [ADR 0011](0011-transport-neutral-submission-outcomes.md): acceptance and receipt ownership; extends retention across later cleanup failure.
- [ADR 0012](0012-terminal-send-observation.md): per-email scope and notification ordering remain authoritative.
- [ADR 0015](0015-execution-views-and-transport-pooling.md): execution mode and connection ownership remain separate.
- [ADR 0017](0017-deadlines-and-physical-cancellation.md): elapsed diagnostics do not change stop arbitration or deadline pauses.
- [ADR 0020](0020-dedicated-smtp-connection-diagnostics.md): probes describe their dedicated connection, not a completed send.
