[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](modules/simple-java-mail/LICENSE-2.0.txt) 
[![Latest Release](https://img.shields.io/maven-central/v/org.simplejavamail/simple-java-mail.svg?style=flat)](https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22org.simplejavamail%22%20AND%20v%3A%229.2.0%22)
[![Javadocs](https://img.shields.io/badge/javadoc-9.2.0-brightgreen.svg?color=brightgreen)](https://www.javadoc.io/doc/org.simplejavamail/maven-master-project)
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
- [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html)
- [9.0 migration notes](https://www.simplejavamail.org/migration-notes-9.0.0.html)

### Installation ###

Simple Java Mail is available in [Maven Central](https://search.maven.org/search?q=g:org.simplejavamail):

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>9.2.0</version>
</dependency>
```

Read about additional modules you can add here: [simplejavamail.org/modules](https://www.simplejavamail.org/modules.html). 

### Development ###

- [Project mechanisms catalogue](PROJECT_MECHANISMS_CATALOGUE.md) for optional module loading, CLI metadata generation, MIME selection, proxy bridging, concurrency, and non-null instrumentation.
- [API expansion workflow](API_EXPANSION_WORKFLOW.md) for adding public API fields or builder methods.
- [Developer environment setup](DEVELOPMENT.md) for JDK and build constraints.

### Latest progress ###

[v9.2.0](https://github.com/bbottema/simple-java-mail/releases/tag/9.2.0) - [Maven Central](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.2.0/)

**Major OAuth2 feature in v9.2.0:** Long-lived Mailers can now use an `OAuth2AccessTokenProvider` instead of holding one fixed access token. Simple Java Mail asks the provider for a current token whenever it opens or reconnects a physical SMTP connection; an already-connected pooled transport is reused without another lookup. The provider owns acquisition, expiry checks, caching and refresh. This works with regular and custom-Session Mailers, open-connection and simple-batch sending, pooled or clustered connections, and the Spring module. Existing fixed-token configuration remains supported. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#refresh-aware-oauth2).

**Security default change in v9.2.0:** Simple Java Mail no longer trusts every TLS certificate by default. SMTP connections now use the JVM trust store while continuing to verify the server hostname. Applications using self-signed certificates or a private CA that is not installed in the JVM trust store may stop connecting after the upgrade. Install the issuing CA in the JVM trust store when possible; otherwise use `trustingSSLHosts("smtp.internal.example")` as a narrow compatibility exception, or `trustingAllHosts(true)` as the broader, less-safe escape hatch. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html).

**Embedded-image containment change in v9.2.0:** Configured file, classpath and URL bases now form actual boundaries during embedded-image auto-resolution. Normalized paths, filesystem symlinks, URL origins and HTTP redirects can no longer escape the configured base by default. Applications that intentionally resolve outside a base can keep that behavior with the matching `allowingEmbeddedImageOutsideBase...(true)` option; omitting a base also remains unrestricted. Review this before accepting editable HTML and see the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#embedded-image-containment).

**Email serialization overhaul in v9.2.0:** Native Java serialization now keeps attachment, embedded-image and decrypted-attachment bytes, forwarded MIME content, and S/MIME signing configuration, so a restored email remains ready to send. A custom `DataSource` becomes a read-only byte snapshot; its runtime behavior is not recreated, and lazy or remote content is read while serializing. Serialized output can contain PKCS12 key material and passwords. Pre-9.2 streams remain readable for message metadata, but attachment content those versions never stored now fails with a clear version-specific error when used. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#email-serialization-snapshots).

**Asynchronous failure-reporting change in v9.2.0:** With `async=true`, the returned `CompletableFuture` now covers defaults and overrides, validation, scheduling, conversion, connection and transport. Preparation and validation still run on the calling thread, but their failures complete the future exceptionally instead of escaping before a future is returned. Null arguments remain immediate call errors, and synchronous behavior is unchanged. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#async-failure-reporting).

- **DKIM configuration guard in v9.2.0:** `From` must be signed. Simple Java Mail now rejects a builder or property configuration that excludes `From`, case-insensitively, instead of passing a misleading setting to signing. Relay-specific exclusions such as `Message-ID` or `Date` still work when that relay genuinely rewrites them. The `l=` body-length tag remains off by default; enable it only when you accept that later-appended content is outside the signed length. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#dkim-from-header).

- **Explicit DKIM private-key sources in v9.2.0:** property-backed DKIM defaults now accept `file:` for a deliberate file path and `base64:` for inline Base64-encoded key bytes. Existing unprefixed values retain their earlier path-or-UTF-8-data behavior. Explicit missing files and malformed Base64 fail during configuration without echoing key data. See the [DKIM security reference](https://www.simplejavamail.org/security.html#section-sending-dkim).

- **S/MIME signature-status change in v9.2.0:** `getSmimeSignatureValid()` now reports only cryptographic verification that actually happened. Outlook conversion no longer infers `true` from successfully extracted content, and one failed signature makes a combined result `false`. A `true` result verifies content integrity against the signer certificate included with the message; it does not establish certificate trust or authenticate the `From` address. Invalid signed content remains available when it can be parsed. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#smime-signature-status).

- **Extra-property precedence change in v9.2.0:** `simplejavamail.extraproperties.*` now follows the same precedence as other configuration: system properties override environment variables, which override property-file values. Applications that define the same extra Jakarta Mail property in more than one source may resolve a different value after upgrading. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#extra-property-precedence).

- **Email governance builder fix in v9.2.0:** the legacy pre-start API lost the override opt-out for blank emails and the defaults opt-out for copied emails. Governance options now follow any email starter, removing that faulty hand-off. The old pre-start `EmailBuilder.ignoringDefaults()` and `EmailStartingBuilder` ignore methods have been removed; call `.ignoringDefaults()` and `.ignoringOverrides()` on the builder returned by `startingBlank()`, `copying(...)`, `forwarding(...)`, or a reply starter instead. The boolean variants remain available. See the [9.2 migration notes](https://www.simplejavamail.org/migration-notes-9.2.0.html#email-governance-builder).

- [#692](https://github.com/bbottema/simple-java-mail/issues/692): **Refresh-aware OAuth2 access tokens:** ask a caller-supplied provider for a current token at each physical SMTP connection boundary, including reconnects and pooled or clustered allocation. Spring can apply a single provider bean automatically; fixed-token configuration remains available. Connection-pool support comes from [smtp-connection-pool 3.1.0](https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/3.1.0).
- [#691](https://github.com/bbottema/simple-java-mail/issues/691): **Complete asynchronous failures through the returned future:** use one error channel for email preparation, validation, scheduling and sending while leaving synchronous calls and null-argument checks unchanged.
- [#690](https://github.com/bbottema/simple-java-mail/issues/690): **Serialize send-ready email snapshots:** preserve resource bytes, forwarded MIME content and S/MIME signing configuration; give pre-9.2 attachment data an explicit failure mode.
- [#689](https://github.com/bbottema/simple-java-mail/issues/689): **Fix governance opt-outs lost by email starters:** remove the faulty pre-start flag forwarding and put the options on the builder returned by every starter.
- [#685](https://github.com/bbottema/simple-java-mail/issues/685): **Align extra Jakarta Mail property precedence:** let deployment-time system and environment settings override matching property-file values.
- [#684](https://github.com/bbottema/simple-java-mail/issues/684): **Valid S/MIME configuration example:** use `AES256_CBC` as the message-content cipher instead of the unrelated `DES_EDE3_WRAP` key-wrapping algorithm.
- [#683](https://github.com/bbottema/simple-java-mail/issues/683): **Keep CLI recipient parsing internal:** remove the CLI-only string parser from `EmailPopulatingBuilder` while preserving public recipient builders and all CLI/property behavior.
- [#680](https://github.com/bbottema/simple-java-mail/issues/680): **Make S/MIME verification metadata fail closed:** reserve `true` for signatures that were actually checked, preserve `false` when results are combined, and document the boundary between signature integrity and certificate trust.
- [#679](https://github.com/bbottema/simple-java-mail/issues/679): **Require DKIM signatures to include From:** reject `From` in header-exclusion configuration case-insensitively while retaining relay-specific exclusions for non-mandatory headers.
- [#678](https://github.com/bbottema/simple-java-mail/issues/678): **Contain embedded-image auto-resolution:** enforce real-path containment for files, segment containment for classpath resources, and same-origin normalized-path containment across URL redirects.
- [#677](https://github.com/bbottema/simple-java-mail/issues/677): **Strict TLS certificate trust by default:** changed `DEFAULT_TRUST_ALL_HOSTS` to `false`, leaving `mail.*.ssl.trust` unset unless an application explicitly configures a trust exception. Server identity verification remains enabled.
- [#676](https://github.com/bbottema/simple-java-mail/issues/676): **Authenticated SOCKS bridge binding:** restrict the temporary bridge to the JVM loopback address and configure Jakarta Mail with that same address. The bridge is not exposed on network interfaces and is not a general-purpose proxy.

[v9.1.0](https://github.com/bbottema/simple-java-mail/releases/tag/9.1.0) - [v9.1.8](https://github.com/bbottema/simple-java-mail/releases/tag/9.1.8) - [Maven Central](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.1.8/)

> **Spring-module notice:** Versions 9.0.0 through 9.1.5 package test application settings that can override an application's YAML configuration and prevent SMTP connections. If you use `spring-module`, upgrade to 9.1.6 or later.

> **CLI recipient notice:** Versions 9.0.0 through 9.1.6 cannot combine TO, CC, and BCC recipients in one CLI command. Repeated `--email:withRecipients` options are merged and rejected. Upgrade to 9.1.7 or later.

- **v9.1.8:** [#686](https://github.com/bbottema/simple-java-mail/issues/686): **Custom-session proxy routing:** `usingSession(session).withProxy(...)` now updates the intended `mail.smtp.socks.*` route while leaving the rest of the caller's session configuration untouched.
- **v9.1.8:** [#687](https://github.com/bbottema/simple-java-mail/issues/687): **SMTPS through SOCKS:** removed the obsolete restriction that rejected SOCKS proxying for implicit-TLS SMTP connections.
- **v9.1.8:** [#696](https://github.com/bbottema/simple-java-mail/issues/696), [#697](https://github.com/bbottema/simple-java-mail/issues/697): **Connection-pool resets:** the max-size and claim-timeout reset methods now restore their own settings without changing the core size or connection expiry.
- **v9.1.7:** [#682](https://github.com/bbottema/simple-java-mail/issues/682): **Dedicated CLI recipient options:** restore independent `--email:to`, `--email:cc`, and `--email:bcc` options so one command can combine all three recipient types.
- **v9.1.6:** [#681](https://github.com/bbottema/simple-java-mail/issues/681): **Spring configuration isolation:** stop packaging the Spring test `application.properties` in `spring-module`. Sample local-bind, SMTP client-hostname, transfer-encoding, and other test values can no longer override an application's YAML configuration or break SMTP connections.
- **v9.1.5:** [#674](https://github.com/bbottema/simple-java-mail/issues/674), [#675](https://github.com/bbottema/simple-java-mail/issues/675): **SOCKS5 domain framing:** place the port after the UTF-8-encoded domain bytes and decode domain replies after their length octet, fixing internationalized-host requests and reply diagnostics.
- **v9.1.4:** [#669](https://github.com/bbottema/simple-java-mail/issues/669), [#670](https://github.com/bbottema/simple-java-mail/issues/670): **EML file stream ownership:** close streams created internally by the `File`-based EML conversion overloads after synchronous parsing, while leaving caller-provided `InputStream` ownership unchanged.
- **v9.1.4:** **Java 8-compatible dependency and release-tool maintenance:** updated SpotBugs annotations to 4.10.3 ([#671](https://github.com/bbottema/simple-java-mail/pull/671)), the Central Publishing Maven Plugin to 0.11.0 ([#672](https://github.com/bbottema/simple-java-mail/pull/672)), and Objenesis to 3.6 ([#673](https://github.com/bbottema/simple-java-mail/pull/673)).
- **v9.1.3:** [#668](https://github.com/bbottema/simple-java-mail/issues/668): **New Outlook inline images:** updated `outlook-message-parser` to 1.16.2 so native-HTML-only `.msg` files match inline `cid:` images correctly and trailing NUL terminators no longer leak into attachment metadata.
- **v9.1.2:** **Dependency and Java 8-compatible build-tool maintenance:** updated JMail to 2.2.0 ([#663](https://github.com/bbottema/simple-java-mail/pull/663)), Zip4j to 2.11.6 ([#666](https://github.com/bbottema/simple-java-mail/pull/666)), Exec Maven Plugin to 3.6.3 ([#664](https://github.com/bbottema/simple-java-mail/pull/664)), Maven Enforcer Plugin to 3.6.3 ([#665](https://github.com/bbottema/simple-java-mail/pull/665)), and Maven JAR Plugin to 3.5.1 ([#667](https://github.com/bbottema/simple-java-mail/pull/667)).
- **v9.1.1:** **Java 8 build-tool maintenance** ([#662](https://github.com/bbottema/simple-java-mail/pull/662)): updated annotations and Maven compiler, JAR, OSGi bundle, and Karaf tooling to Java 8-compatible versions, with Dependabot guards against newer-Java-only upgrade lines.
- **v9.1.0:** [#653](https://github.com/bbottema/simple-java-mail/issues/653): a configurable SMTP client hostname for the `EHLO` / `HELO` command.
- **v9.1.0:** [#654](https://github.com/bbottema/simple-java-mail/issues/654): SMTP submission receipts for reading the server acceptance response after a send.
- **v9.1.0:** No breaking changes; existing `sendMail(...)` behavior is unchanged.

[v9.0.0](https://github.com/bbottema/simple-java-mail/releases/tag/9.0.0) - [v9.0.4](https://github.com/bbottema/simple-java-mail/releases/tag/9.0.4) - [Maven Central](https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/9.0.4/)

> **Spring-module notice:** Versions 9.0.0 through 9.1.5 package test application settings that can override an application's YAML configuration and prevent SMTP connections. If you use `spring-module`, upgrade to 9.1.6 or later.

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

- **v9.0.0:** [#613](https://github.com/bbottema/simple-java-mail/issues/613): **Recipient builder API:** added dedicated builders for constructing single recipients and recipient collections.
- **v9.0.0:** [#297](https://github.com/bbottema/simple-java-mail/issues/297): **Per-recipient S/MIME certificates:** enabled encrypted mail for multiple recipients with different certificates.
- **v9.0.0:** [#574](https://github.com/bbottema/simple-java-mail/issues/574): **Delivery Status Notification (DSN):** added first-class DSN configuration.
- **v9.0.0:** [#573](https://github.com/bbottema/simple-java-mail/issues/573): **Pre-encoded resources:** added pre-encoded attachment and embedded-image APIs.
- **v9.0.0:** [#196](https://github.com/bbottema/simple-java-mail/issues/196): **Mailer-level DKIM defaults:** added default DKIM signing configuration so DKIM can be configured once per `Mailer`.
- **v9.0.0:** [#569](https://github.com/bbottema/simple-java-mail/issues/569): **Simple batch and open-connection sending:** added `sendMailsInSimpleBatch(...)` for sequential batch work without the batch module and `withOpenConnection(...)` for callback-scoped reuse of a single SMTP connection.

#### Enhancements ####

- **v9.0.2:** [#645](https://github.com/bbottema/simple-java-mail/issues/645): **Outlook last-modifier metadata:** exposed `PR_LAST_MODIFIER_NAME` / `0x3FFA` as `OutlookMessageData#getLastModifierName()` without treating it as sender identity.
- **v9.0.0:** [#614](https://github.com/bbottema/simple-java-mail/issues/614): **Outlook conversion metadata:** added explicit result APIs for inspecting source `.msg` headers and metadata without copying structural headers into converted emails, resolving [#609](https://github.com/bbottema/simple-java-mail/issues/609).
- **v9.0.0:** [#605](https://github.com/bbottema/simple-java-mail/issues/605): **Per-body content-transfer encoding:** added `Content-Transfer-Encoding` configuration for plain text, HTML, and calendar content.
- **v9.0.0:** [#566](https://github.com/bbottema/simple-java-mail/issues/566), [#597](https://github.com/bbottema/simple-java-mail/issues/597), [#602](https://github.com/bbottema/simple-java-mail/issues/602), [#607](https://github.com/bbottema/simple-java-mail/issues/607): **MIME resource `Content-ID` handling:** improved explicit IDs, parsed embedded images, and generated fallback IDs.
- **v9.0.0:** [#589](https://github.com/bbottema/simple-java-mail/issues/589): **Jakarta Mail debug output:** added configurable debug output routing.
- **v9.0.0:** [#568](https://github.com/bbottema/simple-java-mail/issues/568): **Local SMTP bind address:** added configuration for multi-IP SMTP hosts.
- **v9.0.0:** [#565](https://github.com/bbottema/simple-java-mail/issues/565), [#618](https://github.com/bbottema/simple-java-mail/issues/618): **Batch cluster configuration:** fixed Java API cluster configuration and added property-defined cluster configurations for property-file and Spring-configured clustered sending.
- **v9.0.0:** [#572](https://github.com/bbottema/simple-java-mail/issues/572), [#571](https://github.com/bbottema/simple-java-mail/issues/571): **S/MIME conversion leniency:** tolerate unsupported S/MIME payloads during Outlook conversion and preserve parsed email content when signature verification fails.
- **v9.0.0:** [#606](https://github.com/bbottema/simple-java-mail/issues/606): **MIME type sanitizing:** sanitize malformed resource MIME types before generating attachment and embedded-image headers.
- **v9.0.0:** [#541](https://github.com/bbottema/simple-java-mail/issues/541): **Resource headers:** removed the non-standard `filename` parameter from resource `Content-Type` headers; filenames remain available through `Content-Disposition`.
- **v9.0.0:** [#265](https://github.com/bbottema/simple-java-mail/issues/265), [#237](https://github.com/bbottema/simple-java-mail/issues/237): **Java module support:** added Java 9 module descriptors to the core and facade jars so modular applications can require `org.simplejavamail` directly.

#### Fixes and Compatibility ####

- **v9.0.4:** [#652](https://github.com/bbottema/simple-java-mail/issues/652): **RFC 2047 address validation:** reject encoded-word syntax inside address specs during validation while keeping encoded display names valid.
- **v9.0.3:** [#651](https://github.com/bbottema/simple-java-mail/issues/651): **Outlook plain-text RTF rendering:** preserved line breaks without exposing browser-default `<pre>` styling in converted HTML.
- **v9.0.0:** [#615](https://github.com/bbottema/simple-java-mail/issues/615): **Async test connections:** fixed `MailerGenericBuilder.async()` so no-arg `testConnection()` uses the configured async default.
- **v9.0.0:** [#611](https://github.com/bbottema/simple-java-mail/issues/611): **SMTPS custom SSL socket factories:** fixed custom SSL socket factory configuration for SMTPS mailers.
- **v9.0.0:** [#535](https://github.com/bbottema/simple-java-mail/issues/535): **Async failure reporting:** let async send and connection-test failures surface through the returned `CompletableFuture` without duplicate framework error logs.
- **v9.0.0:** [#583](https://github.com/bbottema/simple-java-mail/issues/583): **Java 25 CLI startup:** fixed CLI startup on Java 25.
- **v9.0.0:** [#616](https://github.com/bbottema/simple-java-mail/issues/616): **CLI optional-argument detection:** removed the runtime JetBrains annotation fork from CLI optional-argument detection.
- **v9.0.0:** **Standalone CLI command cleanup:** `send`, `connect`, and `validate` now wait for command work and close mailer resources, preventing batch-module resources from keeping the process alive.

#### Dependency and Supporting-Library Updates ####

- **v9.0.3:** **Angus runtime alignment:** bumped Angus Mail to 2.0.5 and added Angus Activation 2.0.3.
- **v9.0.3:** **Logging dependencies:** bumped Log4j from 2.25.4 to 2.26.1.
- **v9.0.2:** **Jakarta API alignment:** bumped Jakarta Mail API to 2.1.5 and Jakarta Activation API to 2.1.4.
- **v9.0.0:** **Core dependency maintenance:** bumped JMail to 2.1.0 ([#634](https://github.com/bbottema/simple-java-mail/pull/634)), commons-io to 2.22.0 ([#579](https://github.com/bbottema/simple-java-mail/pull/579), [#627](https://github.com/bbottema/simple-java-mail/pull/627)), Kryo to 5.6.2 ([#586](https://github.com/bbottema/simple-java-mail/pull/586)), Zip4j to 2.11.5 ([#587](https://github.com/bbottema/simple-java-mail/pull/587)), SubEthaSMTP to 7.2.2 ([#593](https://github.com/bbottema/simple-java-mail/pull/593), [#632](https://github.com/bbottema/simple-java-mail/pull/632)), Angus Mail to 2.0.4 ([#604](https://github.com/bbottema/simple-java-mail/pull/604)), Objenesis to 3.5 ([#580](https://github.com/bbottema/simple-java-mail/pull/580), [#635](https://github.com/bbottema/simple-java-mail/pull/635)), Lombok to 1.18.46 ([#636](https://github.com/bbottema/simple-java-mail/pull/636)), AssertJ Core to 3.27.7 ([#622](https://github.com/bbottema/simple-java-mail/pull/622)), and SpotBugs annotations to 4.10.2 ([#629](https://github.com/bbottema/simple-java-mail/pull/629)).
- **v9.0.0:** **Logging dependencies:** aligned Log4j to 2.25.4 ([#624](https://github.com/bbottema/simple-java-mail/pull/624)) and SLF4J API to 2.0.18 ([#631](https://github.com/bbottema/simple-java-mail/pull/631)), keeping the Log4j bridge on `log4j-slf4j2-impl` for SLF4J 2.x.

##### Supporting Libraries #####

- **v9.0.3:** **`outlook-message-parser` 1.16.1 / `rtf-to-html` 2.0.2:** fixed browser-default `<pre>` styling in Outlook plain-text RTF conversion ([#651](https://github.com/bbottema/simple-java-mail/issues/651)).
- **v9.0.2:** **`outlook-message-parser` 1.16.0:** added source last-modifier metadata used by `OutlookMessageData#getLastModifierName()`.
- **v9.0.0:** **`utils-mail-dkim` 3.3.0:** added configurable DNS provider URL support for DKIM domain-key TXT lookups, fixed the published automatic module name, and kept packaged artifacts free of JaCoCo probes.
- **v9.0.0:** **`clustered-object-pool` 4.0.1** ([#6](https://github.com/bbottema/clustered-object-pool/issues/6)): added cluster-specific Java configuration for pool defaults, claim timeout, and load balancing.
- **v9.0.0:** **`smtp-connection-pool` 3.0.1** ([#8](https://github.com/simple-java-mail/smtp-connection-pool/issues/8)): pulled in `clustered-object-pool` 4.0.1 so the batch-module fix for [#565](https://github.com/bbottema/simple-java-mail/issues/565) can keep connection-pool defaults per cluster key.
- **v9.0.0:** **`smtp-connection-pool` 3.0.0:** made clustered SMTP pools generic over their cluster-key type and kept already-unusable connections from surfacing as generic pool error logs during transport close.
- **v9.0.0:** **`java-socks-proxy-server` 4.2.0:** updated SOCKS live tests to use dynamic proxy ports instead of fixed ports.
- **v9.0.0:** **`outlook-message-parser` 1.15.0:** improved Outlook `.msg` conversion by preserving nested message attachment metadata, fixing sent-date extraction ([#534](https://github.com/bbottema/simple-java-mail/issues/534)), fixing recipient bucket parsing ([#504](https://github.com/bbottema/simple-java-mail/issues/504)), broadening S/MIME detection, improving RTF-only body conversion ([#576](https://github.com/bbottema/simple-java-mail/issues/576)), and updating Apache POI.

##### Build and Test Maintenance #####

- **v9.0.3:** **Build maintenance:** bumped Maven Source Plugin to 3.4.0, NotNull Instrumenter Maven Plugin to 1.1.1, and Mycila License Maven Plugin to 4.6.
- **v9.0.2:** **Build maintenance:** bumped Maven Assembly Plugin to 3.8.0 and Nexus Staging Maven Plugin to 1.7.0, and extended Dependabot guards for Java 11-only plugin lines.
- **v9.0.1:** **Release packaging:** restored generated license headers in published source JARs and enabled publication of the standalone CLI ZIP and TAR classifier artifacts.
- **v9.0.0:** **Build plugins and test stack:** bumped Maven Surefire Plugin to 3.5.6 ([#592](https://github.com/bbottema/simple-java-mail/pull/592), [#625](https://github.com/bbottema/simple-java-mail/pull/625)), Maven Clean Plugin to 3.5.0 ([#626](https://github.com/bbottema/simple-java-mail/pull/626)), Appassembler Maven Plugin to 2.1.0 ([#581](https://github.com/bbottema/simple-java-mail/pull/581)), Exec Maven Plugin to 3.5.0 ([#582](https://github.com/bbottema/simple-java-mail/pull/582)), Maven Deploy Plugin to 3.1.4 ([#619](https://github.com/bbottema/simple-java-mail/pull/619)), Maven Install Plugin to 3.1.4 ([#639](https://github.com/bbottema/simple-java-mail/pull/639)), Maven Javadoc Plugin to 3.12.0 ([#637](https://github.com/bbottema/simple-java-mail/pull/637)), Maven GPG Plugin to 3.2.8 ([#621](https://github.com/bbottema/simple-java-mail/pull/621)), and JaCoCo Maven Plugin to 0.8.15 ([#638](https://github.com/bbottema/simple-java-mail/pull/638)); aligned JUnit Platform/Jupiter at 1.14.4/5.14.4 while preserving Java 8 compatibility ([#596](https://github.com/bbottema/simple-java-mail/pull/596), [#633](https://github.com/bbottema/simple-java-mail/pull/633)); kept JUnit Pioneer on 1.9.1 because 2.x is Java 11 bytecode ([#630](https://github.com/bbottema/simple-java-mail/pull/630)); added Java 8 Dependabot guards; and replaced live embedded-image URL tests with deterministic local coverage ([#617](https://github.com/bbottema/simple-java-mail/issues/617)).

The full stand-alone release history is maintained in [RELEASE_HISTORY.md](RELEASE_HISTORY.md).
