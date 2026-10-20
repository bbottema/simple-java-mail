# ADR 0021: Reject configuration that contradicts mandatory STARTTLS

- Status: Accepted (retrospective record of implemented design)
- Recorded: 2026-09-16
- Applies to: Unreleased 10.0.0, regular-builder Sessions owned by Simple Java Mail
- Implementation: Committed in `5de87177`, with Java, Spring and CLI coverage

## Context and historical rationale

Extra Jakarta Mail properties intentionally let applications customize provider behavior. However, `mail.smtp.starttls.required=false` could override the requirement implied by `SMTP_TLS` or `SMTP_OAUTH2`. The Mailer would still expose a mandatory strategy while the provider could authenticate without encryption when STARTTLS was unavailable.

[#735](https://github.com/bbottema/simple-java-mail/issues/735) records this contradiction and the deliberately narrow remedy: reject it during construction while keeping opportunistic `SMTP`, caller-owned Sessions and custom transports. [5de87177](https://github.com/bbottema/simple-java-mail/commit/5de8717779df110d076edf407273c024fc7ac983), 15 September 2026, implements the guard. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/735#issuecomment-5679587327) states the migration: remove the conflicting override or choose opportunistic SMTP intentionally.

This follows separate security work rather than replacing it. [270c6173](https://github.com/bbottema/simple-java-mail/commit/270c6173dbb77dfc579cccb4cd3e87fda5827511) explained why server identity checking also matters for opportunistic TLS; [dac194be](https://github.com/bbottema/simple-java-mail/commit/dac194beae6c7ab8719b0e4abd096de560fdc8c5) restored trusted-certificate defaults. Requiring an upgrade, checking identity and trusting a certificate are distinct settings.

## Decision

Validate the final extra properties at Mailer construction for library-owned regular-builder Sessions, including logging-only mode. For `SMTP_TLS` and `SMTP_OAUTH2`, permit no direct override or an override that retains the requirement: Boolean `true` or an untrimmed case-insensitive `"true"` string. Reject false or malformed values before proxy, transport-lifecycle or pool setup. Match the direct-entry semantics used when properties are copied; do not reinterpret inherited `Properties` defaults as applied entries.

Fail with an actionable message that names the conflicting key and correction without rendering its value. Do not silently rewrite the user's value, resolve configuration sources again, add a probe preflight or create another TLS-policy enum.

Retain the existing ownership boundary for caller-supplied Sessions and `CustomMailer`. Keep `SMTP` opportunistic, including password authentication, and retain existing strategy/port and explicit trust/identity settings. A failed mandatory TLS upgrade must not become a plaintext retry.

Caller-Session connection customization follows [ADR 0015](0015-execution-views-and-transport-pooling.md#caller-supplied-session-baseline):
only explicit generic choices, configured additional Jakarta Mail properties and applicable locks replace supplied settings. Recognizing a
transport-strategy marker is not permission to apply ordinary SJM TLS/trust defaults or initialize a caller-owned transport as a managed one.
This does not weaken the mandatory-STARTTLS guard on Sessions created by the regular builder.

## Alternatives and consequences

Silently forcing the override back to true would conceal contradictory configuration. Rejecting it makes the chosen strategy meaningful and lets the caller correct their intent. Changing every authenticated `SMTP` connection into mandatory TLS would be a broader compatibility and policy change; the issue explicitly excludes it.

Using the diagnostic probe as proof of safety would inspect a different connection and add resource ownership to construction. [ADR 0020](0020-dedicated-smtp-connection-diagnostics.md) keeps inspection separate. The guard instead validates the configuration owned by this builder, before connection activity.

This is a construction-time consistency guarantee. It does not protect against subsequent mutation of the exposed Session, override caller-owned providers/socket factories, or constitute a new independently verified trust verdict. Those limits are explicit in #735, not inferred exceptions added by this record.

The [custom-factory safeguards](../concurrency/07-angus-transport-abort.md) complements, rather than expands, this TLS
strategy check. An owned custom factory defaults to no fallback; explicitly configured true/false is preserved. Deliberate fallback can
abandon that factory's trust or client identity, even though implicit-TLS fallback still uses TLS. It never becomes a STARTTLS plaintext
retry. A thin SSL decorator closes the connected socket if wrapping fails before handoff, preserving the failure and suppressing close
errors; Angus still owns TLS negotiation and trust. Public class-factory access respects JPMS exports, with an instance as the alternative.
Locks are checked again after owned provider initialization so a locked value cannot silently be replaced by a provider safeguard.

## Implementation evidence

### Implicit-TLS OAuth2 preset (accepted 2026-10-20)

Add `TransportStrategy.SMTPS_OAUTH2` for XOAUTH2 over a connection encrypted from its first byte. Its default port is 465;
`SMTP_OAUTH2` continues to require STARTTLS on port 587. Keep the bounded transport-preset API, the default opportunistic
`SMTP` strategy, and all existing setup signatures. This additive preset does not require existing applications to migrate.

The implicit-TLS preset uses `smtps` and its `mail.smtps.*` authentication, endpoint, timeout and TLS properties. Reuse
the ordinary SMTPS property definitions rather than maintaining another independent list. A regular-builder override
must not change the selected implicit-TLS protocol into `smtp`; reject that contradiction before resource setup without
rendering its value. An `ssl.enable=false` property does not turn Angus's `smtps` transport into a plaintext transport.
Retain the existing STARTTLS consistency rule for the two STARTTLS strategies; the implicit-TLS preset does not require
or negotiate STARTTLS.

Both fixed tokens supplied as the SMTP password and application-owned `OAuth2AccessTokenProvider` instances work with
either OAuth2 preset. Credentials remain mutually exclusive; providers remain lazy, thread-safe application integrations
resolved at physical connection/reconnection, not Mailer construction or an unauthenticated probe. Select the OAuth2
username and mechanism keys from the actual protocol. A caller-supplied Session remains caller-owned even when it has
an SJM strategy marker: recognizing `smtps` authentication does not apply regular-builder trust defaults or install a
managed provider on it. Certificate-chain and hostname checks, probe isolation, secret redaction and SDK ownership
remain under their existing contracts.

An OAuth2 password lock compares the fixed token, not the unrelated `mail.*.auth=true` flag used by the password
Authenticator route. Preserve token equality and strategy-mechanism restrictions without requiring that unused flag.

The implementation and local-fixture verification ledger is [the SMTPS OAuth2 plan](https://github.com/bbottema/simple-java-mail/blob/e3fbaf777082955e6ea3d387b245254996d49505/docs/plans/10.0.0-smtps-oauth2.md).

- [MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java), especially `validateMandatoryStartTls(...)` and its constructor call before resource setup.
- [SmtpTlsConfigurationTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpTlsConfigurationTest.java), [Spring TLS configuration tests](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SimpleJavaMailSpringTlsConfigurationTest.java) and [CLI TLS configuration tests](../../modules/cli-module/src/test/java/org/simplejavamail/internal/clisupport/CliTlsConfigurationTest.java).
- [10.0.0 migration guidance](https://www.simplejavamail.org/migration-notes-10.0.0.html#mandatory-starttls). Existing test sources were inspected; no runtime behavior was changed or new test run claimed here.
