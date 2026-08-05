# Document the SMTPS proxy restriction

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

Proxy sections omit that builder-managed SOCKS proxying is rejected with `TransportStrategy.SMTPS`. A Spring example combines SMTPS with incomplete proxy settings and suggests the combination is valid.

## Plan

State the restriction beside the first proxy example. Use SMTP_TLS for proxy examples when the upstream server supports STARTTLS, and repair the Spring profile.

## Acceptance criteria

- [ ] Proxy examples never combine with SMTPS.
- [ ] The restriction appears in capabilities, configuration, and proxy API guidance.
- [ ] Spring sample properties form a valid configuration.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1536-1564`
- Spring example: `simplejavamail.org/src/pages/configuration.hbs:690-698`
- Guard: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:311-313`
