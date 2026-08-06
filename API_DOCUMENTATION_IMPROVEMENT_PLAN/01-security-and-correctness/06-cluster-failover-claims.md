# Remove unsupported cluster failover claims

- Status: Done
- Priority: High
- Work: Documentation

## Problem

The configuration page says a failed server is removed and remaining mailers continue transparently. Current behavior selects one pool, invalidates a failed transport, and rethrows. It does not retry, quarantine the server, or remove its pool.

## Plan

1. Rename the documented capability to clustered load balancing.
2. Describe failure behavior precisely.
3. Remove automatic removal and failover examples.
4. Explain that retries, circuit breaking, and health management are application-owned.
5. Treat true failover as a separate future feature if desired.

## Acceptance criteria

- [x] No page promises transparent failover or automatic server removal.
- [x] The failure example shows the exception reaching caller code.
- [x] Load-balancing behavior remains clearly documented.
- [x] Configuration and homepage cluster language agree.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:863`
- Pool selection: `modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchSupport.java:111`
- Failure path: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportRunner.java:97`
