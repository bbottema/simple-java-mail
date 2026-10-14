# #756 — Safe, bounded mail-send retries

- Accepted implementation plan: 2026-10-13.
- Tracking: [#756](https://github.com/bbottema/simple-java-mail/issues/756).
- Decision: [ADR 0030](../adr/0030-safe-bounded-mail-send-retries.md).
- Delivery: production review accepted on 2026-10-14; selective semantic commits, root/website publication and #756 closure authorized.

## 1. Public contract and ownership

Implement opt-in retries on existing sends. The selected Mailer owns attempts, delay, backoff and jitter. The initiating Mailer owns execution, admission, completion, observer, overall deadline, suspended-retry capacity and replay-memory limits. Selected SMTP configuration, templates, security, allowance and applicable route locks retain their established owners.

A selected Mailer without an enabled schedule does not inherit the initiator's schedule. Ordinary cluster selection and initial failover remain available; once submission starts, retries remain with that selected Mailer and registration.

Add immutable, serializable MailSendRetryPolicy:

```java
MailSendRetryPolicy policy = MailSendRetryPolicy.builder()
    .withMaximumAttempts(3)
    .withRetryDelay(Duration.ofSeconds(30))
    .withRetryBackoff(2, Duration.ofMinutes(5))
    .withRetryJitter(0.2)
    .withWaitingCapacity(100)
    .withMaximumMessageSize(32L * 1024 * 1024)
    .withMemoryLimit(128L * 1024 * 1024)
    .build();

mailerBuilder.withMailSendRetryPolicy(policy);
mailerBuilder.clearMailSendRetryPolicy();
```

Attempts and initial delay are required to enable retries. A policy containing only resource limits remains disabled. Maximum attempts includes the initial invocation and is at least two. Delay is positive and representable in nanoseconds. Optional backoff defaults to fixed delay; increasing backoff requires an integer multiplier greater than one and a maximum at least as large as the initial delay. Jitter defaults to zero and accepts finite fractions in [0, 1).

For retry k, cap initial-delay times multiplier^(k-1), apply uniform plus/minus jitter, and never exceed an explicit maximum. Validate arithmetic representability. Optional reset methods restore fixed backoff/zero jitter. Setters replace previous values. Constructors validate supplied data; builders resolve defaults from the centralized MailerGenericBuilder catalogue. Public getters explicitly link to their builder methods. OperationalConfig exposes a non-null resolved policy; isRetryEnabled() distinguishes disabled schedules.

Keep initiating-Mailer resource limits on the same policy builder, not as standalone Mailer setters/getters:

```java
.withWaitingCapacity(100)
.withMaximumMessageSize(32L * 1024 * 1024)
.withMemoryLimit(128L * 1024 * 1024)
```

Waiting capacity counts ordinary async retries waiting for backoff/resumed execution, not active invocations or synchronous scope waits. Maximum message size counts serialized replay content. Aggregate memory covers active and waiting replay representations, not total JVM heap. Values are positive; a single buffer must be representable and aggregate memory must cover the maximum message size.

resetWaitingCapacity(), resetMaximumMessageSize() and resetMemoryLimit() restore the centralized defaults. Replacing a policy replaces its entire supplied value. clearMailSendRetryPolicy() restores disabled retries and default resources, subject to locks. A resources-only initiating policy can bound a selected destination's enabled retries without supplying its own schedule. Runtime trackers and ownership remain separate from this immutable configuration grouping.

No new send family, Email setting, eligibility enum or application exception predicate.

## 2. Retry engine and lifecycle

### Safety

Eligibility requires REJECTED plus SAFE_TO_RETRY_ALL, structured temporary transaction/recipient evidence, no permanent/accepted/unknown contradiction, a live deadline/control, remaining attempts and successful failed-attempt cleanup. Exception prose and missing receipts do not qualify.

Permanent, partial, uncertain, local compatibility/configuration, cancellation, deadline and cleanup failures stop. Acceptance followed by cleanup failure never retries. CustomMailer remains an opaque single invocation; logging-only performs no SMTP retry.

Separate submission-attempt results from logical terminal failure. Capture exact receipt/exception before policy decisions. Recoverable intermediate failures do not freeze MailSendControl. Already-terminal failures preserve first-failure precedence before cleanup. Secondary cleanup failures remain suppressed on the primary cause and prohibit another attempt.

Retain genuine final exceptions/receipts without an exhaustion wrapper. If suspended capacity or resumption fails, retain the last submission exception with an actionable suppressed explanation. A later acquisition/preparation failure is the actual terminal failure, while the observer can retain the last genuine submission receipt.

Extend existing operation states for retry waiting, resumption and finishing. Fence cancellation/timers/executor work so only one owner submits or completes. No recursive public sends or repeated admission.

### Replay

Use bounded memory only. Render/protect once for the selected destination and freeze content before first invocation. Reuse existing FinalizedMimeMessage machinery with bounded capture and owned immutable bytes. Preserve final Message-ID, exact/protected bytes, ordinary omitted transport headers, envelope occurrences/options and signatures. Do not reread changing attachments or regenerate MIME/protection between attempts.

Reserve aggregate capacity incrementally. Limit exhaustion fails before submission and identifies the relevant setting/remedy. Reuse already-finalized bytes when possible. Release content, application references and permits on every terminal path before observation. No temporary files.

Add one default-unsupported provider SPI hook preparing repeatable submission from original PreparedMail and frozen content. Angus preserves resolved envelope/provider options; the hook issues no SMTP commands. Retry-enabled SMTP adapters must support repeatable preparation; generic fallback cannot assume it. Suppress optional provider MIME rewriting. Each invocation uses fresh local metadata, generated ENVID stays transaction-specific, and fixed identifiers stay fixed. Capability/security checks remain connection-authoritative.

### Ownership

Retain selected registration and participating lifecycle scopes across the logical operation, but release ordinary transport/proxy/abort/reservation ownership before backoff. Reacquire only the pinned destination. Apply governance once and reserve/charge the selected allowance per invocation.

Simple batches/open scopes retain their connection and execution thread during retry waits. Stop if that connection becomes unusable; do not reconnect or repeat callbacks. Preserve lazy iteration, first-terminal-failure stopping and pinned callback behavior. Batch route reconsideration occurs only after the current email finishes.

### Async scheduling and shutdown

Release ordinary send workers during backoff. Keep a bounded per-initiator pending collection and its permit until resumption starts or terminates. Reuse deadline scheduler for short signals only. One lazy retry-dispatch worker uses coalesced wakeups, not an unbounded task queue, to resubmit the existing operation through its configured executor.

Preserve executor capacity/direct/caller-runs policy and remaining deadline; never block deadline threads on SMTP, observer or executor admission. Cancellation removes pending continuations. Sync/retained waits wake for stop/deadline.

Shutdown closes new admission, drains admitted work including suspended retries, then shuts owned executors/control workers/pools. Caller executors remain borrowed. Preserve self-deadlock checks during resumed/delegated execution.

## 3. Configuration and diagnostics

```properties
simplejavamail.smtp.retry.attempts=3
simplejavamail.smtp.retry.delay=PT30S
simplejavamail.smtp.retry.backoffmultiplier=2
simplejavamail.smtp.retry.maximumdelay=PT5M
simplejavamail.smtp.retry.jitter=0.2

simplejavamail.defaults.mailsend.retry.waitingcapacity=100
simplejavamail.defaults.mailsend.retry.maximummessagesize=33554432
simplejavamail.defaults.mailsend.retry.memorylimit=134217728
```

Absent policy properties disable retries. Any policy field requires attempts and delay; increasing backoff requires a maximum. Resource settings alone do not enable it. Non-secret typed policy schema belongs to SMTP_CONNECTION; limits belong to EXECUTION_AND_POOLING. Reuse snapshot/provenance/named definition/locked counterpart mechanisms. Selected policy locks and initiating resource locks keep their owners.

Spring metadata uses existing Environment loading. Complex policy setter is CLI-excluded; expose all typed schedule and resource fields through the internal CLI facade. Preserve existing CLI/property names. CLI overrides extend the builder's current policy rather than reloading cleared or replaced factory values. Regenerate both caches and include effective settings in daemon identity.

Add MailSendDiagnostics.getRetryWait(), getSubmissionHistory(), getOmittedSubmissionSteps(). MailSendSubmissionStep is immutable/serializable, with explicit attempt-number, submission-status, optional reply-code, duration, optional next-delay and bounded explanation getters. Do not retain intermediate receipts/throwables/recipient lists/raw replies in history.

Observed retry-enabled operations retain first 63 plus latest steps. Only actual invocations create steps. Retry waiting includes backoff and wait until resumption begins; initial scheduling remains separate. Accumulate real work timings, preserve one terminal failure marker, serialization and empty/unavailable old values. Internal construction follows ADR 0031 rather than retaining unreleased constructor overloads. toString includes safe history without endpoints/IDs/content/exception prose.

One final observer/completion; exact final receipt identity. Log first retry WARN, repeats DEBUG and recovery INFO with bounded content-free context.

## 4. Verification and order

1. Establish public black-box safety/lifecycle/observer regressions.
2. Policy, owner configuration and limits.
3. Bounded replay/provider integration.
4. Intermediate-versus-terminal boundaries and synchronous retries.
5. Async suspension/resumption/shutdown.
6. Retained scopes, diagnostics, documentation and full verification.

Deterministic clocks/randomness/barriers and loopback fixtures cover:
- Temporary RCPT/DATA refusal then acceptance; permanent/partial/uncertain/missing evidence; acceptance then cleanup failure.
- Recipient-specific refusal alongside successful mail; bounded service-wide backlog without cooldown.
- Differing/absent selected policies through cluster/failover/direct calls.
- Wire/content/signature/attachment-read/ID/provider/envelope stability.
- Resource bounds, concurrent reservations and complete cleanup.
- Cancellation/deadline at capture, cleanup, waiting, dispatch and queued resumption; late timers; one completion.
- Queue pressure, direct/caller-runs/custom shutdown; graceful draining with suspended/delegated work.
- Pinned pool reuse/retirement, per-invocation limits and pool-size-one reentrant observers.
- Batches/callbacks, custom/logging/third-party boundaries and unchanged unconfigured path.
- Receipt/exception identity, bounded history/rendering/serialization.
- Property/locks/provenance/named/Spring matrix/CLI daemon/classpath/JPMS parity.

Run focused suites, real JDK 11 library verification, modern non-live reactor/Javadocs, existing conformance, generated getter and CLI cache checks, website checks/build/internal links. Apply coding guide; remove generated license headers. No live mail, benchmarks or SpotBugs.

## 5. Documentation and delivery

Move #756 In Progress, retaining 10.0.0 and major feature only. Amend ADR 0030/related references with selected-schedule versus initiating-resource ownership.

Provide canonical retry walkthrough with schedules, memory/backlog limits, pinned replay, final outcome, uncertainty/partial stops, cancellation/shutdown and illustrative output. Update README, sending/configuration/failover/diagnostics/results/Spring/CLI/use-cases/field-guide and compiled examples. Keep application-owned durable retry recipe acting only after terminal completion.

Update state/ownership diagrams; assess infographic accuracy. Add Major Feature release entry and truthful companion progress. No migration entry for opt-in additive behavior.

Use existing branches; preserve staged PIPELINING/CHUNKING research, artwork and concurrent website/Journal/Case Study edits. No upstream release.
The initial delivery boundary required five uncommitted production-review groups: API/configuration; replay/provider; operation/scheduling/lifecycle;
scopes; diagnostics/docs. Review is now accepted, including the [API boundary cleanup](756-api-boundary-cleanup.md). On 2026-10-14 the maintainer
authorized selective semantic commits, publication of both repositories and closing #756 with a usage example. Do not reopen completed issues.

## Execution evidence

Implemented and verified locally on 2026-10-13. Production-only review remains pending; all feature changes are uncommitted and unpublished.

### Passed verification

- Full real-JDK-11 library `clean verify`: `C:/workstation/sjm-verification-756/jdk11-verify-final.log` (9 minutes 19 seconds). Subsequent scope and final retry changes were checked again on Java 11 with Javadocs and classpath/JPMS consumers.
- Complete modern-JDK non-live reactor, Javadocs and generated CLI caches: `mvn verify -Ppublish-cli -Dlicense.skip=true`; `C:/workstation/sjm-verification-756/jdk21-full-verified.log` (13 minutes 38 seconds). The main module passed 1,694 tests; the CLI process tests and remaining reactor modules passed too. Performance-only tests remained disabled.
- Latest Java 11 retry/configuration/provider/diagnostic/rate tests plus Boot 2.7.18 / Spring 5.3.39 / SLF4J 1.7.36: `jdk11-final-retry-and-boot2.log` (2 minutes 36 seconds).
- Boot 3.0.13 / Spring 6.0.14 / SLF4J 2.0.9 on Java 17: `boot3-java17-matrix.log` (29 seconds).
- Boot 3.5.16 / Spring 6.2.19 / SLF4J 2.0.18 on Java 21, including the final added retry regressions: `boot35-java21-final-retries.log` (2 minutes 13 seconds).
- The latest retry suites include 18 public SMTP wire cases and four scheduling cases. They cover concurrent pooled sends, reentrant observation, signed/exact replay, recipient-specific independence, exhaustion, rejected resumption and receipt identity. A deterministic sending-limit regression proves that resumed allowance waiting holds no ordinary lease.
- `python tools/smtp-conformance/run.py --mode all`: passed 518 named scenarios, including 24 Postfix/Exim cases, with ten separate independent protected-content checks. Sanitized local evidence: `target/smtp-conformance/20261013T010037Z-20522dd9/evidence`. The runner cleaned up its own Docker project; the unrelated comments service stayed running. This is not a hosted CircleCI result.
- Website `npm run check`: 84 tests plus Eleventy/TypeScript checks passed. Clean build and internal-link verification passed: 4,091 local links across 67 pages.
- All seven Mermaid diagrams in the updated operation, lifecycle and retry pages rendered through the installed headless Edge browser. Final check covered 447 relative documentation targets across 22 files; no missing targets. Replaced a pre-existing deleted prompt-file link with its verified immutable GitHub archive.
- Downloadable field-guide Java examples compiled on Java 11; their 12 offline checks passed without SMTP.
- Applied the coding guide. `mvn license:remove` passed after safe header cleanup; no generated headers or empty Java sources remain. Build/test commands used `-Dlicense.skip=true` after a Windows mapped-file failure in the license plugin; source backups and final cleanup checks are retained under `C:/workstation/sjm-verification-756`.

### Corrections and review boundaries

Verification caught and corrected typed CLI parsing for fractional jitter/large byte counts, diagnostic handling of non-SMTP sentinel codes, misleading delay history when cleanup/admission prevents a retry, and allowance-before-reacquisition ordering. Continuation construction/startup failure also retires its ownership. The new dedicated-scope regressions preserve the existing non-clustered scope behavior and prohibit retry replacement of a connection closed by SMTP 421.

Extra regressions added after the full reactor passed were run on both Java 11 and Java 21. Initial failures from new tests were corrected rather than counted as green: their connection assertions now respect existing failed-lease invalidation, signing uses the established DNS-free fixture identity, and setup bounds allow cold provider initialization without imposing a throughput assertion.

Both original staged indexes and all 35 pre-existing dirty files were verified unchanged. Feature files are unstaged for review. A verified stale, empty root Git lock was moved recoverably to the task verification folder. No commits, pushes, issue closure, upstream releases, live-account sends, benchmarks or SpotBugs runs were performed.

Review in five production groups: API/configuration; replay/provider; operation/scheduling/lifecycle; retained scopes; diagnostics/demo/documentation. #756 remains open, milestone 10.0.0, `major feature` only, In Progress.

### Review follow-up: central defaults, 13 October 2026

The accepted first API batch was followed by a defaults-ownership clarification in ADR 0003 and the API expansion workflow.
Failover defaults and optional submission-retry backoff/jitter defaults now come from `MailerGenericBuilder`; Java policy builders,
resets and property/CLI resolution use the same constants. Required submission counts/delays remain required, and the fixed
failover maximum still follows the configured initial interval. No effective default or sending behavior changed.

All 21 focused policy/configuration/boundary regressions passed on real Java 11; core verification passed on Java 21. Fresh Javadocs were
checked for constant links rather than relying on an old packaged HTML cache. The failover cleanup and documentation clarification were
committed separately as `953e177c` under #755, without push or sign-off. All #756 changes remain uncommitted; staged #699 research is unchanged.

The broader Java 11 failover regression run did not pass: `ConnectionFailoverTest.aDueRecheckDoesNotHoldOtherSendsAwayFromTheWorkingDestination`
timed out, then failed again alone. A clean archived HEAD control (`4a9ee1cdca4ee3e2a4c375e4a65d0b2f8f31585e`), containing neither this
cleanup nor #756, also failed that concurrent-recheck fixture. Logs remain under `C:/workstation/sjm-verification-756/defaults-*.log`.
This is an unresolved pre-existing verification limitation, not a green failover-suite result or a diagnosed production defect.
No timing threshold was increased and no test was weakened; investigate the fixture/underlying scheduling separately.
The disposable baseline snapshot is outside the repository at `C:/workstation/sjm-verification-756/defaults-baseline-20261013`.
Environment policy blocked its cleanup; it remains separate from the source checkout and can be removed after retaining the logs/report.

## Review refinement: one retry policy, separate runtime owners (2026-10-13)

The user accepted grouping waiting capacity, per-message replay size and aggregate replay memory into MailSendRetryPolicy.
The canonical Java Mailer API no longer has their standalone setters, resets or getters. OperationalConfig exposes the complete,
non-null supplied policy. A resources-only policy leaves its own schedule disabled, but bounds work selecting another retry-enabled
destination. Policy replacement/clearing and locked-field checks cover the complete value. The typed internal CLI facade preserves
existing CLI flags; property names, Spring paths and diagnostic groups remain unchanged.

Fresh verification for this refinement:

- Real Java 11 clean focused run: 46 policy, configuration, scheduling, replay-boundary, independent-provider and SMTP black-box tests
  passed (`grouped-policy-jdk11-clean-tests.log`). The new failover regression proves a resource-only initiator's limits apply while
  the selected destination supplies its schedule. The initial incremental run had stale compiled callers of the changed unreleased
  getter signature; a clean rebuild corrected that build-artifact mismatch.
- Modern-JDK reactor verify with focused tests, fresh Javadocs, classpath/JPMS consumers and regenerated CLI caches passed
  (`grouped-policy-modern-verify.log`). A final CLI run includes resource-only overrides after clearing and daemon-profile separation
  for different memory limits (`grouped-policy-cli-final.log`). Both getter-to-builder and default-constant links were inspected in rendered HTML.
- Real Java 11 library packaging/verification with tests skipped additionally ran the classpath/JPMS consumers
  (`grouped-policy-jdk11-consumers.log`). This is separate from, not a claim of another full test-suite run.
- Spring retry/loading, starter discovery and metadata packaging passed in all three existing combinations: Boot 2.7.18 / Spring
  5.3.39 / Java 11; Boot 3.0.13 / Spring 6.0.14 / Java 17; Boot 3.5.16 / Spring 6.2.19 / Java 21 (`grouped-policy-spring-*.log`).
- Website check passed all 84 tests; clean build and internal-link verification passed 4,091 links across 67 pages.
  The modified downloadable FieldGuideExamples.java also compiled with real Java 11.
- Coding-guide checks and license:remove passed; no generated license headers or empty changed Java files remain.
  Root and website staged binary diffs match their before-task snapshots exactly.

All logs are under C:/workstation/sjm-verification-756. No full-suite rerun, push, commit or GitHub-state change was performed for
this refinement. The separately documented pre-existing concurrent-recheck fixture limitation remains unresolved.
Resume production review with the revised grouped-policy API, then its shared property/CLI resolution and runtime-owner access.

## Review follow-up: explain non-obvious choices at their point of use

The Angus review exposed a rationale present in the review narrative but missing beside setAllow8bitMIME(false). A targeted
production-source pass now explains optional MIME conversion versus already-8-bit content, the preservation requirement for
unsigned frozen messages, Angus sendpartial precedence, header-copy versus byte ownership, serialized-content accounting versus
heap overhead, reservation confinement, duplicate safety versus temporary evidence, bounded delay arithmetic, intermediate
failure handling, provider-entry accounting, waiting-permit lifetime, and cleanup detachment. Stale facade/history Javadocs were
corrected and duplicate imports removed. These are comments, documentation and import cleanup only; runtime logic is unchanged.

Twenty focused Java 11 boundary, scheduling and diagnostic-recorder tests passed (retry-why-comments-jdk11.log). No full-suite
rerun, commit, push or GitHub change was performed for this follow-up. The accepted diff batches remain accepted.

## Review follow-up: structural coding-guide pass (2026-10-13)

The user paused production review to request a complete guide pass over the pending retry code, rather than another comment-only cleanup.
The pass covers the public policy/diagnostic API, property and CLI resolution, Spring metadata, provider/replay boundaries,
operation scheduling, retained scopes, diagnostic recording, tests/fixtures, the observer demo and the downloadable retry example.
Unrelated research, artwork and Journal changes remain outside this scope.

Changes are intended to preserve behavior:

- Whole-message refusal eligibility now reads as named safety vetoes followed by positive temporary-refusal evidence.
- Provider invocation, invocation history, resource retirement and retry-or-completion are separate steps. A small private
  invocation-result holder carries their supplied facts; it is not another operation state machine.
- Timer activation, synchronous waiting, replay cleanup and observer-before-completion publishing have named boundaries.
  Atomic transition order, waiting-ticket lifetime and executor admission outside bookkeeping monitors are unchanged.
- Delay calculation separates capped backoff from jitter. Snapshot capture separates representation selection, bounded
  serialization and provider preparation. The two Angus facades reuse the existing header-copy helper.
- Policy validation, configured-schedule completeness, numeric parsing and submission-history validation are named rules.
  Public getters and internal CLI methods link to their builder contracts; defaults remain on the key builder.
- Test plumbing is extracted without weakening assertions, barriers or timeouts. Twenty named eligibility-matrix cases
  pin temporary evidence, unsafe guidance, ambiguous acceptance, contradictory recipient facts and non-temporary replies.
  The shared EmailHelper fixture was corrected to use the already-accepted grouped OperationalConfig constructor.

Verification for this pass:

- A clean real-Java-11 focused run passed 101 tests, including replay/provider boundaries, scheduling, configuration,
  diagnostic recording, sending limits and all 19 SMTP retry wire cases (`retry-style-jdk11.log`).
- Modern-JDK reactor verification with focused tests, refreshed Javadocs, classpath/JPMS consumers and regenerated CLI
  caches passed (`retry-style-modern-verify.log`). Getter-to-builder links were checked in the rendered policy Javadocs.
- All three existing Spring compatibility lanes passed, including broader configuration, operation and observer checks:
  Boot 2.7.18/Spring 5.3.39/Java 11, Boot 3.0.13/Spring 6.0.14/Java 17 and Boot 3.5.16/Spring 6.2.19/Java 21
  (`retry-style-spring-*.log`).
- Website checks passed all 84 tests, Eleventy dry-run and TypeScript checks. The downloadable guide compiled with real
  Java 11 (`retry-style-website-check.log`).
- Real-Java-11 packaging/verification with tests skipped also ran the classpath/JPMS consumers successfully
  (`retry-style-jdk11-consumers.log`); this is additional compatibility verification, not another full test run.
- Whitespace and changed/new-line width checks passed. `mvn license:remove` passed; no generated headers or empty changed
  Java files remain. Root and website staged binary diffs still match their before-pass snapshots exactly.

No full-suite, SMTP-conformance or benchmark rerun is claimed for this style pass. The separately recorded pre-existing
concurrent-recheck fixture limitation remains unresolved. No commit, push, staging or GitHub-state change was performed.

## Review follow-up: named diagnostic snapshot construction

The initial builder candidate below is superseded by the internal-construction correction in ADR 0031; its verification remains a historical record.
The user accepted a builder for MailSendDiagnostics before reviewing the recorder's final assembly. That initial candidate followed the existing
SmtpConnectionReport convention: Lombok generates named fluent assignments and build(), calling the same validating constructor.
Original constructors and serialVersionUID remain unchanged. The builder requires the total and six original measurements;
optional waiting measurements explicitly mean not recorded, endpoints are unknown, histories are empty and omitted counts are zero.
It reads no configuration, measures no work and adds no builder allocation to the unobserved fast path.

The recorder now supplies every report field by name instead of passing 15 positional arguments. Added focused tests for distinct
field mapping, required omissions, optional absence, immutable collection copying, builder reuse/isolation, validation and serialization.
Classpath and JPMS consumers also construct a report without Angus. ADR 0026 records this construction decision. The website's
Analyzing send results page now shows abbreviated retry output directly and explains the construction boundary.

The real-Java-11 focused run passed 56 tests (`diagnostics-builder-jdk11-tests.log`). Website checks passed all 84 tests
(`diagnostics-builder-website-check.log`). The first modern run passed the diagnostic tests but failed the extra SMTP fixture's
five-second initial-refusal assertion before report construction (`diagnostics-builder-modern-verify.log`). That fixture has no
observer, so the new report builder is not invoked on its sending path. Its timeout and assertions have not been changed.
The unchanged extended modern rerun failed the same assertion (`diagnostics-builder-modern-confirmation.log`). A read-only local
hostname lookup took 4,958 ms (`diagnostics-builder-hostname-check.log`). The Jakarta Mail 2.1.5 sources show that generated Message-IDs
can resolve the local canonical hostname when Session mail.from/mail.host is absent, matching the five-second gap before MIME logging.
This supports a local startup/DNS explanation, not a diagnosed retry or builder defect. The fixture remains unchanged and its failures remain recorded.

Modern reactor verification with the focused builder/diagnostics/CLI tests and the observed byte-preserving SMTP retry case passed
(`diagnostics-builder-focused-modern-verify.log`), including generated CLI caches, Javadocs and packaged classpath/JPMS consumers.
The failing unobserved startup fixture was excluded from that narrower run; this is not a claim that the extended run or full suite passed.
Real-Java-11 packaging with tests skipped also ran both packaged consumers successfully (`diagnostics-builder-jdk11-consumers.log`).
Fresh Javadocs include the explicit builder factory and construction contract. Coding-guide/whitespace checks and license:remove passed;
no generated headers or empty touched Java sources remain. Existing staged research and the website index are unchanged. The new builder
test is staged, but no staging command, commit, push or GitHub-state change was performed in this turn.

## Review correction: internal construction and no telescoping constructors (2026-10-13)

The diagnostics builder is implementation machinery, not an application API. ADR 0031 records that distinction, the existing internal-package
convention and the prohibition on telescoping constructors. The API expansion and maintainer workflows now check it explicitly.

Moved the builder into `org.simplejavamail.internal.mailer`, marked it `@ApiStatus.Internal`, and removed the public report's builder factory,
nested builder and four positional constructors. One `@ApiStatus.Internal`/`@hidden` bridge accepts the internal builder and validates/copies
its observations. Lombok generates the builder's fluent read/write accessors; its `build()` calls that bridge. Required measurements, optional
absence behavior, immutable histories, serialized fields and serialVersionUID remain unchanged. The unobserved recorder still returns before
constructing the builder.

Migrated internal callers/tests to the internal builder. Application classpath/JPMS fixtures now compile inspection methods rather than
demonstrating unsupported report construction. Removed the website paragraph advertising construction; the approved sample output remains.
Added a regression requiring no public builder factory/nested builder and only the single internal construction bridge.

Verification passed:

- Real Java 11: 57 focused model, recorder, observer/dispatch and retry-scheduling tests (`diagnostics-internal-builder-jdk11.log`).
- Modern-JDK reactor verification: 43 selected tests, fresh Javadocs, generated CLI caches and packaged classpath/JPMS consumers
  (`diagnostics-internal-builder-modern-verify.log`). This is focused verification, not a full-suite rerun.
- Inspected fresh public diagnostics HTML: getters are present; constructor and builder are absent. Verified the bridge's compiled Internal annotation.
- Website checks: all 84 tests, templates and TypeScript (`diagnostics-internal-builder-website-check.log`).
- Relative documentation links, changed Java line widths/whitespace, and `mvn license:remove` passed
  (`diagnostics-internal-builder-license-remove.log`).

The broader result/SPI construction audit is recorded as pending, not silently included in this correction. No commit, push or GitHub state change.

### Module-boundary clarification after review

The maintainer clarified that `core-module` is the API-bearing artifact and `simple-java-mail` is the main implementation, following the established
Email/Mailer builder split. Moving the diagnostics builder into an internal package in core was therefore only a partial correction. Its concrete
assembly belongs with the recorder's implementation; the public result must retain a core-owned contract without a reverse module dependency.

ADR 0031 and the API/maintainer workflows now distinguish module placement from visibility and require a core-only continuation compile check.
`MailSendMeasurement` is already in core. The recipient builder interfaces are also in core, while their concrete main-module entry points and
`SimpleJavaMail.converter()`'s concrete main-module return type remain audit targets. The recipient builders existed in released `9.3.5`, so any
entry-point migration needs an explicit compatibility decision. This clarification changes documentation only; it does not claim those moves or
the new compile check are implemented or verified yet. No commit, push or GitHub state change.

## Final review and delivery approval, 2026-10-14

The earlier construction candidates and pending boundary audit above are historical checkpoints. The completed cleanup follows
[ADR 0031](../adr/0031-public-api-and-internal-construction.md); its final scope and full-reactor evidence are in the
[boundary implementation record](756-api-boundary-cleanup.md).

The final holistic review found no further runtime defect. Corrected the API workflow's template-selector guidance and clarified on the website
that resource limits alone do not enable a retry schedule. The reviewed code and documentation were accepted for publication.

Fresh final-review verification passed 167 focused Java 11 tests, including retry boundaries/scheduling, SMTP retry contracts, provider boundaries,
converter/receipt behavior, historical Email serialization, diagnostics and allowance-before-reacquisition. Packaged core-only, provider-neutral
and third-party classpath/JPMS consumers and Javadocs also passed. Website checks passed all 84 tests after the final wording correction;
`license:remove` completed across the reactor. Logs are `%TEMP%/sjm-retry-holistic-review-java11.log`,
`%TEMP%/sjm-retry-holistic-review-boundaries.log`, `%TEMP%/sjm-retry-holistic-review-license.log` and
`%TEMP%/sjm-retry-doc-corrections-check.log`. The full modern-JDK reactor, Spring matrix and clean website build retain their separately recorded
earlier evidence; this final review did not rerun those full lanes or claim a fresh hosted CI result.

Before publication, exported the selected website commit without concurrent Journal/Case Study work and verified it independently.
Its committed test selection passed all 78 tests; clean build and 4,069 internal links across 67 pages passed. The earlier 84-test count
includes concurrent website test additions deliberately excluded from this delivery. Logs are in the task's temporary delivery directory,
alongside the exact commit-selection manifests. No live website deployment or SJM release was performed.
