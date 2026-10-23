# A workflow waits for mail feedback

Let a business process react to later mail feedback without putting the workflow inside the mail library.

Temporal executes a guarded mail activity, then waits for a signal containing an application-owned action. The receiving endpoint converts a synthetic notification into Email, reads its report, correlates the envelope identifier and signals the workflow. The Camunda variation expresses the same handoff with BPMN jobs and a correlated message.

The workflow engine owns durable process history, waiting and business transitions. Activities own side effects; SJM interprets received mail and performs SMTP operations. No Email, private key or SJM implementation object is stored in workflow history.

## Start here

From the matching SJM checkout root, start the Temporal version with Docker's Linux containers. No host JDK, workflow installation or real mail account is needed.

```text
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml run --rm scenario
```

Open [the jobs](http://localhost:8787/jobs). The scenario also waits for a `WORKFLOW_DECISION` application event: that proves feedback reached the workflow, rather than merely being parsed.

## Follow one job

Start at [`TemporalMailWorkflow.FeedbackWorkflowImpl.run`](src/main/java/org/simplejavamail/examples/patterns/TemporalMailWorkflow.java). Its story is small: submit the guarded activity, hold an unsafe result, otherwise wait for one report and record a decision. The activity uses [`MailWork.deliver`](../common/src/main/java/org/simplejavamail/examples/patterns/MailWork.java). Then read [`PatternEndpoints.report`](../common/src/main/java/org/simplejavamail/examples/patterns/PatternEndpoints.java) to follow received EML through SJM's typed report into the workflow signal. [`mail-feedback.bpmn`](src/main/resources/mail-feedback.bpmn) expresses the alternative Camunda route.

## Try the waiting path

Run `docker compose -f tools/integration-patterns/mail-workflow/compose.yaml run --rm scenario delayed`. The fixture injects a delayed report; the workflow records its first correlated report and finishes. It does **not** keep running to model every later delivery update. A real process must choose its own waiting period and terminal business condition.

To try Camunda, stop Temporal first and use both files:

```text
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml down --volumes --remove-orphans
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml -f tools/integration-patterns/mail-workflow/camunda.yaml up --build -d --wait
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml -f tools/integration-patterns/mail-workflow/camunda.yaml run --rm scenario
```

The scenario explicitly injects a synthetic delivery notification. Waiting for it is not a new SMTP connection or proof of actual delivery. Missing correlation is recorded for review, not guessed. A production workflow must decide trust, duplicate/out-of-order handling and how long to wait.

Recording a report and notifying the workflow are separate operations. A failed notification is visible for reconciliation; repeating a recorded report is not a durable notification queue. Production handoff needs its own outbox or equivalent arrangement. The application startup recovery also assumes one instance, as explained in [the shared notes](../README.md).

Keep the variation file on cleanup:

```text
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml -f tools/integration-patterns/mail-workflow/camunda.yaml down --volumes --remove-orphans
```
