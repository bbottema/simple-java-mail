[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](modules/simple-java-mail/LICENSE-2.0.txt) 
[![Latest Release](https://img.shields.io/maven-central/v/org.simplejavamail/simple-java-mail.svg?style=flat)](https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22org.simplejavamail%22%20AND%20v%3A%229.0.0%22)
[![Javadocs](https://img.shields.io/badge/javadoc-9.0.0-brightgreen.svg?color=brightgreen)](https://www.javadoc.io/doc/org.simplejavamail/maven-master-project)
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
    <version>9.0.0</version>
</dependency>
```

Read about additional modules you can add here: [simplejavamail.org/modules](https://www.simplejavamail.org/modules.html). 

### Development ###

- [Project mechanisms catalogue](PROJECT_MECHANISMS_CATALOGUE.md) for optional module loading, CLI metadata generation, MIME selection, proxy bridging, concurrency, and non-null instrumentation.
- [API expansion workflow](API_EXPANSION_WORKFLOW.md) for adding public API fields or builder methods.
- [Developer environment setup](DEVELOPMENT.md) for JDK and build constraints.

### Latest Progress ###

Unreleased 9.0.0

- [#297](https://github.com/bbottema/simple-java-mail/issues/297): Added per-recipient S/MIME certificates, enabling encrypted mail for multiple recipients with different certificates.
- [#613](https://github.com/bbottema/simple-java-mail/issues/613): Added a dedicated recipient builder API for constructing single recipients and recipient collections.
- [#614](https://github.com/bbottema/simple-java-mail/issues/614): Added explicit Outlook conversion result APIs for inspecting source `.msg` headers and metadata without copying structural headers into converted emails, resolving [#609](https://github.com/bbottema/simple-java-mail/issues/609).
- [#574](https://github.com/bbottema/simple-java-mail/issues/574): Added Delivery Status Notification (DSN) configuration.
- [#605](https://github.com/bbottema/simple-java-mail/issues/605): Added per-body `Content-Transfer-Encoding` configuration for plain text, HTML, and calendar content.
- [#573](https://github.com/bbottema/simple-java-mail/issues/573): Added pre-encoded attachment and embedded-image APIs.
- [#566](https://github.com/bbottema/simple-java-mail/issues/566), [#597](https://github.com/bbottema/simple-java-mail/issues/597), [#602](https://github.com/bbottema/simple-java-mail/issues/602), [#607](https://github.com/bbottema/simple-java-mail/issues/607): Improved MIME resource `Content-ID` handling for explicit IDs, parsed embedded images, and generated fallback IDs.
- [#589](https://github.com/bbottema/simple-java-mail/issues/589): Added Jakarta Mail debug output configuration.
- [#568](https://github.com/bbottema/simple-java-mail/issues/568): Added local bind address configuration for multi-IP SMTP hosts.
- [#196](https://github.com/bbottema/simple-java-mail/issues/196): Added mailer-level default DKIM signing configuration so DKIM can be configured once per `Mailer`.
- [#569](https://github.com/bbottema/simple-java-mail/issues/569): Added simple sequential batch sending and callback-scoped open connection sending without the batch module.
- [#565](https://github.com/bbottema/simple-java-mail/issues/565): Fixed batch-module cluster configuration so different cluster keys can keep separate connection-pool defaults through the Java API.
- [#618](https://github.com/bbottema/simple-java-mail/issues/618): Added property-defined batch cluster configurations for property-file and Spring-configured clustered sending, complementing the Java API cluster configuration from [#565](https://github.com/bbottema/simple-java-mail/issues/565).
- [#615](https://github.com/bbottema/simple-java-mail/issues/615): Fixed `MailerGenericBuilder.async()` so no-arg `testConnection()` uses the configured async default.
- [#611](https://github.com/bbottema/simple-java-mail/issues/611): Fixed custom SSL socket factory configuration for SMTPS mailers.
- [#535](https://github.com/bbottema/simple-java-mail/issues/535): Let async send and connection-test failures surface through the returned `CompletableFuture` without duplicate framework error logs.
- [#572](https://github.com/bbottema/simple-java-mail/issues/572): Tolerate unsupported S/MIME payloads during Outlook conversion instead of failing conversion.
- [#571](https://github.com/bbottema/simple-java-mail/issues/571): Preserve parsed email content when S/MIME signature verification fails and expose the invalid signature status.
- [#606](https://github.com/bbottema/simple-java-mail/issues/606): Sanitize malformed resource MIME types before generating attachment and embedded-image headers.
- [#541](https://github.com/bbottema/simple-java-mail/issues/541): Removed the non-standard `filename` parameter from resource `Content-Type` headers; filenames remain available through `Content-Disposition`.
- [#265](https://github.com/bbottema/simple-java-mail/issues/265): Added Java 9 module descriptors to the core and facade jars so modular applications can require `org.simplejavamail` directly, completing the multi-release jar support requested in [#237](https://github.com/bbottema/simple-java-mail/issues/237).
- [#583](https://github.com/bbottema/simple-java-mail/issues/583): Fixed CLI startup on Java 25.
- [#616](https://github.com/bbottema/simple-java-mail/issues/616): Removed the runtime JetBrains annotation fork from CLI optional-argument detection.
- Dependency maintenance: bumped commons-io to 2.18.0 ([#579](https://github.com/bbottema/simple-java-mail/pull/579)), Kryo to 5.6.2 ([#586](https://github.com/bbottema/simple-java-mail/pull/586)), Zip4j to 2.11.5 ([#587](https://github.com/bbottema/simple-java-mail/pull/587)), SubEthaSMTP to 7.1.7 ([#593](https://github.com/bbottema/simple-java-mail/pull/593)), Angus Mail to 2.0.4 ([#604](https://github.com/bbottema/simple-java-mail/pull/604)), Objenesis to 3.4 ([#580](https://github.com/bbottema/simple-java-mail/pull/580)), and AssertJ Core to 3.27.7 ([#622](https://github.com/bbottema/simple-java-mail/pull/622)).
  - `utils-mail-dkim` 3.3.0: added configurable DNS provider URL support for DKIM domain-key TXT lookups, fixed the published automatic module name, and kept packaged artifacts free of JaCoCo probes.
  - `clustered-object-pool` 4.0.1 ([#6](https://github.com/bbottema/clustered-object-pool/issues/6)): added cluster-specific Java configuration for pool defaults, claim timeout, and load balancing.
  - `smtp-connection-pool` 3.0.1 ([#8](https://github.com/simple-java-mail/smtp-connection-pool/issues/8)): pulled in `clustered-object-pool` 4.0.1 so the batch-module fix for [#565](https://github.com/bbottema/simple-java-mail/issues/565) can keep connection-pool defaults per cluster key.
  - `smtp-connection-pool` 3.0.0: made clustered SMTP pools generic over their cluster-key type and kept already-unusable connections from surfacing as generic pool error logs during transport close.
  - `java-socks-proxy-server` 4.2.0: updated SOCKS live tests to use dynamic proxy ports instead of fixed ports.
  - `outlook-message-parser` 1.15.0: improved Outlook `.msg` conversion by preserving nested message attachment metadata, fixing sent-date extraction ([#534](https://github.com/bbottema/simple-java-mail/issues/534)), fixing recipient bucket parsing ([#504](https://github.com/bbottema/simple-java-mail/issues/504)), broadening S/MIME detection, improving RTF-only body conversion ([#576](https://github.com/bbottema/simple-java-mail/issues/576)), and updating Apache POI.
  - Logging dependencies: aligned Log4j to 2.24.3 and SLF4J API to 2.0.16, keeping the Log4j bridge on `log4j-slf4j2-impl` for SLF4J 2.x.
- Build and test maintenance: bumped JUnit Pioneer to 2.3.0 ([#578](https://github.com/bbottema/simple-java-mail/pull/578)), Maven Surefire Plugin to 3.5.3 ([#592](https://github.com/bbottema/simple-java-mail/pull/592)), Appassembler Maven Plugin to 2.1.0 ([#581](https://github.com/bbottema/simple-java-mail/pull/581)), Exec Maven Plugin to 3.5.0 ([#582](https://github.com/bbottema/simple-java-mail/pull/582)), Maven Deploy Plugin to 3.1.4 ([#619](https://github.com/bbottema/simple-java-mail/pull/619)), and Maven GPG Plugin to 3.2.8 ([#621](https://github.com/bbottema/simple-java-mail/pull/621)); aligned JUnit Platform/Jupiter at 1.13.0/5.13.0 while preserving Java 8 compatibility ([#596](https://github.com/bbottema/simple-java-mail/pull/596)); and replaced live embedded-image URL tests with deterministic local coverage ([#617](https://github.com/bbottema/simple-java-mail/issues/617)).

v8.12.0 - [v8.12.6](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/8.12.6/)

- v8.12.6 (18-April-2025): [#595](https://github.com/bbottema/simple-java-mail/issues/595): [bug] Spring configuration - fix support for simplejavamail.extraproperties
- v8.12.5 (05-March-2025): Bumped PATCH versions of various dependencies [#553](https://github.com/bbottema/simple-java-mail/pull/553), [#554](https://github.com/bbottema/simple-java-mail/pull/554), [#555](https://github.com/bbottema/simple-java-mail/pull/555), [#562](https://github.com/bbottema/simple-java-mail/pull/562), [#567](https://github.com/bbottema/simple-java-mail/pull/567)
- v8.12.4 (12-December-2024): [#558](https://github.com/bbottema/simple-java-mail/pull/558): [bug] Mailer.close() exception because it attempts to shutdown batch-module connection pools, even if not available on the classpath
- v8.12.3 (25-November-2024): [#563](https://github.com/bbottema/simple-java-mail/pull/563): [enhancement] Add getter for Authenticated SOCKS server port
- v8.12.2 (05-October-2024): [#552](https://github.com/bbottema/simple-java-mail/pull/552): [bug] support iCalendar events with METHOD defined in body instead of Content-Type
- v8.12.1 (02-October-2024): [#533](https://github.com/bbottema/simple-java-mail/pull/533): [maintenance] Bump com.github.therapi:therapi-runtime-javadoc-scribe from 0.13.0 to 0.15.0
- v8.12.1 (02-October-2024): [#532](https://github.com/bbottema/simple-java-mail/pull/532): [maintenance] Bump com.sanctionco.jmail:jmail from 1.4.1 to 1.6.3
- v8.12.1 (02-October-2024): [#531](https://github.com/bbottema/simple-java-mail/pull/531): [maintenance] Bump com.github.bbottema:java-socks-proxy-server from 4.0.0 to 4.1.2
- v8.12.1 (02-October-2024): [#528](https://github.com/bbottema/simple-java-mail/pull/528): [maintenance] Buump com.github.davidmoten:subethasmtp from 7.0.1 to 7.1.1
- v8.12.1 (02-October-2024): [#522](https://github.com/bbottema/simple-java-mail/pull/522): [maintenance] Bump jakarta.annotation:jakarta.annotation-api from 1.3.5 to 3.0.0
- v8.12.0 (26-September-2024): [#550](https://github.com/bbottema/simple-java-mail/issues/550): [bug] Environment variables are not being loaded properly
- v8.12.0 (26-September-2024): [#538](https://github.com/bbottema/simple-java-mail/issues/538): [bug] System properties are only read if configuration file exists in class path
- v8.12.0 (26-September-2024): [#546](https://github.com/bbottema/simple-java-mail/pull/546): [Enhancement] Trim whitespace in encoder values for Content-Encoding

Older release notes are maintained in [RELEASE_HISTORY.md](RELEASE_HISTORY.md).
