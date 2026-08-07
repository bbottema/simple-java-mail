# Remove batch retry claims

- Status: Done
- Priority: Medium
- Work: Documentation, Javadoc

## Problem

The batch module is recommended for retries, but it has no automatic retry policy. The same page later says failures are propagated without recovery.

## Resolution

Removed retry-oriented wording from the public Mailer Javadoc, the connection-reuse guidance and the 9.0 migration notes. The batch module is now described
in terms of asynchronous queueing, throughput, pooled connections and clustered load balancing. Failed sends are reported to the application, which owns any
retry policy.

## Acceptance criteria

- [x] No batch-module description promises automatic retries.
- [x] Failure and retry ownership are consistent across pages.
- [x] Cluster wording agrees with the dedicated cluster correction item.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs` and `migration-notes-9.0.0.hbs`
- Public API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java`
