# Read delivery-report actions with comments

The maintainer approved this narrow repair and delegated its complete review workflow on 29 October 2026. Design, implementation and delivery to `codex/10.0.0` and applicable `codex/10.0.0-content` changes are authorized after independent combined-diff review and issue-lead acceptance. No release, deployment, history rewriting or unrelated cleanup is included.

Tracking: [#917](https://github.com/bbottema/simple-java-mail/issues/917), governed by ADR 0035 and ADR 0008. Baselines: main `ea31b5217a454ed4493332cc5318dcad86b85edd`, website `5b9d6c53bdeb1e4e427abe578146fd14f9dd05f3`. Preserve the existing staged Angus files, dirty root/website work and earlier scratch/plans. Baseline patches, working-file hashes and isolated verification source are retained in `.local/dsn-action-comments-20261029`.

Read exactly one recognized case-insensitive Action atom while ignoring valid external comments and whitespace. Require the end of the field after comments. Missing/unknown actions, quoted values, extra tokens, malformed comments and existing conflicting/duplicate fields retain their current unavailable/warning behavior. Preserve neighboring facts, source bytes, exact/protected representations and content ownership. No public API, property, CLI or warning model is added. ENVID trimming and other review findings remain outside this scope.

The delegated issue lead owns `DeliveryStatusReportParser.parseAction`, focused public conversion regressions, scoped ADR/release/website/OpenRewrite guidance, issue bookkeeping and exact cleanup additions. Verify red first, then focused converter controls using real JDK 11 and JDK 21 against the isolated reactor. Review every workflow surface and obtain a separate read-only combined-diff review before publishing. Publish website before parent gitlink; preserve the original index through a task-owned alternate index and stage only exact issue changes.

The cleanup script must retain its existing guards and allowlists. Syntax-check and run `-WhatIf`; no deletion is authorized. Keep the task scratch and this plan on exact cleanup allowlists, preserving the known older `node_modules` reparse-point blocker.

Completed and retired after independent review and issue-lead acceptance: main `c239fda20c435b9843d2438b387e0124b3babc55`, website `086da085647ebd6d9678f6f12cecd9910550aee9`. Issue #917 is closed/completed and Done. This untracked plan and its task scratch remain exact guarded cleanup targets; removal is deferred at the existing older `node_modules` reparse-point guard.
