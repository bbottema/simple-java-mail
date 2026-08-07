# Add Mailer lifecycle and ownership guidance

- Status: Done
- Priority: High
- Work: Documentation, lifecycle tests

## Gap

The website warns only that batch connection pools keep the JVM alive. A non-batch asynchronous Mailer also owns a single-thread executor by default. `Mailer` is `AutoCloseable`, Spring closes managed mailer beans, and caller-provided executors remain caller-owned.

## Plan

Add one lifecycle section covering synchronous mailers, non-batch async executors, batch pools, Spring destruction, caller-owned executors, and waiting for futures before close. Link to it from every async and batch introduction.

## Resolution

Added one lifecycle and resource-ownership section to Configuration, with an ownership table and safe synchronous and asynchronous try-with-resources examples. The separate async, batch and module introductions now link to that section instead of each carrying their own shutdown instructions.

The public Javadoc now presents `close()` as the normal lifecycle operation and explains how the historically named `shutdownConnectionPool()` also shuts down a Mailer-owned executor without batch-module. The `withExecutorService(...)` Javadoc now reflects that custom executors work with and without batch-module and remain caller-owned.

Lifecycle tests verify that the default non-batch executor uses a non-daemon worker and is shut down with the Mailer, while a caller-provided executor is left running.

## Acceptance criteria

- [x] Users can identify who owns and closes every executor/pool.
- [x] `close()` and `shutdownConnectionPool()` are explained together.
- [x] The async try-with-resources caveat is explicit.
- [x] Non-batch JVM-liveness behavior is documented.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:750-842`
- Cross-links: `simplejavamail.org/src/pages/features.hbs:462-464,497-499`; `simplejavamail.org/src/pages/modules.hbs:64-70`
- API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java:218-250`
- Custom executor API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:400-425`
- Default executor: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:786-790`
- Close behavior: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:552-558`
- Lifecycle tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/MailerImplTest.java:61-95`
