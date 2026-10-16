# OpenTelemetry integration

Add `org.simplejavamail:open-telemetry-module` at the same version as your Simple Java Mail libraries, then explicitly register your
application's configured instance:

```java
import org.simplejavamail.otel.OtelAdapter;

mailerBuilder.withMailSendTelemetry(
    OtelAdapter.wrap(openTelemetry));
```

`OtelAdapter.wrap(...)` accepts OpenTelemetry's public interface and returns the core `MailSendTelemetry` handle. It retains the exact
supplied instance without starting instrumentation, installing a global instance or creating runtime resources. All sending preferences
are configured through the regular Mailer builder and properties. Core/main acquire no OpenTelemetry dependency.

The module uses OpenTelemetry API/context only. The application owns the SDK, exporters, sampling, batching, flush and shutdown.
JAR or global-SDK presence alone does not activate it. For property-based activation, use:

```properties
simplejavamail.telemetry.enabled=true
```

Every Mailer created from that factory's captured configuration inherits the choice. Without a specific Java registration, the loaded
module uses the application's global OpenTelemetry instance. Initialize it before building enabled Mailers; an unconfigured global
instance provides no-op reporting, not an automatically created SDK or exporter. `withMailSendTelemetryEnabled(false)` disables a local
Mailer; reset selects the false built-in default, and clearing an integration restores activation from captured properties. The effective
choice is exposed by `OperationalConfig.isMailSendTelemetryEnabled()`. Activation also supports `simplejavamail.locked.telemetry.enabled`.

One INTERNAL `mail.send` span covers the logical email send; each provider invocation creates a CLIENT `mail.submit` child.
Retries retain the logical parent and create another invocation child. CustomMailer uses an opaque INTERNAL `mail.custom_submit` child;
logging-only sends have no submission child. Completion is recorded before result-observer delivery, independently of progress callbacks.
SMTP acceptance is not final delivery.

Logical-send and invocation counts/durations, active sends and available phase durations use the `org.simplejavamail.mail` scope and
`simplejavamail.mail.*` instruments. Metrics are independent of trace sampling; missing measurements are not zero.

The logical `mail.send` span also carries each available duration as `simplejavamail.send.phase.<phase>.duration`, in seconds.
The eight phases are preparation, scheduling, rate-limit waiting, connection acquisition, MIME preparation, submission, retry waiting
and cleanup. Measured zero stays present; unavailable timings stay absent. `simplejavamail.send.failure.phase` identifies the existing
primary failure measurement when known, never a later suppressed cleanup failure. Success and unmeasured failures omit that attribute.
These details stay on the logical span, not its children or metric labels. They introduce no additional timing boundaries.

All duration histograms provide second-based bucket advice: OpenTelemetry's HTTP duration scale from 5 ms through 10 s, extended with
30, 60, 120 and 300 seconds for SMTP setup and local rate/retry waits. This avoids generic whole-second buckets obscuring short sends;
longer durations enter the overflow bucket rather than being dropped. Application SDK Views can override the advice. Collection/export
cadence remains application-owned and does not round the recorded durations. See the
[observation comparison](https://www.simplejavamail.org/analyzing-send-results.html#section-observation-options) for progress versus results versus telemetry.

Endpoint attributes are disabled by default. The Mailer builder's `withMailSendTelemetrySmtpEndpointAttributes(true)` permits actual selected
logical host/port in traces, never metric labels. `clearMailSendTelemetrySmtpEndpointAttributes()` restores captured configuration;
`resetMailSendTelemetrySmtpEndpointAttributes()` explicitly chooses the published false fallback. Replacing or clearing a handle does not
change this permission, and sharing a handle between Mailers does not share their settings.

```properties
simplejavamail.telemetry.smtp.endpointattributes=true
# Or centrally prohibit this reporting permission:
simplejavamail.locked.telemetry.smtp.endpointattributes=false
```

In Spring, activation properties apply to every Mailer from the configured factory; a `MailSendTelemetry` bean can select a particular
instance for the default Mailer. The CLI's `--mailer:withMailSendTelemetryEnabled` activates the included module, while
`--mailer:withMailSendTelemetrySmtpEndpointAttributes` controls permission only. Neither creates an SDK. When using OpenTelemetry's Java
instrumentation agent, use its installed global instance rather than creating a competing application SDK.

`ModuleLoader.loadOpenTelemetryModule()` loads a stateless internal entry point. That module interprets the adapted instance and creates
the Mailer-owned runtime; the main builder requires only the core handle. Runtime bridges remain internal. With no supplied handle,
enabled telemetry uses the application-global instance. SDKs and per-Mailer runtimes never enter the shared module cache.

No email content, mailbox identities, credentials, Message-ID, ENVID, raw exception text or stack traces are exported by this integration.
Observation UUIDs correlate traces and results; they are not trace IDs or metric labels. Telemetry changes neither MIME bytes nor SMTP commands.

See the [full walkthrough and illustrative output](https://www.simplejavamail.org/opentelemetry.html),
[architecture decision](../../docs/adr/0034-optional-opentelemetry-integration.md) and
[scope/lifetime documentation](../../docs/concurrency/13-mail-send-telemetry.md).

## Verification

`mvn -pl modules/open-telemetry-module -am verify` runs the module through the matching reactor. Tests cover real loopback SMTP and supplied
SDKs, plus separate JVMs with/without the OpenTelemetry Java agent. The latter uses a loopback OTLP/protobuf receiver, not private agent APIs.
Configuration-only classpath/JPMS consumers run without the main module, SDK or exporters. Separate packaged runtime consumers exercise
property-driven module loading with the main library and SMTP provider present, still without SDK or exporter dependencies.
No live email or external telemetry collector is required.

`MailSendTelemetryDemoApp` under `src/test/java/org/simplejavamail/otel` is a runnable loopback example with an application-owned SDK and
in-memory test exporter. It prints the request/send/submission parentage without sending to an external mailbox.
