# SMTP robustness improvement plan

> **Implementation status:** Phases 1 and 2 are complete and accepted. The Angus safety gate was resolved through supported socket/provider hooks; the original negative characterization remains in the tests. These are unreleased 10.0 changes. Later phases remain proposals. Benchmarking is not part of this work.

- Status: Phases 1 and 2 complete and accepted; unreleased
- Resumption after the pool fix: The supporting-library patches and Simple Java Mail 9.3.4 are released. This 10.0 checkout now adopts SMTP Connection Pool 4.1.0 and retains all eight mixed-failure waiter-recovery regression cases from the patch. Integration verification is recorded in [step 2](phase-2-execution-control/02-bound-asynchronous-submission-and-expose-backpressure.md). See the [dependency finding](phase-1-transaction-truth/01-preserve-recipient-replies-and-derive-retry-guidance.md#separate-dependency-finding).
- Plan order: 03 of 03
- Preceded by: [02 - CLI daemon improvement plan](../02_CLI_DAEMON_IMPROVEMENT_PLAN/README.md) in planning order only
- GitHub parent issue: [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- GitHub child issues: [#723](https://github.com/bbottema/simple-java-mail/issues/723) (phase 1), [#725](https://github.com/bbottema/simple-java-mail/issues/725) and [#726](https://github.com/bbottema/simple-java-mail/issues/726) (phase 2); later children not created
- Release train: 10.x; assign an exact release milestone to each child only when scheduled
- Working branch for planning: `codex/10.0.0`
- Baseline inspected: 7 September 2026
- Research source: `simple-java-mail-world-class-smtp-research.pdf`

This mini project turns the useful parts of the SMTP robustness report into independently reviewable improvements. It builds on capabilities already present in the unreleased 10.0 line: provider-neutral submission receipts, honest unknown outcomes, per-mail terminal observers, exact EML submission, rehearsal, configuration provenance, pooling, and explicit lifecycle control.

The objective is not to turn Simple Java Mail into an MTA or to copy every feature from another client. It is to make SMTP submission more precise at three boundaries:

1. Tell callers what the server said and what that means for retrying.
2. Bound and control work that has not yet completed.
3. Expose negotiated behavior safely and prove it against hostile SMTP peers.

## Why the seven findings become ten steps

The original review produced seven headings. The “modern ESMTP additions” heading contained four capabilities with different RFCs, compatibility risks, and release value. Keeping them in one step would recreate the oversized issue this plan is intended to avoid, so it is split into:

- complete DSN identifiers and original-recipient support;
- per-message REQUIRETLS;
- explicit SMTPUTF8 and 8BITMIME requirements;
- SIZE based on finalized transmitted bytes.

The other findings remain one step each. PIPELINING and CHUNKING are deliberately not included; issue #699 remains parked pending Angus direction.

## GitHub tracking model

Use one parent tracking issue and one child issue per step.

The parent issue should explain the shared developer problem and retain the release narrative. Each child should contain its own API decision, tests, documentation, classification, exact-version milestone, project status, and closure comment. GitHub's native sub-issue relationship should be the structural source of truth. The parent body should also contain a compact linked table recording each child's intended and actual release so the narrative remains readable without opening every issue.

This is preferable to one large issue because the steps differ substantially in risk and may ship in different releases. It also avoids declaring the entire project complete merely because one useful slice was delivered.

### Proposed parent issue

- Working title: `Track SMTP submission robustness improvements`
- Classification: `major feature`, never also `enhancement`
- Milestone: leave unset while children can span releases; set it only if every remaining child is committed to the same exact release
- Project board: optional umbrella card; child cards carry implementation status
- Closure: close only when every child is complete or explicitly moved to a successor parent

The parent issue should maintain this release-oriented table:

| Step | Child issue | Classification | Planned release | Shipped release | User-visible result |
| --- | --- | --- | --- | --- | --- |
| 1 | [#723](https://github.com/bbottema/simple-java-mail/issues/723) | Major feature | 10.0.0 | - | Recipient replies and retry guidance |
| 2 | [#725](https://github.com/bbottema/simple-java-mail/issues/725) | Major feature | 10.0.0 | - | Bounded async demand and visible overflow |
| 3 | [#726](https://github.com/bbottema/simple-java-mail/issues/726) | Major feature | 10.0.0 | - | Deadlines and protocol-aware cancellation |
| 4 | Not created | Enhancement | Unscheduled | - | Safe SMTP capability diagnostics |
| 5 | Not created | Security + enhancement | Unscheduled | - | Explicit authentication-over-TLS policy |
| 6 | Not created | Enhancement | Unscheduled | - | Complete DSN identifiers and recipient metadata |
| 7 | Not created | Enhancement | Unscheduled | - | Per-message REQUIRETLS |
| 8 | Not created | Enhancement | Unscheduled | - | Explicit SMTPUTF8 and 8BITMIME requirements |
| 9 | Not created | Enhancement | Unscheduled | - | Exact SIZE capability handling |
| 10 | Not created | Maintenance | Unscheduled | - | Reproducible SMTP conformance evidence |

`major feature` and `enhancement` remain mutually exclusive on every issue. Orthogonal labels such as `security` or `maintenance` may accompany the one appropriate functionality label. A test-only child does not need either functionality label.

### Commit and cross-link convention

Commits should primarily name the child issue because that is the unit being implemented and closed. Link the parent in the commit body rather than putting two issue numbers into every subject:

```text
feat(smtp): expose recipient replies (#<child>)

Part of #<parent>.
```

For a correction:

```text
fix(smtp): normalize report-success outcomes (#<child>)

Part of #<parent>.
```

Do not use `Closes #<parent>` from child work. When website work is committed in the separate `simplejavamail.org` repository, use the cross-repository form `bbottema/simple-java-mail#<child>` and `bbottema/simple-java-mail#<parent>` in the commit body so GitHub links the intended repository.

At release time, the exact-version milestone and the parent ledger determine which children belong in that release's narrative. Release notes should describe the shipped child outcomes; the parent supplies the connecting explanation and should not replace the individual issue links.

## Design rules shared by every step

1. Provider-neutral public contracts come before Angus-specific extraction details.
2. Never describe mailbox delivery; `ACCEPTED` means the SMTP peer returned final success.
3. Never convert uncertainty into automatic retry. SMTP offers no exactly-once guarantee.
4. Preserve existing receipt getters and ordinary `CompletableFuture` APIs unless a reviewed step explicitly changes them for 10.0.
5. Exact or protected content must not be silently rewritten to satisfy a missing SMTP capability.
6. No diagnostic may expose credentials, authentication payloads, message bodies, private keys, or protected content.
7. Pool release or invalidation happens before terminal completion is published, except where an intentionally open shared transport remains caller-owned.
8. Custom mailers and third-party transport adapters remain supported even when they cannot provide SMTP-specific details.
9. Every public addition follows `API_EXPANSION_WORKFLOW.md`; every substantial class follows `CODING_STYLE_GUIDE.md`.
10. Each implementation starts with characterization or fault-injection tests and leaves the branch reviewable before commit.
11. Before completing each step, review the [shared send-architecture infographic and its phase checklist](../docs/concurrency/inside-a-mail-send.md#phase-completion-check) alongside the concurrency catalogue. Update the master, website copy, and #722 embed together when their meaning changes; otherwise record an explicit unchanged review in the infographic ledger. Repeat this at every phase boundary and before release.

## Phases and steps

### Phase 1: Complete transaction truth

- [x] [1. Preserve per-recipient replies and derive retry guidance](phase-1-transaction-truth/01-preserve-recipient-replies-and-derive-retry-guidance.md) - implemented, verified and accepted on 9 September 2026

### Phase 2: Bound and control execution

- [x] [2. Bound asynchronous submission and expose backpressure](phase-2-execution-control/02-bound-asynchronous-submission-and-expose-backpressure.md) - implemented, verified and accepted on 9 September 2026, including direct-handoff worker-expiry recovery
- [x] [3. Give deadlines and cancellation protocol meaning](phase-2-execution-control/03-give-deadlines-and-cancellation-protocol-meaning.md) - implemented, verified and accepted; holistic-review corrections completed on 11 September 2026

Step 3's [supporting-library cancellation plan](phase-2-execution-control/support-library-cancellation/README.md) is implemented and released: Generic Object Pool 2.5.0, Clustered Object Pool 4.1.0 and SMTP Connection Pool 4.1.0. The downstream implementation connects their cancellable claims and fenced lease abort to `MailSend.requestCancellation()` and total deadlines. Physical SMTP abort is supported for compatible SJM-owned Angus Sessions; unsupported integrations remain cooperative and reject a configured total deadline before connecting.

### Phase 3: Make connection behavior diagnosable and safe

- [ ] [4. Add a structured SMTP capability probe](phase-3-diagnostics-and-security/04-add-structured-smtp-capability-probe.md)
- [ ] [5. Make authenticated plaintext fallback explicit](phase-3-diagnostics-and-security/05-make-authenticated-plaintext-fallback-explicit.md)

### Phase 4: Model modern ESMTP requirements

- [ ] [6. Complete the DSN envelope model](phase-4-modern-esmtp/06-complete-dsn-envelope-model.md)
- [ ] [7. Add per-message REQUIRETLS](phase-4-modern-esmtp/07-add-per-message-requiretls.md)
- [ ] [8. Make SMTPUTF8 and 8BITMIME requirements explicit](phase-4-modern-esmtp/08-make-smtputf8-and-8bitmime-requirements-explicit.md)
- [ ] [9. Enforce SIZE against finalized transmitted bytes](phase-4-modern-esmtp/09-enforce-size-against-finalized-transmitted-bytes.md)

### Phase 5: Publish reproducible protocol evidence

- [ ] [10. Build the SMTP conformance and fault-injection suite](phase-5-conformance/10-build-smtp-conformance-and-fault-injection-suite.md)

## Dependency and scheduling guidance

- Step 1 should precede any API that claims retry safety.
- Step 2 should define bounded waiting before Step 3 adds total deadlines across that waiting.
- Step 4 should precede or accompany Steps 5 through 9 so negotiated facts are represented once and reused.
- Steps 6 through 9 are independently shippable after their shared capability contract is stable.
- Step 10 starts by retaining today's scripted fault-boundary tests, grows alongside every feature, and finishes only when the public evidence can be reproduced outside this repository.

The plan number records planning order, not a hard dependency on the unfinished package-manager work in plan 02.

## Deliberately out of scope

- Automatic retry or blind transport failover.
- Exactly-once submission or delivery claims.
- A durable spool, application queue, scheduler, or MTA behavior.
- POP3, IMAP, direct-to-MX policy, or commercial provider APIs.
- PIPELINING, CHUNKING, BDAT, or BINARYMIME while #699 is parked.
- Mandatory Micrometer, OpenTelemetry, or framework dependencies in core.
- Rate limiting and general transport failover helpers; revisit them only after bounded demand and retry guidance exist.
- A broad marketing claim before the conformance evidence exists.

## Completion definition

This mini project is complete when every scheduled child is closed with its exact shipped release recorded, the parent issue tells the coherent release story, and the resulting APIs can:

- retain the SMTP facts available for each recipient without inventing unavailable facts;
- distinguish safe retry from duplicate risk without retrying automatically;
- bound pending work and apply deadlines through the actual SMTP operation;
- explain negotiated capabilities and authentication policy without leaking secrets;
- apply modern ESMTP requirements to the finalized bytes that are actually sent; and
- reproduce the claimed behavior against scripted and real SMTP implementations.
