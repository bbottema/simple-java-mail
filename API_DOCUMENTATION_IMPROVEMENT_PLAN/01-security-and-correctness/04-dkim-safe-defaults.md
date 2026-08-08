# Replace unsafe DKIM examples

- Status: Done
- Priority: High
- Work: Configuration validation, tests, documentation

## Problem

Primary DKIM examples enable the body-length parameter and exclude `From` and `Subject`. The API warns that `l=` permits unsigned appended content; excluding `From` is incompatible with a valid DKIM signature and excluding `Subject` weakens integrity.

## Plan

1. Use safe defaults in the first complete example.
2. Remove `From` and `Subject` from header-exclusion examples.
3. Put `useLengthParam(true)` in an advanced warning block, if retained at all.
4. Reject case-insensitive attempts to exclude the mandatory `From` header.
5. Explain what header exclusions are for and which headers should remain signed.

## Acceptance criteria

- [x] The normal example keeps `useLengthParam(false)`.
- [x] `From` is always signed in examples and rejected as an exclusion, case-insensitively.
- [x] Security consequences precede the advanced `useLengthParam(true)` opt-in example.
- [x] Property and programmatic examples agree on safe defaults and relay-specific exclusions.
- [x] The Java builder, convenience APIs, and property-driven defaults all enforce the configuration boundary.

## Evidence

- Issue: [#679](https://github.com/bbottema/simple-java-mail/issues/679), with historical context from [#344](https://github.com/bbottema/simple-java-mail/issues/344) and [#499](https://github.com/bbottema/simple-java-mail/issues/499).
- Configuration guard: `modules/core-module/src/main/java/org/simplejavamail/api/email/config/DkimConfig.java`
- API and property coverage: `modules/simple-java-mail/src/test/java/org/simplejavamail/api/email/config/DkimConfigTest.java` and `modules/simple-java-mail/src/test/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl2Test.java`
- Documentation and migration material: `simplejavamail.org/src/pages/security.hbs`, `simplejavamail.org/src/pages/configuration.hbs`, and `simplejavamail.org/src/pages/migration-notes-9.2.0.hbs`
