# Add Mailer lifecycle and ownership guidance

- Status: Planned
- Priority: High
- Work: Documentation, lifecycle tests

## Gap

The website warns only that batch connection pools keep the JVM alive. A non-batch asynchronous Mailer also owns a single-thread executor by default. `Mailer` is `AutoCloseable`, Spring closes managed mailer beans, and caller-provided executors remain caller-owned.

## Plan

Add one lifecycle section covering synchronous mailers, non-batch async executors, batch pools, Spring destruction, caller-owned executors, and waiting for futures before close. Link to it from every async and batch introduction.

## Acceptance criteria

- [ ] Users can identify who owns and closes every executor/pool.
- [ ] `close()` and `shutdownConnectionPool()` are explained together.
- [ ] The async try-with-resources caveat is explicit.
- [ ] Non-batch JVM-liveness behavior is documented.

## Evidence

- API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java:32-36,213-223`
- Default executor: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:786-790`
- Close behavior: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:561-570`
