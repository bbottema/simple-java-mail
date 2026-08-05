# Separate startingBlank from mailer defaults

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

`EmailBuilder.startingBlank()` is described as leaving the builder with defaults. It starts blank; mailer defaults and overrides are applied later during completion or send-time governance.

## Plan

Explain builder initialization, `buildEmail()`, `buildEmailCompletedWithDefaultsAndOverrides()`, and send-time governance as separate stages.

## Acceptance criteria

- [ ] `startingBlank()` is described as blank.
- [ ] Eligibility for later mailer defaults is not confused with already-applied values.
- [ ] The two build paths are contrasted with a small example.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:203-207`
- Builder creation: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailStartingBuilderImpl.java:65`
- Build behavior: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java:397`
