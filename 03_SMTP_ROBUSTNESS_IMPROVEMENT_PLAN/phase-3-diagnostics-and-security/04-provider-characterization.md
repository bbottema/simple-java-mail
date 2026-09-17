# SMTP capability probe: provider characterization

- Issue: [#733](https://github.com/bbottema/simple-java-mail/issues/733), part of [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- Date: 11 September 2026
- Status: Characterization complete. The subsequent Java/CLI probe, dedicated-connection integration, demo and third-party adapter fixture were accepted under #733 on 15 September 2026; see the [completed implementation plan](04-add-structured-smtp-capability-probe.md).
- Provider baseline: the repository's pinned Angus Mail 2.0.5, not Angus `master` or the separate upstream PR branch.
- Executable evidence: [SmtpCapabilityProbeCharacterizationTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpCapabilityProbeCharacterizationTest.java).

## What we can observe without taking over SMTP or TLS

| Fact | Supported hook | Boundary |
| --- | --- | --- |
| Greeting | Override `readServerResponse()`, keep the first greeting only | Do not collect every later response: AUTH replies can contain challenges or echoed credentials. |
| Pre-/post-TLS EHLO | Override `ehlo()`, record that call's result and `getLastServerResponse()` immediately | The last reply changes again during AUTH, NOOP and QUIT. A successful connection's final reply is not its capability list. |
| STARTTLS attempted/completed | Override `startTLS()`, record entry and successful return separately | A rejected command, failed handshake or rejected identity check must not look like a completed upgrade. |
| Encrypted SMTP connection | `isSSL()` at the greeting/EHLO boundary | This says the socket is TLS, not that a particular trust or identity policy was enforced. |
| TLS session metadata | A configured `mail.smtp.ssl.hostnameverifier` receives `SSLSession` | Angus does not provide a transport TLS-session getter. A reporting hook must preserve existing verification, including its veto and later trust checks. |
| Advertised AUTH mechanisms | Each successful EHLO reply | Neither configured mechanisms nor `connect()` returning successfully proves authentication happened. |

The characterization subclass is deliberately confined to test code. At that initial checkpoint no production API changed. The subsequent Java implementation uses a separate inspection-only Angus subclass through `SmtpConnectionProbeAdapter`; the existing send implementation and authentication defaults remain unchanged.

## Important interleavings and differences

### A failed second EHLO leaves Angus's old extension map intact

The local peer advertises `SIZE 1024` and `STARTTLS`, upgrades successfully, then rejects the second EHLO. Angus 2.0.5 still returns successfully from `connect()` and `getExtensionParameter("SIZE")` still returns `1024`.

This is a reproduced provider behavior, not a new Simple Java Mail send fix. The diagnostic must keep the failed post-TLS EHLO as a separate result and report effective capabilities as unknown. It must not relabel the earlier advertisement as post-TLS information. A successful second EHLO does replace the provider map; the successful-upgrade case proves that separately.

For implicit TLS there is no plaintext EHLO and no STARTTLS command. Its first EHLO belongs to the encrypted connection; an absent plaintext snapshot does not mean discovery failed.

### "Do not authenticate" needs to be real behavior

Passing username/password can trigger AUTH even when `mail.smtp.auth=false`. Passing `null` credentials can still invoke a caller-owned Session's `Authenticator` when authentication is enabled. An unauthenticated probe therefore cannot simply forward the ordinary connection helper with different arguments.

The no-auth path must suppress credential lookup, cached authentication and OAuth2 token-provider calls as well as AUTH commands. It must do so without changing the original Session. The authenticated path must retain the original authenticator/token-provider semantics. Caller-owned/custom-provider preservation is still an integration gate, not something these initial tests prove.

Conversely, with `mail.smtp.auth=true` and credentials supplied, Angus can return from `connect()` without authenticating if the server never advertised AUTH. A report must distinguish successful connection setup from successful credential testing.

### TLS details must not change the security behavior being diagnosed

The local TLS tests prove that the configured hostname-verifier hook receives protocol/cipher/certificate facts and retains its ability to reject the connection. Returning from that hook is not yet the final connection result; Angus can perform additional trust checks afterwards.

A probe-specific observer may use that hook only when it can preserve the configured verification path. In particular, a verifier configured by class name must not silently be replaced by an always-accepting observer. Do not copy Angus's private verifier-loading and trust logic just to fill in a report. Missing metadata remains unavailable with an explanation.

Certificate metadata and encryption are not substitutes for evidence of hostname verification or certificate trust. Custom socket factories and trust managers can change those checks. Display configured policy separately from observed results; do not invent a single "verified TLS" boolean from configuration.

### Safe formatting belongs to the public report, not the characterization helper

The characterization subclass retains only greeting/EHLO responses, not a transcript or AUTH exchange. It is not the safe public report. The implemented report copies and escapes display fields, retains no raw exception or authentication reply, and replaces arbitrary failure text with phase-specific descriptions. Capability parsing bounds the retained snapshot after Angus reads the reply; it does not introduce a new limit on Angus's network response buffering. Oversized lines/replies leave capabilities unavailable rather than presenting truncated names, values or extension lists as complete.

The current test also demonstrates mixed-case extensions, duplicate SIZE entries, an unknown extension with parameters, and a malformed empty line. Angus keeps the last duplicate value and ignores the empty line. The report parser must have an explicit, tested policy for these cases rather than accidentally inherit a mutable provider map.

## Implementation direction

1. Put the probe on `Mailer` so it uses the configuration the developer is diagnosing. Keep `testConnection()` unchanged; do not redirect its existing pool/custom-mailer behavior through a new API.
2. Use a dedicated connection, outside the send pool and mail-send observer/cancellation pipeline. This reports a fresh negotiation with the configured endpoint, not the capabilities of every endpoint in a pool cluster.
3. Add a narrow provider boundary for connection inspection. The existing submission adapter owns sending, and the lifecycle adapter owns abort control; neither should acquire a second unrelated responsibility merely to avoid another interface.
4. For known Angus providers, use a small inspection-only subclass built around the supported hooks characterized here. Do not add probe state to `ManagedAngusTransport` or borrow a pooled send's transport.
5. Preserve custom providers and caller-owned Sessions. Unsupported diagnostics must be explicit; never silently replace an arbitrary provider/subclass with stock Angus. Characterize dedicated-session credentials, socket factories, proxies and provider selection before declaring this path compatible.
6. Return an immutable provider-neutral report, including partial facts on a connection failure. It must distinguish an absent/failed measurement from a measured negative result, without exposing provider objects, raw exceptions or credentials in its safe display.
7. Default to capability discovery without authentication. Authentication must be an explicit per-probe choice, not a new default applied to ordinary sends. Settle its public spelling together with the synchronous/asynchronous entry points before adding API methods.
8. Keep the following authentication-policy step separate. Do not change authentication/TLS defaults or implement PIPELINING/CHUNKING here.

## Verification and remaining gates

Verification so far: all 13 new cases pass on JDK 11 and JDK 21; the 35 existing Angus adapter/abort/result cases also pass on JDK 21. This is focused characterization, not full feature or reactor verification.

The initial suite has 13 local cases:

- greeting/EHLO retention after QUIT, multiline/case/duplicate/unknown-extension behavior;
- EHLO rejection with HELO fallback;
- successful STARTTLS with separate snapshots;
- successful TLS followed by rejected EHLO and stale provider capabilities;
- STARTTLS absent/refused, strict certificate rejection, implicit TLS;
- a configured hostname verifier accepting/rejecting the connection;
- explicit credentials despite `auth=false`, Session-authenticator fallback from null credentials, and connection success without advertised AUTH.

It reuses the existing `smtp_test_server.jks` fixture. The implicit-TLS ordering case explicitly permits test certificates but still enables hostname checking: local mail-shield software was observed replacing even the loopback peer's certificate. The separate rejection case uses an empty trust store and remains strict. No production trust policy was relaxed. Tests should not be described as end-to-end peer-certificate pinning or complete hostname-mismatch coverage.

## Implemented Java slice

The real `Mailer` API is covered separately by [SmtpConnectionProbeTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpConnectionProbeTest.java), and immutable display behavior by [SmtpConnectionReportTest](../../modules/core-module/src/test/java/org/simplejavamail/api/mailer/SmtpConnectionReportTest.java). Coverage includes:

- synchronous/asynchronous probes, executor rejection and a closed Mailer;
- untouched original Session/provider settings, credential-cache/Authenticator forwarding, no-auth credential isolation and OAuth2 success/failure;
- actual AUTH mechanism recording, authentication not advertised, incompatible mechanisms and secret-bearing server errors excluded from reports;
- strict certificate rejection, native endpoint-identity rejection with an intentionally mismatched TLS peer name, object/class verifier veto preservation, STARTTLS failures and implicit TLS;
- failed post-TLS EHLO with no stale effective snapshot or AUTH continuation;
- HELO fallback, explicit EHLO disablement, repeated and unknown extensions, invalid lines and exact diagnostic-size boundaries;
- cleanup timeout with earlier facts retained, proxy routing, logging-only mode, unsupported providers and CustomMailer;
- pool-size-one probing while the ordinary pool lease is held, plus snapshots remaining unchanged across later probes;
- public API classpath/JPMS linking without Angus, and managed-Angus SPI discovery on the module path.

The [manual demo](../../modules/simple-java-mail/src/test/java/demo/SmtpConnectionProbeDemoApp.java) runs against its own loopback peer by default. README, Diagnostics website examples, release entries and [ADR 0020](../../docs/adr/0020-dedicated-smtp-connection-diagnostics.md) describe the Java API and its boundaries. The shared send infographic was reviewed and remains unchanged: this dedicated diagnostic connection is outside email sending, pooling, observation and send control.

Remaining before feature completion: CLI command/report integration and one-shot/daemon checks, a third-party partial-detail adapter runtime fixture, and the production-diff/holistic review. The current custom-provider test proves explicit unsupported behavior, not compatibility with every provider. The authentication-policy step remains separate.

## Source references

- [Angus 2.0.5 SMTPTransport](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java): connection, EHLO, STARTTLS, AUTH and last-response behavior.
- [Angus 2.0.5 SocketFetcher](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java): TLS setup, endpoint identification, hostname-verifier callback and later trust checks.
- [Angus 2.0.5 SMTP package documentation](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/package-info.java): supported SMTP/TLS configuration hooks.

These references explain the provider boundary; the local scripted tests establish the runtime behaviors listed above.
