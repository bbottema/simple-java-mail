# ADR 0034: Integrate optional OpenTelemetry at authoritative send boundaries

- Status: Accepted
- Decision date: 2026-10-15
- Typed-adapter boundary revision: Accepted, 2026-10-16.
- Target: 10.0.0
- Implementation: Implemented, verified locally and accepted after production-only and holistic review on 2026-10-16; not released.
- Tracking: [#762](https://github.com/bbottema/simple-java-mail/issues/762)

## Context and decision drivers

Applications should be able to follow a request into an email send, see its submission attempts and measure the result without wrapping
every sync, async or batch call. Connection failover, retry waiting and ordinary cleanup belong to that logical operation, but SMTP
acceptance still does not establish final delivery.

The existing result observer exposes useful facts, but its callback can be delayed or rejected by an application executor. Live progress
is deliberately coalesced and lossy. Neither delivery mechanism can own authoritative span completion or send counting. Ordinary
applications must not acquire an OpenTelemetry dependency merely to keep using those observers.

The priorities are explicit activation, trustworthy completion, portable measurements, low-cardinality metrics, privacy-safe attributes,
existing SDK ownership and unchanged SMTP behavior. The [source research](../research/762-opentelemetry-integration-research.md)
retains the inspected sources and the scope of those findings; this record states the architecture, not implementation progress.

## Decision

### Typed adaptation of an existing instance (revised 2026-10-16)

Applications configure telemetry through core contracts and the main builder API, or through factory properties. The optional
`open-telemetry-module` supplies runtime behavior through `ModuleLoader`. Its support implementation and lifecycle bridges remain internal.

Supplying an existing `OpenTelemetry` instance is the narrow foreign-instance exception recorded in
[ADR 0006](0006-optional-modules.md#choose-the-input-boundary-for-the-caller-accepted-2026-10-16) and
[ADR 0031](0031-public-api-and-internal-construction.md). Use a small adapter in the optional module whose parameter is OpenTelemetry's
public API type and whose return type is the core-owned `MailSendTelemetry` contract:

```java
MailSendTelemetry telemetry = OtelAdapter.wrap(openTelemetry);

mailerBuilder.withTelemetryConfig(MailSendTelemetryConfig.builder()
    .withIntegration(telemetry)
    .withEnabled(true)
    .build());
```

The adapter accepts a non-null instance through the public `OpenTelemetry` interface, without requiring a particular SDK implementation.
It retains that exact instance in an immutable SJM wrapper; it does not reconstruct its providers or configuration. Adapting does not initialize
an SDK, configure exporters, install a global instance, start instrumentation, flush resources or transfer shutdown ownership to SJM.

This keeps useful type information at the call site. Reject a public `Object`, `Supplier<Object>` or an untyped source interface that would hide
the required input and postpone mistakes until runtime. Core/main must not gain an OpenTelemetry API/context/SDK dependency, including an optional
API-only dependency, just to accept this object. The adapter call explicitly needs the optional module and foreign API on the application's
compile/runtime path. Subsequent SJM configuration and sending use core contracts; property-only activation needs no adapter call.

The adapter exposes adaptation only. Activation, endpoint permission, defaults and locks remain core/main configuration responsibilities.
`MailSendTelemetry` is an opaque handle with no application methods. The module interprets its own handle; it must not treat an unrecognized
supplied handle as permission to select a different instance. The main builder neither unwraps it nor requires an internal lifecycle interface.
Application setting methods retain explicit contracts and getter/reset/clear references to their setter. Do not move them into a module-owned
fluent API or expose `getOpenTelemetry()` through core. Internal storage/unwrapping must not become application lifecycle or binding machinery.

The accepted 19 October API revision groups supplied integration, activation and endpoint permission in core-owned `MailSendTelemetryConfig`.
Mailer exposes `withTelemetryConfig`, `getTelemetryConfig` and `useConfiguredTelemetryConfig`. A replacement replaces the supplied group;
unset fields inherit captured properties. Its explicit builder provides `withIntegration/getIntegration/clearIntegration`, independent of
`withEnabled` and `withSmtpEndpointAttributes`. Supplying or clearing integration does not change activation. This is not an Email setting or a
serializable SDK handle. `builder()` and `toBuilder()` capture choices without reading sources or binding an SDK.

This revision supersedes the earlier module-owned `OtelSupport.using(...).withSmtpEndpointAttributes(...)` design and the subsequent blanket
ban on module setup entry points. The accepted distinction is typed adaptation versus ordinary configuration, not a less descriptive name for
the same module-owned configuration API.

### Factory-inherited activation and ordinary module loading

Support `simplejavamail.telemetry.enabled`, defaulting to the key builder's `DEFAULT_MAIL_SEND_TELEMETRY_ENABLED = false`.
Every Mailer from a configured factory inherits its captured value. The configuration builder's `withEnabled(boolean)` replaces it;
`resetEnabled()` selects the central false default and `useConfiguredEnabled()` restores inheritance. `OperationalConfig.getTelemetryConfig().getEnabled()` reports the
effective choice. Ordinary Java customization overrides ordinary properties; a conflicting locked counterpart rejects construction.

When enabled, use `ModuleLoader.loadOpenTelemetryModule()` through the core `OpenTelemetryModule` contract, following ADR 0006.
Check module availability before constructing Mailer resources. A missing requested module is a configuration error, not a telemetry
SDK exception to swallow. The cached module is stateless; application SDKs and bound runtimes never enter the shared module cache.

The optional module creates the Mailer-owned runtime using the wrapped application instance when supplied;
otherwise it uses `GlobalOpenTelemetry.get()`. Initialize the application's global instance before building enabled Mailers.
An unconfigured global instance is OpenTelemetry's no-op implementation; enabling SJM does not create an SDK, exporter or collector.
No global lookup, module load or runtime binding occurs when disabled.

`MailSendTelemetry` is a core application contract, not a lifecycle bridge. The main builder uses that contract without requiring an internal
implementation interface or concrete-class whitelist. The loaded module owns foreign-instance interpretation; only that module and internal
orchestration exchange `MailSendTelemetryBridge` lifecycle objects. Do not silently substitute the global instance for an explicitly supplied one.

This revises the original explicit-registration-only decision: property-based activation is normal configuration, not merely a gate
around a per-Mailer Java registration. SDK/exporter ownership remains with the application.

### Scalar configuration is resolved by the owning Mailer

Support these Boolean, non-secret properties:

```properties
simplejavamail.telemetry.enabled=true
simplejavamail.telemetry.smtp.endpointattributes=true
simplejavamail.locked.telemetry.smtp.endpointattributes=false
```

Declare `MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES = false` as the single built-in fallback.
Factory initialization, reset, property and CLI resolution all reference it. Explicit Java SDK selection still requires a non-null instance;
property-only activation uses the documented application-global route instead of inventing an SDK.

| Choice in SJM configuration | Resolution when constructing the Mailer |
| --- | --- |
| Never set, or cleared | Inherit the captured property; otherwise use the published fallback. |
| Explicit `true` or `false` | Override the ordinary property, subject to a configured lock. |
| Reset | Explicitly choose the published fallback, rather than reload the property. |
| Locked property | Supply the effective value; reject conflicting explicit choices or resets and allow equal ones. |

The SJM configuration API preserves absence separately from an explicit choice. The owning Mailer builder resolves the effective value and
supplies it to `OperationalConfig.getTelemetryConfig().getSmtpEndpointAttributes()`. Getters and the typed adapter do not inspect sources or
resolve defaults. Sharing a wrapped instance across factories never mutates the wrapper or lets one factory's settings reach another.
Use `withSmtpEndpointAttributes(boolean)` and its getter/reset/useConfigured companions on the telemetry configuration builder. Replacing or clearing
the adapted handle leaves this separately configured permission intact.

Reuse `ConfigLoader.Property`, Boolean schema parsing, per-leaf provenance and the `DIAGNOSTICS_AND_VALIDATION` diagnostic group.
The locked counterpart uses the same type and visibility. Conflicts identify the `simplejavamail.locked.*` property and explain how to
use its value or change its source. Check the final setting before creating runtime resources.

Activation and endpoint permission are independent Boolean properties, each with normal metadata, provenance, CLI and locked support.
Endpoint permission alone never activates telemetry. Locking activation does not install an SDK or guarantee export; it restricts the
local Mailer's enabled choice. Clearing a supplied integration does not bypass an activation lock or its captured value.

Spring metadata mirrors both namespaces; runtime loading stays with the shared Environment-backed loader. The enabled property applies
to every Mailer from the configured factory, not only Spring's default Mailer. An explicitly supplied `MailSendTelemetry` bean can select
an SDK for the default Mailer. An arbitrary `OpenTelemetry` bean is not discovered by SJM; use the global instance or the integration bean.

Internal CLI adapters preserve `--mailer:withMailSendTelemetryEnabled` and `--mailer:withMailSendTelemetrySmtpEndpointAttributes`, delegating into the grouped Java configuration. Runtime-object
registration remains Java-only. The standalone CLI includes the optional module but no SDK or exporter; an agent or application supplies those.

### Optional-module and application API boundaries remain intact

Core contains the framework-neutral `MailSendTelemetry` application contract and necessary internal cross-module lifecycle contracts.
Its application API must not expose recording, binding or completion machinery. Main-runtime orchestration belongs in `simple-java-mail`;
OpenTelemetry API/context dependencies and implementation belong in the optional module. No provider SPI change is needed.

Supplying an existing instance through the typed adapter requires the optional module and OpenTelemetry types. Core/property activation and
continuing ordinary Mailer calls do not expose those types.
Internal bridges belong in `internal` packages with `@ApiStatus.Internal` where cross-module visibility is necessary. Keep application
methods explicit; Lombok may provide internal assembly/value boilerplate, not undocumented public configuration.

Like other optional features, the runtime implementation is loaded behind a core module contract. The application may additionally
supply a core wrapper produced by the typed adapter. Do not confuse that wrapper with the loaded module or its lifecycle machinery.
Core/main configure through the neutral contract; only the optional module deals with OpenTelemetry types. The adapter is a supported
application entry point, but the support implementation is not. Neither is a new configuration owner.

### Trace logical sends and actual provider invocations

Create one logical-send span per reached email, beginning at attempt entry before admission and ending at the existing once-only
terminal boundary, after ordinary cleanup and before application result observation. Create one child per actual provider invocation.
The logical span has INTERNAL semantics; SMTP invocation children have CLIENT semantics without claiming pure network time.

Capture initiating trace context before admission or executor handoff. Attach it only around executing work, including retry resumptions,
and close every scope on its owning thread. Retain captured context across suspended work, not an open thread-bound scope. Capture batch
invocation context separately so later emails cannot accidentally become children of the previous email's completed span.

The initiating Mailer owns telemetry and its resolved reporting permission through selected-Mailer routing. A selected destination does
not replace its SDK or observers. Acquisition, admission, rate/retry waits, preparation and ordinary cleanup stay inside the logical send;
connection failover does not become another send or submission before provider invocation.

For example, a temporary refusal followed by acceptance has this shape:

```text
application request
  mail send                              successful logical completion
    submission 1                         temporary refusal
    submission 2                         SMTP accepted
```

The refused child records failure, but recovery does not make the parent fail. Successful spans need no explicit error status. If a
submission is accepted and ordinary cleanup then fails, retain the accepted child and mark the logical send unsuccessful. Keep partial
and uncertain acceptance distinct from rejection. Telemetry never changes the original receipt, exception or retry decision.

CustomMailer invocation is an opaque INTERNAL callback child, not an invented SMTP exchange. Logging-only has no submission child.
Shared batch/open-connection setup and final teardown remain outside per-email spans. Untouched iterator entries have no per-email span.
Basic arguments rejected before a logical attempt begins do not manufacture a send observation.

### Metrics and correlation have separate responsibilities

Record logical-send and provider-invocation counts/durations, active sends and available portable timing measurements. Do not count
retries as new logical sends or turn unavailable timings into zero. Use bounded outcome categories; metrics remain independent of
trace sampling. Timing describes elapsed work at the existing boundaries, not CPU time or SMTP server-processing time.

Put each available phase duration on the logical-send span as `simplejavamail.send.phase.<phase>.duration`, with a numeric value in seconds,
as well as recording the phase histogram. Reuse the frozen measurements, including measured zero, rather than adding clocks or converting
delivered progress callbacks into intervals. Put the existing primary failure location on that span as `simplejavamail.send.failure.phase`
when it falls inside a measured boundary; omit it for success or unknown attribution. Secondary cleanup failures must not replace it.
These attributes do not become metric labels or appear on submission children.

Provide duration histogram bucket advice in seconds, using the established
[OpenTelemetry HTTP duration scale](https://opentelemetry.io/docs/specs/semconv/http/http-metrics/) from 5 ms through 10 s and extending it
with 30, 60, 120 and 300 seconds for SMTP setup and local rate/retry waits. This supplies useful sub-second resolution without a new public
tuning policy. Application SDK Views retain precedence; longer measurements enter the overflow bucket. Collection/export intervals remain
with the SDK and are unrelated to the progress dispatcher's upload throttle or the precision of captured durations.

Pass the stored logical-send or submission context explicitly when recording its metrics, even after the thread-bound scope has closed.
This supports SDK-selected exemplars linking metric samples to the correct span without relying on ambient thread context or adding
per-send metric labels. The application's SDK owns exemplar selection as well as trace sampling.

Use SJM-namespaced instruments where no applicable semantic convention exists, and standard attributes only where their meanings fit.
Counts do not establish billing, provider quotas or final delivery. Never derive exact counts from bounded diagnostic histories.

Reuse the logical-send observation UUID for span/log correlation, including telemetry-only sends. It remains different from a TraceId,
Message-ID, ENVID or business deduplication key. Never use per-send identifiers as metric labels. Generate none when telemetry, progress
and result observation are all absent.

### Privacy, overhead and lifecycle

SJM-generated telemetry excludes content, sender/recipient identities, credentials, Message-ID, ENVID, raw protocol text, exception
messages and stack traces. Record safe bounded failure classifications instead. Endpoint permission allows the actual selected logical
SMTP host/port when known, only as appropriate trace attributes, never metric labels. Do not substitute a configured endpoint when the
selected one is unknown, or describe a proxy/physical peer as that logical endpoint.

This governs SJM instrumentation, not arbitrary attributes added by applications or other agents. Insert no tracing headers, baggage
or observation identifiers into email or SMTP traffic. Preserve exact/protected bytes.

Telemetry is independent of public progress and result registration. It does not enable upload counters, progress snapshots/dispatch,
public diagnostic histories or a span per progress stage. Reuse lightweight internal measurement boundaries without additional content
reads, serialization, network commands, probes, retries, queues, executors or another send state machine. Unconfigured sends keep their fast path.

Catch and safely report telemetry `RuntimeException`s without replacing send failures or changing cleanup. Do not invoke instrumentation
under bookkeeping monitors. Arbitrary SDK processors can execute synchronously; SJM cannot promise they never delay sending. Recommend
application-owned batching rather than add another SJM exporter/dispatch subsystem.

The application owns SDK sampling, buffering, exporters, flushing and shutdown. Closing a Mailer does not close the SDK. Retire per-send
context, span and callback references at terminal completion, including cancellation/rejection. Shared integration/SDK references last
as long as their configured owners; do not keep application instances in a process-global module cache.

## Alternatives considered

| Alternative | Benefit | Reason not selected |
| --- | --- | --- |
| Activate from JAR or global-SDK presence alone | Less configuration | Presence is not intent; activation requires Java configuration or an enabled property. |
| Bundle/configure SDKs and exporters | One-stop setup | Duplicates application infrastructure and its lifecycle/security decisions. |
| Translate result/progress callbacks only | Little internal integration | Dispatch can fail or lag; progress cannot own completion/counts. |
| One span only, or one per progress stage | Simple summary, or apparent detail | The former hides attempts; the latter implies unsupported precision/order. |
| Always include endpoints or identifier metric labels | Convenient filtering | Exposes deployment details and creates uncontrolled cardinality. |
| Positional Boolean factories | Short calls | Makes the privacy permission unclear at the call site. |
| Module-owned fluent configuration alongside SDK selection | Short setup with one import | Mixes foreign-instance adaptation with ordinary settings, which belong to core/main and their property/lock paths. |
| Accept `Object`, an untyped supplier or a neutral interface returning either | Keeps every entry point in the main API | Obscures the accepted type and permits incorrect calls; a typed adapter is clearer to callers. |
| Add the OpenTelemetry API dependency to core/main | Direct typed main-builder parameter | Makes an optional feature's foreign types part of the ordinary dependency surface, even when the dependency is marked optional. |
| Reconstruct the application's SDK from scalar configuration | Avoids accepting a foreign object | Cannot preserve the supplied instance, providers, processors and application lifecycle. |
| Require per-Mailer Java registration even with enabled properties | Simple explicit wiring | Breaks factory configuration parity; the module can use the application's global instance without owning it. |

## Consequences and boundaries

Applications can join existing traces to actual sends without callback adapters, while ordinary users retain the existing dependency
surface. The cost is lifecycle/context integration and opt-in measurement overhead, not a claim of cost-free tracing. Application SDK
code and exporter configuration remain external responsibilities.

Explicit SDK supply requires one typed adapter import from the optional module. That is an intentional usability tradeoff: it exposes the real
input requirement and permits compiler/IDE guidance without making ordinary SJM calls depend on OpenTelemetry. Property-only activation retains
the existing runtime module-loading path. The adapter does not expand SJM's responsibility into SDK configuration or lifecycle management.

Java-agent coexistence means testing a JVM with and without the OpenTelemetry Java instrumentation agent. It does not mean an AI agent,
an agent requirement for users or private access to agent internals. Use the supplied public API/context and verify parentage, balanced
accounting and no unexpected duplicate SJM spans. Other agents can legitimately create lower-level spans; SJM does not promise universal
duplicate suppression for arbitrary vendor instrumentation.

When the agent owns the SDK, use the installed global instance through property activation or explicitly supply it, rather than create a
competing SDK. Coexistence tests inspect public OTLP export on loopback,
not private agent APIs or debug-log output.

Detailed connection progress, raw transcripts, PIPELINING/CHUNKING, packaged exporters and tracing through transmitted emails remain
outside this decision. Existing cancellation, failover, retry, observer ordering and resource ownership remain authoritative.

## Verification obligations

Implementation must verify immutable wrapper and setting/reset/clear behavior, centralized defaults, property/lock precedence, provenance,
snapshot isolation, Spring metadata/compatibility, CLI generation and rendered Javadocs. Verify core-only and optional-module classpath/JPMS
consumers, including negative compilation checks for leaked internal or OpenTelemetry types.

Keep the typed-adapter consumer separate from core-only continuation. Verify that wrong foreign inputs fail compilation, the adapter accepts
implementations of the public `OpenTelemetry` interface, and the wrapped instance retains identity without initialization or ownership side effects.
The returned contract and continuing configuration/send APIs must remain core-owned. Verify no direct OpenTelemetry dependencies in core/main,
no internal-bridge requirement on the main setter, and no silent substitution of a global instance. Exercise the ModuleLoader missing-module
error through core/property activation; a direct adapter call deliberately requires its module at compile/runtime.

Use deterministic clocks/barriers and loopback SMTP for admission, preparation, invocation, retries, failover, cancellation, deadlines,
partial/uncertain acceptance, cleanup failure and retained scopes. Assert exact receipt/exception identity, once-only reporting, balanced
active-send counts, independent sampling, privacy and unchanged content-read/protocol counts. Exercise failing SDK operations, observer
handoff rejection, concurrent context isolation and release of per-send references.

The [telemetry ownership guide](../concurrency/13-mail-send-telemetry.md#verification) identifies regression coverage and repeatable verification commands.
Implementation and review acceptance do not imply release.

## Related decisions and evidence

- [0003: Configuration snapshots and centralized defaults](0003-immutable-configuration-snapshots.md)
- [0004: Configuration provenance](0004-configuration-provenance-diagnostics.md)
- [0005: Spring integration](0005-spring-integration-and-boot-compatibility.md)
- [0006: Optional modules](0006-optional-modules.md)
- [0011: Submission outcomes](0011-transport-neutral-submission-outcomes.md)
- [0012: Terminal observation](0012-terminal-send-observation.md)
- [0018: CLI generation](0018-cli-from-builder-contracts.md)
- [0025: Configuration locks](0025-factory-scoped-locked-configuration.md)
- [0026: Actual-send diagnostics](0026-actual-send-diagnostics.md)
- [0030: Safe retries](0030-safe-bounded-mail-send-retries.md)
- [0031: API boundaries and construction](0031-public-api-and-internal-construction.md)
- [0032: Live progress](0032-live-mail-send-progress.md) and [0033: Detailed progress](0033-detailed-mail-send-progress.md)
- [OpenTelemetry library instrumentation](https://opentelemetry.io/docs/concepts/instrumentation/libraries/): API versus SDK ownership.
- [OpenTelemetry Java API](https://opentelemetry.io/docs/languages/java/api/): context and scope integration.
- [OpenTelemetry error recording](https://opentelemetry.io/docs/specs/semconv/general/recording-errors/): recovered attempts versus failed operations.
- [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/): optional JVM instrumentation, not SJM activation.

The dated [research findings](../research/762-opentelemetry-integration-research.md#research-findings-2026-10-15) identify the OpenTelemetry repositories
and limits of the absence finding. They do not assert that SMTP has no standards or that no third-party mail instrumentation exists.
