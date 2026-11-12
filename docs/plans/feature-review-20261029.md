Delivered on codex/10.0.0 at 1a2a1d74a4f172780a899af364f70121646fad65 and codex/10.0.0-content at a0f5007e10f69cbb10d99c8ac7580aaf23262783. Approved issues #900 through #907 are closed/completed and Done; configuration-source maintenance has no issue. Only guarded scratch/plan cleanup remains pending because the existing cleanup script stops at an older node_modules reparse point.

# Feature review coordination, 29 October 2026

The maintainer approved design, delegated implementation and complete review-workflow delivery for findings 1, 2, 4, 7, 9, 10, 11 and 12. After the layer explanation, the maintainer also approved finding 6 and explicitly delegated its complete workflow. Publish accepted changes on `codex/10.0.0` and, where applicable, `codex/10.0.0-content`; no release, deployment or history rewriting is included. Finding 12 is maintenance without a new GitHub issue, as requested.

Execution baseline: main `39c69206bfe7ad2e2d2afd70b5f8ba818c9f40ea`, website `be91411fa78a23cdd5fd41e8e1de96c4d009c422`. Preserve the existing staged and unstaged Angus research, cleanup script changes, website narratives/styles/tests and all unrelated scratch/worktrees.

| Findings | Tracking | Worker ownership | Bounded scratch |
| --- | --- | --- | --- |
| 1, 7: authentication secrecy and complete replies | #901, #902 | SOCKS authentication helper, focused tests, ADR 0010 | `.local/feature-review-auth-20261029` |
| 6: transparent established SOCKS tunnels | #907 | SOCKS establishment boundary and focused integration tests, ADR 0010 | `.local/feature-review-tunnel-timeout-20261029` |
| 2: truthful final retry receipt | #900 | send diagnostics and focused retry tests, ADRs 0011/0012/0030 as needed | `.local/feature-review-retry-20261029` |
| 4, 9: opaque S/MIME facts and atomic key cache | #905, #906 | S/MIME processing/cache and focused tests, ADRs 0008/0006 | `.local/feature-review-smime-20261029` |
| 10: delivery-report offset hours | #903 | DSN parser and focused tests, ADR 0035's date section | `.local/feature-review-dsn-20261029` |
| 11: atomic required calendar inputs | #904 | calendar setter and focused tests, ADR 0031, affected CLI caches | `.local/feature-review-calendar-20261029` |
| 12: required configuration-source references | No issue | ConfigLoader and focused source tests | `.local/feature-review-config-inputs-20261029` |

The lead owns shared release history, ADR index, OpenRewrite plans, website feature/migration pages and cleanup allowlists. Workers return applicable documentation changes or a no-change rationale. They use isolated source snapshots with owned overlays for Maven so concurrent checks do not share target directories. All behavioral repairs start with a focused failing regression, then relevant controls on Java 11 and the modern build JDK.

The lead reviews actual combined changes and evidence before serialized selective commits, publication and issue completion. GitHub issues and commit messages contain behavior, usage and architectural references, never verification prose. No worker may publish before lead acceptance. Preserve the pre-existing index when committing.

Findings 3, 5 and 8 remain design/analysis only. Separate custom recipient headers from typed delivery overrides for finding 3. Analyze remote SOCKS setup ownership/cancellation for finding 5, preserving established pooled sockets. Analyze daemon authenticated dispatch, application completion waits and bounded stop for 8. A broader SOCKS review is requested independently of the narrow approved fixes. Finding 13 is an ADR reconciliation: establish how the already accepted source-preservation principle governs malformed optional address fields; no broader outgoing validation change is approved.

Finding 6 removes the accidental bridge-side read timeout on entering transparent forwarding. Angus retains the configured client-side SMTP read timeout. No new builder setting, per-hop idle policy or listener-transience change is approved. Pending remote setup ownership and cancellation remain finding 5.

Task scratch and disposable issue plans enter the exact cleanup allowlists. Existing cleanup blockers and unrelated entries remain intact. Retire this coordination plan after the approved repairs and their useful decisions are delivered.
