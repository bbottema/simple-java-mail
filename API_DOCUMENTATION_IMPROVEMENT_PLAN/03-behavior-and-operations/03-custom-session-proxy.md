# Explain proxying with a custom Session

- Status: Planned
- Priority: High
- Work: Documentation, integration test

## Problem

`usingSession(session).withProxy(...)` is presented as self-contained. With a normal caller-created Session, Simple Java Mail may not know the transport strategy and expects protocol-specific SOCKS properties to be configured already.

## Plan

Document the required `mail.smtp(s).socks.host` and `.port` setup. For authenticated proxying, explain how the Session points to the local bridge while `withProxy` supplies upstream credentials.

## Acceptance criteria

- [ ] Anonymous and authenticated custom-Session examples work.
- [ ] Required protocol properties are explicit.
- [ ] Examples state which transport strategies are supported.
- [ ] An integration test exercises the documented arrangement.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:153-164`
- Strategy marker: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/TransportStrategy.java:912-917`
- Proxy configuration: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:318-329`
