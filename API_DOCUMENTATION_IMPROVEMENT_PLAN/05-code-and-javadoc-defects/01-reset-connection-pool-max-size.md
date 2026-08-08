# Fix resetConnectionPoolMaxSize

- Status: Done
- Priority: High
- Work: Code, unit tests, Javadocs

## Defect

`resetConnectionPoolMaxSize()` calls `withConnectionPoolCoreSize(DEFAULT_CONNECTIONPOOL_MAX_SIZE)`. It changes the wrong property and leaves max size untouched.

## Plan

Change it to `withConnectionPoolMaxSize(DEFAULT_CONNECTIONPOOL_MAX_SIZE)` and add a regression test that first changes both core and max values.

## Acceptance criteria

- [x] Reset changes max size to its default.
- [x] Core size remains unchanged.
- [x] A regression test fails on the old implementation.
- [x] Generated Javadocs and CLI help retain the correct contract.

## Evidence

- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:713-722`
- Defect: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:833-836`
- Fixed by [#696](https://github.com/bbottema/simple-java-mail/issues/696): `resetConnectionPoolMaxSize()` now delegates to `withConnectionPoolMaxSize(DEFAULT_CONNECTIONPOOL_MAX_SIZE)`.
- Regression coverage changes core and max independently, then verifies that only max returns to its default.
- Focused verification: `mvn -pl modules/simple-java-mail -Dtest=MailerBuilderTest test` passed on JDK 8.
- The public Javadoc contract was already correct and the existing API surface did not change, so generated Javadocs and CLI metadata require no update.
- Implementation commit: `c04642bb fix(mailer): reset connection pool max size`.
