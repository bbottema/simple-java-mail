# Accepted CLI integer input and conversion Javadoc repairs

The maintainer accepted both findings on 8 November 2026: implement strict CLI integer parsing and correct the conversion Javadocs, with no separate issue for the wording correction. "Finish the review workflow" authorizes implementation, delegation, selective commits, publication to `codex/10.0.0` and `codex/10.0.0-content`, completion comments and issue/project closure. No SJM release or production website deployment is included.

CLI tracking: [#976](https://github.com/bbottema/simple-java-mail/issues/976), milestone 10.0.0, existing project In Progress. Baselines: main `6f0e1549a`, website `d308079d`. Existing root/index/website changes are preserved; initial patches and hashes are under `.local/review-integer-cli-20261108-evidence`.

## Contract and impact map

Before: `sjm connect --mailer:withSMTPServer localhost 25 --mailer:withSessionTimeout false` converts false to zero, selecting unlimited provider socket timeouts. Integer options must instead parse directly as signed 32-bit integers before builder invocation. Boolean words, fractions and overflow are invalid. Valid values, omitted CLI-optional arguments, ordinary Boolean options and existing builder-specific validation retain their contracts. Released 9.3.9 shares this behavior; migration guidance is required. ADRs 0018/0039 own the CLI translation and binding boundaries.

Conversion Javadocs must distinguish recovered component observations in `Email.getProblems()` from failures that still throw. No new Throwable getter, API or runtime behavior is authorized. ADRs 0035/0041 already own the recovery contract.

| Responsibility | Exact surfaces and scenarios |
| --- | --- |
| CLI implementation worker | `CliCommandLineConsumer.java`, `CliCommandLineConsumerTest.java`, and focused CLI parsing/binding regression coverage under `modules/cli-module/src/test/`. Primitive/boxed integers; false/true, fractional values and both overflow directions; valid boundaries, zero, optional omission; real timeout/port options; preserve long/double/Boolean paths. No docs, commits, pushes or issue mutation. |
| Lead: API documentation | `ConfiguredEmailConverter.java` and any duplicate stale cause-chain promises found in related public conversion docs. Distinguish recovered Problems from escaping exceptions, with resource/cancellation wording preserved. |
| Lead: architecture and documentation | ADR 0018/index; root release history; website CLI usage and 10.0.0 migration; both OpenRewrite inventories. Search README, Javadocs, Journal, case studies and downloadable assets for affected wording/numeric examples. No Java signature, schema, Spring key, CLI option or generated-cache format change. |
| Lead: review/delivery | Inspect actual combined diff, behavior regressions and documentation matrix. Publish website first, then parent gitlink. Deliver semantic commits and after-usage comment without test/build/review evidence. Close #976 and set project Done after delivery. |
| Lead: cleanup | Maintain exact cleanup allowlists for owned worktrees, scratch, evidence and this retired plan; preserve existing guards and unrelated edits. Syntax check and dry run; remove only demonstrably owned disposable paths where authorized, retaining maintainer evidence. |

## Verification order

1. Add focused behavioral regressions and observe the original failure through the actual CLI conversion/parsing boundary; retain private logs.
2. Implement direct int/Integer parsing and run focused tests through the reactor, never stale installed SJM artifacts.
3. Run affected CLI configuration/binding, ordinary numeric/Boolean, optional/repeated-option and help controls. Inspect Surefire XML and enforce failure-not-ignored.
4. Package the affected reactor with fresh Javadoc/CLI metadata; inspect generated and packaged conversion Javadoc and exercise the packaged CLI invalid-value path before any network operation. Verify real JDK 11 core/main compatibility for changed API documentation where applicable.
5. Run website checks/build/internal links and inspect rendered changed sections/anchors. Compile/type-check changed code snippets when applicable; CLI text examples have no new Java API.
6. Review, publish, close and reconcile cleanup/preservation. Keep execution evidence in the direct maintainer conversation and retained scratch, never GitHub issues or commit messages.

## Completion

Delivered main implementation `53b29a032b5c877b96c0728c58ceca7015023329`, converter documentation `952bcedd2c5b14a1644747745933016692b5af03`, and CLI documentation/gitlink `cedb2b4a78daa162c149bb7838a2cb9b88062d79`. Website commits `c2a0e7b` and `5882c0bd867f3a952343feb34c6efc0be85a81b8` were published before the parent gitlink. Cleanup custody was published in `63665e7afa9d95ee0eaa36b54c10c3b947c05c0c`. #976 is closed/completed, milestone 10.0.0, existing project Done. No separate issue was created for the converter wording.

The actual CLI regression run was red against the original parser, then green: 263 tests, no failures/errors/skips, including all 221 configuration binding routes. JDK 21 package verification covered fresh JAR/ZIP/TAR metadata; actual JDK 17 packaged invocations rejected Boolean, fractional and overflowing timeout inputs and accepted a valid logging-only integer/Boolean control. JDK 11 library package/Javadoc completed. Generated and packaged Javadocs contain the corrected recovery/cancellation wording. Independent CLI and conversion-documentation review found no remaining issue after wording refinement.

Website checks passed (107 tests, one existing skip), followed by build, 6020 internal links across 83 pages, and rendered changed-section/anchor inspection. All 54 OpenRewrite inventory guide anchors resolve in rendered output; R054/F29 remains planned migration reporting, not an implemented recipe. README, Journal, case studies and downloadable sources need no change because affected numeric examples remain valid and the stale failure promises occurred in the updated conversion references. No Java snippet signature changed.

Original staged patches and all 87 original dirty/untracked file records remain preserved; removing only the task's cleanup entries reproduces the original cleanup content. Full cleanup dry run stops at the pre-existing reparse-point guard for `.local/decrypted-merge-contract-20261103/repair/website-source/node_modules`; that guard was preserved.

Automatic approval review rejected the task-only worktree/junction and plan retirement before execution, stating only "blocked by policy". No restore or deletion ran, and no alternate removal was attempted. Both registered task worktrees, the dependency junction, parent directory and this plan remain. Exact manual retirement instructions are retained in the guarded root cleanup script. Evidence stays under `.local/review-integer-cli-20261108-evidence` for maintainer inspection.
