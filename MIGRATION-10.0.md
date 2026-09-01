# Migrating to Simple Java Mail 10.0

Simple Java Mail 10 replaces process-wide mutable configuration with immutable configuration snapshots. It also separates MIME processing from the Jakarta Mail implementation that performs SMTP submission. Angus remains the default sending implementation, but provider-specific SMTP behavior now lives behind a replaceable adapter at the final transport boundary.

## The libraries now require Java 11

Simple Java Mail 10 no longer runs on Java 8. All library modules require Java 11 or newer. Applications that must stay on Java 8 should remain on the 9.x release line.

The `cli-module` artifact and standalone `sjm` command require Java 17 or newer.

After 10.0.0, the 9.x line is maintenance-only. It will not receive new features or routine dependency updates. Critical fixes may be backported when the change is small enough to keep the existing Java 8 and public API contracts intact.

## The CLI now has an optional local daemon

Existing one-shot commands remain valid and remain the default:

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

## Mailer validation now rehearses the effective message

In 9.x, `mailer.validate(email)` only ran the ordinary checks against the supplied `Email`. It did not apply that Mailer's defaults or overrides, build MIME, run signing or encryption, or enforce the encoded-size limit.

In 10.0, validation follows the Mailer's preparation rules. It applies defaults and overrides, checks the resulting email, builds the MIME message, runs configured S/MIME, OpenPGP, and DKIM processing, and checks the final encoded size. It does not open an SMTP connection and it does not change the supplied `Email`.

Choose between validation and rehearsal by what the caller needs back, not by validation depth or cost:

| Caller need | Use |
| --- | --- |
| Only allow execution to continue when preparation succeeds, or fail with an exception | `mailer.validate(email)` |
| Preview/export EML, inspect the effective Email or envelope, diagnose size, or retain the generated Message-ID | `MailRehearsal rehearsal = mailer.rehearse(email)` |

The two default methods perform the same preparation. `validate` delegates to `rehearse` and discards its result; its boolean return is always `true` after success and is never `false` for invalid input. A successful rehearsal has already validated the Email, so calling `validate` and then `rehearse` only runs the preparation pipeline twice.

```java
mailer.validate(email);       // only success or an exception matters
mailer.validate(email, true); // same behavior, explicitly
```

When you need the prepared result, use `rehearse(...)` directly:

```java
MailRehearsal rehearsal = mailer.rehearse(email);

Email effectiveEmail = rehearsal.getEffectiveEmail();
byte[] eml = rehearsal.getEmlBytes();
long encodedSize = rehearsal.getEncodedSize();
String messageId = rehearsal.getEmailId();
List<String> envelopeRecipients = rehearsal.getEnvelopeRecipients();
```

The EML byte array is returned defensively. A rehearsal is a snapshot, not a promise that a later send of the original `Email` will produce identical
bytes: generated dates, boundaries, Message-IDs, and cryptographic output can change when the message is prepared again.

Use the quicker overload when you want to check governance, ordinary validation, and base MIME construction without running message security or measuring the final encoded message:

```java
mailer.validate(email, false);
MailRehearsal baseMime = mailer.rehearse(email, false);
```

The boolean parameter is named `processSecurityAndValidateSize`. In the `false` mode, every result field remains available, but the EML and encoded size
describe the unsecured base MIME message and the configured size limit is not enforced. Raw `Email` checks remain available through
`MailerHelper.validate(email)`. Both Mailer rehearsal modes build MIME, so both need Angus Mail or another compatible Jakarta Mail implementation at
runtime. The `sjm validate` command uses the complete rehearsal and still makes no SMTP connection.

## Finalized EML can now remain byte-exact

This is additive; existing EML conversion keeps its normal behavior. Conversion parses a message into editable `Email` fields and later builds a fresh
MIME representation. That is still the right path when you want to change content, apply defaults or overrides, resolve embedded resources, or let Simple
Java Mail add DKIM, S/MIME, or OpenPGP/MIME protection.

Use the new exact starter when the supplied RFC 822 bytes are already the final outbound representation and even a semantically equivalent rebuild would
be wrong:

```java
byte[] eml = Files.readAllBytes(Path.of("ready-to-send.eml"));

Email email = mail.emailBuilder()
        .startingFromExactEml(eml)
        .withEnvelopeRecipients("actual-recipient@example.org")
        .withEnvelopeSender("bounces@example.org") // optional
        .buildEmail();

MailSubmissionReceipt receipt = mailer.sendMailAndGetReceiptSync(email);
```

`Email` remains the canonical type: all existing getters expose the parsed fields and every existing send API accepts the result. The copied EML bytes,
not those parsed values, remain authoritative for conversion, rehearsal, custom mailers, logging-only mode, synchronous and asynchronous sends, simple
batches, open connections, and pooled submission. Copying an exact email through `mail.emailBuilder().copying(email)` intentionally creates a composed
email and gives up that guarantee.

Exact submission deliberately bypasses Mailer defaults and overrides, the configured composed-email validator, dynamic embedded-resource resolution,
MIME rebuilding, and Simple Java Mail's signing and encryption steps. At least one explicit SMTP-envelope recipient is required because visible headers
cannot represent Bcc or relay routing reliably. Recipients append in call order without deduplication; the optional envelope sender replaces its previous
value. DSN notify and return options remain available on the exact builder because they belong to the SMTP envelope.

The input must be non-empty, parseable by Jakarta Mail, use canonical CRLF line endings, and end in CRLF. Every byte is retained, including header order,
folding, MIME boundaries, transfer encodings, `Bcc`, `Resent-Bcc`, and `Content-Length`, so the caller is responsible for supplying an already safe outbound
message. A missing Message-ID stays missing in the `Email`, rehearsal, receipt, and observer outcome. Full rehearsal and sending apply the configured
maximum message size to the supplied byte length; the `false` rehearsal/validation mode skips that size check without changing the bytes.

The bundled Angus adapter supports full-byte preservation. A replacement `MailTransportAdapter` must explicitly opt into
`ContentRequirement.PRESERVE_ALL_BYTES`; otherwise the attempt fails before submission. A `CustomMailer` continues to receive the usual `Email` and
`MimeMessage`, with that message backed by the exact bytes. The `InputStream` starter consumes its stream immediately but leaves closing it to the caller.
The legacy boolean `PreparedMail` constructor and `requiresStableContent()` remain available as deprecated compatibility shims; new adapters should use
the three-valued `ContentRequirement` contract so protected content and fully exact content are not conflated.

The CLI byte-array overload treats its argument as a file, including through the local daemon:

```text
sjm send --email:startingFromExactEml ready-to-send.eml --email:withEnvelopeRecipients actual-recipient@example.org --mailer:withSMTPServer smtp.example.org 587 user password
```

Relative paths resolve against the invoking client's working directory. See the exact EML feature guide and
`modules/simple-java-mail/src/test/java/demo/ExactEmlSendDemoApp.java` for a manually runnable Java example.

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

## Authenticated SOCKS bridge ports are now automatic

Authenticated SOCKS proxies still use a small loopback-only bridge between Jakarta Mail and the remote proxy. In 9.x that bridge used port `1081` by default, so applications with more than one authenticated-proxy Mailer had to assign a different bridge port to each one.

In 10.0 the default is `0`, which asks the operating system for an available loopback port whenever the bridge starts. Separate Mailers can therefore use authenticated proxies at the same time without coordinating ports. Simple Java Mail writes the selected port to the effective Session before opening the SMTP connection. If the bridge stops and later starts on another port, the Session is updated again.

Existing positive values remain fixed. Keep a setting like this only when your application really needs a predictable local port:

```java
Mailer mailer = mail.mailerBuilder()
        .withProxy("proxy.example.com", 1080, "user", "password")
        .withProxyBridgePort(7777)
        .buildMailer();
```

The equivalent property is `simplejavamail.proxy.socks5bridge.port=7777`. Remove it, or set it to `0`, to use automatic allocation.

## Spring uses context-local configuration

Spring Boot applications now discover Simple Java Mail automatically whenever `spring-module` is on the classpath. The recommended dependency is the new
starter, which brings in `spring-module` while leaving Boot, Spring Framework, and SLF4J to the application's existing Boot dependency management:

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail-spring-boot-starter</artifactId>
    <version>10.0.0</version>
</dependency>
```

No `@Import` is needed in a Boot application. An existing explicit `@Import(SimpleJavaMailSpringSupport.class)` remains safe and still produces one bean
of each type. Plain Spring applications continue to use that explicit import.

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

The three auto-configured beans back off independently by type. A custom `SimpleJavaMailConfig` feeds the default factory and Mailer, a custom
`SimpleJavaMail` feeds the default Mailer, and a custom `Mailer` replaces only that Mailer. Spring closes its own default Mailer with the context; it does
not add a lifecycle rule to a replacement. The replacement follows the destroy method declared or inferred by its own Spring bean definition; use
`@Bean(destroyMethod = "")` only when the application deliberately owns shutdown elsewhere.

Boot now creates the default beans even when no SMTP properties have been supplied. In that case the fallback host is `localhost`, which is convenient
for a local development SMTP server but does not make sending succeed by itself. Configure `simplejavamail.smtp.host` before sending in any environment
without a local SMTP service. Use Spring Boot's standard exclusion when the application should not create any of these defaults:

```properties
spring.autoconfigure.exclude=org.simplejavamail.springsupport.SimpleJavaMailAutoConfiguration
```

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

The adapter's `sendMessage(...)` method now returns a non-null `MailTransportResult`, not a nullable `SmtpServerResponse`. Return `MailTransportResult.accepted(...)` after a successful submission, `MailTransportResult.unknown(...)` only when a successful opaque send cannot report server acceptance, and `MailTransportResult.failed(...)` after catching a Jakarta Mail submission failure. The failure factory copies recipient arrays from `SendFailedException` and retains the original exception for the public send failure.

If no Jakarta Mail implementation is available, MIME conversion and sending fail with an error naming the dependency that must be added. If an implementation can create MIME but cannot submit mail, sending reports the missing provider or adapter separately.

## Sending can name its execution mode explicitly

New code can make its blocking and failure behavior visible at the call site:

```java
mailer.sendMailSync(email);                         // returns void; failures are thrown here
CompletableFuture<Void> send = mailer.sendMailAsync(email);

MailSubmissionReceipt receipt = mailer.sendMailAndGetReceiptSync(email);
CompletableFuture<MailSubmissionReceipt> receiptFuture =
        mailer.sendMailAndGetReceiptAsync(email);
```

The explicit methods ignore the Mailer's configured `async()` default. `Sync` methods finish on the calling thread and throw preparation or submission failures directly. `Async` methods return a future that covers preparation, scheduling and submission; operational failures complete that future exceptionally. A null Email remains an immediate `IllegalArgumentException` in either mode.

This is additive. Existing `sendMail(email)`, `sendMailAndGetReceipt(email)`, and their boolean overloads remain supported. The one-argument methods still follow the Mailer's configured async default, and the boolean overloads still support runtime mode selection. They now delegate to the same explicit implementation paths rather than maintaining separate send behavior.

## Submission receipts now describe partial and uncertain outcomes

`sendMailAndGetReceipt(...)` is no longer an Angus-only view of the final SMTP response. Its `MailSubmissionReceipt` now always has a provider-neutral `MailSubmissionStatus`, the effective Message-ID and timestamp, immutable accepted, valid-unsent and invalid recipient lists, and an optional `SmtpServerResponse` when the provider exposes one.

| Status | Meaning |
| --- | --- |
| `ACCEPTED` | Every submitted envelope recipient is known to have been accepted for SMTP submission. |
| `PARTIALLY_ACCEPTED` | At least one recipient was accepted, but the attempt did not complete as a clean success for every recipient. Any provider-supplied unsent or invalid groups identify the remainder. The call fails with `MailSubmissionException`. |
| `REJECTED` | The transport reports that no recipient was accepted. The call fails with `MailSubmissionException`. |
| `UNKNOWN` | The send path cannot determine acceptance. A custom mailer or logging-only send can return this successfully; an ambiguous transport failure throws `MailSubmissionException` with this status. |

Handle the synchronous path like this:

```java
try {
    MailSubmissionReceipt receipt = mailer.sendMailAndGetReceiptSync(email);
    checkpoint(receipt.getEmailId(),
            receipt.getStatus(), receipt.getSubmittedAt());
} catch (MailSubmissionException failure) {
    MailSubmissionReceipt outcome = failure.getSubmissionReceipt();

    audit(outcome.getAcceptedRecipients(),
            outcome.getValidUnsentRecipients(),
            outcome.getInvalidRecipients(),
            failure.getCause()); // original MessagingException

    if (outcome.getStatus() == MailSubmissionStatus.UNKNOWN) {
        markForReconciliation();
    }
}
```

With asynchronous sending, the future completes exceptionally with `MailSubmissionException`. Inspect it as the future's cause. With `withOpenConnection`, `MailSender.sendMailAndGetReceipt(...)` throws the same exception directly. Pooled and non-pooled transports use the same model.

`UNKNOWN` does not mean rejected: the connection may have failed after the server accepted some or all recipients. Do not automatically retry an unknown outcome unless your design prevents or tolerates duplicates. Likewise, retrying an entire `PARTIALLY_ACCEPTED` message can duplicate delivery to the accepted recipients. None of these states proves final mailbox delivery; use DSNs, bounces, or provider webhooks for that.

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
