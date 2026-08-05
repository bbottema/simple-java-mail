# Correct the validator reset example

- Status: Planned
- Priority: High
- Work: Documentation

## Problem

The example has `clearEmailValidator()` and `resetEmailValidator()` backwards. Reset restores `JMail.strictValidator()`; clear sets the configurable address validator to `null`.

## Plan

Swap the calls and comments, then distinguish clearing the address validator from disabling all client-side validation.

## Acceptance criteria

- [ ] `resetEmailValidator()` is shown restoring the strict default.
- [ ] `clearEmailValidator()` is shown removing address validation only.
- [ ] The example agrees with API Javadocs and implementation tests.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:206-209`
- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:643-769`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:773-885`
