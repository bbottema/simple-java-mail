# Stop a simple batch before another source callback

Retired after delivery: implementation `b7aa0f553e574731f98ba90d5880fbdd43ed251d` and shared guidance `846cf560c1403daaf42db4c8709ec64597bc534d` are published on `codex/10.0.0`; final main publication is `7e6d1eefb82ff1483ef4489905907f3853a6c9a6`, including exact cleanup references. The website companion is `e82ab2fb32280681353ba5198c340e18a18217e6`. #943 is closed/completed and Done in milestone 10.0.0. No implementation, review, documentation or issue work remains.

Only bounded disposal remains. Automatic approval review rejected deletion of this round's exact scratch directory and retired plan with `blocked by policy`; no deletion occurred. The complete cleanup-script dry run separately stops at the older `.local/tls-inspection-20261030/source/simplejavamail.org/node_modules` reparse point. Preserve its guards and unrelated targets; exact task scratch and plan paths and manual deletion statements are already registered.

The maintainer accepted finding 4's concrete design and said to implement it on 1 November 2026, continuing the review workflow. This authorizes the bounded implementation, focused checks, companion documentation, reviewed selective commits, publication on `codex/10.0.0` and applicable `codex/10.0.0-content`, and issue completion. No release, deployment or history rewrite is included. The exact-EML discussion remains design-only; finding 2 stays deferred, and there is no DKIM builder expansion.

Baseline: main `653996920cf6d146c277ea3199295ed104de3195`, website `af49224b3945427cf21000264aaeea23750c306b`. Preserve the existing root/nested index and working-tree changes, old plans/scratch and registered worktrees. Current statuses, index copies and binary patches are under `.local/batch-cancel-boundary-20261101/preservation`.

Tracking: [#943](https://github.com/bbottema/simple-java-mail/issues/943), milestone 10.0.0 and existing project Simple Java Mail 10.0.0. The exact project item is `PVTI_lAHOACBIhs4BggiOzhFHaxI`, currently In Progress.

## Accepted behavior and impact map

`batch.requestCancellation()` must be observed before SJM enters another iterator callback. A database-backed source must not receive a new `hasNext()` after the stop is already known. Check the same existing control for deadline expiry; do not force-stop a callback already running. Preserve completed per-email outcomes and leave untouched emails without outcomes. Cancellation requested by the final email's observer yields a cancelled batch without consulting the source for exhaustion. Existing first-failure and cleanup ordering remain authoritative.

| Path/scenario | Required result |
| --- | --- |
| Main `SendMailsInSimpleBatchClosure` initial iterator, logging-only, CustomMailer and SMTP loops | Check before source callbacks, retaining lazy order, unchanged API and empty-source connection behavior. |
| `FailoverSharedSends` initial and post-email iteration, including retained route selection before `next()` | The same stop boundary applies without replaying a completed email or reviving cancelled work during recovery. |
| Inline success observer cancels, with additional source work remaining or source already exhausted | No additional source call; current outcome stays successful, batch reports cancellation after cleanup. |
| Cancellation/deadline inside a source callback or while connection selection is running | A completed callback cannot cause entry into another source callback after the stop becomes known. Preserve exception identity and first-failure rules. |
| Uncancelled, empty, failing source and safe-retry controls | Preserve established callbacks, outcomes, first failure, lazy ordering, cleanup and retry/failover semantics. |

`fix_auth_setup` is reused as the implementation worker and owns the two main batch implementations, a narrow shared internal helper only if it earns its place, dedicated regressions/fixtures, relevant public Mailer Javadoc and ADR 0017. The worker is not alone: preserve concurrent work and do not revert others. Use a task-owned export/targets under `.local/batch-cancel-boundary-20261101/implementation`, with real Java 11 and 21 and no shared target or installed SJM dependency. Start with a behavioral regression, then apply the smallest ordering repair and run selected meaningful controls. Windows builds skip the license formatter; use the established startup Byte Buddy agent for Mockito controls when needed while retaining JaCoCo.

`accepted_review_docs` owns release history, ADR index, the two OpenRewrite inventories and applicable website execution/failover guidance. Inspect README, Journal, case studies, downloadable examples, CLI/cache and concurrency documentation/artwork; update affected surfaces or report why unchanged. The lead owns this plan, cleanup script, independent diff/evidence review and publication coordination. Implementation and shared-documentation ownership do not overlap.

Before publication, the lead reviews the actual combined diff, final source freshness, focused Java 11/21 evidence, generated Javadoc/help implications and affected rendered website pages/links. Publish the website companion before the parent gitlink. Workers wait for a serialized delivery checkpoint; this is coordination within standing authorization. Issue/commit prose contains behavior, usage and ADR references, never testing/verification evidence. After delivered acceptance, add an after-usage completion comment, close completed and set the existing project item Done.

Stop owned helpers and retire the completed plan after delivery. Track exact disposable paths in the existing guarded cleanup allowlists and validate syntax/dry run. Do not run earlier cleanup rounds or retry an action already rejected by automatic approval review. Preserve exact manual deletion statements if disposal remains blocked.
