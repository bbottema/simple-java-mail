# Define clearEmailValidator scope

- Status: Done
- Priority: Medium
- Work: Documentation and source Javadocs

## Problem

`clearEmailValidator()` is described as turning validation off. It removes the configurable JMail address validator, while completeness, encoded-word, and CRLF/injection checks still run.

## Plan

Rename the example comment and list the checks that remain active. Link to `disablingAllClientValidation(...)` without presenting it as a routine recommendation.

## Acceptance criteria

- [x] “Turn off validation” is removed.
- [x] Remaining validation layers are explicit.
- [x] Security consequences of broader disabling are clear.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1345`
- API contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:769`
- Validation implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/MailerHelper.java:58` and `:139`
