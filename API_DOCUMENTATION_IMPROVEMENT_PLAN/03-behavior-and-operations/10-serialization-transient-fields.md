# Overhaul Email serialization

- Status: Done
- Priority: Medium
- Work: API, compatibility handling, tests, Javadocs, website documentation, and release notes

## Problem

The serialization section lists removed `Email.dkimPrivateKeyInputStream` and does not accurately reflect current transient state. More importantly,
native Java serialization silently drops every attachment `DataSource`, the forwarded `MimeMessage`, and S/MIME signing configuration. A restored
`Email` therefore looks intact but can no longer reproduce the message that was serialized.

## Plan

Make Java serialization a send-ready content snapshot. Materialize resource data into repeatable byte sources, preserve forwarded MIME content and
S/MIME signing configuration, and define a clear compatibility failure for attachment bytes that pre-9.2.0 streams never contained. Preserve content
rather than promising to reconstruct the runtime behavior of custom `DataSource` implementations.

## Acceptance criteria

- [x] Resource bytes and MIME metadata survive a Java serialization round trip.
- [x] Forwarded MIME content and S/MIME signing configuration survive and can still be sent.
- [x] Custom `DataSource` implementations are documented and tested as read-only content snapshots, not reconstructed objects.
- [x] A genuine 9.1.7 fixture remains inspectable and fails clearly only when unavailable legacy content is used.
- [x] Javadocs, feature documentation, migration notes, and release notes describe the same contract and security boundary.

## Resolution

- Added versioned custom serialization to `Email` and `AttachmentResource` without changing their existing `serialVersionUID` values.
- Resource input streams are consumed and closed during serialization, then restored as independent, repeatable, read-only byte-backed data sources.
- Forwarded messages are stored as RFC 822 bytes and restored with a neutral Jakarta Mail session.
- `SmimeSigningConfig` and `Pkcs12Config` are now serializable, including the key material and passwords needed to preserve signing behavior.
- Pre-9.2.0 resource metadata remains readable. Accessing bytes those releases never serialized now produces an explicit version-specific error instead
  of a later null failure.
- Added unit coverage for custom, pre-encoded, embedded and decrypted resources; custom source behavior; unreadable sources; forwarded messages;
  S/MIME configuration; and legacy streams. Added a local SMTP round trip that signs and sends the restored email.
- Recorded the behavior, historical design lineage, migration impact, and sensitive-data warning in GitHub issue #690.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs` and `simplejavamail.org/src/pages/migration-notes-9.2.0.hbs`
- Models: `modules/core-module/src/main/java/org/simplejavamail/api/email/Email.java` and `AttachmentResource.java`
- Sensitive configuration: `modules/core-module/src/main/java/org/simplejavamail/api/email/config/SmimeSigningConfig.java` and
  `modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/Pkcs12Config.java`
- Compatibility and round-trip tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/api/email/EmailSerializationTest.java`
- Send behavior: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/EmailSerializationSmtpTest.java`
- Legacy fixture: `modules/simple-java-mail/src/test/resources/serialization/email-9.1.7.base64`
