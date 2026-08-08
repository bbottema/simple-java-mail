# Document the TLS trust model accurately

- Status: Done
- Priority: High
- Work: Implementation, documentation, tests

## Problem

The SMTPS and SMTP_TLS sections promised strict trust-store and hostname validation, but the default set `mail.*.ssl.trust=*`. Hostname verification was enabled separately, and the security page contradicted its earlier description by later saying that all hosts were trusted.

## Plan

1. Explain issuer trust and hostname verification as two independent controls.
2. Change the default to JVM trust-store validation and document the compatibility impact.
3. Show the secure defaults: `trustingAllHosts(false)` plus `verifyingServerIdentity(true)`.
4. Explain that `trustingSSLHosts(...)` accepts the named hosts outside normal CA trust.
5. Add prominent v9.2.0 migration and release guidance for private and self-signed PKI.

## Acceptance criteria

- [x] Security, configuration, and Javadocs describe the same defaults.
- [x] The recommended production example enables both issuer and hostname verification.
- [x] No text claims that either control alone is sufficient against MITM.
- [x] Tests anchor `ssl.trust` and `checkserveridentity` defaults.

## Evidence

- Issue: [#677](https://github.com/bbottema/simple-java-mail/issues/677)
- Documentation: `simplejavamail.org/src/pages/security.hbs`, `simplejavamail.org/src/pages/configuration.hbs`, and `simplejavamail.org/src/pages/migration-notes-9.2.0.hbs`
- Defaults: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java`
- Tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerTest.java`
