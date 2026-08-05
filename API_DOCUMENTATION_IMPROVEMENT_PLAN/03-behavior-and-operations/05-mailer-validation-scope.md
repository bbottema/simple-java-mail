# Define Mailer.validate scope

- Status: Planned
- Priority: Medium
- Work: Documentation, Javadocs

## Problem

The website says `mailer.validate(email)` performs all checks or applies configured message rules. It validates the supplied Email for completeness, addresses, and injection, but does not apply mailer defaults/overrides or check final serialized MIME size.

## Plan

Enumerate the actual validation stages and explain which send-time checks are outside `Mailer.validate`.

## Acceptance criteria

- [ ] Validation scope is listed concretely.
- [ ] Defaults, overrides, conversion, and size checks are described separately.
- [ ] Features and Get Started pages use consistent wording.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1336`
- Additional wording: `simplejavamail.org/src/pages/download.hbs:76`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:460-477`
