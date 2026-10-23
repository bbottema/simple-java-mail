# Business events drive communications

Consume business events without treating broker redelivery as permission to send the same email again.

The example publishes an application job ID to Kafka. A consumer loads the intent, claims its separately committed submission guard and sends through SJM. A RabbitMQ variation changes the broker, not the meaning of the SMTP receipt.

The broker owns event transport, acknowledgement and redelivery. PostgreSQL holds the application's submission evidence. SJM does not participate in a broker transaction or provide exactly-once delivery.

## Start here

Run these commands from the matching SJM checkout root with Docker's Linux containers. Kafka, PostgreSQL, Java and the synthetic SMTP server all run inside the stack.

```text
docker compose -f tools/integration-patterns/event-communications/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/event-communications/compose.yaml run --rm scenario
```

Inspect [the job records](http://localhost:8787/jobs) beside [the captured SMTP submissions](http://localhost:8788/stats). A broker can redeliver an event; the repeated event must not produce another SMTP capture.

## Follow one job

Begin with [`PatternEndpoints.submit`](../common/src/main/java/org/simplejavamail/examples/patterns/PatternEndpoints.java), which stores the intent and publishes only its job ID. In [`CommunicationEvents`](src/main/java/org/simplejavamail/examples/patterns/CommunicationEvents.java), follow the Kafka `consume` method into [`MailWork.deliver`](../common/src/main/java/org/simplejavamail/examples/patterns/MailWork.java). The database claim there is the important boundary: broker acknowledgement cannot roll SMTP back. Read [`JobStore`](../common/src/main/java/org/simplejavamail/examples/patterns/JobStore.java) last for the SQL mechanics.

## Try another broker

Stop Kafka before trying RabbitMQ. Keep both files on every command for the variation:

```text
docker compose -f tools/integration-patterns/event-communications/compose.yaml down --volumes --remove-orphans
docker compose -f tools/integration-patterns/event-communications/compose.yaml -f tools/integration-patterns/event-communications/rabbitmq.yaml up --build -d --wait
docker compose -f tools/integration-patterns/event-communications/compose.yaml -f tools/integration-patterns/event-communications/rabbitmq.yaml run --rm scenario
```

The supporting broker changes; the expected `SUBMITTED`, `PARTIALLY_ACCEPTED` and `UNKNOWN` evidence does not. For a focused lost-reply experiment, append `unknown` to the scenario command.

The small HTTP producer is not a transactional event-publication guarantee. A production business producer needs its normal outbox or equivalent arrangement. The consumer still needs the SMTP guard even when Kafka transactions or RabbitMQ acknowledgements are enabled.

This example runs one application instance. Its startup recovery is not a multi-instance ownership protocol. See [the shared workbench notes](../README.md) for that boundary, resource requirements and licensing.

When finished with the variation, remove only its synthetic data:

```text
docker compose -f tools/integration-patterns/event-communications/compose.yaml -f tools/integration-patterns/event-communications/rabbitmq.yaml down --volumes --remove-orphans
```
