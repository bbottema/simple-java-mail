Delivered on codex/10.0.0 at 101cfbb4ab460979dfa9d8500c43039e5229ecb7 and codex/10.0.0-content at e93b4f4b940f6278a7cd5e7e7bd108661ec63baa. Issues #908 and #909 are closed/completed and Done. Only guarded scratch/plan cleanup remains pending at the existing older node_modules junction; remaining review decisions are findings 8 and 13.

# Typed delivery validation and pending SOCKS setup

The maintainer approved findings 3 and 5 and delegated their complete review workflows on 29 October 2026. Publish accepted repairs on `codex/10.0.0` and applicable website changes on `codex/10.0.0-content`, with lead review before delivery. No release or deployment is included.

Baseline: main `1a2a1d74a4f172780a899af364f70121646fad65`, website `a0f5007e10f69cbb10d99c8ac7580aaf23262783`. Preserve the three existing staged Angus research paths, their unstaged companions, the dirty cleanup script and all unrelated nested website work.

| Work | Approved behavior | Ownership |
| --- | --- | --- |
| #908, typed delivery override validation | Apply configured address validation and relevant injection checks to `withOverrideReceivers` values. Raw custom recipient headers retain the expert mailbox-validation escape, general injection checks and mandatory locks. Preserve ordinary validation, warnings-only, occurrences and exact EML contracts. | Composition worker: validation implementation, focused tests, affected public Javadocs, ADRs 0002/0014, genuine CLI caches. Scratch `.local/feature-review-envelope-validation-20261029`. |
| #909, pending authenticated SOCKS setup | Own both sockets before blocking setup, propagate exact local-peer closure to its remote socket, and preserve transparent forwarding, established pooled connections and existing client connection budgets. | SOCKS worker: early socket ownership contract, bridge/session/pump lifecycle and tests, ADRs 0010/0017. No additional bridge timers or Session timeout plumbing. Scratch `.local/feature-review-pending-socks-20261029`. Its detailed invasive preflight is in `909-pending-socks-setup.md`. |

The lead owns release history, ADR index, OpenRewrite research/plan, shared website pages and cleanup allowlists. Workers inspect all affected documentation and return updates or a no-change rationale. All checks use isolated snapshots and focused red-first behavioral regressions on Java 11 and a modern supported JDK. Root reviews actual combined changes before selective commits, website-first publication and completed/Done issue bookkeeping. Keep verification evidence out of GitHub text and commit messages.

The new local task **SJM SOCKS investigation** (`01a1873a-cc6a-7cd1-a0f3-8a24d0cbc41b`) owns read-only investigation of IPv6, remote DNS, CONNECT framing, credential lengths and related gaps. It shares this checkout and must not overlap #909 implementation.

Finding 8, daemon management starvation, remains an unapproved repair. Finding 13 needs a concrete import-boundary work packet under existing ADR 0035: preserve malformed address source without aborting readable conversion, and retain outgoing validation/rehearsal behavior. No finding 13 implementation is authorized by the current request. Previously delivered issues #900 through #907 remain complete. Earlier cleanup is deferred at the existing reparse-point guard; preserve its protections.
