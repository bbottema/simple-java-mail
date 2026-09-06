# Simple Java Mail

[![Maven Central](https://img.shields.io/maven-central/v/org.simplejavamail/simple-java-mail.svg?style=flat&label=Maven%20Central)](https://central.sonatype.com/artifact/org.simplejavamail/simple-java-mail)
[![Javadocs](https://javadoc.io/badge2/org.simplejavamail/maven-master-project/javadoc.svg)](https://javadoc.io/doc/org.simplejavamail/maven-master-project)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-607d8b.svg?style=flat)](DEVELOPMENT.md)
[![Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg?style=flat)](LICENSE)

**Simple to use. Built for the real world.**

Simple Java Mail builds well-formed MIME through a streamlined Java API. That same API covers reusable message rules, transport security, DKIM, S/MIME, OpenPGP/MIME, diagnostics, conversion, authenticated SOCKS, and advanced SMTP delivery.

Jakarta Mail provides the standard mail API, and Angus Mail provides its transport implementation. You can still supply a `Session`, access the generated `MimeMessage`, set raw Jakarta Mail properties, or provide the final send operation.

[Get started](https://www.simplejavamail.org/download.html) · [Capabilities](https://www.simplejavamail.org/features.html) · [Modules](https://www.simplejavamail.org/modules.html) · [Javadocs](https://javadoc.io/doc/org.simplejavamail/maven-master-project) · [9.2 migration guide](https://www.simplejavamail.org/migration-notes-9.2.0.html) · [Compare libraries](https://www.simplejavamail.org/feature-matrix.html)

## Send a first email

Simple Java Mail is published to Maven Central. Start with `simple-java-mail`, the core dependency. Add a feature module only when you need it, using the same version as the core dependency. The 10.x libraries require Java 11 or newer; 9.x is the last line that runs on Java 8. After 10.0.0, 9.x is maintenance-only: new work goes to 10.x, while critical fixes may be backported when that can be done safely without dropping Java 8 support.

### Maven

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>9.3.3</version>
</dependency>
```

### Gradle

```groovy
implementation 'org.simplejavamail:simple-java-mail:9.3.3'
```

### Build and send

```java
SimpleJavaMail mail = SimpleJavaMail.fromDefaults();

Email email = mail.emailBuilder().startingBlank()
    .from("Sender", "sender@example.org")
    .withRecipients(RecipientBuilder.to("Recipient", "recipient@example.net"))
    .withSubject("It works")
    .withPlainText("Your first Simple Java Mail message.")
    .buildEmail();

Mailer mailer = mail.mailerBuilder().withSMTPServer(
        System.getenv("SMTP_HOST"),
        587,
        System.getenv("SMTP_USER"),
        System.getenv("SMTP_PASSWORD"))
    .withTransportStrategy(TransportStrategy.SMTP_TLS)
    .buildMailer();

mailer.sendMailSync(email);
```

The port and transport strategy must match your SMTP server. Build the `Mailer` once, reuse it, and close it during application shutdown. Use `sendMailSync(...)` when failures should be thrown on the calling thread, or `sendMailAsync(...)` when the complete operation should be represented by a `CompletableFuture`. The receipt-returning equivalents are `sendMailAndGetReceiptSync(...)` and `sendMailAndGetReceiptAsync(...)`. Existing `sendMail(email)` and boolean overloads remain supported for code that deliberately uses the Mailer's configured async default or selects the mode dynamically.

Before sending, `mailer.testConnection()` checks the SMTP path. Choose message preflight by what your code needs back:

| Need | Call |
| --- | --- |
| Only stop if this Mailer cannot prepare the Email; no prepared details are needed | `mailer.validate(email)` |
| Inspect or retain the effective Email, EML bytes, size, Message-ID, or envelope addresses | `MailRehearsal rehearsal = mailer.rehearse(email)` |

Both full calls perform the same no-SMTP preparation. `validate` is a success-or-exception convenience that discards the rehearsal result; it never reports invalid input by returning `false`. A successful `rehearse` call has therefore already validated the Email—do not call `validate` first. The [complete getting-started guide](https://www.simplejavamail.org/download.html) explains each step.

When the application needs a durable checkpoint after SMTP submission, request a provider-neutral receipt:

```java
try {
    MailSubmissionReceipt receipt = mailer.sendMailAndGetReceiptSync(email);

    database.markSubmitted(
        receipt.getEmailId(),
        receipt.getStatus(),
        receipt.getSubmittedAt());
} catch (MailSubmissionException failure) {
    MailSubmissionReceipt receipt = failure.getSubmissionReceipt();
    log.warn("Submission {}: accepted={}, unsent={}, invalid={}",
        receipt.getStatus(),
        receipt.getAcceptedRecipients(),
        receipt.getValidUnsentRecipients(),
        receipt.getInvalidRecipients(),
        failure.getCause());
}
```

`ACCEPTED` confirms SMTP submission for all envelope recipients, not final mailbox delivery. Failed and partial attempts throw `MailSubmissionException` while preserving the original Jakarta Mail exception and the same recipient facts. `UNKNOWN` means the transport cannot tell whether the server accepted anything; do not automatically retry that outcome unless duplicate submission is acceptable or prevented. The [submission receipt guide](https://www.simplejavamail.org/features.html#section-submission-receipts) covers asynchronous and open-connection use.

For cross-cutting audit and metrics code, configure one terminal observer on the reusable `Mailer`:

```java
Mailer mailer = mail.mailerBuilder()
    .withSMTPServer("smtp.example.org", 587, "user", "password")
    .withMailSendObserver(outcome -> {
        Duration totalTime = Duration.between(
            outcome.getRequestedAt(), outcome.getCompletedAt());

        audit.record(
            outcome.getEffectiveMessageId(),
            outcome.isSuccessful(),
            totalTime);
    })
    .buildMailer();
```

`MailSendOutcome` describes the whole Simple Java Mail attempt, including preparation and asynchronous queue time; its optional `MailSubmissionReceipt` describes SMTP submission facts only. The callback is terminal—not a connection-lifecycle event stream—and asynchronous sends can invoke it concurrently on worker threads. See [observing completed mail sends](https://www.simplejavamail.org/features.html#section-mail-send-observer) and the manually runnable [streaming progress demo](modules/simple-java-mail/src/test/java/demo/MailSendObserverDemoApp.java).

## Submit already-finalized EML exactly

Ordinary EML conversion is the right choice when you want an editable `Email`: Simple Java Mail parses its meaning and later builds a fresh MIME message. Use exact EML when the existing wire representation itself is authoritative—for example, an archived message or output already protected by DKIM, S/MIME, or OpenPGP/MIME.

```java
Email email = mail.emailBuilder()
    .startingFromExactEml(Path.of("ready-to-send.eml"))
    .withEnvelopeRecipients("actual-recipient@example.org")
    .withEnvelopeSender("bounces@example.org") // optional
    .buildEmail();

MailSubmissionReceipt receipt = mailer.sendMailAndGetReceiptSync(email);
```

The result remains an ordinary `Email`, so all getters and all existing send methods still apply. Its getters are a parsed view; sending, conversion, and rehearsal use the copied input bytes unchanged. Exact mode therefore bypasses defaults, overrides, composed-content validation, embedded-image resolution, MIME rebuilding, and cryptographic processing. It also retains headers normally removed during submission—including `Bcc`, `Resent-Bcc`, and `Content-Length`—so supply an already safe outbound message and always provide the real SMTP-envelope recipients separately. Copying this `Email` through the normal builder intentionally returns to composed-email behavior.

Exact EML must be non-empty, parseable, use canonical CRLF line endings, and end in CRLF. A missing Message-ID remains missing. The bundled Angus adapter supports full-byte preservation; another transport adapter must opt in explicitly. See the [exact EML guide](https://www.simplejavamail.org/features.html#section-exact-eml) and the manually runnable [file submission demo](modules/simple-java-mail/src/test/java/demo/ExactEmlSendDemoApp.java).

## What the API handles

| Job | Support |
| --- | --- |
| [Messages and MIME](https://www.simplejavamail.org/features.html#section-basic-usage) | Plain text and HTML alternatives, embedded images, attachments, calendar content, headers, encodings, and the corresponding multipart structure. |
| [Shared message rules](https://www.simplejavamail.org/configuration.html#section-config-mailer) | Recipient builders, address validation, defaults, enforced overrides, bounce addresses, receipts, and maximum message size. |
| [Security](https://www.simplejavamail.org/security.html) | TLS and SMTPS, server identity and certificate checks, fixed or refresh-aware OAuth2 tokens, header-injection protection, DKIM, and S/MIME. |
| [Delivery](https://www.simplejavamail.org/configuration.html#section-batch-and-clustering) | Synchronous and asynchronous sends, terminal mail-send observation, provider-neutral submission outcomes and partial failures, a scoped open connection, simple batches, connection pools, and independently configured SMTP clusters. |
| [Diagnostics](https://www.simplejavamail.org/debugging.html) | Connection tests, message validation and rehearsal, configuration inspection, Jakarta Mail debug routing, logging integrations, and SMTP submission receipts. |
| [Conversion and interoperability](https://www.simplejavamail.org/features.html#section-converting) | Conversion between `Email`, `MimeMessage`, and EML; exact finalized-EML submission; send-ready serialization; Outlook `.msg` conversion; and custom sending logic. |

## Configure once, reuse everywhere

A `Mailer` keeps SMTP settings, transport policy, defaults, overrides, validation, signing, proxy configuration, and delivery behavior out of individual call sites. Configure it with the [Java builder API](https://www.simplejavamail.org/configuration.html#section-programmatic-api-common), [property files, system properties, or environment variables](https://www.simplejavamail.org/configuration.html#section-config-properties), or let the [Spring module](https://www.simplejavamail.org/spring.html) create and inject it.

`SimpleJavaMail.fromDefaults()` resolves the conventional classpath file, environment variables, and system properties into one lazy immutable snapshot. For explicit source ordering or more than one mail setup in the same JVM, build a `SimpleJavaMailConfig` with `ConfigLoader.builder()`—including `withPropertiesFile(Path)` for filesystem configuration—and pass it to `SimpleJavaMail.withConfig(config)`.

This leaves application code to describe each email while shared rules stay in one reusable place.

### Spring Boot

Spring Boot 2.7 and 3.x applications can add the dedicated starter. It brings in `spring-module`, discovers the integration automatically, and creates
one `SimpleJavaMailConfig`, `SimpleJavaMail`, and `Mailer` bean without an `@Import`. The application supplies its own Boot line and therefore keeps its
Boot-managed Spring Framework and SLF4J versions:

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail-spring-boot-starter</artifactId>
    <version>10.0.0</version>
</dependency>
```

```groovy
implementation 'org.simplejavamail:simple-java-mail-spring-boot-starter:10.0.0'
```

Configure the Mailer through the usual keys and inject it directly:

```properties
simplejavamail.smtp.host=smtp.example.org
simplejavamail.smtp.port=587
simplejavamail.transportstrategy=SMTP_TLS
simplejavamail.smtp.username=${SMTP_USER}
simplejavamail.smtp.password=${SMTP_PASSWORD}
```

```java
@Service
class NotificationService {
    private final Mailer mailer;
    private final SimpleJavaMail mail;

    NotificationService(Mailer mailer, SimpleJavaMail mail) {
        this.mailer = mailer;
        this.mail = mail;
    }
}
```

Each bean backs off independently when the application supplies the same type. The auto-configured Mailer is closed with the application context; a
replacement follows the lifecycle declared or inferred by its own Spring bean definition. With no SMTP host at all, the context still starts with
`localhost` as a development fallback, but sending requires an SMTP server there. Plain Spring applications can continue to use `spring-module` with
`@Import(SimpleJavaMailSpringSupport.class)`.

## Add only what you need

Optional modules extend the same API and add their dependencies only when selected. Use the same Simple Java Mail version for every module. Some integration artifacts already depend on `simple-java-mail`; the modules guide notes when you do not need to declare it separately.

| Requirement | Artifact |
| --- | --- |
| Default Angus SMTP provider and delivery adapter (included transitively; exclude it only when replacing the provider) | `angus-mail-provider-module` |
| [DKIM domain signing](https://www.simplejavamail.org/modules.html#dkim-module) | `dkim-module` |
| [S/MIME signing, encryption, and reading](https://www.simplejavamail.org/modules.html#smime-module) | `smime-module` |
| OpenPGP/MIME signing, encryption, verification, and decryption | `openpgp-module` |
| [Pooled and clustered delivery](https://www.simplejavamail.org/modules.html#batch-module) | `batch-module` |
| [Authenticated SOCKS proxies](https://www.simplejavamail.org/modules.html#authenticated-socks-module) | `authenticated-socks-module` |
| [Outlook `.msg` parsing and conversion](https://www.simplejavamail.org/modules.html#outlook-module) | `outlook-module` |
| [Spring Boot auto-configuration](https://www.simplejavamail.org/spring.html) | `simple-java-mail-spring-boot-starter` |
| [Spring-managed configuration](https://www.simplejavamail.org/modules.html#spring-module) | `spring-module` |
| [Command-line sending and validation](https://www.simplejavamail.org/modules.html#cli-module) | `cli-module` (Java 17+; the other modules require Java 11+) |
| [OSGi and Apache Karaf](https://www.simplejavamail.org/modules.html#karaf-module) | `karaf-module` |

The [modules guide](https://www.simplejavamail.org/modules.html) lists each coordinate, its main transitive dependencies, and the capabilities it enables.

## Jakarta Mail remains available

Simple Java Mail handles the higher-level work involved in outbound email while keeping Jakarta Mail within reach. When needed, you can:

- [build a mailer around your own `Session`](https://www.simplejavamail.org/features.html#section-custom-session);
- [add raw Jakarta Mail properties](https://www.simplejavamail.org/features.html#section-custom-properties);
- [access the effective `Session`](https://www.simplejavamail.org/features.html#section-session-access);
- convert an `Email` to a `MimeMessage` for inspection or integration;
- [provide the final sending implementation](https://www.simplejavamail.org/features.html#section-custom-mailer).

## Documentation by task

| If you want to... | Start here |
| --- | --- |
| Send the first message | [Get started](https://www.simplejavamail.org/download.html) |
| Build rich content and recipients | [Capabilities](https://www.simplejavamail.org/features.html) |
| Configure an application | [Configuration](https://www.simplejavamail.org/configuration.html) |
| Protect transport and message content | [Security](https://www.simplejavamail.org/security.html) |
| Test, inspect, or diagnose a send | [Diagnostics](https://www.simplejavamail.org/debugging.html) |
| Understand multipart structures | [MIME and interoperability](https://www.simplejavamail.org/rfc-compliant.html) |
| Choose an optional artifact | [Modules](https://www.simplejavamail.org/modules.html) |
| Compare Java mail libraries | [Comparison](https://www.simplejavamail.org/feature-matrix.html) |
| Upgrade existing code | [9.2 migration guide](https://www.simplejavamail.org/migration-notes-9.2.0.html) |
| Understand the replaceable provider boundary in 10.0 | [10.0 migration guide](MIGRATION-10.0.md) |
| Ask a question or contribute | [Help and contribute](https://www.simplejavamail.org/contact.html) |

## Current release

The current release is **9.3.3**.

[GitHub release](https://github.com/bbottema/simple-java-mail/releases/tag/9.3.3) · [Maven Central artifacts](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.3.3/) · [Migration guide](https://www.simplejavamail.org/migration-notes-9.2.0.html) · [Complete release history](RELEASE_HISTORY.md)

Read the migration guide before upgrading from an older release when source compatibility, defaults, security behavior, or configuration precedence matters to your application.

## Develop and contribute

Simple Java Mail is open source under the Apache License 2.0. It was first published in 2009 and is maintained by Benny Bottema with contributions from its users and the surrounding open-source projects.

- Use the [issue tracker](https://github.com/bbottema/simple-java-mail/issues) for reproducible bugs and concrete feature ideas.
- Read [DEVELOPMENT.md](DEVELOPMENT.md) for the supported JDKs and local build setup.
- Read the [project mechanisms catalogue](PROJECT_MECHANISMS_CATALOGUE.md) before changing module loading, CLI metadata, MIME selection, proxy bridging, concurrency, or non-null instrumentation.
- Follow the [API expansion workflow](API_EXPANSION_WORKFLOW.md) when adding a public field or builder method.
- Use [Help and contribute](https://www.simplejavamail.org/contact.html) to choose the right public channel or arrange private follow-up for a sensitive report.

## License

Simple Java Mail is licensed under the [Apache License 2.0](LICENSE).
