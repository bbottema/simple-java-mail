# Use a valid S/MIME content cipher

- Status: Done
- Priority: Medium
- Work: Documentation

## Problem

The configuration example used `DES_EDE3_WRAP`, a key-wrap OID, where the API expects a content-encryption cipher.

## Plan

The configuration and security examples now use the recommended `AES256_CBC`. `DES_EDE3_CBC` is mentioned only as a legacy compatibility choice. The property-default test carries the exact algorithm names into `SmimeEncryptionConfig`, and the existing end-to-end alternative-algorithm test now encrypts and decrypts with `AES256_CBC`.

## Acceptance criteria

- [x] The example creates a usable content encryptor.
- [x] The preferred algorithm matches the API recommendation.
- [x] The example is covered by an S/MIME test or fixture.

## Evidence

- Issue: `https://github.com/bbottema/simple-java-mail/issues/684`
- Documentation: `simplejavamail.org/src/pages/configuration.hbs` and `simplejavamail.org/src/pages/security.hbs`
- API: `modules/core-module/src/main/java/org/simplejavamail/api/email/config/SmimeEncryptionConfig.java:73-92`
- Implementation: `modules/smime-module/src/main/java/org/simplejavamail/internal/smimesupport/SMIMESupport.java:542-551`
- Property mapping: `modules/simple-java-mail/src/test/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl2Test.java`
- End-to-end encryption: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerLiveTest.java`
