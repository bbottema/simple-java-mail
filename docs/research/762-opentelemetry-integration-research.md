# Optional OpenTelemetry integration: source research

This research supports [ADR 0034](../adr/0034-optional-opentelemetry-integration.md), implemented under
[#762](https://github.com/bbottema/simple-java-mail/issues/762).
The [telemetry ownership guide](../concurrency/13-mail-send-telemetry.md) describes current behavior, regression coverage and verification commands.
The findings below describe the inspected sources, not a continuing claim about every OpenTelemetry release or third-party integration.

## Research findings (2026-10-15)

### OpenTelemetry sources inspected

The investigation used repositories maintained by the OpenTelemetry project:

| Source | Inspection and limitation |
| --- | --- |
| [Semantic Conventions](https://github.com/open-telemetry/semantic-conventions/tree/331541c4057c53d133c32efadaa7b656636eda77) | Full, non-truncated tree: 958 paths; no SMTP/mail-specific convention path found. |
| [Java Instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/24316c305a04881e399e28a18cfd65c51577a541) | Full, non-truncated tree: 20,607 paths; no dedicated SMTP/JavaMail/Jakarta Mail instrumentation path found. |
| [Java agent supported libraries](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/) | The inspected list did not identify dedicated JavaMail/SMTP instrumentation. |

These searches inspected mail-related path names, not every source line or the whole ecosystem. They support a bounded absence finding,
not a claim that SMTP has no conventions or third-party instrumentation. Revisit the primary sources when adopting new conventions or agent versions.
The implementation uses SJM-owned instrument names rather than forcing unrelated messaging semantics onto SMTP.

The primary guidance established the integration boundaries:

- [Library instrumentation](https://opentelemetry.io/docs/concepts/instrumentation/libraries/): library API use versus application SDK/exporter ownership.
- [Java API/context](https://opentelemetry.io/docs/languages/java/api/): capture context across handoff; retire thread-bound scopes before suspension.
- [Error recording](https://opentelemetry.io/docs/specs/semconv/general/recording-errors/): a retried failed child does not require a failed recovered parent.
- [Java SDK](https://opentelemetry.io/docs/languages/java/sdk/): processing, batching, aggregation and export remain application responsibilities.
- [Java agent](https://opentelemetry.io/docs/zero-code/java/agent/): optional JVM instrumentation; coexistence uses public API/context and OTLP, not private agent APIs.

### Histogram scale

The histogram review used the [HTTP duration scale](https://opentelemetry.io/docs/specs/semconv/http/http-metrics/), in seconds, from
5 ms through 10 s, extended with 30, 60, 120 and 300 seconds for SMTP setup and rate/retry waits. This is aggregation advice, not a claim
that SMTP follows HTTP semantics. SDK Views can replace the advice; longer durations remain in the overflow bucket.

### Existing SJM boundaries reused

`MailSendAttempt` owns the logical observation and once-only terminal snapshot. `MailSendOperation` owns admission, execution,
retry suspension/resumption and completion. Telemetry observes those owners rather than adding a state machine or callback dispatcher.
`TransportRunner` identifies actual provider invocation and result capture. `MailSendDiagnosticsRecorder` supplies fixed portable timings
without requiring public connection/submission histories. Spring continues using its shared default-Mailer construction route.

Typed adaptation is limited to `OtelAdapter.wrap(OpenTelemetry)`, returning the opaque core `MailSendTelemetry` handle. Ordinary settings,
properties, locks and defaults remain on core/main APIs. `ModuleLoader` caches a stateless support entry point, never SDKs or bound runtimes.
The initiating Mailer owns telemetry through selected-destination failover and retries. Actual selected endpoint facts remain optional.

## Agent coexistence and evidence boundaries

A Java agent can install the application's global instance. Coexistence tests must use that instance rather than create a competing SDK,
and inspect public OTLP/protobuf output rather than private agent internals. Cover both actual loopback SMTP and opaque CustomMailer callbacks.
This verifies the supported arrangement, not duplicate suppression for arbitrary vendor instrumentation.

Sampling must not govern send counts or durations. Metric recording supplies the stored send/submission context explicitly after work scopes close,
so SDK-selected exemplars retain the correct span identity without per-send metric labels.

The maintained [verification guidance](../concurrency/13-mail-send-telemetry.md#verification) covers context ownership, receipt/exception identity,
reference retirement, agent coexistence and unchanged SMTP/content work. Finite tests are not a universal leak proof for application SDK
processors or exporters. Dated implementation runs and intermediate API revisions remain in Git history rather than this research document.
