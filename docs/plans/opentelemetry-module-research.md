# Research an optional OpenTelemetry module for mail sending

Target: 10.0.0. Status: research only; module not implemented.
Tracking: [#762](https://github.com/bbottema/simple-java-mail/issues/762).
Related: [#750](https://github.com/bbottema/simple-java-mail/issues/750), [#760](https://github.com/bbottema/simple-java-mail/issues/760).

## Why

Applications should be able to connect mail sends to their existing traces and metrics without each application reimplementing the observation adapter.
Keep OpenTelemetry optional; applications continue owning SDK, exporter and collector configuration.

## Research decisions

- Capture initiating trace context before asynchronous execution and preserve it across retries, failover and retained scopes.
- Establish authoritative operation start/completion. Lossy progress callbacks cannot own send counting or span completion.
- Use the generated observation ID for log/span correlation, not as a TraceId/SpanId replacement or metric label.
- Design low-cardinality measurements and bounded, privacy-safe attributes; omit content, credentials and recipient identities by default.
- Avoid duplicate spans when an application uses a Java instrumentation agent; research semantic conventions before naming exported instruments.
- Follow the optional-module boundary. Core has no OpenTelemetry dependency, and the module must not silently install a global SDK/exporter.
- Cover context isolation, concurrent sends, partial/uncertain acceptance, cleanup failures, cancellation, retries and telemetry-export failures.

## Acceptance checklist

- [ ] ADR decisions and proposed usage brought to the maintainer
- [ ] Optional module/API and SDK ownership specified
- [ ] Authoritative lifecycle integration and agent coexistence designed
- [ ] Privacy/cardinality and deterministic tests specified
- [ ] Implementation planned separately from live progress
