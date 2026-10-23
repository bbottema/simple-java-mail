# Strategic email integration patterns

Runnable application slices, not a new Simple Java Mail workflow engine. The existing
[workload field guide](https://www.simplejavamail.org/email-workload-field-guide.html) supplies Tactical Patterns;
these Strategic Patterns show how those choices fit jobs, events, workflows and inbound feedback.

## Start here: pick one problem

Pick one row, open its README and follow **Start here → Follow one job → Try a failure or variation**. Each README gives the exact Docker commands and the first source method to read. Run the example before exploring its framework configuration; its job records and scenario output give the code a concrete story.

| Directory | Problem | Application platform |
| --- | --- | --- |
| [transaction-outbox](transaction-outbox/README.md) | Committed work must survive application restart | Spring Boot, PostgreSQL, JobRunr |
| [event-communications](event-communications/README.md) | A business event drives customer communication | Kafka; RabbitMQ variation; PostgreSQL |
| [mail-workflow](mail-workflow/README.md) | A process waits for later mail feedback | Temporal; Camunda variation |
| [restartable-batch](restartable-batch/README.md) | An interrupted nightly run resumes its untouched remainder | Spring Batch, PostgreSQL |
| [protected-partners](protected-partners/README.md) | A protected document needs a verified, byte-preserving archive | Apache Camel, S/MIME, local archive |
| [incoming-feedback](incoming-feedback/README.md) | Received mail facts feed an ordinary HTTP integration | Spring Integration |
| [relaydesk](relaydesk/README.md) | Each customer needs an independently operated mail route | Unchanged article helpers, Java, PostgreSQL, authenticated SMTP |
| [polar-meridian](polar-meridian/README.md) | Two dispatchers coordinate priorities, limits and protected archives | Unchanged Dispatcher/JDBC helpers, PostgreSQL; optional OpenTelemetry |
| [custom-handoff](custom-handoff/README.md) | Prepare protected mail without an SMTP service | CustomMailer, HTTPS; RabbitMQ variation |
| [aws-correspondence](aws-correspondence/README.md) | Queued protected documents need submission evidence and later feedback | Local contract fixtures; optional Fargate/SES with Terraform or CloudFormation |

## Prerequisites and quickstart

Use Linux containers in Docker Engine/Desktop and Docker Compose **2.24.4 or newer** (the variants use `!override`).
No host JDK, Maven, Python, database or real mail account is needed for the local examples. Run commands from the matching Simple Java Mail checkout root.
The build compiles that checkout inside containers; it does not assume an unreleased API exists in Maven Central.

For example:

```text
docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml run --rm scenario
docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml logs app
docker compose -f tools/integration-patterns/transaction-outbox/compose.yaml down --volumes --remove-orphans
```

Open `http://localhost:8787/jobs` for application evidence and `http://localhost:8788/stats` for captured SMTP submissions.
Use `PATTERN_PORT` and `PATTERN_SMTP_PORT` environment variables to change the loopback-only ports. Run one example at a time with the defaults.
The scenario container is also the portable command-line client. The original six framework examples accept `happy`, `partial`, `unknown` or `delayed`
to run one case; the batch additionally accepts `scenario restart`. The four application extensions have their own scenario checks and endpoint contracts
in their READMEs. They use the separate `applications.Dockerfile`; the published SJM reactor gains no application/framework dependencies.

The first build downloads Maven dependencies and container images. Later examples reuse the SJM build/cache. Reserve several GB of disk and
at least 4 GB of Docker memory; Kafka/workflow builds and concurrent verification can need more. The verification record distinguishes tested environments
and behavior from unmeasured cold-build times and resource minima; an image manifest alone is not an ARM interoperability test.

### Technology variations

Use both files for startup, scenarios and cleanup:

```text
docker compose -f tools/integration-patterns/event-communications/compose.yaml -f tools/integration-patterns/event-communications/rabbitmq.yaml up --build -d --wait
docker compose -f tools/integration-patterns/event-communications/compose.yaml -f tools/integration-patterns/event-communications/rabbitmq.yaml run --rm scenario
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml -f tools/integration-patterns/mail-workflow/camunda.yaml up --build -d --wait
docker compose -f tools/integration-patterns/mail-workflow/compose.yaml -f tools/integration-patterns/mail-workflow/camunda.yaml run --rm scenario
```

Do not run a primary and its variation against the same project concurrently. Stop one before starting the other.

For any selected manifest, `docker compose -f <manifest> ps` reports service status and `logs app` shows the application. `restart app` keeps its data.
The documented `down --volumes --remove-orphans` command resets that project's synthetic data; start it again to obtain a fresh fixture. Keep variation
files on all of those commands. Development TLS certificates expire after 30 days; if an old cached image expires, reset its demo volumes and rebuild
the application with `docker compose -f <manifest> build --no-cache app` rather than disabling certificate checks.

Polar Meridian's `observability.yaml` adds an application-owned SDK and local Collector. Custom handoff's `rabbitmq.yaml` replaces HTTPS publication with
confirmed durable broker publication and independent consumption; rejected packages remain in a dead-letter queue. Neither variation changes SJM APIs.

### The optional AWS lane is different

The AWS chapter first runs locally with ElasticMQ, DynamoDB Local, S3Mock and synthetic SMTP/SES events. That verifies application contracts, not AWS
conformance. Its separate cloud runner requires Python with the pinned tooling requirements, an AWS profile and explicit account/region/identity inputs.
Terraform and CloudFormation create only an isolated named run, use SES mailbox simulator recipients, and incur real AWS charges. Read the AWS README
and dated verification record before provisioning; do not confuse `docker compose down` with cloud cleanup.

## What the evidence means

The local SMTP fixture has no forwarding implementation. It captures synthetic `.test` mail, rejects one recipient for `partial`, and accepts content
but deliberately loses the final response for `unknown`. The scenario asserts one SMTP content submission even when the business input is repeated.
It explicitly injects a synthetic DSN to demonstrate conversion/correlation; that is **not** a real downstream delivery or bounce test.

SJM supplies submission receipts, MIME/protection and `Email.getDeliveryStatusReport()`. The application owns its SQL evidence, trust checks,
idempotency, recovery and business decisions. The examples use deliberately trusted local input; they are not authenticated public receiving services.
HTTP DTOs contain application projections, not serialized Email/Mailer/SDK objects or keys.

The application commits `SENDING` before provider invocation, in a separate transaction even inside Spring Batch. If the process stops after SMTP
acceptance but before the next SQL commit, it holds that work for review after restart. A framework's redelivery cannot silently repeat the SMTP call.
This deliberately favors avoiding duplicates over automatic recovery of every possibly unsent message. There is no atomic SMTP/database transaction.
Production designs need an explicit reconciliation policy and trusted, duplicate/out-of-order-aware feedback history.

The original six framework examples each run one application instance. Their startup scan holds `SENDING` rows because it assumes the old process has stopped. It must not run beside another live instance against that database. Polar Meridian demonstrates a different design with explicit two-instance claim ownership; copying one startup scan does not provide that design.

Recording feedback and handing its projection to a workflow or HTTP service are also separate operations. A failed handoff is visible for reconciliation, not hidden behind a successful parse or automatically repaired by duplicate-report detection. The workflow example intentionally makes its decision from the first correlated report, including a delay; it is not an all-events delivery-state engine.

The outbox polling and framework boundaries are readable application code, not a general broker/store/handler SPI. The HTTP integration uses an
in-process direct channel; durable downstream handoff would require the application's own outbox or durable channel, not another SJM setting.

## Safety, data and licensing

- Images are pinned by digest, including multi-platform manifests where available. Runtime bridge networks are project-scoped;
  only the declared loopback HTTP ports are published. The fixture has no onward-delivery implementation.
- Only application/fixture HTTP ports bind to host loopback. Databases, brokers and SMTP are not exposed on the host.
- Passwords and the generated 30-day S/MIME identity are **synthetic demo credentials**, never production secrets. Do not deploy these manifests as a service.
- Named volumes hold each demo's database, captured messages and archive. `down` retains them; `down --volumes` deletes only this Compose project's data.
  Do not use global Docker prune commands. Stopping unrelated containers is unnecessary.
- Spring, Camel and Kafka are Apache-licensed; PostgreSQL, RabbitMQ, Temporal, JobRunr and their dependencies retain their respective licenses.
  Camunda's distribution is governed by its own development/production licensing: this is a local development example, not a promise of free production use.
  No paid subscription or cloud account is needed for these local scenarios. Consult the pinned distributions' notices before redistribution or deployment.

## Development and downloads

These standalone Maven projects are intentionally outside SJM's published reactor. They target Java 21 in their containers; the library keeps its Java 11 baseline.
Canonical source lives here. `package_examples.py` normalizes text line endings and ZIP metadata for a deterministic website download; `--check` rejects drift. During unreleased development,
extract the bundle into the matching SJM source checkout, not an unrelated released JAR. The source build keeps that requirement explicit.

Verification has two distinct lanes: SJM's normal library/API tests, and real local Compose scenarios for these applications. Compilation does not prove
broker, workflow, restart or SMTP behavior. The dated verification record records passes, failures and untested claims separately.
