# Explain synchronous and asynchronous failures

- Status: Planned
- Priority: Medium
- Work: Documentation, tests

## Problem

The future guidance implies all failures are represented by the returned `CompletableFuture`. Preparation and validation can throw before asynchronous scheduling, and synchronous sends can throw before returning an already-completed future.

## Plan

Document the boundary between immediate caller-thread failures and exceptional future completion. Provide handling examples for both modes.

## Acceptance criteria

- [ ] Synchronous throws and asynchronous completion are distinguished.
- [ ] Preparation/validation timing is explicit.
- [ ] Diagnostics links back to the async handling example.
- [ ] Tests cover an immediate validation failure and an asynchronous transport failure.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:503-517`
- Send path: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:401-410`
