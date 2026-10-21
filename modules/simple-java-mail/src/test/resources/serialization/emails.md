# Released Email construction fixtures

`email-9.3.6-plain.base64` and `email-9.3.6-builder.base64` were serialized on JDK 11.0.16.1 using the published 9.3.6 artifacts
from Maven Central, rather than the current checkout's artifacts with the same development version.

| Artifact | SHA-256 |
| --- | --- |
| core-module-9.3.6.jar | eee16e53c9daf86c28e8351e0bbb50ad17cfc28bc6643b16429e51a65cd18920 |
| simple-java-mail-9.3.6.jar | e530fbf3d80052b833342b6a78de630f203d94d827db23dd56baeedb82690113 |

Both snapshots start with this released API:

```java
EmailPopulatingBuilder builder = EmailBuilder.startingBlank()
    .from("Legacy sender", "sender@example.test")
    .withRecipients(new Recipient("Legacy recipient", "recipient@example.test", RecipientType.TO, null))
    .withSubject("Released 9.3.6 snapshot")
    .withPlainText("Released body");
```

The plain fixture serializes `new Email(builder)`; the builder fixture serializes `builder.buildEmail()` with ObjectOutputStream and encodes
its bytes with Base64. The distinction tests ADR 0031's construction boundary independently from the retained Email serialization format:
both remain inspectable, while the library-created subtype retains its supported runtime behavior.


`email-9.3.6-notifications.base64` was generated on JDK 11.0.16.1 against the same published artifact hashes above.
It contains one delivery recipient and explicit named disposition/return destinations (`Legacy read <read@example.org>`
and `Legacy return <return@example.org>`), both requested. It tests upgrading the released scalar serialized fields to
immutable singleton destination lists without changing the stored field types or Email serial identity.
