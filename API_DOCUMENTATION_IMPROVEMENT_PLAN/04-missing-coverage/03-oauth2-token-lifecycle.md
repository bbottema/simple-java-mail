# Support refresh-aware OAuth2 access-token providers

- Status: Done
- Priority: Medium
- Work: Code and documentation

## Gap

OAuth2 examples show one access token in the password position. That works for a short-lived mailer, but a reusable mailer or connection pool has no way to obtain a current token after the original one expires.

## Plan

Add a plain-Java, thread-safe access-token provider to the generic Mailer builder. Resolve it only when a physical SMTP connection is opened or reconnected, including pooled connections. Keep the existing fixed-token form, add optional Spring bean discovery without a Spring Security dependency, and document that the provider owns acquisition, caching and refresh.

## Acceptance criteria

- [x] A regular or custom-session Mailer can use an `OAuth2AccessTokenProvider`.
- [x] Direct, open-connection, simple-batch and pooled paths resolve the provider at the physical connection boundary.
- [x] Fixed tokens remain supported; mixed fixed/provider configuration fails clearly.
- [x] Provider failures and blank results fail without exposing token material.
- [x] Spring can auto-detect one provider bean without depending on Spring Security.
- [x] Documentation distinguishes access tokens from provider-owned acquisition and refresh.
- [x] The CLI and property surface do not pretend a runtime provider is string-configurable.

## Evidence

- Simple Java Mail issue: https://github.com/bbottema/simple-java-mail/issues/692
- SMTP connection pool issue: https://github.com/simple-java-mail/smtp-connection-pool/issues/9
- SMTP connection pool release: https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/3.1.0
- Historical fixed-token support: https://github.com/bbottema/simple-java-mail/issues/421
- Public API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/OAuth2AccessTokenProvider.java` and `MailerGenericBuilder.java`
- Connection-time validation and resolution: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/OAuth2AccessTokenResolver.java` and `TransportConnectionHelper.java`
- Batch and pool bridge: `modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchSupport.java`, using `smtp-connection-pool` 3.1.0
- Spring bean integration: `modules/spring-module/src/main/java/org/simplejavamail/springsupport/SimpleJavaMailSpringSupport.java`
- Simple Java Mail commits: `7d732c1b` (implementation and tests), `b565bfb5` (pool dependency), and `7d67fe0c` (release documentation)
- Website documentation commit: `be2bd8c` on `codex/website-relaunch`
- Verification: full Java 8 `mvn clean verify -Ppublish-cli -DexcludeLiveServerTests=true`; focused provider, physical-connection, batch/pool, open-connection and Spring tests; website check/build and 1,314 internal links
