[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](modules/simple-java-mail/LICENSE-2.0.txt) 
[![Latest Release](https://img.shields.io/maven-central/v/org.simplejavamail/simple-java-mail.svg?style=flat)](https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22org.simplejavamail%22%20AND%20v%3A%229.1.0%22)
[![Javadocs](https://img.shields.io/badge/javadoc-9.1.0-brightgreen.svg?color=brightgreen)](https://www.javadoc.io/doc/org.simplejavamail/maven-master-project)
[![Codacy](https://img.shields.io/codacy/grade/c7506663a4ab41e49b9675d87cd900b7.svg?style=flat)](https://app.codacy.com/gh/bbottema/simple-java-mail)
![Java 8+](https://img.shields.io/badge/java-8+-lightgray.svg)

# Simple Java Mail #

Simple Java Mail is a robust Java mailing library built to make production email simple to use: rich content, recipient governance, signing and encryption, transport security, configuration, diagnostics, conversion, and high-throughput batch or clustered sending.

It keeps those concerns behind a consistent high-level API, while still giving you fluent builders, property/Spring configuration, defaults and overrides, validation, logging, and lower-level Jakarta Mail escape hatches when you need them.

Under the hood, Simple Java Mail sits on top of [Angus Mail](https://eclipse-ee4j.github.io/angus-mail/) (previously [Jakarta Mail](https://jakartaee.github.io/mail-api/README-JakartaMail)).

Simple Java Mail remains Java 8-compatible; Java 8 is the source, target, and minimum supported runtime.

### Documentation ###

The full user documentation lives at [simplejavamail.org](https://www.simplejavamail.org). Start there for the minimal banner example, grand examples, feature guides, configuration reference, diagnostics, and module overview.

- [Features](https://www.simplejavamail.org/features.html#navigation)
- [Configuration and Spring support](https://www.simplejavamail.org/configuration.html#navigation)
- [Security](https://www.simplejavamail.org/security.html#navigation)
- [Logging and debugging](https://www.simplejavamail.org/debugging.html#navigation)
- [Modules](https://www.simplejavamail.org/modules.html)
- [CLI](https://www.simplejavamail.org/cli.html#navigation)
- [9.0 migration notes](https://www.simplejavamail.org/migration-notes-9.0.0.html)

### Installation ###

Simple Java Mail is available in [Maven Central](https://search.maven.org/search?q=g:org.simplejavamail):

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>9.1.0</version>
</dependency>
```

Read about additional modules you can add here: [simplejavamail.org/modules](https://www.simplejavamail.org/modules.html). 

### Development ###

- [Project mechanisms catalogue](PROJECT_MECHANISMS_CATALOGUE.md) for optional module loading, CLI metadata generation, MIME selection, proxy bridging, concurrency, and non-null instrumentation.
- [API expansion workflow](API_EXPANSION_WORKFLOW.md) for adding public API fields or builder methods.
- [Developer environment setup](DEVELOPMENT.md) for JDK and build constraints.

### Latest Release ###

[v9.1.0](https://github.com/bbottema/simple-java-mail/releases/tag/9.1.0) - [Maven Central](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.1.0/)

#### The Short Version ####

9.1.0 is a small SMTP operations release. It adds two public APIs for production mail setups that need better protocol identity, tracing and submission auditing while keeping existing `sendMail(...)` behavior unchanged.

#### SMTP Operations ####

- [#654](https://github.com/bbottema/simple-java-mail/issues/654): **SMTP submission receipts:** added `sendMailAndGetReceipt(...)` on `Mailer` and the scoped open-connection `MailSender`. The returned `MailSubmissionReceipt` exposes the effective message id, submission timestamp and, when Angus SMTP is used, an `SmtpServerResponse` with the return code and final server response such as `250 ... queued as ...`.
- [#653](https://github.com/bbottema/simple-java-mail/issues/653): **SMTP EHLO/HELO client hostname:** added typed Java, property and Spring configuration for the SMTP client hostname sent in the `EHLO` or `HELO` command. This maps to `mail.smtp.localhost` or `mail.smtps.localhost` according to the active transport strategy.

#### Compatibility ####

There are no breaking changes. `sendMail(...)` still returns `CompletableFuture<Void>`, custom mailers and logging-only transport mode still work, and the new receipt API returns no SMTP response when no SMTP transport response is available. Submission receipts confirm SMTP server acceptance, not final mailbox delivery; use DSN, bounces, read receipts or provider-specific tracking for final delivery signals.

Older release notes are maintained in [RELEASE_HISTORY.md](RELEASE_HISTORY.md).
