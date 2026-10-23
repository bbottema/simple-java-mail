# Resume an interrupted nightly run

Restart the untouched remainder while preserving evidence of emails already accepted or left uncertain.

Spring Batch owns job and step metadata. The application separately commits each submission guard and its resulting receipt facts. A restarted reader selects only untouched ready jobs, rather than assuming SMTP rolled back with the chunk.

The batch framework owns restart and chunk processing. The application's independently committed evidence protects the SMTP boundary. A completed batch means work was processed, not that every recipient received mail.

## Start here

From the matching SJM checkout root, use Docker's Linux containers. The second command runs the interruption experiment, not just an ordinary successful send.

```text
docker compose -f tools/integration-patterns/restartable-batch/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/restartable-batch/compose.yaml run --rm scenario restart
```

Expect two submissions before interruption and five after resumption, not seven. Compare [the stored jobs](http://localhost:8787/jobs) with [the five SMTP captures](http://localhost:8788/stats).

## Follow one job

Start with [`NightlyMailJob.NightlyEndpoint.run`](src/main/java/org/simplejavamail/examples/patterns/NightlyMailJob.java). Follow the ready-item reader into the `submitMail` writer, then into [`MailWork.deliver`](../common/src/main/java/org/simplejavamail/examples/patterns/MailWork.java). Finally read [`JobStore.claim`](../common/src/main/java/org/simplejavamail/examples/patterns/JobStore.java): its guard is committed independently of the batch chunk, because SMTP cannot join a database rollback.

## Try uncertain acceptance

Run `docker compose -f tools/integration-patterns/restartable-batch/compose.yaml run --rm scenario unknown`. The fixture receives the message but loses the final reply. Its `HELD`/`UNKNOWN` record must not turn into another send merely because a batch or process restarts.

Run the additional `restart` scenario: it deliberately fails after two committed submissions, restarts the same job and checks that exactly five messages, not seven, were submitted. Ambiguous `SENDING` attempts are held after process restart, not silently resent.

The original framework stack runs one application instance; its startup recovery does not coordinate multiple owners of `SENDING` rows. See [the shared notes](../README.md) before adapting the example. To clear only this local experiment:

```text
docker compose -f tools/integration-patterns/restartable-batch/compose.yaml down --volumes --remove-orphans
```
