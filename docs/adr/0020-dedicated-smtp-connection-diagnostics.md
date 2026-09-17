# ADR 0020: Inspect SMTP capabilities on a dedicated diagnostic connection

- Status: Accepted (retrospective record of implemented design)
- Recorded: 2026-09-16
- Applies to: Unreleased 10.0.0 connection probes
- Implementation: Java, CLI and external-adapter fixture committed for #733

## Context and historical rationale

A successful connection test cannot explain an endpoint's advertised features, changes after STARTTLS, or the phase in which setup failed. Protocol debug logs are unsuitable as the ordinary structured report and may expose sensitive material. [#733](https://github.com/bbottema/simple-java-mail/issues/733) explicitly calls for a separate inspection API, a dedicated connection, safe partial results and supported provider hooks without reflection or debug-log scraping.

[60d5198e](https://github.com/bbottema/simple-java-mail/commit/60d5198eb1c0f7b16f74fc77a68ba2ffa230b204), 15 September 2026, records provider-aware dedicated probes preserving Session, proxy and authentication boundaries. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/733#issuecomment-5676669596) confirms Java and CLI use. These are committed but unreleased; older local research notes describing CLI/fixture work as unfinished do not describe this baseline.

## Decision

Expose `probeConnection()` through the existing synchronous and asynchronous views, keeping `testConnection()` as the simple health check. Probe one fresh connection outside the send pool. Select an optional `SmtpConnectionProbeAdapter` for the exact Jakarta Mail provider via `ServiceLoader`; unsupported providers and `CustomMailer` return an explicit unsupported report without connecting.

Clone Session properties and retain provider selection without modifying the original Session. Authentication is opt-in per invocation. The default path has no authenticator or cached credentials and does not call the OAuth2 token provider; explicit authentication reuses normal credential resolution. The CLI's `--authenticate` belongs to the request, not the retained Mailer profile.

Capture separate greeting, pre-TLS EHLO, STARTTLS, post-TLS EHLO and optional authentication facts through supported provider hooks. Retain unknown and repeated extensions. Reject stale discovery: a failed post-TLS EHLO must not authorize authentication using earlier advertisements. TLS metadata must preserve the configured trust and hostname-verifier decisions; missing metadata is preferable to changing that policy to populate a report.

Return immutable, bounded and escaped display facts and phase-specific failures. Do not retain message content, authentication exchanges, raw provider exceptions or transport references in the report. A capability advertisement is evidence of the server's response, not proof that a later send used it. Inspecting a connection never sends an Email or produces a mail-send observer event.

HELO-only discovery and oversized EHLO replies leave capabilities unknown. The SIZE helper accepts only consistent positive numeric limits. `AngusProbeTlsObserver` retains an application-supplied verifier object's veto and leaves a verifier configured by class name untouched; it can report unavailable metadata rather than duplicate private class-loading behavior. TLS protocol, cipher, peer identities, and configured identity checking are separate facts, not an independent verified-trust verdict.

Third-party adapters own secret filtering before constructing the report: escaping text alone does not make raw exceptions or AUTH replies safe. A successful connection may omit unavailable EHLO/TLS details instead of fabricating empty snapshots. The public fixture verifies `META-INF/services` and JPMS `provides` discovery with Angus absent and is not part of published artifacts.

## Alternatives and consequences

The dedicated connection and prohibition on debug-log parsing/reflection are explicit issue requirements. Reusing a pooled transport would mix diagnosis with send ownership and report whichever prior state the pool happened to retain. Introducing probes as a send preflight would add another connection without proving the subsequent connection has identical capabilities. Those causal tradeoffs explain the chosen boundary; the probe is not a new gate on ordinary sends.

Existing proxy accounting is reused. The synchronous probe holds the Mailer monitor through cleanup; the asynchronous wrapper executes that same path. This keeps the proxy alive but serializes probes on one Mailer. Provider I/O timeouts apply; total mail-send deadlines and physical cancellation from [ADR 0017](0017-deadlines-and-physical-cancellation.md) do not. Cancelling the returned future does not abort probe I/O.

The probe API defaults to no authentication, but construction of a Mailer with a nonzero pool core size can separately warm/authenticate send connections. Probe isolation does not disable that existing behavior. Logging-only sending also does not prohibit a caller from explicitly requesting a connection probe.

## Implementation evidence

- [SmtpConnectionProbe](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/SmtpConnectionProbe.java) owns provider selection and Session/credential isolation.
- [SmtpConnectionReport](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/SmtpConnectionReport.java), [AngusProbeTransport](../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusProbeTransport.java) and [AngusProbeTlsObserver](../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusProbeTlsObserver.java) define report and provider boundaries.
- Existing [probe integration tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpConnectionProbeTest.java) and the separately packaged [third-party provider fixture](../../modules/simple-java-mail/src/test/third-party-probe/org/simplejavamail/thirdpartyprobe/ThirdPartyProbeConsumer.java) supply implementation evidence. Their presence is not a new test run or proof of compatibility with every real provider.
