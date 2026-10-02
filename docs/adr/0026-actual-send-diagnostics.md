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
Collect whenever an observer is registered, retaining the unobserved fast path. Preserve existing constructors and older serialized outcomes without diagnostics.

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
Preserve the exact produced receipt across later cleanup failure without changing the caller-facing throwable or first-failure arbitration.

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

- [ADR 0027](0027-factory-scoped-sending-limits.md): adds the explicit `getRateLimitWait()` measurement for intentional local rate waiting.
  It is separate from scheduling and acquisition, follows the existing observation opt-in, and adds no provider timing hooks.

- [ADR 0011](0011-transport-neutral-submission-outcomes.md): acceptance and receipt ownership; extends retention across later cleanup failure.
- [ADR 0012](0012-terminal-send-observation.md): per-email scope and notification ordering remain authoritative.
- [ADR 0015](0015-execution-views-and-transport-pooling.md): execution mode and connection ownership remain separate.
- [ADR 0017](0017-deadlines-and-physical-cancellation.md): elapsed diagnostics do not change stop arbitration or deadline pauses.
- [ADR 0020](0020-dedicated-smtp-connection-diagnostics.md): probes describe their dedicated connection, not a completed send.
