# Migrating to Simple Java Mail 10.0

Simple Java Mail 10 replaces process-wide mutable configuration with immutable configuration snapshots. It also separates MIME processing from the Jakarta Mail implementation that performs SMTP submission. Angus remains the default sending implementation, but provider-specific SMTP behavior now lives behind a replaceable adapter at the final transport boundary.

## The CLI now has an optional local daemon

The Java libraries remain Java 8-compatible. The `cli-module` artifact and standalone `sjm` command now require Java 17 or newer. Existing one-shot commands remain valid and remain the default:

```text
sjm send ...
sjm connect ...
sjm validate ...
```

Opt into the per-user daemon when repeated commands should avoid JVM, parser, and Mailer setup:

```text
sjm daemon start
sjm send -d ...
sjm daemon status
sjm daemon stop
```

`-d`, bare `--daemon`, and `--daemon=acquire` use a compatible daemon or start one. `--daemon=require` uses only an already-running daemon. `--daemon=off` and `--no-daemon` explicitly select one-shot execution. Help, version, and daemon management remain local.

One daemon can safely retain several Mailers. Existing mailer options choose the effective configuration automatically, so two SMTP hosts or credentials become separate bounded entries without a public profile name. Use `--daemon-instance=<name>` only when you want a separate process, startup configuration, lifecycle, or security boundary.

The daemon captures classpath, environment, and system-property configuration when it starts. Restart it after those defaults, fixed OAuth2 tokens, or security material change. Explicit request options continue to override captured defaults. Relative attachment, body, EML, MSG, and certificate paths resolve against the invoking client's working directory.

The standalone distribution includes `batch-module`. A retained Mailer can therefore keep its connection pool alive between compatible requests, subject to the configured pool core size and idle expiry. Custom distributions without `batch-module` still reuse Mailers and daemon initialization, but cannot reuse pooled SMTP transports. The daemon does not change pool defaults.

Daemon requests remain synchronous from the caller's perspective. The command succeeds only after the existing send future completes. If the client loses an authenticated response after submission, it reports an ambiguous outcome and does not automatically resend or fall back to one-shot mode. The daemon is local-only and is not a durable queue, scheduler, or exactly-once delivery service.

See [CLI daemon operations](modules/cli-module/DAEMON.md) for exit codes, state locations, optional background supervision, package-manager status, shutdown, and recovery.

## Builder entry points now come from `SimpleJavaMail`

The fluent email and Mailer builder APIs remain. The secondary static `EmailBuilder` and `MailerBuilder` entry classes are gone. Start both kinds of builder from one `SimpleJavaMail` instance instead.

Before:

```java
Email email = EmailBuilder.startingBlank()
        .withSubject("Hello")
        .buildEmail();

Mailer mailer = MailerBuilder
        .withSMTPServer("smtp.example.com", 587, "user", "password")
        .withTransportStrategy(TransportStrategy.SMTP_TLS)
        .buildMailer();
```

In 10.0:

```java
SimpleJavaMail mail = SimpleJavaMail.fromDefaults();

Email email = mail.emailBuilder().startingBlank()
        .withSubject("Hello")
        .buildEmail();

Mailer mailer = mail.mailerBuilder()
        .withSMTPServer("smtp.example.com", 587, "user", "password")
        .withTransportStrategy(TransportStrategy.SMTP_TLS)
        .buildMailer();
```

Every old `EmailBuilder` starter has the same name on `mail.emailBuilder()`, including `copying`, `replyingTo`, `replyingToAll`, and `forwarding`. Every old `MailerBuilder` starter is available after `mail.mailerBuilder()`. Replace `MailerBuilder.usingSession(session)` with `mail.mailerBuilder(session)`, and replace `MailerBuilder.buildMailer()` with `mail.mailerBuilder().buildMailer()`.

Keep the `SimpleJavaMail` instance in application scope. It owns no network resources and returns a fresh builder for each construction flow. Keep and close each built `Mailer` according to your application lifecycle.

## Configuration is now an immutable snapshot

`ConfigLoader` no longer owns mutable static state. An instance loader resolves ordered sources into a detached `SimpleJavaMailConfig`, which you then give to a `SimpleJavaMail` factory.

The short form is:

```java
SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
```

It is exactly equivalent to this source recipe:

```java
SimpleJavaMailConfig config = ConfigLoader.builder()
        .withClasspathResource("simplejavamail.properties")
        .withEnvironmentVariables()
        .withSystemProperties()
        .load();

SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
```

`fromDefaults()` resolves that conventional snapshot lazily on its first call and reuses it afterwards. Changes to files, environment variables, or system properties after first use do not alter it. It never reads Spring's `Environment`.

For an application-owned source set, list sources from lowest to highest priority. A later non-blank value wins:

```java
SimpleJavaMailConfig config = ConfigLoader.builder()
        .withClasspathResource("mail-base.properties")
        .withInputStream("deployment file",
                Files.newInputStream(Paths.get("config", "simplejavamail.properties")))
        .withProperties("runtime overrides", runtimeOverrides)
        .load();

SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
```

`withInputStream` still consumes and closes the stream immediately, including when reading fails. `withProperties`, `withMap`, and a custom `ConfigSource` are sampled when `load()` runs. The resulting snapshot is detached, so later changes to a source map or `Properties` object have no effect.

### Replacing `addProperties`

Before:

```java
ConfigLoader.loadProperties(baseProperties, false);
ConfigLoader.loadProperties(overrideProperties, true);
```

In 10.0, express the order directly:

```java
SimpleJavaMailConfig config = ConfigLoader.builder()
        .withProperties("base", baseProperties)
        .withProperties("overrides", overrideProperties)
        .load();
```

To extend an existing snapshot, use it as one source for a new snapshot:

```java
SimpleJavaMailConfig replacement = ConfigLoader.builder()
        .withConfig(previous)
        .withProperties("new overrides", overrideProperties)
        .load();
```

Environment variables and system properties participate only when you add those sources. This avoids the old behavior where every `loadProperties` call silently read them again.

### Inspecting configuration

Replace static reads such as `ConfigLoader.hasProperty(...)`, `ConfigLoader.getProperty(...)`, and `ConfigLoader.valueOrProperty(...)` with the corresponding method on the snapshot:

```java
if (config.hasProperty(ConfigLoader.Property.SMTP_HOST)) {
    String host = config.getStringProperty(ConfigLoader.Property.SMTP_HOST);
    String source = config.getPropertySource(ConfigLoader.Property.SMTP_HOST);
}
```

`asMap()` is unmodifiable. Values have the type declared for that property, such as `String`, `Integer`, `Boolean`, or the relevant enum. Direct `Map` and `Properties` sources may contain already typed values, but a value of the wrong type now fails with the source name and property key. The message does not include the configured value. Unknown keys in caller-supplied sources fail; unrelated keys in the full process environment and JVM system properties are ignored.

Only the winning value is parsed. A malformed low-priority value therefore does not fail loading when a valid higher-priority source replaces it.

### More than one configuration in one JVM

Factories and their builders are isolated:

```java
SimpleJavaMailConfig transactionalConfig = ConfigLoader.builder()
        .withClasspathResource("transactional-mail.properties")
        .load();
SimpleJavaMailConfig reportingConfig = ConfigLoader.builder()
        .withClasspathResource("reporting-mail.properties")
        .load();

SimpleJavaMail transactionalMail = SimpleJavaMail.withConfig(transactionalConfig);
SimpleJavaMail reportingMail = SimpleJavaMail.withConfig(reportingConfig);

Mailer transactionalMailer = transactionalMail.mailerBuilder().buildMailer();
Mailer reportingMailer = reportingMail.mailerBuilder().buildMailer();
```

Loading or replacing one snapshot does not change the other factory, an existing builder, or an existing Mailer. Build a replacement factory and Mailer first, hand new work to it, let work on the old Mailer finish, and then close the old Mailer.

Static inbound methods on `EmailConverter` use `SimpleJavaMail.fromDefaults()` when they produce an email builder. Use `mail.converter()` when parsed or converted emails must retain a particular explicit snapshot.

## Opportunistic TLS moved to the Mailer builder

`TransportStrategy` is now stateless. Replace the mutable enum call:

```java
TransportStrategy.SMTP.setOpportunisticTLS(false);
```

with a setting on the Mailer being built:

```java
Mailer mailer = mail.mailerBuilder()
        .withTransportStrategy(TransportStrategy.SMTP)
        .withOpportunisticTLS(false)
        .buildMailer();
```

This is still a narrow compatibility escape hatch. It only controls the optional STARTTLS upgrade attempted by plain `SMTP`. It has no effect on mandatory TLS with `SMTP_TLS`, OAuth2 transport, or implicit TLS with `SMTPS`. The `simplejavamail.opportunistic.tls` property supplies the builder's initial value, and an explicit builder call wins.

## Spring uses context-local configuration

`SimpleJavaMailSpringSupport` now exposes these beans in each application context:

- `simpleJavaMailConfig`, an immutable `SimpleJavaMailConfig`;
- `simpleJavaMail`, the configured factory used to request fresh builders;
- `defaultMailer`, a Mailer that Spring closes with the context.

The `defaultMailerBuilder` bean is gone. Replace builder injection with the factory:

```java
@Autowired
private SimpleJavaMail simpleJavaMail;

@Bean(destroyMethod = "close")
Mailer customMailer() {
    return simpleJavaMail.mailerBuilder()
            .withProxyBridgePort(7777)
            .buildMailer();
}
```

Spring's `Environment` is authoritative for profiles, placeholders, system properties, and environment variables. The conventional `simplejavamail.properties` resource remains a low-priority source, but Simple Java Mail no longer overlays raw JVM and process values a second time after Spring resolves them. Separate application contexts therefore keep separate snapshots and factories.

## Embedded-image route flags are no longer crossed in Spring

The two similarly named settings now always control the route named by their key:

```properties
simplejavamail.embeddedimages.dynamicresolution.outside.base.url=false
simplejavamail.embeddedimages.dynamicresolution.outside.base.classpath=true
```

In 9.x, the internal URL and classpath identifiers were crossed. Ordinary property-file loading happened to compensate for that, but Spring mapping could apply these two flags to the opposite route. If a Spring application worked around that defect by swapping the values, put each intended value under its correctly named key for 10.0.

## Default sending setup

Existing senders still need only the `simple-java-mail` dependency. It brings the supported Angus provider adapter and Angus Mail implementation at runtime:

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>10.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'org.simplejavamail:simple-java-mail:10.0.0'
```

The adapter preserves envelope sender, delivery-status notification, and enhanced SMTP submission-response behavior without exposing Angus types to the MIME or cryptography pipeline.

## Excluding the default Angus provider

Only exclude the default adapter when you also supply another compatible Jakarta Mail implementation. The exclusion removes its transitive Angus implementation:

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>10.0.0</version>
    <exclusions>
        <exclusion>
            <groupId>org.simplejavamail</groupId>
            <artifactId>angus-mail-provider-module</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Gradle:

```groovy
implementation('org.simplejavamail:simple-java-mail:10.0.0') {
    exclude group: 'org.simplejavamail', module: 'angus-mail-provider-module'
}
```

The Jakarta Mail API jar is not an implementation. MIME and EML conversion use Jakarta Mail and Jakarta Activation service providers as well as sending, so a conversion-only application still needs Angus or another compatible implementation at runtime. Without one, conversion fails with an error that tells you to restore `angus-mail-provider-module` or add a replacement.

## Using another Jakarta Mail provider

Exclude `angus-mail-provider-module` as shown above and put the replacement implementation on the runtime path. No other provider adapter ships with Simple Java Mail today. Ordinary messages can use the generic transport fallback. Envelope sender and DSN requests require a matching `MailTransportAdapter`; unsupported capabilities fail before submission instead of being ignored. The replacement implementation is needed for MIME conversion as well as sending.

Third-party adapters implement `org.simplejavamail.api.mailer.spi.MailTransportAdapter` and are discovered with `ServiceLoader`. Register the implementation in `META-INF/services/org.simplejavamail.api.mailer.spi.MailTransportAdapter`, or use a JPMS `provides` directive. Adapter signatures expose Jakarta Mail and provider-neutral Simple Java Mail types only.

If no Jakarta Mail implementation is available, MIME conversion and sending fail with an error naming the dependency that must be added. If an implementation can create MIME but cannot submit mail, sending reports the missing provider or adapter separately.

## MIME finalization and signing order

10.0 finalizes headers, Message-ID, transfer encodings, and multipart boundaries before content protection. The supported order is:

1. Build and finalize the ordinary MIME entity.
2. Apply S/MIME or OpenPGP/MIME signing, then encryption when configured.
3. Apply DKIM to the final outgoing representation.
4. Pass the immutable, repeatable entity and separate delivery envelope to the selected transport adapter.

S/MIME and OpenPGP/MIME are alternative protection families in 10.0. Configuring both on one email throws a focused validation error. Within either family, signing precedes encryption.

## OpenPGP/MIME

Add the optional RFC 3156 module:

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>openpgp-module</artifactId>
    <version>10.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'org.simplejavamail:openpgp-module:10.0.0'
```

Signing and encryption accept in-memory key-ring data:

```java
OpenPgpSigningConfig signing = OpenPgpSigningConfig.builder()
        .secretKeyRing(secretKeyRingBytes)
        .passphrase(passphraseChars)
        .build();

OpenPgpEncryptionConfig encryption = OpenPgpEncryptionConfig.builder()
        .addRecipientPublicKeyRing(recipientPublicKeyRingBytes)
        .build();

SimpleJavaMail mail = SimpleJavaMail.fromDefaults();

Email email = mail.emailBuilder().startingBlank()
        .from("sender@example.com")
        .withRecipients(new Recipient(null, "receiver@example.com", Message.RecipientType.TO, null))
        .withSubject("Protected message")
        .withPlainText("Hello")
        .signWithOpenPgp(signing)
        .encryptWithOpenPgp(encryption)
        .buildEmail();
```

Verification and decryption are configured while converting incoming MIME:

```java
OpenPgpReceiveConfig receive = OpenPgpReceiveConfig.builder()
        .addVerificationKeyRing(senderPublicKeyRingBytes)
        .addDecryptionKeyRing(recipientSecretKeyRingBytes, passphraseChars)
        .build();

Email received = EmailConverter.emlToEmailWithOpenPgp(emlInputStream, receive);
OriginalOpenPgpDetails result = received.getOriginalOpenPgpDetails();
```

The result separates cryptographic signature validity from application trust. A valid signature does not establish that a key belongs to the claimed sender. Initial 10.0 support deliberately leaves WKD, keyserver lookup, key discovery, and trust policy to the application.

OpenPGP secret material and passphrases are redacted from `toString()` and excluded from Java serialization. Supply the sending configuration again after deserializing an `Email`.

Incoming OpenPGP processing follows at most two nested protection wrappers. That covers the normal sign-then-encrypt shape and bounds work on recursively protected input. A third wrapper is left unprocessed, the status records the nesting failure, and the exact outer protected EML remains available.

## Compatibility notes

- Explicit and generated Message-IDs remain stable across plain, DKIM, S/MIME, and OpenPGP messages.
- Bounce addresses and DSN options are SMTP envelope metadata and do not appear in conversion-only `MimeMessage` output.
- Angus-specific message subclasses are no longer part of the MIME/cryptography pipeline.
- Incoming S/MIME and OpenPGP protection is recognized before generic multipart parsing. Clear signed content remains readable for invalid or missing verification keys; encrypted bytes remain available when no decryption key matches.
- Ordinary conversion does not keep a second full EML snapshot. Cryptographically protected messages do keep their finalized or received protected bytes in memory because signatures and encryption cover that exact representation. Incoming protected bytes are also included when the converted `Email` is Java serialized; no temporary-file spill setting is used.
