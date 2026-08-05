# Correct the testConnection overload

- Status: Planned
- Priority: Low
- Work: Documentation

## Problem

The async example references nonexistent `mailer.testConnection(email, true)`.

## Plan

Replace it with `mailer.testConnection(true)` and explain that the boolean selects asynchronous execution.

## Acceptance criteria

- [ ] The example compiles.
- [ ] The connection test is not shown taking an Email.
- [ ] Its future and error behavior agree with the Mailer contract.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:509-511`
- API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java:48-57`
