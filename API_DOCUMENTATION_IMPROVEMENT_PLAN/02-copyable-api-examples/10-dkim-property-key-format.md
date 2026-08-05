# Define the DKIM property key format

- Status: Planned
- Priority: Medium
- Work: Documentation or code enhancement

## Problem

The property guide says inline Base64 DKIM key data is accepted, but a non-file property value is currently converted directly to UTF-8 bytes without Base64 decoding.

## Plan

Choose one contract: document file paths/raw PEM text only, or add an explicit Base64 format/prefix and decoder. Avoid guessing based on content.

## Acceptance criteria

- [ ] Property documentation matches implementation exactly.
- [ ] A test covers the chosen inline format.
- [ ] Invalid values fail with a useful message.
- [ ] Security and configuration pages show the same contract.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:375-384`
- Duplicate reference: `simplejavamail.org/src/pages/configuration.hbs:407-415`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/EmailGovernanceImpl.java:238-243`
