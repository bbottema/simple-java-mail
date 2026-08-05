# Explain OAuth2 token ownership

- Status: Planned
- Priority: Medium
- Work: Documentation

## Gap

OAuth2 examples show an access token in the password position but do not explain that acquisition and refresh are entirely caller-owned. There is no token supplier or refresh hook in Mailer.

## Plan

State that callers provide a current access token, not a refresh token, and must rebuild or update their mailer/session strategy when it expires. Point to provider SDKs without adding provider-specific flows.

## Acceptance criteria

- [ ] Access and refresh tokens are distinguished.
- [ ] Token expiry ownership is explicit.
- [ ] No text implies automatic refresh.
- [ ] Reusable-mailer guidance acknowledges token lifetime.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:212-221`
- Session setup: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:180-191`
