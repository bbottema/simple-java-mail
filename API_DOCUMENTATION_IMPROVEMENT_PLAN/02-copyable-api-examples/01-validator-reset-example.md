# Correct the validator reset example

- Status: Done
- Priority: High
- Work: Documentation, source Javadocs and regression tests

## Problem

The example has `clearEmailValidator()` and `resetEmailValidator()` backwards. Reset restores `JMail.strictValidator()`; clear sets the configurable address validator to `null`.

## Plan

Swap the calls and comments, then distinguish clearing the address validator from disabling all client-side validation.

## Acceptance criteria

- [x] `resetEmailValidator()` is shown restoring the strict default.
- [x] `clearEmailValidator()` is shown removing address validation only.
- [x] The example agrees with API Javadocs and implementation tests.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:205`
- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:648` and `:769`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:773` and `:883`
- Regression coverage: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerBuilderTest.java:12`
