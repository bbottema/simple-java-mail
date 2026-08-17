# Simple Java Mail

[![Maven Central](https://img.shields.io/maven-central/v/org.simplejavamail/simple-java-mail.svg?style=flat&label=Maven%20Central)](https://central.sonatype.com/artifact/org.simplejavamail/simple-java-mail)
[![Javadocs](https://javadoc.io/badge2/org.simplejavamail/maven-master-project/javadoc.svg)](https://javadoc.io/doc/org.simplejavamail/maven-master-project)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-607d8b.svg?style=flat)](DEVELOPMENT.md)
[![Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg?style=flat)](LICENSE)

**Simple to use. Built for the real world.**

Simple Java Mail builds well-formed MIME through a streamlined Java API. That same API covers reusable message rules, transport security, DKIM, S/MIME, OpenPGP/MIME, diagnostics, conversion, authenticated SOCKS, and advanced SMTP delivery.

Jakarta Mail provides the standard mail API, and Angus Mail provides its transport implementation. You can still supply a `Session`, access the generated `MimeMessage`, set raw Jakarta Mail properties, or provide the final send operation.

[Get started](https://www.simplejavamail.org/download.html) · [Capabilities](https://www.simplejavamail.org/features.html) · [Modules](https://www.simplejavamail.org/modules.html) · [Javadocs](https://javadoc.io/doc/org.simplejavamail/maven-master-project) · [9.2 migration guide](https://www.simplejavamail.org/migration-notes-9.2.0.html) · [Compare libraries](https://www.simplejavamail.org/feature-matrix.html)

## Send a first email

Simple Java Mail is published to Maven Central. Start with `simple-java-mail`, the core dependency. Add a feature module only when you need it, using the same version as the core dependency. Java 8 is the source, target, and minimum supported runtime.

### Maven

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>9.3.2</version>
</dependency>
```

### Gradle

```groovy
implementation 'org.simplejavamail:simple-java-mail:9.3.2'
```

### Build and send

```java
Email email = EmailBuilder.startingBlank()
    .from("Sender", "sender@example.org")
    .withRecipients(new RecipientBuilder()
        .withName("Recipient")
        .withAddress("recipient@example.net")
        .withType(Message.RecipientType.TO)
        .build())
    .withSubject("It works")
    .withPlainText("Your first Simple Java Mail message.")
    .buildEmail();

Mailer mailer = MailerBuilder.withSMTPServer(
        System.getenv("SMTP_HOST"),
        587,
        System.getenv("SMTP_USER"),
        System.getenv("SMTP_PASSWORD"))
    .withTransportStrategy(TransportStrategy.SMTP_TLS)
    .buildMailer();

mailer.sendMail(email);
```

The port and transport strategy must match your SMTP server. Build the `Mailer` once, reuse it, and close it during application shutdown. Before sending, `mailer.testConnection()` checks the SMTP path and `mailer.validate(email)` checks the message. The [complete getting-started guide](https://www.simplejavamail.org/download.html) explains each step.

## What the API handles

| Job | Support |
| --- | --- |
| [Messages and MIME](https://www.simplejavamail.org/features.html#section-basic-usage) | Plain text and HTML alternatives, embedded images, attachments, calendar content, headers, encodings, and the corresponding multipart structure. |
| [Shared message rules](https://www.simplejavamail.org/configuration.html#section-config-mailer) | Recipient builders, address validation, defaults, enforced overrides, bounce addresses, receipts, and maximum message size. |
| [Security](https://www.simplejavamail.org/security.html) | TLS and SMTPS, server identity and certificate checks, fixed or refresh-aware OAuth2 tokens, header-injection protection, DKIM, and S/MIME. |
| [Delivery](https://www.simplejavamail.org/configuration.html#section-batch-and-clustering) | Synchronous and asynchronous sends, a scoped open connection, simple batches, connection pools, and independently configured SMTP clusters. |
| [Diagnostics](https://www.simplejavamail.org/debugging.html) | Connection tests, message validation, configuration inspection, Jakarta Mail debug routing, logging integrations, and SMTP submission replies. |
| [Conversion and interoperability](https://www.simplejavamail.org/features.html#section-converting) | Conversion between `Email`, `MimeMessage`, and EML, send-ready serialization, Outlook `.msg` conversion, and custom sending logic. |

## Configure once, reuse everywhere

A `Mailer` keeps SMTP settings, transport policy, defaults, overrides, validation, signing, proxy configuration, and delivery behavior out of individual call sites. Configure it with the [Java builder API](https://www.simplejavamail.org/configuration.html#section-programmatic-api-common), [property files, system properties, or environment variables](https://www.simplejavamail.org/configuration.html#section-config-properties), or let the [Spring module](https://www.simplejavamail.org/configuration.html#section-spring-support) create and inject it.

This leaves application code to describe each email while shared rules stay in one reusable place.

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
| [Spring-managed configuration](https://www.simplejavamail.org/modules.html#spring-module) | `spring-module` |
| [Command-line sending and validation](https://www.simplejavamail.org/modules.html#cli-module) | `cli-module` |
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

The current release is **9.3.2**.

[GitHub release](https://github.com/bbottema/simple-java-mail/releases/tag/9.3.2) · [Maven Central artifacts](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.3.2/) · [Migration guide](https://www.simplejavamail.org/migration-notes-9.2.0.html) · [Complete release history](RELEASE_HISTORY.md)

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
