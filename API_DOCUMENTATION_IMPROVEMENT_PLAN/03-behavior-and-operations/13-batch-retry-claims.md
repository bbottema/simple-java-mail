# Remove batch retry claims

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

The batch module is recommended for retries, but it has no automatic retry policy. The same page later says failures are propagated without recovery.

## Plan

Replace “retries” with the actual capabilities: asynchronous queueing, throughput, pooled connections, and clustered load balancing. State that retry policy remains application-owned.

## Acceptance criteria

- [ ] No batch-module description promises automatic retries.
- [ ] Failure and retry ownership are consistent across pages.
- [ ] Cluster wording agrees with the dedicated cluster correction item.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:803-805,918-923`
