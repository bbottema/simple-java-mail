# Turn incoming mail into application data

Use typed mail facts as input to an ordinary integration flow, without introducing an SJM event bus.

The receiving endpoint converts supplied EML into Email and reads its DeliveryStatusReport. After application-owned correlation, it projects a job ID and action into a Spring Integration flow, which invokes the example business HTTP endpoint.

The application owns the endpoint, trust policy, correlation and business event. Spring Integration owns the HTTP flow. SJM supplies interpreted facts through Email; it does not own a dispatcher, webhook subscription or persistent workflow.

## Start here

From the matching SJM checkout root, start Docker's Linux containers. The scenario supplies the synthetic received message for you; no inbox, host JDK or real mail account is needed.

```text
docker compose -f tools/integration-patterns/incoming-feedback/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/incoming-feedback/compose.yaml run --rm scenario
```

Open [the job evidence](http://localhost:8787/jobs). The scenario also checks an `HTTP_BUSINESS_FEEDBACK` application event, showing that a correlated report reached the receiving business endpoint.

## Follow one job

Start at [`PatternEndpoints.report`](../common/src/main/java/org/simplejavamail/examples/patterns/PatternEndpoints.java), not the sending path. It converts EML to Email, reads the typed delivery report and asks [`JobStore.recordFeedback`](../common/src/main/java/org/simplejavamail/examples/patterns/JobStore.java) to correlate and retain the facts. Follow the small job/action projection into [`IncomingFeedback`](src/main/java/org/simplejavamail/examples/patterns/IncomingFeedback.java): its direct channel calls the local business HTTP endpoint. That is the integration boundary; downstream code does not need MIME knowledge.

## Try delayed and repeated feedback

Run `docker compose -f tools/integration-patterns/incoming-feedback/compose.yaml run --rm scenario delayed`. The job records `DELAYED`, and the application endpoint receives that action. The full scenario also repeats reports and sends an uncorrelated one: neither should become an invented customer update.

This example uses a synchronous direct channel and local HTTP service. Durable downstream handoff requires the application's own outbox or durable messaging arrangement. A recorded notification and a completed HTTP callback are not one atomic transaction.

If the HTTP handoff fails after recording the report, the application exposes the failure for reconciliation. Duplicate-report detection is not an automatic callback retry. This local stack also assumes one application instance for startup recovery. [The shared notes](../README.md) cover those limits and production responsibilities.

```text
docker compose -f tools/integration-patterns/incoming-feedback/compose.yaml down --volumes --remove-orphans
```
