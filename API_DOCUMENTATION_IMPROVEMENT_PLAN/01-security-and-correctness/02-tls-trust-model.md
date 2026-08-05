# Document the TLS trust model accurately

- Status: Planned
- Priority: High
- Work: Documentation, tests if defaults change

## Problem

The SMTPS and SMTP_TLS sections promise strict system-trust-store and hostname validation. The current default sets `mail.*.ssl.trust=*`, while hostname verification is enabled separately. The page later says all hosts are trusted, contradicting its earlier claim.

## Plan

1. Explain issuer trust and hostname verification as two independent controls.
2. State the current defaults without calling them strict PKIX validation.
3. Show the strict configuration: `trustingAllHosts(false)` plus `verifyingServerIdentity(true)`.
4. Explain that `trustingSSLHosts(...)` accepts the named hosts outside normal CA trust.
5. Decide separately whether the library default should change in a future major release.

## Acceptance criteria

- [ ] Security, configuration, and Javadocs describe the same defaults.
- [ ] The recommended production example enables both issuer and hostname verification.
- [ ] No text claims that either control alone is sufficient against MITM.
- [ ] Tests anchor `ssl.trust` and `checkserveridentity` defaults.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:159-189,249-291`
- Defaults: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:39-45`
- Property application: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:259-284`
