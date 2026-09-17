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

## Alternatives and consequences

Silently forcing the override back to true would conceal contradictory configuration. Rejecting it makes the chosen strategy meaningful and lets the caller correct their intent. Changing every authenticated `SMTP` connection into mandatory TLS would be a broader compatibility and policy change; the issue explicitly excludes it.

Using the diagnostic probe as proof of safety would inspect a different connection and add resource ownership to construction. [ADR 0020](0020-dedicated-smtp-connection-diagnostics.md) keeps inspection separate. The guard instead validates the configuration owned by this builder, before connection activity.

This is a construction-time consistency guarantee. It does not protect against subsequent mutation of the exposed Session, override caller-owned providers/socket factories, or constitute a new independently verified trust verdict. Those limits are explicit in #735, not inferred exceptions added by this record.

## Implementation evidence

- [MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java), especially `validateMandatoryStartTls(...)` and its constructor call before resource setup.
- [SmtpTlsConfigurationTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpTlsConfigurationTest.java), [Spring TLS configuration tests](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SimpleJavaMailSpringTlsConfigurationTest.java) and [CLI TLS configuration tests](../../modules/cli-module/src/test/java/org/simplejavamail/internal/clisupport/CliTlsConfigurationTest.java).
- [10.0.0 migration guidance](../../MIGRATION-10.0.md#conflicting-mandatory-starttls-overrides-now-fail-construction). Existing test sources were inspected; no runtime behavior was changed or new test run claimed here.
