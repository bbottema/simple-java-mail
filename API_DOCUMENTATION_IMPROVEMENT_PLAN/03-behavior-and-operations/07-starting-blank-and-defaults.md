# Put governance options after email starters

- Status: Done
- Priority: Medium
- Work: API, tests, Javadocs, website documentation, and release notes

## Problem

`EmailBuilder.startingBlank()` is described as leaving the builder with defaults. It starts blank; mailer defaults and overrides are applied later during completion or send-time governance.

The legacy pre-start opt-outs also contain two propagation defects: `startingBlank()` drops `ignoreOverrides`, while `copying(Email)` calls `ignoringOverrides(...)` twice and never propagates `ignoreDefaults`.

## Plan

Explain builder initialization, `buildEmail()`, `buildEmailCompletedWithDefaultsAndOverrides()`, and send-time governance as separate stages.

Keep governance opt-outs on `EmailPopulatingBuilder`, after any starter has selected what kind of email is being built. Remove the historical pre-start API that existed to suppress eager builder defaults before the 8.0 governance overhaul.

## Acceptance criteria

- [x] `startingBlank()` is described as blank.
- [x] Eligibility for later mailer defaults is not confused with already-applied values.
- [x] The two build paths are contrasted with a small example.
- [x] No-argument governance opt-outs are available after every email starter.
- [x] The obsolete pre-start governance API is removed for 9.2.0.

## Resolution

- Added `EmailPopulatingBuilder.ignoringDefaults()` and `ignoringOverrides()` as Java convenience methods; the conditional boolean and CLI forms remain.
- Removed governance state and methods from `EmailStartingBuilder`, along with `EmailBuilder.ignoringDefaults()`.
- Updated internal conversion and test call sites to start the email operation first.
- Added behavioral tests showing that the follow-up options control later default and override application.
- Documented the source-level migration in the 9.2.0 notes and release notes.
- Recorded the defects, their pre-8.0 lineage, and the chosen API correction in GitHub issue #689.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:203-207`
- Builder creation: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailStartingBuilderImpl.java:65`
- Build behavior: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java:397`
