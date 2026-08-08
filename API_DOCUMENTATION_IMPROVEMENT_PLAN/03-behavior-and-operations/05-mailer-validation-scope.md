# Define Mailer.validate scope

- Status: Done
- Priority: Medium
- Work: Documentation, Javadocs

## Problem

The website said `mailer.validate(email)` performed all checks or applied configured message rules. The API Javadoc also claimed that subject and
content were required and referred to undefined "NPM notification flags." In practice the method validates the supplied `Email` as-is for sender and
recipient presence, addresses and injection-sensitive fields. It does not apply mailer defaults or overrides, convert to MIME, or check final encoded
message size.

## Resolution

Documented the exact direct-validation scope, including strict and lenient modes. The API now distinguishes validation of the supplied instance from
the send path, which applies defaults and overrides before validation and performs MIME conversion and size enforcement afterward. Capabilities and
Get Started now use the same concrete wording.

## Acceptance criteria

- [x] Validation scope is listed concretely.
- [x] Defaults, overrides, conversion, and size checks are described separately.
- [x] Strict and lenient validation modes are distinguished.
- [x] Features and Get Started pages use consistent wording.

## Evidence

- Public API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java`
- Validation helpers: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/MailerHelper.java`
- Documentation: `simplejavamail.org/src/pages/features.hbs`
- Get Started: `simplejavamail.org/src/pages/download.hbs`
- Send preparation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java`
- MIME size enforcement: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/SessionBasedEmailToMimeMessageConverter.java`
