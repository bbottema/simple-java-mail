Delivered on codex/10.0.0 at 929b1699cecf39708497ff5bab6dd4824335cbe5 and codex/10.0.0-content at 841614b4825117ccde6bb8c57119e36f71ccec41. Issues #914 and #915 are closed/completed and Done. Only guarded scratch/plan cleanup remains pending at the existing older node_modules junction; all original feature-review findings have been delivered.

# Daemon control and malformed address import

The maintainer approved both remaining designs and explicitly delegated their complete review workflows on 29 October 2026. Design, implementation and delivery to `codex/10.0.0` and applicable `codex/10.0.0-content` changes are authorized after lead review. No release, deployment or history rewriting is included.

Baseline: main `101cfbb4ab460979dfa9d8500c43039e5229ecb7`, website `e93b4f4b940f6278a7cd5e7e7bd108661ec63baa`. Preserve existing staged/unstaged Angus research, cleanup-script changes, website narratives/styles/tests and all older scratch/worktrees. The original staged patch is retained under `.local/feature-review-daemon-address-delivery-20261029`.

| Finding | Approved behavior | Ownership |
| --- | --- | --- |
| 8, daemon management starvation (#915) | Keep existing application workers and split short authentication/control handling from bounded execution-reply waits. Reserve reply capacity before new command admission; return authenticated BUSY on overload while preserving control admission, ledger deduplication, accepted work after disconnect, bounded stop and ambiguous outcomes. No new wire endpoint or public setting. | Configuration/integrations worker: daemon lifecycle/admission/channel handling, focused unit/process tests and ADR 0019. Scratch `.local/feature-review-daemon-control-20261029`; detailed plan `915-daemon-control-admission.md`. |
| 13, malformed incoming address fields (#914) | Extend the existing tolerant Recipient fallback to MIME/EML From, To/Cc/Bcc and Reply-To extraction. Preserve the complete offending address text after narrow grammar failure, readable neighboring content and existing cardinality/missing-value rules. Ordinary outgoing validation still sees it. Keep Return-Path's separate contract, exact bytes and content/codec/protection failures unchanged. | Import/conversion worker: parser/mapper boundary, focused public conversion/validation tests, ADR 0035 and applicable Javadocs. Scratch `.local/feature-review-address-import-20261029`. |

The lead owns shared release history, ADR index, OpenRewrite research/plan, website pages and cleanup allowlists. Workers return a surface matrix, proposed shared prose and no-change rationales, and never overwrite another worker's files. CLI metadata regeneration is coordinated if an actual builder Javadoc input changes.

Use isolated snapshots for focused failing regressions and fresh reactor checks. Library compatibility uses real Java 11 plus a modern JDK; CLI/process checks use Java 17 or newer. The daemon packet records concrete channel ownership, admission and shutdown races before refactoring. Root reviews actual combined changes and evidence before selective commits, website-first publication, issue completion and Done status. Do not publish verification evidence in GitHub issues/comments or commit messages.

The separate **SJM SOCKS investigation** task remains responsible for the broader protocol/lifecycle scrutiny. Previously delivered findings and issues #900 through #909 are complete. Existing cleanup remains deferred at the older `node_modules` reparse-point guard; preserve all its safeguards and record exact new task targets.
