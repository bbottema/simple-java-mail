# Use a valid S/MIME content cipher

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

The configuration example uses `DES_EDE3_WRAP`, a key-wrap OID, where the API expects a content-encryption cipher.

## Plan

Use the recommended `AES256_CBC`. Mention `DES_EDE3_CBC` only as a legacy compatibility choice if still supported.

## Acceptance criteria

- [ ] The example creates a usable content encryptor.
- [ ] The preferred algorithm matches the API recommendation.
- [ ] The example is covered by an S/MIME test or fixture.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:397-405`
- API: `modules/core-module/src/main/java/org/simplejavamail/api/email/config/SmimeEncryptionConfig.java:73-92`
- Implementation: `modules/smime-module/src/main/java/org/simplejavamail/internal/smimesupport/SMIMESupport.java:542-551`
