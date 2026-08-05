# Define clearEmailValidator scope

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

`clearEmailValidator()` is described as turning validation off. It removes the configurable JMail address validator, while completeness, encoded-word, and CRLF/injection checks still run.

## Plan

Rename the example comment and list the checks that remain active. Link to `disablingAllClientValidation(...)` without presenting it as a routine recommendation.

## Acceptance criteria

- [ ] “Turn off validation” is removed.
- [ ] Remaining validation layers are explicit.
- [ ] Security consequences of broader disabling are clear.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1326-1336`
- Validation implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/MailerHelper.java:138-178`
