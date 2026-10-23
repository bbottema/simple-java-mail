# A committed order needs an email

Save the business intent first, then let a durable job send it without turning redelivery into duplicate mail.

The application commits its mail intent to PostgreSQL. Its outbox poller enqueues a stable JobRunr job. The worker commits a submission guard, invokes SJM and stores the returned receipt facts.

JobRunr owns background execution and its persistent job history. The application owns the outbox and the decision to hold uncertain mail. SJM owns one SMTP submission, not the transaction spanning those systems.

## Start here

Use Docker with Linux containers and Compose from the matching SJM checkout root. You do not need a host JDK, Maven or a mail account.

```text
docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml run --rm scenario
```

Open [the job records](http://localhost:8787/jobs) and [the SMTP capture count](http://localhost:8788/stats). The scenario prints its own checks, so start there before reading the framework setup.

## Follow one job

Start at [`PatternEndpoints.submit`](../common/src/main/java/org/simplejavamail/examples/patterns/PatternEndpoints.java). It saves a job before asking a worker to do anything. Next read [`OutboxJobs.dispatchCommittedIntents`](src/main/java/org/simplejavamail/examples/patterns/OutboxJobs.java): its poller gives JobRunr a stable job identity. Follow that into [`MailWork.deliver`](../common/src/main/java/org/simplejavamail/examples/patterns/MailWork.java), where the separately committed guard prevents another invocation from becoming another send. Finally, [`JobStore`](../common/src/main/java/org/simplejavamail/examples/patterns/JobStore.java) shows how submission and feedback evidence stay in PostgreSQL.

## Try a failure

Run `docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml run --rm scenario unknown` to lose the final SMTP reply deliberately. The job becomes `HELD` with `UNKNOWN` acceptance, while the fixture has captured one message. Repeating the same business input must not create a second capture.

A crash after SMTP acceptance but before the SQL result is recorded leaves `SENDING`. Restart holds that attempt for reconciliation instead of turning it back into ready work. This deliberately avoids duplicates at the cost of manual recovery for some possibly unsent mail.

This startup recovery assumes one application instance. Do not run several copies of these original framework examples against one database: one instance must not classify another instance's active `SENDING` rows as abandoned. The Polar Meridian example demonstrates a separate two-instance ownership design.

See [the shared workbench notes](../README.md) for privacy, licensing and resources. To remove only this example's synthetic data:

```text
docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml down --volumes --remove-orphans
```
