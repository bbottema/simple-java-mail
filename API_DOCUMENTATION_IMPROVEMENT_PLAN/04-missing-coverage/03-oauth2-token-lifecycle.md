# Support refresh-aware OAuth2 access-token providers

- Status: In progress
- Priority: Medium
- Work: Code and documentation

## Gap

OAuth2 examples show one access token in the password position. That works for a short-lived mailer, but a reusable mailer or connection pool has no way to obtain a current token after the original one expires.

## Plan

Add a plain-Java, thread-safe access-token provider to the generic Mailer builder. Resolve it only when a physical SMTP connection is opened or reconnected, including pooled connections. Keep the existing fixed-token form, add optional Spring bean discovery without a Spring Security dependency, and document that the provider owns acquisition, caching and refresh.

## Acceptance criteria

- [ ] A regular or custom-session Mailer can use an `OAuth2AccessTokenProvider`.
- [ ] Direct, open-connection, simple-batch and pooled paths resolve the provider at the physical connection boundary.
- [ ] Fixed tokens remain supported; mixed fixed/provider configuration fails clearly.
- [ ] Provider failures and blank results fail without exposing token material.
- [ ] Spring can auto-detect one provider bean without depending on Spring Security.
- [ ] Documentation distinguishes access tokens from provider-owned acquisition and refresh.
- [ ] The CLI and property surface do not pretend a runtime provider is string-configurable.

## Evidence

- Simple Java Mail issue: https://github.com/bbottema/simple-java-mail/issues/692
- SMTP connection pool issue: https://github.com/simple-java-mail/smtp-connection-pool/issues/9
- Historical fixed-token support: https://github.com/bbottema/simple-java-mail/issues/421
- Documentation: `simplejavamail.org/src/pages/security.hbs:212-221`
- Session setup: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:180-191`
