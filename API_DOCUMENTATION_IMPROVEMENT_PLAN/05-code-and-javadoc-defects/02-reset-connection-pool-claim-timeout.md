# Fix resetConnectionPoolClaimTimeoutMillis

- Status: Planned
- Priority: High
- Work: Code, unit tests, documentation

## Defect

`resetConnectionPoolClaimTimeoutMillis()` calls `withConnectionPoolExpireAfterMillis(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS)`. It changes expiry instead of the claim timeout.

## Plan

Call `withConnectionPoolClaimTimeoutMillis(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS)` and add a regression test that keeps expiry independent. While here, replace “forever” in the website: `Integer.MAX_VALUE` milliseconds is about 24.9 days.

## Acceptance criteria

- [ ] Reset changes the claim timeout only.
- [ ] Expire-after remains unchanged.
- [ ] The regression test fails on the old implementation.
- [ ] Documentation states the actual finite default.

## Evidence

- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:724-731`
- Defect: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:841-844`
- Website wording: `simplejavamail.org/src/pages/configuration.hbs:839`
