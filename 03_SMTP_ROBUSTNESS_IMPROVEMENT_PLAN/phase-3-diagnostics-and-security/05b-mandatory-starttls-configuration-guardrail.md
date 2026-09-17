# Step 5 implementation: reject contradictory mandatory STARTTLS settings

- Status: Implemented, verified and accepted on 15 September 2026; unreleased 10.0.0 work
- Default decision: Keep opportunistic `SMTP`, with or without credentials
- Parent step: [Authentication and TLS](05-make-authenticated-plaintext-fallback-explicit.md)
- Evidence: [Wire-level characterization](05a-authentication-tls-characterization.md)
- Tracking: [#735](https://github.com/bbottema/simple-java-mail/issues/735), enhancement/security child of [#722](https://github.com/bbottema/simple-java-mail/issues/722), milestone 10.0.0

## Why this check exists

Before this change, the characterization proved that this configuration built successfully:

```java
mail.mailerBuilder()
    .withSMTPServer("smtp.example.com", 587, "username", "password")
    .withTransportStrategy(TransportStrategy.SMTP_TLS)
    .withProperty("mail.smtp.starttls.required", "false")
    .buildMailer();
```

The selected strategy still says `SMTP_TLS`, but Angus can authenticate without encryption when STARTTLS is absent. The same override weakens `SMTP_OAUTH2`. Property files and Spring can supply it through `simplejavamail.extraproperties.*`; this is not limited to Java calls.

The implementation rejects that contradiction during `buildMailer()`. It does not make the default stricter. Applications that want opportunistic username/password authentication continue using `SMTP` without another opt-in.

Rejecting the conflict gives the caller an actionable configuration error. Overwriting it instead would hide a mistake in application configuration and leave the loaded-property diagnostics showing a value that was not used.

## Public behavior

- Keep all four strategies, their port defaults and the existing opportunistic-TLS setting unchanged.
- Do not add a builder option, property, enum, exception type, or probe-report getter.
- Reject an extra property that disables `mail.smtp.starttls.required` for `SMTP_TLS` or `SMTP_OAUTH2`, even when no password is supplied. This is consistency with the chosen strategy, not a credential-presence rule.
- Accept a redundant `required=true` setting: Boolean true or an untrimmed case-insensitive `"true"` string, matching Angus `PropUtil` with the SMTP transport's false default. Other values are rejected without rendering them. Inherited `Properties` defaults are not copied and are not checked.
- Leave `SMTP` overrides alone, including opting out of opportunistic TLS or explicitly requiring STARTTLS through raw properties.
- Do not reject `starttls.enable=false` merely because its name looks contradictory: the characterization proves `starttls.required=true` still requires an upgrade.
- Leave `SMTPS` alone. The characterized `mail.smtps.ssl.enable=false` override does not turn its built-in implicit-TLS transport into plaintext.
- Keep certificate trust exceptions, identity-verification settings, and their existing precedence unchanged. Requiring TLS is not the same as requiring the default trust configuration.

`MailerException` text for `SMTP_TLS`:

```text
SMTP_TLS requires STARTTLS, but mail.smtp.starttls.required disables it.
Remove that override to require TLS, or choose TransportStrategy.SMTP if
you want opportunistic TLS.
```

For `SMTP_OAUTH2`, use a separate final sentence: `Remove that override so the access token is sent only after TLS succeeds.` Do not suggest switching OAuth2 tokens to the ordinary password strategy.

Only the known strategy and property name belong in the error. Do not render arbitrary property values, usernames, passwords, tokens, the Session or its full property map.

## Placement and ownership

Keep this as a small internal validation method in [MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java), not a new policy framework.

1. Resolve the regular builder and create the initial strategy-configured Session as today.
2. Validate the selected strategy against the effective additional properties before initializing MailSendOperations, configuring the proxy, installing transport lifecycle support or registering a cluster.
3. Continue the existing OAuth2 validation, Session initialization and resource setup unchanged.

Inspect the properties that will actually be copied by `initSession`; do not resolve configuration sources a second time or mutate the supplied property objects. Account for `Properties` defaults consistently with the existing `putAll` behavior. Configuration-source provenance remains owned by `ConfigLoader`, not this check.

The guard applies to regular-builder, SJM-owned Sessions using the ordinary transport path. Logging-only builds use the same configuration check, so a configuration does not become invalid only when logging-only mode is turned off.

Do not introduce this new restriction for a caller-supplied Session, even if it carries an older SJM strategy marker. `MailerFromSessionBuilder` does not select a transport strategy; its existing connection-configuration ownership contract stays intact. Do not change the other existing Session initialization behavior as part of this work.

Skip the guard when `CustomMailer` owns the transport. A configured strategy cannot prove that a custom transport established TLS. No probe preflight or runtime capability check is added to sends, connection tests, batches or pool leasing.

## Deliberate limits

This is a construction-time guard for the reproduced mandatory-STARTTLS override, not a security sandbox for arbitrary Jakarta Mail configuration:

- Application code can still mutate `mailer.getSession().getProperties()` after construction. Do not add checks to every operation or make Session immutable in this step.
- A substituted provider or custom socket factory still owns its implementation. Do not claim provider-independent wire guarantees from a property check.
- Raw transport-protocol/provider replacement, socket-factory retry behavior, and stale post-TLS capabilities need separate characterization and decisions. Do not silently fold those restrictions into this check.
- The established no-plaintext-retry behavior after failed STARTTLS negotiation or certificate validation remains unchanged.

Public documentation must name these boundaries. In particular, avoid claiming that no raw property or custom implementation can ever weaken a selected strategy.

## Verification

Replace the regular-builder characterization cases that currently demonstrate the override with assertions for early rejection; preserve the original observations in the dated investigation note. Cover:

1. Java single-property and whole-Properties inputs; string and Boolean values; redundant true; mixed case; malformed input; and property-default behavior.
2. Both mandatory STARTTLS strategies, fixed and supplied OAuth2 credentials, absent password, and logging-only configuration.
3. Named configuration sources, source precedence, Spring Environment and Boot starter paths using the existing extra-properties namespace.
4. CLI argument handling and exit behavior: configuration failure before SMTP activity, with the useful cause visible and no credentials echoed.
5. Zero transport connection, proxy start, token-supplier invocation and cluster registration on rejection. No send attempt has begun, so no observer callback is due.
6. An unchanged caller-owned Session, with and without an SJM marker, and unchanged CustomMailer behavior.
7. The accepted opportunistic default; successful required TLS; no-STARTTLS rejection; refused upgrade; trust and hostname failures; explicit trust exceptions; pooled reuse/reconnect; sync/async and non-pooling paths.
8. Public signatures unchanged, classpath/JPMS compatibility, and the normal non-live Java 11/modern-JDK verification and Spring compatibility matrix.

Use loopback peers and fake credentials only. Do not run live-email demos, SpotBugs or benchmarks. Finish with `mvn license:remove` and a coding-guide audit of every touched Java class.

## Documentation and delivery

The initial documentation-only checkpoint below records the unchanged behavior before implementation. The current pass also documents the construction-time rejection.

Implementation checklist:

- Put the complete construction-failure contract on the regular builder API, with implementation references. Keep Jakarta Mail key knowledge localized; do not expand the user-facing API just to expose a validation helper.
- Update the website's raw-property warning to explain the actual rejection and how to resolve it.
- Add a 10.0.0 migration note for formerly accepted contradictory configurations. Explain that the default is unchanged and show removing the override versus choosing `SMTP` for opportunistic password authentication.
- Add a concise enhancement entry to both release-note files and describe the construction boundary in [ADR 0021](../../docs/adr/0021-mandatory-starttls-configuration-consistency.md).
- Regenerate CLI metadata when builder Javadocs change. No new Spring property or Email governance field is needed.
- Recheck the concurrency catalogue and approved infographic. This adds a construction-time validation step, not a new send owner, lock, state machine or layer.
- Run website checks, a clean build and internal-link verification, preserving all unrelated Journal edits.

Work on the existing root and website branches. Leave implementation and documentation uncommitted for review; do not close the issue or declare Phase 3 complete before acceptance.

## Documentation checkpoint: 15 September 2026

- Corrected `TransportStrategy.SMTP` Javadoc; no Java signatures or executable statements changed. Reviewed that documentation change against the root coding guide.
- Updated the Security and Configuration pages with the default/mandatory distinction, password-authentication examples, a strategy comparison, and the current raw-property limitation. Removed the obsolete TLS-version quotation. No Journal source was edited.
- Java 11 core-module package and Javadoc generation passed. Tests were deliberately skipped for this documentation-only pass; the earlier 121-case characterization results remain recorded separately, not claimed as rerun here.
- Website `npm run check` passed, including all 24 helper tests; the clean `npm run build` passed.
- All 238 local links originating in the two edited website pages passed. The unfiltered site scan reported 12 broken links, all originating in unrelated Journal pages; those remain untouched.
- All 37 local Markdown links in the five touched planning/catalogue documents resolved, including heading anchors.
- Scoped `mvn license:remove` passed, no generated header remains on the touched Java class, and the root/edited-website diffs passed whitespace checks.
- No runtime guard, commit, push, GitHub issue mutation or Phase 3 completion is included in this checkpoint.

## Implementation checkpoint: 15 September 2026

- Added one private `MailerImpl` validation method at the construction boundary described above. No public signature, default, property schema, Spring runtime mapping, send state or resource owner changed.
- Kept the complete contract on `MailerRegularBuilder.buildMailer()`, with an implementation reference. Corrected the strategy selector's stale Javadoc link and documented all four strategies. CLI metadata was regenerated through `publish-cli`.
- Updated the four regular-builder override characterization cases to expect early rejection; the original wire observations remain in the dated investigation. Two existing OAuth2 probe tests now use real loopback STARTTLS instead of weakening their test Mailer configuration.
- Added construction, source-precedence/snapshot-isolation, plain-Spring, Boot and CLI regressions. Rejection is tested before proxy/pool/lifecycle setup, token resolution and observer notification; arbitrary configured values are never rendered. Both supported true forms, malformed values, inherited `Properties` defaults, no-host logging-only builds and ownership exemptions are covered.
- Red/green check: the initial single-property regression failed on the original implementation because it did not throw. The focused Java 11 run then passed all 180 tests, and the focused CLI run passed all 18 tests.
- Java 11 non-live library `clean verify` passed: 968 tests, zero failures/errors/skips, plus packaged classpath/JPMS consumers and Javadocs. Log: `tmp/phase3-starttls-verify-java11.log`.
- Java 21 complete non-live `clean verify -Ppublish-cli` passed: 1,140 tests, zero failures/errors/skips, plus the provider-neutral consumers, CLI process/daemon checks, metadata generation and Javadocs. Log: `tmp/phase3-starttls-verify-java21.log`.
- All three existing Spring matrix combinations passed, with the new TLS construction tests added to the selection: Boot 2.7.18 / Spring 5.3.39 / Java 11; Boot 3.0.13 / Spring 6.0.14 / Java 17; Boot 3.5.16 / Spring 6.2.19 / Java 21. Each run passed 89 tests. Logs: `tmp/phase3-starttls-spring-<boot-version>.log`.
- Website checks passed, including 24 helper tests, and the clean build passed. All 365 local links originating in Security, Configuration and the 10.0.0 migration page resolved. The latest full scan found three unrelated Journal-originated links; Journal work is changing in a separate session and was not edited here. All 91 local Markdown links in the seven edited planning/mechanism/migration documents resolved.
- Coding-guide audit covered the three production classes and touched test classes: a named constructor step with local parsing/validation, no speculative abstraction, explicit resource boundaries, interface-owned documentation and bounded loopback fixtures. No further production refactor was needed.
- `mvn license:remove` passed. No generated header remains in the changed Java files or diff. Four pre-existing headers in untouched legacy tests are already present in HEAD and were left alone. Root and scoped website whitespace checks passed.
- The concurrency overview is unchanged: master and website PNGs still share SHA-256 `6678f77dd10760e193af9c88ebc17c31ea3483c7d9e8c8da58eed9ab0621f7c9`. Release entries keep #735 an enhancement and reflect the maintainer's existing major-feature classification for #733.
- #735 remains open and In Progress. Root and website changes remain uncommitted for review, with unrelated staged research, delivery artifacts and Journal edits preserved. No push or Phase 3 completion is included.

## Acceptance checkpoint: 15 September 2026

- The maintainer accepted the implementation and both rejection messages, then authorized semantic commits, pushing both development branches and closing #735.
- Step 5 and Phase 3 are complete. The accepted scope keeps opportunistic SMTP as the default and rejects the reproduced mandatory-STARTTLS configuration conflict; the separate findings in the characterization remain separate work.
- The shared send overview remains unchanged after the phase-completion review: neither the dedicated probe nor this construction-time check changes send ownership, synchronization, pool leasing or outcome reporting.
- Delivery includes only the #735 runtime, regression coverage, generated CLI metadata, plans, release/migration guidance and three website pages. Unrelated staged research, delivery artifacts and Journal edits remain outside these commits.
