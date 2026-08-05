# Fix resetConnectionPoolMaxSize

- Status: Planned
- Priority: High
- Work: Code, unit tests, Javadocs

## Defect

`resetConnectionPoolMaxSize()` calls `withConnectionPoolCoreSize(DEFAULT_CONNECTIONPOOL_MAX_SIZE)`. It changes the wrong property and leaves max size untouched.

## Plan

Change it to `withConnectionPoolMaxSize(DEFAULT_CONNECTIONPOOL_MAX_SIZE)` and add a regression test that first changes both core and max values.

## Acceptance criteria

- [ ] Reset changes max size to its default.
- [ ] Core size remains unchanged.
- [ ] A regression test fails on the old implementation.
- [ ] Generated Javadocs and CLI help retain the correct contract.

## Evidence

- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:713-722`
- Defect: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:833-836`
