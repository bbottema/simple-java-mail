# Define the DKIM property key format

- Status: Done
- Priority: Medium
- Work: Documentation or code enhancement

## Problem

The property guide said inline Base64 DKIM key data was accepted, but a non-file property value was converted directly to UTF-8 bytes without Base64 decoding.

## Plan

Version 9.2.0 adds two explicit formats: `file:` reads key bytes from a deliberate path and `base64:` decodes inline Base64 key bytes. Existing unprefixed values retain the pre-9.2 path-or-UTF-8-data behavior for compatibility. Explicit malformed values fail without repeating inline key material in the error.

## Acceptance criteria

- [x] Property documentation matches implementation exactly.
- [x] A test covers the chosen inline format.
- [x] Invalid values fail with a useful message.
- [x] Security and configuration pages show the same contract.

## Evidence

- Resolver: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/DkimPrivateKeyPropertyResolver.java`
- Integration: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/EmailGovernanceImpl.java`
- Tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/DkimPrivateKeyPropertyResolverTest.java`
- Documentation: `simplejavamail.org/src/pages/security.hbs` and `simplejavamail.org/src/pages/configuration.hbs`
- Migration and release notes: `simplejavamail.org/src/pages/migration-notes-9.2.0.hbs`, `README.md`, `RELEASE.txt`, and `RELEASE_HISTORY.md`
