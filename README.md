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

### Latest progress ###

[v9.1.0](https://github.com/bbottema/simple-java-mail/releases/tag/9.1.0) - [Maven Central](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.1.0/)

- [#653](https://github.com/bbottema/simple-java-mail/issues/653): a configurable SMTP client hostname for the `EHLO` / `HELO` command.
- [#654](https://github.com/bbottema/simple-java-mail/issues/654): SMTP submission receipts for reading the server acceptance response after a send.
- No breaking changes; existing `sendMail(...)` behavior is unchanged.

[v9.0.0](https://github.com/bbottema/simple-java-mail/releases/tag/9.0.0) - [v9.0.4](https://github.com/bbottema/simple-java-mail/releases/tag/9.0.4) - [Maven Central](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.0.4/)

#### The Short Version ####

**Simple Java Mail has been going strong-ish for about 20 years**, and **9.0.0** rolls *roughly two years of backlog* into a new major release.

```
simple-java-mail
└── outlook-message-parser
    └── rtf-to-html
└── java-utils-mail-dkim
└── java-utils-mail-smime
└── smtp-connection-pool
    └── clustered-object-pool
        └── generic-object-pool
└── java-socks-proxy-server
```

Across Simple Java Mail and the supporting libraries that keep the stack moving, **more than 100 GitHub issues and PRs** were reviewed, fixed, merged, or closed while keeping the project **Java 8-compatible**.

**Major features:** the dedicated recipient builder API, per-recipient S/MIME certificates, first-class Delivery Status Notification support, pre-encoded attachment and embedded-image sending, mailer-level DKIM defaults, and simple batch/open-connection sending without the batch module.
**Enhancements:** broader Outlook conversion metadata, MIME resource handling, content-transfer encoding control, debug routing, local SMTP bind configuration, batch cluster configuration, and Java module support.

#### Migration Note ####

**This is a breaking major release.** The old recipient-addition method jungle has been cleaned up in favor of the **recipient builder API**, so code that relied on the removed recipient overloads needs to migrate. Start with the [9.0 migration notes](https://www.simplejavamail.org/migration-notes-9.0.0.html) before upgrading.

#### Major Features ####

- [#613](https://github.com/bbottema/simple-java-mail/issues/613): **Recipient builder API:** added dedicated builders for constructing single recipients and recipient collections.
- [#297](https://github.com/bbottema/simple-java-mail/issues/297): **Per-recipient S/MIME certificates:** enabled encrypted mail for multiple recipients with different certificates.
- [#574](https://github.com/bbottema/simple-java-mail/issues/574): **Delivery Status Notification (DSN):** added first-class DSN configuration.
- [#573](https://github.com/bbottema/simple-java-mail/issues/573): **Pre-encoded resources:** added pre-encoded attachment and embedded-image APIs.
- [#196](https://github.com/bbottema/simple-java-mail/issues/196): **Mailer-level DKIM defaults:** added default DKIM signing configuration so DKIM can be configured once per `Mailer`.
- [#569](https://github.com/bbottema/simple-java-mail/issues/569): **Simple batch and open-connection sending:** added `sendMailsInSimpleBatch(...)` for sequential batch work without the batch module and `withOpenConnection(...)` for callback-scoped reuse of a single SMTP connection.

#### Enhancements ####

- [#614](https://github.com/bbottema/simple-java-mail/issues/614): **Outlook conversion metadata:** added explicit result APIs for inspecting source `.msg` headers and metadata without copying structural headers into converted emails, resolving [#609](https://github.com/bbottema/simple-java-mail/issues/609).
- [#645](https://github.com/bbottema/simple-java-mail/issues/645): **Outlook last-modifier metadata:** exposed `PR_LAST_MODIFIER_NAME` / `0x3FFA` as `OutlookMessageData#getLastModifierName()` without treating it as sender identity.
- [#605](https://github.com/bbottema/simple-java-mail/issues/605): **Per-body content-transfer encoding:** added `Content-Transfer-Encoding` configuration for plain text, HTML, and calendar content.
- [#566](https://github.com/bbottema/simple-java-mail/issues/566), [#597](https://github.com/bbottema/simple-java-mail/issues/597), [#602](https://github.com/bbottema/simple-java-mail/issues/602), [#607](https://github.com/bbottema/simple-java-mail/issues/607): **MIME resource `Content-ID` handling:** improved explicit IDs, parsed embedded images, and generated fallback IDs.
- [#589](https://github.com/bbottema/simple-java-mail/issues/589): **Jakarta Mail debug output:** added configurable debug output routing.
- [#568](https://github.com/bbottema/simple-java-mail/issues/568): **Local SMTP bind address:** added configuration for multi-IP SMTP hosts.
- [#565](https://github.com/bbottema/simple-java-mail/issues/565), [#618](https://github.com/bbottema/simple-java-mail/issues/618): **Batch cluster configuration:** fixed Java API cluster configuration and added property-defined cluster configurations for property-file and Spring-configured clustered sending.
- [#572](https://github.com/bbottema/simple-java-mail/issues/572), [#571](https://github.com/bbottema/simple-java-mail/issues/571): **S/MIME conversion leniency:** tolerate unsupported S/MIME payloads during Outlook conversion and preserve parsed email content when signature verification fails.
- [#606](https://github.com/bbottema/simple-java-mail/issues/606): **MIME type sanitizing:** sanitize malformed resource MIME types before generating attachment and embedded-image headers.
- [#541](https://github.com/bbottema/simple-java-mail/issues/541): **Resource headers:** removed the non-standard `filename` parameter from resource `Content-Type` headers; filenames remain available through `Content-Disposition`.
- [#265](https://github.com/bbottema/simple-java-mail/issues/265), [#237](https://github.com/bbottema/simple-java-mail/issues/237): **Java module support:** added Java 9 module descriptors to the core and facade jars so modular applications can require `org.simplejavamail` directly.

#### Fixes and Compatibility ####

- [#615](https://github.com/bbottema/simple-java-mail/issues/615): **Async test connections:** fixed `MailerGenericBuilder.async()` so no-arg `testConnection()` uses the configured async default.
- [#611](https://github.com/bbottema/simple-java-mail/issues/611): **SMTPS custom SSL socket factories:** fixed custom SSL socket factory configuration for SMTPS mailers.
- [#535](https://github.com/bbottema/simple-java-mail/issues/535): **Async failure reporting:** let async send and connection-test failures surface through the returned `CompletableFuture` without duplicate framework error logs.
- [#583](https://github.com/bbottema/simple-java-mail/issues/583): **Java 25 CLI startup:** fixed CLI startup on Java 25.
- [#616](https://github.com/bbottema/simple-java-mail/issues/616): **CLI optional-argument detection:** removed the runtime JetBrains annotation fork from CLI optional-argument detection.
- [#652](https://github.com/bbottema/simple-java-mail/issues/652): **RFC 2047 address validation:** reject encoded-word syntax inside address specs during validation while keeping encoded display names valid.
- **Standalone CLI command cleanup:** `send`, `connect`, and `validate` now wait for command work and close mailer resources, preventing batch-module resources from keeping the process alive.

#### Dependency and Supporting-Library Updates ####

- **Core dependency maintenance:** bumped JMail to 2.1.0 ([#634](https://github.com/bbottema/simple-java-mail/pull/634)), commons-io to 2.22.0 ([#579](https://github.com/bbottema/simple-java-mail/pull/579), [#627](https://github.com/bbottema/simple-java-mail/pull/627)), Kryo to 5.6.2 ([#586](https://github.com/bbottema/simple-java-mail/pull/586)), Zip4j to 2.11.5 ([#587](https://github.com/bbottema/simple-java-mail/pull/587)), SubEthaSMTP to 7.2.2 ([#593](https://github.com/bbottema/simple-java-mail/pull/593), [#632](https://github.com/bbottema/simple-java-mail/pull/632)), Angus Mail to 2.0.4 ([#604](https://github.com/bbottema/simple-java-mail/pull/604)), Jakarta Mail API to 2.1.5 with Jakarta Activation / Angus Activation alignment, Objenesis to 3.5 ([#580](https://github.com/bbottema/simple-java-mail/pull/580), [#635](https://github.com/bbottema/simple-java-mail/pull/635)), Lombok to 1.18.46 ([#636](https://github.com/bbottema/simple-java-mail/pull/636)), AssertJ Core to 3.27.7 ([#622](https://github.com/bbottema/simple-java-mail/pull/622)), and SpotBugs annotations to 4.10.2 ([#629](https://github.com/bbottema/simple-java-mail/pull/629)).
- **Logging dependencies:** aligned Log4j to 2.25.4 ([#624](https://github.com/bbottema/simple-java-mail/pull/624)) and SLF4J API to 2.0.18 ([#631](https://github.com/bbottema/simple-java-mail/pull/631)), keeping the Log4j bridge on `log4j-slf4j2-impl` for SLF4J 2.x.

##### Supporting Libraries #####

- **`utils-mail-dkim` 3.3.0:** added configurable DNS provider URL support for DKIM domain-key TXT lookups, fixed the published automatic module name, and kept packaged artifacts free of JaCoCo probes.
- **`clustered-object-pool` 4.0.1** ([#6](https://github.com/bbottema/clustered-object-pool/issues/6)): added cluster-specific Java configuration for pool defaults, claim timeout, and load balancing.
- **`smtp-connection-pool` 3.0.1** ([#8](https://github.com/simple-java-mail/smtp-connection-pool/issues/8)): pulled in `clustered-object-pool` 4.0.1 so the batch-module fix for [#565](https://github.com/bbottema/simple-java-mail/issues/565) can keep connection-pool defaults per cluster key.
- **`smtp-connection-pool` 3.0.0:** made clustered SMTP pools generic over their cluster-key type and kept already-unusable connections from surfacing as generic pool error logs during transport close.
- **`java-socks-proxy-server` 4.2.0:** updated SOCKS live tests to use dynamic proxy ports instead of fixed ports.
- **`outlook-message-parser` 1.16.1:** improved Outlook `.msg` conversion by preserving nested message attachment metadata, fixing sent-date extraction ([#534](https://github.com/bbottema/simple-java-mail/issues/534)), fixing recipient bucket parsing ([#504](https://github.com/bbottema/simple-java-mail/issues/504)), broadening S/MIME detection, improving RTF-only body conversion ([#576](https://github.com/bbottema/simple-java-mail/issues/576)), avoiding browser-default `<pre>` styling for Outlook plain-text RTF conversion ([#651](https://github.com/bbottema/simple-java-mail/issues/651)), updating Apache POI, and exposing Outlook last-modifier source metadata.

##### Build and Test Maintenance #####

- **Build plugins and test stack:** bumped Maven Surefire Plugin to 3.5.6 ([#592](https://github.com/bbottema/simple-java-mail/pull/592), [#625](https://github.com/bbottema/simple-java-mail/pull/625)), Maven Clean Plugin to 3.5.0 ([#626](https://github.com/bbottema/simple-java-mail/pull/626)), Appassembler Maven Plugin to 2.1.0 ([#581](https://github.com/bbottema/simple-java-mail/pull/581)), Exec Maven Plugin to 3.5.0 ([#582](https://github.com/bbottema/simple-java-mail/pull/582)), Maven Assembly Plugin to 3.8.0, Maven Deploy Plugin to 3.1.4 ([#619](https://github.com/bbottema/simple-java-mail/pull/619)), Maven Install Plugin to 3.1.4 ([#639](https://github.com/bbottema/simple-java-mail/pull/639)), Maven Javadoc Plugin to 3.12.0 ([#637](https://github.com/bbottema/simple-java-mail/pull/637)), Maven GPG Plugin to 3.2.8 ([#621](https://github.com/bbottema/simple-java-mail/pull/621)), Nexus Staging Maven Plugin to 1.7.0, and JaCoCo Maven Plugin to 0.8.15 ([#638](https://github.com/bbottema/simple-java-mail/pull/638)); aligned JUnit Platform/Jupiter at 1.14.4/5.14.4 while preserving Java 8 compatibility ([#596](https://github.com/bbottema/simple-java-mail/pull/596), [#633](https://github.com/bbottema/simple-java-mail/pull/633)); kept JUnit Pioneer on 1.9.1 because 2.x is Java 11 bytecode ([#630](https://github.com/bbottema/simple-java-mail/pull/630)); added Dependabot ignore rules for Java 11-only upgrade lines; restored release packaging for generated source-license headers and standalone CLI ZIP/TAR classifier artifacts; and replaced live embedded-image URL tests with deterministic local coverage ([#617](https://github.com/bbottema/simple-java-mail/issues/617)).

Older pre-9.0 release notes are maintained in [RELEASE_HISTORY.md](RELEASE_HISTORY.md).
