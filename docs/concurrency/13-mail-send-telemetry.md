# Mail-send telemetry ownership

[Catalogue](README.md) · [Send operation](01-send-operation.md) · [Retries](11-mail-send-retries.md) · [Progress](12-live-send-progress.md)

The optional integration observes the existing operation. It does not add another send state machine, executor, delivery queue or callback dispatcher.
[ADR 0034](../adr/0034-optional-opentelemetry-integration.md) owns the public architecture; the
[source research](../research/762-opentelemetry-integration-research.md) records the inspected OpenTelemetry sources and their limitations.

## Owners and lifetimes

| Object | Owner and retirement |
| --- | --- |
| Application SDK and exporters | Application/framework; Mailer shutdown neither flushes nor closes them. |
| Immutable registration | Application; reusable across factories. A Mailer resolves and binds its own endpoint permission. |
| Stateless module entry point | Shared ModuleLoader cache; holds no SDK, registration or Mailer references. Loaded only when enabled. |
| Bound runtime | Initiating Mailer, including selected-destination and cross-factory failover. Holds the supplied or application-global instance. |
| Captured context | Individual API entry, or outer batch entry; retained only while work needs to resume. |
| Logical-send span | Attempt; starts before admission and ends once after ordinary cleanup, before result-observer delivery. |
| Submission child | Actual provider/CustomMailer invocation; ends on result capture or exceptional invocation exit. |
| Thread-bound scope | Current execution turn, closed on that same thread before parking or observer work. |

`MailSendOperation` attaches the logical context around execution and remaining terminal cleanup. A parked async retry retains its span/context,
not an attached scope. Resumption attaches on whichever existing worker executes it. Caller-runs execution obeys the same rule.
Submission scopes nest inside the work scope; exceptional work-turn retirement closes an unfinished child before closing its parent scope.
Queued cancellation/rejection finishes without executing send work. Terminal cleanup can briefly attach the logical context;
that scope is closed before final reporting and application observation.

Factory properties and Java choices resolve activation and endpoint permission before runtime binding. The builder stores configuration only;
the optional module creates the bridge and resolves the adapted SDK or the application's global instance. The public typed adapter only
retains the supplied instance; endpoint permission is resolved by the Mailer builder. Sharing that handle never shares resolved settings.
Disabled configuration does not load the module or consult the global instance. Clearing an explicit integration restores captured activation,
not an unconditional off switch.

Metric recording passes the stored logical-send or submission context explicitly, including active-send increments and decrements.
It never depends on the current thread's context after scope retirement. This lets the SDK link sampled metric measurements to the
correct span through exemplars without attaching another scope or adding trace IDs to metric labels. Sampling and exemplar selection
remain SDK responsibilities; unsampled sends still contribute their counts and durations.

The existing terminal compare-and-set owns exactly-once publication. The optional module also guards its span endings against duplicate calls,
but cannot finish an operation, cancel it or choose another route. SDK RuntimeExceptions are isolated by the internal bridge and logged without
the SDK exception text. No instrumentation is invoked while an SJM bookkeeping monitor is held. A custom synchronous SDK processor may still
delay its caller; the application should use batching rather than rely on another SJM exporter thread.

## Context boundaries

```mermaid
sequenceDiagram
    participant A as Application caller
    participant O as Existing send operation
    participant P as Provider invocation
    participant T as Supplied telemetry
    participant R as Result observer
    A->>T: Capture context; begin logical span
    A->>O: Admit/schedule once
    O->>T: Attach logical context on this work turn
    O->>P: Invoke provider with child span attached
    P-->>O: Receipt or failure
    O->>T: Retire child scope; end child
    O->>O: Ordinary release/disposal/proxy cleanup
    O->>T: Retire work scope
    O->>T: End logical span and record final metrics once
    O->>R: Existing result-observer contract
```

Async retry waiting closes the work scope between turns; it is not a new logical span. A refused child can fail while the recovered parent
succeeds. An accepted child remains accepted if later ordinary cleanup fails; the logical parent reports failure without changing the receipt.
Loss of the final SMTP reply remains unknown acceptance and does not authorize replay.

Batch API entry captures its application context before executor handoff. Each reached email starts from that context, not the preceding email's
span. Shared connection setup and final teardown remain outside per-email spans/measurements. Open-connection callbacks keep their existing
pinned scope and each send's own observation. Untouched iterable entries have no span.

## Independent reporting and retention

Telemetry-only sends reuse fixed timing fields but do not allocate public connection/submission histories, a progress recorder or stream counters.
Result-only and fully unobserved sends do not bind or invoke the optional module. Metrics count actual invocation boundaries, never retained
history entries or delivered progress snapshots. Missing timings are omitted, not zero.

The same frozen durations populate the logical span's `simplejavamail.send.phase.<phase>.duration` attributes and phase histograms.
The recorder's existing first-failure measurement supplies `simplejavamail.send.failure.phase`; telemetry-only completion captures a
terminal failure before finishing its active measurement, just as result diagnostics do. Successful recovery clears prior attribution;
unknown locations remain absent. No additional clocks, state transitions or callbacks are introduced for these span attributes.

Duration instruments provide second-based bucket advice from 5 ms through 300 s, with application SDK Views retaining control.
This changes aggregation resolution, not measurement boundaries, reporting cadence or send deadlines. Neither duration values nor failure
location become metric labels. SDK collection/export intervals remain separate from SJM progress throttling.

The main guard clears span, child and scope references at retirement. Operations clear their captured-context supplier at completion; attempts
release effective Email/receipt references under the existing observer rules. Registration/runtime references have their owner's lifetime,
not a process-global cache. Tests inspect these boundaries; they do not claim universal leak freedom for application SDK processors/exporters.

Endpoint extraction uses the actual transport's bounded logical host/port, never an invoking configuration or physical-peer guess. Permission
belongs to the initiating Mailer. Trace attributes can include its observation UUID; metrics cannot include observation/trace IDs, hosts or ports.
No mail headers, protocol commands or serialized content change for telemetry.

## Verification

Use the normal reactor so core, main and optional-module classes come from the same source revision. For a focused modern-JDK lane:

```powershell
mvn -B -ntp -pl modules/open-telemetry-module,modules/spring-module,modules/cli-module -am `
  '-Dtest=MailerImplTest,MailSendTelemetry*Test,Otel*Test,TelemetryAgentCoexistenceTest,MailSendDiagnosticsRecorderTest,MailSendAttemptDiagnosticsTest,PublicApiBoundaryTest,SpringMailSendTelemetryTest,SpringModulePackagingTest,SimpleJavaMailSpringOAuth2ProviderTest,CliMailSendTelemetryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlicense.skip=true' '-Dspotbugs.skip=true' verify
```

Also run the real-JDK-11 library lane and modern-JDK reactor, Spring compatibility and SMTP conformance checks from the
[maintainer workflow](../MAINTAINER_WORKFLOW.md#6-verify) when the affected boundaries warrant them. A focused command does not replace those gates.
For intercepted TLS on Windows, use the [local TLS guidance](../DEVELOPMENT.md#tls-interception-during-local-tests), never disabled certificate validation.

### Regression landmarks

- `MailSendTelemetryTest`: parentage through async execution, retries, failover, batches and pinned sends; sampling-independent metrics,
  observer handoff, partial/unknown acceptance, SDK failures and unchanged attachment-read/connection counts.
- `MailSendTelemetryRecorderTest`: exact accepted receipt plus cleanup failure, once-only completion, nested scope order, reference retirement,
  frozen monotonic totals and absence of telemetry-only public histories.
- `MailSendTelemetryMetricContextTest`: exact exemplar span identities for synchronous/asynchronous sends and retries, unrelated ambient
  context, active-send accounting and sampling-independent metrics.
- `MailSendTelemetryResolutionTest`: sub-second and long-wait histogram buckets, application View overrides, span/metric timing agreement,
  measured zero, absent timings, failure-location privacy and unchanged metric-label bounds.
- `MailSendTelemetryDiagnosticsTest`: all eight failure locations, both terminal-report orders, first-failure preservation and recovery clearing.
- `TelemetryAgentCoexistenceTest`: separate JVMs with/without the official Java instrumentation agent. Agent setup explicitly uses its installed
  instance; assertions inspect public OTLP/protobuf on loopback, not private agent APIs or debug logs.

The shared infographic's resource layers remain unchanged: instrumentation observes owners already pictured. No artwork regeneration is needed.
