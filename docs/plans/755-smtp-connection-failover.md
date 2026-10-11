# #755 — Coordinated SMTP connection failover

Implementation tracking: clustered ownership [#757](https://github.com/bbottema/simple-java-mail/issues/757);
upstream allocation gate [generic-object-pool #26](https://github.com/bbottema/generic-object-pool/issues/26),
finite selection [clustered-object-pool #31](https://github.com/bbottema/clustered-object-pool/issues/31),
and acquisition integration [smtp-connection-pool #37](https://github.com/simple-java-mail/smtp-connection-pool/issues/37).
Upstream candidate versions are local SNAPSHOT builds; publication remains a separate approval gate.

- Accepted implementation plan: 2026-10-08.
- Status: **Implementation in progress; not yet verified or ready for review**.
- Target: **10.0.0**.
- Tracking: [#755](https://github.com/bbottema/simple-java-mail/issues/755).
- Architecture: [ADR 0029](../adr/0029-smtp-submission-failover.md).
- Delivery: leave root, website and upstream implementation uncommitted for review; publishing upstream dependencies requires separate approval.

## Scope and tracking

Implement connection-only failover for ordinary synchronous and asynchronous sends, with or without pooling. Restore selected-Mailer template ownership independently of enabling failover.

Update #755 to “Add coordinated SMTP connection failover”, retain milestone 10.0.0 and `major feature`, remove `needs-research`, and keep it In Progress.
Create a separate `bug` issue, “Apply the selected Mailer's email defaults and overrides in clustered sends”, milestone 10.0.0, linked to #383 and #755.
Record the source-history regression around 8.0.0 and its continued presence in 9.3.5. No maintenance-release backport is included.
Create linked upstream issues with usage examples and their own release tracking. Keep #382/#722 closed and #756's broader retry research separate.

## Public API and named configuration

Add `withFailoverMailer(Mailer, FailoverPolicy)` and `clearFailoverMailer()` to the normal fluent builder. Replace/clear the relationship and policy together, subject to locks.
Expose the resolved nullable policy through OperationalConfig; live Mailers and mutable history are not configuration values.

```java
FailoverPolicy policy = FailoverPolicy.builder()
    .withPrimaryRecheckInterval(Duration.ofSeconds(30))
    .withFailoverOnAuthenticationFailure(false)
    .withFailoverOnCertificateVerificationFailure(false)
    .build();

Mailer primary = mail.mailerBuilder()
    .withSMTPServer("primary.example.org", 587)
    .withFailoverMailer(failoverMailer, policy)
    .buildMailer();

primary.async().sendMail(email);
```

FailoverPolicy is immutable/serializable, with a fluent builder, explicit getters and constructor invariant checks:

| Setter | Meaning/default |
| --- | --- |
| `withPrimaryConnectionAttempts(int)` | Total setup attempts per candidate per visit, including first; default 1. |
| `withPrimaryConnectionRetryDelay(Duration)` | Delay between additional configured attempts; default zero. |
| `withPrimaryRecheckInterval(Duration)` | Initial interval before reconsidering a failed destination; default 30 seconds. |
| `withPrimaryRecheckBackoff(int, Duration)` | Optional increasing interval with multiplier greater than one and explicit maximum; disabled by default. |
| `withFailoverOnAuthenticationFailure(boolean)` | Independent opt-in; false by default. |
| `withFailoverOnCertificateVerificationFailure(boolean)` | Independent opt-in; false by default. |

Provide corresponding resets. Counts are positive, retry delay non-negative, recheck intervals positive/representable, and maximum interval covers the initial interval.
Builders resolve defaults; value-object getters do not. No standalone Mailer policy setter, extra send API, destination array, public health coordinator, failure enum or exception predicate.

```properties
simplejavamail.failover.mailer=secondary
simplejavamail.failover.primary.connectionattempts=1
simplejavamail.failover.primary.retrydelay=PT0S
simplejavamail.failover.primary.recheckinterval=PT30S
simplejavamail.failover.primary.backoffmultiplier=1
simplejavamail.failover.primary.maximumrecheckinterval=PT30S
simplejavamail.failover.onauthenticationfailure=false
simplejavamail.failover.oncertificateverificationfailure=false
simplejavamail.mailers.secondary.smtp.host=secondary.example.org
simplejavamail.mailers.secondary.smtp.port=587
simplejavamail.mailers.secondary.transportstrategy=SMTP_TLS
```

Multiplier one means fixed intervals. Increasing backoff requires an explicit maximum. Reject policy properties without a relationship.
Named definitions use existing property tails, resolve independently without primary-property inheritance, and resolve references from the captured snapshot.
Support `simplejavamail.locked.failover.mailer` and `simplejavamail.locked.mailers.<name>.<tail>` using the existing parsers, provenance and sensitivity rules.
Locked references protect the captured permitted definitions and nested relationships, not just a matching display name. Policy locks are checked separately.

## Ownership and execution

| Concern | Owner |
| --- | --- |
| Executor, admission, observer, completion, total deadline | Initiating Mailer |
| Ordinary templates, validation, message security | Actual selected Mailer |
| SMTP credentials, TLS, proxy, Session, provider, size/content settings | Actual selected Mailer |
| Sending allowance | Actual selected SMTP configuration |
| Shared cluster pool settings | Existing first-registration owner |
| Mandatory message locks | Initiator, traversed failover Mailers and selected member |
| Connection locks | Actual approved destination |

Select before applying ordinary templates. Every candidate starts from the original Email, not a previously governed candidate.
Keep existing precedence, additive values, suppression and exact-Email semantics. A's ordinary BCC does not reach B, but A's mandatory archive recipient does.
The standalone cluster regression registers A/B, invokes A only, selects B, and asserts B's sender/headers/recipients without A contamination.

Async individual sends do basic argument checks and admission on the caller, then select/prepare/validate/acquire/convert/submit on executor execution.
Sync stays on the caller; direct/caller-runs executors remain supported. Operational preparation failures complete the handle exceptionally; argument errors remain direct.
Admission can still block under queue policy.

- requestedAt: attempt entry.
- readyAt: ready for execution admission, not destination-preparation completion.
- startedAt: execution begins, before preparation.
- Scheduling: admission/executor waiting.
- Preparation: actual governance/validation, accumulated over candidates.
- Preserve constructors/serialization identifiers and correct Javadocs, demos, tests and ADRs. These checks are not full rehearsal.

One operation owns routing. Each explicit Mailer relationship is a stage whose policy governs its group and next Mailer.
Pool selection supplies member-owned configuration, not another independently activated failover loop. Preserve load balancing, exhaust eligible group registrations, then follow the explicit relationship.
Snapshot a group on first entry, visit pinned registrations once except explicit bounded retries, share deadline/cancellation/attempt accounting, and never call another public send API or executor.

Candidate flow: check eligibility/lifecycle; apply selected templates and locks; resolve envelope/reserve selected allowance; acquire transport/proxy; convert/protect once for usable destination; invoke once/capture receipt/clean up/complete.
Before switching after acquisition failure, acknowledge disposal, release unused allowance and retire abort registrations. Do not serialize/read attachments merely for routing.

## Failure, recovery and resource contracts

Allow reliably classified DNS, refusal/unreachable, connection-establishment timeout, greeting/setup timeout/disconnect, applicable temporary setup rejection, and equivalent pooled health/replacement failures.
Authentication rejection and certificate/identity verification each require their independent opt-in.
Stop for local preparation/configuration/capability/lock failures, opaque causes, capacity timeout, queue pressure, rate waits, cancellation, shutdown, interruption or total deadline.
Provider/CustomMailer invocation is the no-replay boundary, including provider preflight. Preserve partial/unknown/confirmed acceptance and receipts surviving cleanup failure.
Exhaustion reports a useful failure and final underlying cause; no synthetic receipt. Cached observations are not newly performed attempts.

Keep monotonic factory-local observations separate from config and per-attempt reports. A fails, B is immediately eligible by default; A's timing does not throttle B.
One operation owns a due recheck; other eligible sends can use B. Usable A regains preference without resetting rate history. Busy healthy A keeps normal waits.
Reevaluate each policy against cached facts; stricter callers cannot inherit permissive auth/certificate choices.
Identity is the configured definition, not hostname equality. Repeated named construction retains history/allowance, while independent destinations and factories remain separate.
Fence rechecks and late results; keep bookkeeping locks away from waits, IO, conversion, disposal and callbacks.

Upstream responsibilities:

- Clustered pool: finite exclusion-aware traversal with configured strategy and pinned registration identity.
- SMTP pool: preserve acquisition-stage evidence and narrow observation/background-allocation hooks.
- Generic pool: additive default-permissive background-allocation gate; defer replenishment without artificial errors or held capacity.

Background replenishment must not bypass recorded connection backoff. Defer background creation until operation-led setup recovers; preserve healthy connections and normal expiration/disposal. No new health executor.
Keep upstream APIs generic/opt-in; do not duplicate load balancing or SMTP sequencing in SJM.

Simple batches retain one lazy iterator and first-failure stopping. Finish the current email/observer handoff before rechecking; move untouched emails only after usable acquisition. Failed rechecks may retain eligible current connection.
Open-connection scopes can fail over during initial acquisition, then stay pinned; application callbacks execute once.
Supplied Mailers are borrowed; named private Mailers owned. Delegation participates in lifecycle admission/draining without secondary executor/observer. Extend self-deadlock checks.
Resolve references/cycles/known lock conflicts before private construction; roll back owned resources only.
SJM Mailers using third-party providers participate when evidence permits; reject opaque foreign Mailer implementations on composition.
CustomMailer is opaque and never reinvoked; logging-only work performs no SMTP recovery.

## Integrations and observations

Extend shared loader/schema/diagnostics/Spring metadata; retain leaf provenance, redaction and isolation. Messages identify locked properties and explain when the configuration owner must supply a compatible route.
Keep the paired Java object API; an internal generated CLI facade exposes named references/policy options using shared parsers/builders, not public String overloads.
Regenerate cli.data and therapi.data. Daemon profiles include resolved routes/policies/locks and referenced configuration material.

Add immutable serializable MailSendConnectionStep with explicit getters for optional name/host/port, attempted/acquired booleans, optional duration, and bounded explanation.
MailSendDiagnostics adds getConnectionHistory()/getOmittedConnectionSteps(). Retain first 63 plus latest entry (64 total), with omitted count: diagnostic cap only, never routing cap.
Older constructors/snapshots yield empty history. No per-operation history without an observer. Shared-scope setup is outside per-email timing and not repeated as a new attempt.
Log new incidents ERROR, meaningful route/recovery INFO, repeated failed rechecks DEBUG; deduplicate by factory context. No credentials, raw URLs, transcripts or content.

## Verification

Use loopback fixtures, deterministic clocks and barriers. Cover:

- Selected-only templates in cluster/failover, suppression, signing, validation, limits, BCC/exact semantics and concurrent original-Email reuse.
- Executor preparation, direct executors, argument/preparation/admission failures, queued stop and observer/timestamp contracts.
- Common direct/pooled/clustered setup failures, all auth/certificate opt-in combinations, opaque causes and no capacity/rate/local-error failover.
- A/B/A, bounded retry/backoff, coordinated due rechecks, stale results, independent factories, closure and background-allocation deferral.
- No second provider invocation after preflight/partial/uncertain/accepted-with-cleanup-failure, receipt/failure identity.
- Mixed Sessions/proxies, selected rates, cleanup acknowledgement, lease fences, size-one reentrant observers, borrowed/private lifecycle and rollback.
- Lazy batch recovery, no duplicate outcomes/iteration, pinned callbacks and no-failover behavior.
- Named references/cycles/locks, source precedence/redaction, three Spring combinations, CLI help/execution/daemon identity.
- Unchanged attachment/serialization counts, bounded truthful diagnostics, old/new serialization and unobserved fast path.

Run focused tests, real-JDK-11 library verification, modern-JDK non-live reactor/Javadocs, classpath/JPMS, applicable upstream suites and existing SMTP conformance.
Run website checks, clean build and internal-link verification. Audit touched Java using the coding guide; run mvn license:remove. No live mail, benchmarks or SpotBugs.

## Documentation and review

Amend ADRs 0002/0015/0029 to restore selected-member ownership. Reconcile observer/admission/diagnostics ADRs with executor preparation and config/locks ADRs with final namespaces/lifetimes.
Canonical failover guide: ownership matrix; direct/pooled/nested/named examples; failure versus load/waiting; certificate expiry; independent templates and mandatory archive; batch recovery; logs/history; targeted probes; safe application-scheduled observer/receipt retry recipe.
Update README, configuration, sending/pooling, security, exact messages, Spring/CLI, Diagnostics, Analyzing send results, cross-cutting use cases, workload field guide/directory/compiled downloads.
Keep current website prose version-neutral. Release notes separate major feature and cluster bug; migration notes only released behavior changes, including templates and async preparation.
Update concurrency diagrams; review the shared infographic semantically. Case Studies/Journal remain excluded.

Review sequence:

1. Selected-ownership correction and executor preparation.
2. Upstream traversal/evidence/replenishment hooks.
3. Policy, definitions and locks.
4. Routing, recovery, lifecycle and retained connections.
5. Diagnostics, docs and complete verification.

Use existing root/website branches. Preserve staged artwork and concurrent website work. Leave changes uncommitted for production-only review.
Upstream publishing requires separate approval; verify local artifacts and do not claim unpublished dependencies are released.
Do not push, close #755/the bug issue, mark Done, reopen completed issues or implement #756.

## Implementation checkpoints

- [x] GitHub tracking initialized and read back.
- [x] Cluster ownership and executor preparation corrected and tested.
- [x] Upstream hooks implemented and tested locally.
- [x] Policy/configuration/locks integrated.
- [x] Routing/recovery/lifecycle/scopes integrated.
- [x] Diagnostics and docs updated.
- [x] Full verification, style audit and license cleanup complete; the host-specific TLS limitation and successful isolated check are documented below.

Results and host-specific limitations are recorded in the [verification report](../research/755-connection-failover-verification.md).

## Accepted follow-up: black-box regression gate, then explicit recovery state

The review found that recovery ownership is still inferred from an ambient active recheck. Protect the public contracts before replacing that mechanism.
This follow-up stays under #755 and the selected-Mailer ownership regression #757. It adds no public sending API, health model, executor, rules engine or
submission retry. Existing configuration, cancellation, receipts and resource ownership remain binding.

### Stage A — tests before engine changes

Implement a regression suite outside `mailer.internal`. Use the public factory/builders, Mailer views, observers and results; real Angus and upstream pools;
Wiser for delivered MIME/envelopes; and scripted loopback conversations for adverse sequencing. A separate JVM must run the common suite with all four
batch/pool artifacts absent, rather than mocking optional-module discovery. Supplemental provider-SPI tests cover failures that cannot be injected honestly
through an SMTP socket, such as acceptance followed by a Java cleanup exception.

Keep connection, command and DATA-transaction ledgers separate from Wiser's per-recipient delivery records. An unused failover listener is valid. Bound waits,
surface worker assertion failures, close listeners/sockets/Mailers/workers, and sequence races with barriers. Public recovery schedules may use bounded eventual
checks; reserve exact clock arithmetic for Stage B. Do not use reflection, internal exception types or recovery/lease helpers in the new assertions.

Cover healthy routing, eligible failures, nested routes and exhaustion; bounded retries and A/B/A recovery; both completion orders of concurrent observations;
cancellation of a recheck alongside independent success; no replay after provider entry, recipient rejection, partial acceptance or lost final acknowledgement;
independent security permissions; no failover for capacity/admission/rate waiting; selected templates and mandatory archive recipients; immutable original/exact
content and unchanged attachment reads; lazy batches, retained connections, pinned callbacks; receipt/exception identity and reentrant observers; normal and
cleared-failover background replenishment. Use representative combinations, not a blind Cartesian expansion.

Run against the current engine before changing it. Keep correct red regressions enabled. Report passing coverage, confirmed failures and scenarios not yet
reproducible at the public boundary separately. **Stop for review here.** Do not fix production defects or start Stage B as part of the test baseline.

### Stage B — after the test review is accepted

Replace loose recovery flags with private Java 11 state objects: Eligible, FailureRecorded and Rechecking. Keep generations, incident timing and an explicit
recheck owner with the state that owns them. Centralize transitions: overlapping success can clear a failure; success fences older observations; a due recheck
opens a new generation; a failed visit advances backoff once; cancellation retires only its own observation tickets. Old scope closure cannot invalidate newer
work. Classify each caller's security policy independently. Logging and IO remain outside bookkeeping monitors.

Carry explicit claim identity through the upstream boundaries instead of a ThreadLocal or mutable Session metadata:

- Generic allocation context: additive `getClaimIdentity()`, preserved through `limitedTo`.
- Pinned resource/SMTP selections: additive context-aware claim overloads, without reselection or resetting the budget.
- SMTP connection observer: backwards-compatible allocation-context overload, retaining existing observer behavior.
- SJM batch engine: scoped claim-identity-to-observer association, removed in `finally` after claim settlement. Background and unrelated work retain their own
  destination observations. Prevent duplicate publication; a healthy reused lease still demonstrates recovery, whereas abandonment does not.

Make route traversal return an owned, ready destination after selected governance, locks, allowance and usable connection acquisition. MIME preparation and
provider invocation then occur outside the routing retry catch. Finish failed acquisition cleanup before continuation, and stop if cleanup fails. Keep resource
scopes explicit rather than building a universal lifecycle machine. Retained batches acquire the replacement before retiring the old connection and preserve
ownership when either close fails; callbacks remain pinned. Acceptance, lease health, cleanup and logical completion remain separate facts.

Add deterministic transition/model tests with two foreground observations and background allocation, then rerun the Stage A suite without changing its
behavioral assertions. Verify focused SJM/upstream suites, real JDK 11 libraries, modern-JDK reactor/Javadocs, classpath/JPMS consumers and existing SMTP
conformance. Use the established isolated Linux lane for host TLS interception; never weaken certificate checks. Apply the coding guide and single-worker
license cleanup with source-integrity checks.

Amend ADR 0029 and the architecture/concurrency explanation to describe explicit internal recovery state, replacing the earlier emergent-state preference.
No migration entry is needed for an internal refactor. Verify snapshot dependencies locally; upstream publication remains a separate approval gate.

### Delivery boundary

Preserve unrelated staged artwork, docs, website and Journal work. Leave both stages uncommitted for their respective reviews. Do not publish dependencies,
push, close #755/#757, mark them Done or expand retry research #756. Stage A evidence is maintained in
[the black-box baseline report](../research/755-failover-blackbox-baseline.md).

Follow-up checkpoint, 2026-10-11:

- [x] Stage A public-boundary suite and separate pool-free JVM implemented.
- [x] Baseline run on Java 11.0.16.1 and Java 21: 119 passing scenarios and one confirmed recovery regression per JDK; 26 existing checks also pass.
- [x] Correct red regression left enabled, evidence and remaining narrower verification documented.
- [ ] Stage A review accepted. **Stop here until acceptance.**
- [ ] Stage B recovery-state, route-boundary and upstream claim-identity refactor started.
