# Explain synchronous and asynchronous failures

- Status: Done
- Priority: Medium
- Work: API, tests, documentation, release notes

## Problem

The future guidance implies all failures are represented by the returned `CompletableFuture`. Preparation and validation can throw before asynchronous scheduling, and synchronous sends can throw before returning an already-completed future.

## Resolution

Version 9.2.0 gives an asynchronous operation one normal failure channel. Defaults and overrides, validation, scheduling, conversion, connection and transport
failures now complete the returned `CompletableFuture` exceptionally. Preparation and validation still run on the calling thread; only their reporting path
changed. Synchronous calls continue to throw directly, and a null top-level argument remains an immediate contract violation.

The Java 8 implementation adds an internal failed-future helper and catches `RuntimeException` only around asynchronous operation preparation and scheduling.
Fatal `Error` instances are not translated. The same rule is applied to send, receipt send, simple batch and connection-test entry points.

Public Javadocs, the capabilities and diagnostics pages, the 9.2 migration guide, and both release-note sources now describe the same contract. GitHub issue
[#691](https://github.com/bbottema/simple-java-mail/issues/691) records the behavior change and its lineage through #148, #294 and #367.

## Acceptance criteria

- [x] Synchronous throws and asynchronous completion are distinguished.
- [x] Preparation/validation timing is explicit.
- [x] Diagnostics links back to the async handling example.
- [x] Tests cover synchronous validation, asynchronous validation and transport, scheduling failures across every async entry point, and the null-argument boundary.

## Evidence

- Public contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java`
- Tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/ResultHandlingTest.java`
- Documentation: `simplejavamail.org/src/pages/features.hbs`, `debugging.hbs`, and `migration-notes-9.2.0.hbs`
