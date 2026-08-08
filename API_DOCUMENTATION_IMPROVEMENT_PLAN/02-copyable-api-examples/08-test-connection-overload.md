# Correct the testConnection overload

- Status: Done
- Priority: Low
- Work: Documentation

## Problem

The async example referenced nonexistent `mailer.testConnection(email, true)`.

## Plan

Use `mailer.testConnection(true)` and handle its returned `CompletableFuture`. Explain that `true` selects asynchronous execution, while synchronous failures are thrown directly.

## Acceptance criteria

- [x] The example compiles.
- [x] The connection test is not shown taking an Email.
- [x] Its future and error behavior agree with the Mailer contract.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:489-520`
- API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java:48-57`
