# Migrating to Simple Java Mail 10.0

Simple Java Mail 10 separates MIME processing from the Jakarta Mail implementation that performs SMTP submission. Angus remains the default sending implementation, but provider-specific SMTP behavior now lives behind a replaceable adapter at the final transport boundary.

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

Conversion-only applications, or applications replacing Angus with another provider module, can exclude the default adapter. The exclusion also removes its transitive Angus implementation:

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

`Email`, `MimeMessage`, and EML conversion works without an SMTP provider on both the classpath and module path.

## Using another Jakarta Mail provider

Exclude `angus-mail-provider-module` as shown above and put the replacement provider module on the runtime path. Ordinary messages can use the generic transport fallback. Envelope sender and DSN requests require a matching `MailTransportAdapter`; unsupported capabilities fail before submission instead of being ignored. Removing Angus does not remove the need for some Jakarta Mail implementation when sending.

Third-party adapters implement `org.simplejavamail.api.mailer.spi.MailTransportAdapter` and are discovered with `ServiceLoader`. Register the implementation in `META-INF/services/org.simplejavamail.api.mailer.spi.MailTransportAdapter`, or use a JPMS `provides` directive. Adapter signatures expose Jakarta Mail and provider-neutral Simple Java Mail types only.

If no Jakarta Mail transport provider is available, the send fails with an error naming the provider/adapter dependency that must be added.

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

Email email = EmailBuilder.startingBlank()
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

## Protected-message storage

Finalized MIME is repeatable. Entities up to 1 MiB are retained in memory by default; larger entities spill to a temporary file and are deleted when the prepared mail is closed. Override the threshold with `org.simplejavamail.mime.finalization.memoryThresholdBytes` when application memory and temporary-storage policy require a different balance.

## Compatibility notes

- Explicit and generated Message-IDs remain stable across plain, DKIM, S/MIME, and OpenPGP messages.
- Bounce addresses and DSN options are SMTP envelope metadata and do not appear in conversion-only `MimeMessage` output.
- Angus-specific message subclasses are no longer part of the MIME/cryptography pipeline.
- Incoming S/MIME and OpenPGP protection is recognized before generic multipart parsing. Clear signed content remains readable for invalid or missing verification keys; encrypted bytes remain available when no decryption key matches.
