# Prepare with SJM, hand off anywhere

Normal Email/Mailer calls use SJM preparation, validation and S/MIME, then CustomMailer hands the prepared package to HTTPS. There is no SMTP server in this
stack. The receiving application can process/archive a communication without sending mail. This is transport independence, not another outbox tutorial.

## Start here

From the matching SJM checkout root, run Docker's Linux containers. This starts HTTPS, not SMTP; no real account or host Java installation is involved.

```text
docker compose -f tools/integration-patterns/custom-handoff/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/custom-handoff/compose.yaml run --rm scenario
```

## Follow one job

Start with [`HandoffApplication.submit`](src/main/java/org/simplejavamail/examples/handoff/HandoffApplication.java), where an ordinary Email/Mailer call reaches `HandoffTransport.sendMessage`. Follow that callback into [`HandoffPackage`](src/main/java/org/simplejavamail/examples/handoff/HandoffPackage.java) and then [`HandoffReceiver`](src/main/java/org/simplejavamail/examples/handoff/HandoffReceiver.java). The package keeps MIME bytes and envelope recipients separate; the receiver verifies/decrypts before storage. Only then look at the HTTPS or RabbitMQ plumbing. That order makes clear which work SJM still does when SMTP is absent.

Inspect the application evidence at [localhost:8787/jobs](http://localhost:8787/jobs). The scenario also queries the receiver inside Docker, so no host HTTP client is needed.

## Try a lost confirmation

The HTTPS scenario deliberately stores a package and loses its confirmation. Expect a held application job even though the receiver retained the package. A successful callback reports `UNKNOWN` **SMTP** acceptance because no SMTP service was involved; it does not mean the HTTPS request failed.

For the independently consuming RabbitMQ variation, add `-f tools/integration-patterns/custom-handoff/rabbitmq.yaml` to all commands. Publication confirms
broker acceptance, not consumer processing. HTTPS verification uses the image's synthetic development CA, without changing host trust or disabling checks.
Invalid consumer packages are rejected into `prepared-mail.dead`, not discarded or endlessly redelivered. The broker scenario injects one and checks retention.

To switch from a finished HTTPS run:

```text
docker compose -f tools/integration-patterns/custom-handoff/compose.yaml down --volumes --remove-orphans
docker compose -f tools/integration-patterns/custom-handoff/compose.yaml -f tools/integration-patterns/custom-handoff/rabbitmq.yaml up --build -d --wait
docker compose -f tools/integration-patterns/custom-handoff/compose.yaml -f tools/integration-patterns/custom-handoff/rabbitmq.yaml run --rm scenario
```

The application package contains MIME bytes, sender, ordered envelope recipients, a checksum and an application idempotency key. Hidden recipients are
separate from visible MIME; no Email/Mailer/SDK/private-key object is serialized. The sender retains its prepared package so restart need not regenerate it.
The composed-message example omits the outer Bcc header at serialization, like SMTP, without rebuilding its protected body. An exact-EML integration
must instead preserve its authoritative representation and establish a compatible hidden-envelope contract.
The receiving application verifies/decrypts the synthetic protected message and writes encrypted storage before confirming acceptance.

The scenario checks duplicate input and hidden-envelope recipients. HTTPS additionally rejects a package and loses confirmation after successful storage.
Neither is silently retried. SJM's successful callback receipt still has UNKNOWN SMTP acceptance; a failed callback may have no receipt. Gateway storage,
broker publication, consumer processing, SMTP acceptance and delivery are different facts.

This particular gateway has no DSN/REQUIRETLS/recipient-rejection contract and rejects those explicit options. Other callbacks must map or reject them too.
Filesystem storage and SFTP can implement the same seam, but are documentation alternatives, not extra runnable implementations here.

Development tokens/keys/certificates, local files and gateway schema are not production trust, storage durability across disk failure or a general SJM SPI.
Canonical code is in this module; the shared fixture contains the executable scenario. No host Java/Maven, real credentials or paid service is needed.

For the ordinary HTTPS stack:

```text
docker compose -f tools/integration-patterns/custom-handoff/compose.yaml down --volumes --remove-orphans
```

Keep the RabbitMQ variation file on cleanup if you used it. [The shared notes](../README.md) explain fresh runs, image caching and development certificates.
