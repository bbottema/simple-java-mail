# ADR 0038: Make connection configuration conflicts explicit

- Status: Accepted, 2026-10-24
- Target: Unreleased 10.0.0
- Tracking: [#867](https://github.com/bbottema/simple-java-mail/issues/867)
- Implementation status: Initial policy implemented for unreleased 10.0.0; shared-resolution adoption under ADR 0039 is pending in #867.

## Context

A configured Mailer can describe one SOCKS proxy while its Session uses another: proxy configuration is applied before additional Jakarta Mail properties. With authenticated proxying, replacing the loopback host while subsequently publishing an allocated bridge port can also create an incoherent route. Values injected through files, Spring, environment variables or deployment systems make such contradictions difficult to discover.

The historical contracts address different responsibilities. [#19](https://github.com/bbottema/simple-java-mail/issues/19) introduced low-level Session customization. [#38](https://github.com/bbottema/simple-java-mail/issues/38) introduced SJM's authenticated proxy bridge, and [#686](https://github.com/bbottema/simple-java-mail/issues/686) reaffirmed configured proxy routing on caller-supplied Sessions. [#785](https://github.com/bbottema/simple-java-mail/issues/785) preserves supplied connection settings; [#845](https://github.com/bbottema/simple-java-mail/issues/845) distinguishes explicit timeout choices from inherited defaults. These decisions do not establish one universal precedence rule for all configuration.

Callers need both early detection of unintended contradictions and deliberate use of convenience APIs to replace supplied setup. A first-assignment lock would make authority depend on initialization history and interfere with normal builder editing.

## Decision

### One advanced construction policy

Introduce the core-owned `ConnectionConflictPolicy` enum with `REJECT_CONFLICTS` and `ALLOW_EXPLICIT_OVERRIDES`. The default for 10.0.0 is `REJECT_CONFLICTS`.

Expose `withConnectionConflictPolicy(...)` on the Mailer builder, independently of individual connection, TLS or proxy group replacement. Provide passive inspection, reset-to-strict and restore-configured companions. Completed operational inspection reports the selected policy. A required policy argument rejects null before mutation.

The shared schema owns `simplejavamail.connection.conflictpolicy`, its typed key and locked counterpart. Java selection overrides the captured policy unless a lock forbids it; absence selects the captured policy or the strict default. Spring and CLI use this same configuration path.

```java
Mailer mailer = mail.mailerBuilder(session)
    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
    .withProxyConfig(proxy)
    .buildMailer();
```

The scope is SJM-supported SMTP, proxy, socket-timeout, local-binding, EHLO and TLS connection settings. Message composition, execution, pooling and telemetry settings retain their own contracts. Unknown provider extensions and hidden Authenticator state do not acquire invented interpretations or guarantees.

### Resolve sources before comparing declarations

Keep the ordered source resolution in [ADR 0003](0003-immutable-configuration-snapshots.md) and Spring's Environment authoritative within that stage. A file/environment disagreement already settled by the loader is not a construction conflict. Preserve winning-source provenance, including individual additional properties.

Then compare the explicitly configured factory values, recognized raw properties, visible supplied Session values and final Java choices that describe the same logical connection setting. Built-in defaults and provider-derived runtime values are not competing declarations. Equivalent values after the existing representation parsing are compatible; do not resolve DNS, invoke callbacks or read external resources to invent equivalence.

Under `REJECT_CONFLICTS`, different explicit declarations fail at construction. This includes a configured factory value and a different visible supplied Session value even when ordinary baseline precedence would have selected one. An absent Session setting is not a contradictory declaration.

Compare final choices rather than assignment history. Repeated setters and replacement groups retain normal editing behavior. Explicit removal of an imported value is a conflicting customization in strict mode; removing a previous local-only choice remains ordinary editing. Reset and restoration retain their distinct meanings under [ADR 0031](0031-public-api-and-internal-construction.md).

### Deliberate overrides and partial choices

Under `ALLOW_EXPLICIT_OVERRIDES`, explicitly supplied typed Java members take precedence over their raw counterparts regardless of setter-call order. Raw Java additions retain their existing precedence over captured additional properties. Remaining members retain applicable supplied raw/baseline values before ordinary SJM fallback. A Java proxy host alone does not replace an explicitly supplied raw port with a default port.

For supplied Sessions, ordinary factory connection defaults do not automatically fill gaps in either mode. Only deliberate customization or applicable locks add or replace settings. Explicitly requested grouped configuration may use its existing companion-value inheritance to form a complete requested operation; this does not authorize filling unrelated Session settings.

This clarifies and supersedes conflicting precedence wording in ADRs 0003 and 0015. It does not change Session ownership or authorize installation of an owned provider on a caller's Session.

### Compose the remote proxy before bridge routing

Recognized `mail.smtp.socks.host`/`port` and `mail.smtps.socks.host`/`port` values contribute to logical remote proxy resolution for the selected protocol. Compatible raw endpoints can combine with SJM credentials from factory or Java configuration. This extends the previous SJM-only endpoint inheritance.

Resolve one remote destination and authentication configuration before deriving Session routing. Authenticated proxying uses the optional authentication module and a loopback bridge; an allocated local port is a generated runtime fact, not another user declaration. Keep the requested remote endpoint distinct from the local endpoint reported by the effective Session. Raw-only anonymous proxying remains supported.

[ADR 0010](0010-authenticated-socks-bridge.md) continues to own loopback binding, listener lifetime, pooled sockets and port refresh on restart. This decision does not turn raw HTTP proxy settings or arbitrary socket factories into an SJM routing guarantee.

### Requirements remain mandatory

[ADR 0025](0025-factory-scoped-locked-configuration.md) applies in both modes, including a locked policy. Explicit customization cannot silently defeat a typed or raw lock. Check compatible logical and physical requirements before applying routing, including the distinction between remote proxy locks and literal raw Session routing locks.

[ADR 0021](0021-mandatory-starttls-configuration-consistency.md) continues to reject mandatory-transport contradictions. Existing required-input, feature-activation and ownership restrictions are not disabled by permissive conflict handling. `CustomMailer` does not activate SJM's SMTP route merely because connection settings exist.

### Validate before effects; explain conflicts safely

Collect declarations and resolve policy before changing a supplied Session or allocating connection resources. Apply a valid resolution only after exclusive Session ownership is claimed. Retain construction rollback, provider initialization order and post-initialization lock checks.

Construction errors identify the logical setting, originating property/setter names and available source labels, using existing escaping and secret-redaction rules. Getters remain passive. Do not expose a new public effective-resolution report; [ADR 0004](0004-configuration-provenance-diagnostics.md) remains a report of captured factory inputs.

## Alternatives and precedent

- **Always prefer convenience APIs:** simple to compose, but can conceal deployment configuration. It remains an explicit opt-in policy.
- **Always prefer raw properties:** preserves historical escape-hatch behavior but can defeat the selected proxy and its authentication bridge.
- **First-write locking on every builder:** makes defaults, copies and resets depend on assignment history. Existing value-specific locks remain the way to express requirements.
- **Reject every repeated setter:** disrupts ordinary draft editing and does not distinguish conflicting configuration mechanisms.
- **A public resolution report:** useful as a separate future feature; source-aware construction errors are sufficient for this decision.

Spring Boot combines [ordered property sources](https://docs.spring.io/spring-boot/reference/features/external-config.html) with a separate [bean-overriding switch](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/SpringApplication.html). Spring Cloud distinguishes [remote authority and local overrides](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html#overriding-the-values-of-remote-properties). Spring Kafka's [JSON deserializer](https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/support/serializer/JacksonJsonDeserializer.java) rejects mixing its recognized properties with explicit setters. [Guice](https://google.github.io/guice/api-docs/latest/javadoc/com/google/inject/util/Modules.html) exposes intentional module overrides; [Immutables](https://immutables.github.io/immutable.html#strict-builder) has separately generated strict builders. These are scoped precedents, not a promise that their contracts equal SJM's.

## Consequences

Applications with conflicting explicit connection declarations may now fail during construction. Align or remove the declarations, or deliberately select `ALLOW_EXPLICIT_OVERRIDES`; locks remain mandatory. This behavior affects migration from released APIs and needs explanatory guidance, not an automatic rewrite that silently opts applications into permissive handling.

Implementation must retain declaration origin and explicit removals independently of resolved defaults. The same rules apply to Java, configuration, Spring and CLI construction, and inspection must describe the resolution actually used. The tracking issue owns delivery status.

## Shared configuration resolution (accepted 25 October 2026)

[ADR 0039](0039-explicit-configuration-resolution.md) provides the shared internal foundation for this policy. Capture final declarations and property views once, resolve complete logical choices, validate conflicts and requirements, then apply an immutable validated plan under Session ownership. String-valued properties retain getProperty visibility separately from direct entries; empty text is present and opaque resources compare by identity. Supported decimal/hexadecimal numeric and Boolean forms have one interpretation across comparison, locks, inspection and application.

Design accepted; shared-foundation implementation is pending under [#867](https://github.com/bbottema/simple-java-mail/issues/867).

TLS flags and host exceptions resolve together while retaining ownership-specific inheritance. Trusted-host lists containing the same exact names are equivalent regardless of order or duplicates; the selected ordering remains visible to inspection. Host spelling remains case-sensitive and exact wildcard syntax remains distinct. A request to disable wildcard trust cannot leave an inherited wildcard active, and compatible bounded trust locks must not become apparent removals.

Raw SOCKS endpoint syntax, including an embedded port and the provider's separate-port precedence, contributes to the remote route before bridge derivation. A supplied Session's omitted proxy port remains omitted unless deliberately customized or required by a lock; provider-default inspection does not authorize inserting an ordinary factory port. Preserve the existing behavior of explicit bounded hosts replacing a supplied Session's wildcard trust, while omitted captured TLS members on owned Sessions retain their inheritance.
