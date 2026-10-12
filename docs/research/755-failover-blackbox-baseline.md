# #755: Public-boundary failover baseline before the recovery refactor

Date: 2026-10-11. Scope: Stage A of the [accepted follow-up plan](https://github.com/bbottema/simple-java-mail/blob/c8baad90556a37de3fb7b23c4e39ed5c36c049ec/docs/plans/755-smtp-connection-failover.md#accepted-follow-up-black-box-regression-gate-then-explicit-recovery-state), preserved in commit-pinned history after completion.

This is a regression gate, not a declaration that failover is finished. Production code and upstream APIs are unchanged by this pass. Stage B needs a separate
review acceptance. Correct failing regressions remain enabled; none is disabled or changed to assert the defect.

## What the tests observe

All new feature tests live in `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/failover`, outside the implementation package. They use factory,
Email/Mailer builders, execution views, observers, completions and public results. They neither reflect into the engine nor mock routing/module availability.

The ordinary run has the real batch module and upstream pools. `FailoverDirectClasspathTest` starts a separate Java process, removes the batch, SMTP pool,
clustered pool and generic pool artifacts, verifies their absence from that process's classpath, then runs the common JUnit suites. It records each child
summary under `modules/simple-java-mail/target/failover-blackbox-direct`. Assertions are the same in both runtimes.

`RecordingSmtpServer` layers a connection/command/DATA ledger over the existing scripted SMTP fixture. A DATA record is one complete SMTP transaction,
not proof of acceptance or delivery. Wiser separately verifies delivered MIME and envelope recipient occurrences. Its six deliveries for two messages with
three recipients must not be mistaken for six submissions. The fixture accepts zero connections, propagates worker assertions, and closes its sockets and
workers. Barrier-driven cases use bounded watchdogs rather than asserting that a send finishes within a tiny timing tolerance.

`FailoverTestProvider` is a test-only ServiceLoader adapter for its own Jakarta Mail Transport. It injects provider/cleanup failures through the supported SPI;
it does not participate in real Angus tests. These supplemental cases are explicitly distinguished from real SMTP/TLS evidence.

## Coverage map

| Contract | Regression suite |
| --- | --- |
| Healthy A, eligible A/B, cross-factory routing, bounded attempts, A/B/C and receipt-free exhaustion | `FailoverSmtpContractTest` |
| No replay after recipient rejection, partial acceptance, final DATA rejection or missing final reply; observed/unobserved paths | `FailoverSmtpContractTest` |
| Independent authentication permission, required STARTTLS and explicit unsupported DSN; successful reuse after local compatibility failure | `FailoverSmtpContractTest` |
| Actual TLS certificate rejection with both authentication/certificate permission combinations | `FailoverTlsContractTest` |
| One due recheck while B continues, A/B/A restoration, overlapping success/failure in both orders, cancelled acquisition | `FailoverRecoveryContractTest` |
| Cancelling a recheck cannot discard an independent successful connection | `FailoverDirectRecoveryContracts` (pool-free process only) |
| Wiser-selected templates, mandatory archive BCC, original Email reuse, exact OpenPGP-signed bytes, attachment-read comparison | `FailoverScopeContractTest` |
| Lazy batch iteration, first-failure boundary, successful/failed recovery between untouched emails, pinned callback and escaped sender | `FailoverScopeContractTest` |
| Admission rejection without opening an iterator, rate waiting/cancellation and a measured rate-wait deadline, setup deadline, delegated shutdown and mid-batch cancellation | `FailoverScopeContractTest` |
| Ordinary round-robin cluster ownership, capacity timeout and reuse, pool-size-one observer reentry | `FailoverPoolContractTest` |
| Ordinary/cleared-failover replenishment; recorded background outage respected by subsequent foreground sends | `FailoverPoolContractTest` |
| Opaque provider setup, preflight failure with connection-looking cause, CustomMailer and logging-only, caught failure followed by cleanup failure | `FailoverProviderContractTest` |
| Acceptance followed by direct-close failure retains acceptance receipt and does not replay | `FailoverDirectProviderContracts` (pool-free process only) |
| Unused listener and worker-assertion propagation | `RecordingSmtpServerTest` |

The core SMTP cases run synchronously and asynchronously. Race cases explicitly use concurrent caller-owned workers when necessary: the no-batch default
executor has one worker, so merely setting its requested thread count is not enough to establish overlap. One pool also serializes connection allocation.
The two-order observation test therefore uses two Mailers created from the same named definition: shared recovery history, separate pools. The same-Mailer
cancelled-recheck reproduction runs only in the pool-free process, where that physical overlap can happen. Pool-specific tests are a separate suite, not skipped
cases inside the pool-free run. No assertion reads a private clock, recovery state or lease implementation.

## Confirmed regression being carried into Stage B

`cancellingARecheckMustNotDiscardAnIndependentSuccessfulConnection` reproduces the observation-ownership defect using only public builders, sends and sockets:

1. A routed send records that a destination is unavailable.
2. A due recheck fails its first configured retry, then holds its second connection attempt.
3. An independent send through that destination starts another connection while the routed recheck is active.
4. Cancel the routed recheck; let the independent connection succeed and submit its email.
5. Send through the original route again. It must use the now-healthy destination, rather than skip it as still unavailable.

The pool-free engine instead exhausts the route with zero new acquisitions and two cached skips. This is no longer only a helper-level/reflection probe.
The failed assertion is deliberately preserved. It motivates explicit observation ownership; it does not authorize starting that refactor before review.

## Baseline runs and limitations

Initial harness bring-up identified and corrected fixture issues: direct concurrency needed an explicit executor; socket watchdogs had to outlive barriers;
Windows child output needed an explicit UTF-8 encoding; and CustomMailer exceptions retain their established public wrapper rather than becoming the raw callback
exception. The pooled overlap fixture also had to use separate instances of the same named definition instead of assuming one pool allocates concurrently.
Do not count these fixture corrections as engine defects.

The final suite contains 120 scenario executions per JDK: 55 common cases in each runtime, six pool-only cases, two direct-only cases and two fixture checks.
This deliberately counts the same contract twice when it runs against genuinely different execution runtimes. The seven parent JVM-launcher invocations are
not seven additional behavior scenarios.

| Runtime group | Scenarios | Java 11.0.16.1 | Java 21 |
| --- | ---: | --- | --- |
| Common suite with real pools | 55 | 55 passed | 55 passed |
| Pool-specific suite | 6 | 6 passed | 6 passed |
| Common suite with all batch/pool artifacts absent | 55 | 55 passed | 55 passed |
| Direct-only supplemental contracts | 2 | 1 passed, 1 confirmed regression | 1 passed, 1 confirmed regression |
| Fixture lifecycle checks | 2 | 2 passed | 2 passed |
| **Stage A total** | **120** | **119 passed, 1 failed** | **119 passed, 1 failed** |

Both JDKs also passed 26 existing compatibility checks: four wire tests, two provider-discovery tests and twenty adapter-dispatch tests. These protect the extended
fixture's existing callers and the test-only ServiceLoader registration; they are not included in the new-suite total.

Java 21 evidence is retained under the ignored `target/755-baseline-evidence/jdk21` directory. The main log is `target/755-blackbox-jdk21-baseline.log`.
The rate-wait deadline case was then added and passed separately (`target/755-blackbox-jdk21-rate-wait.log`); all fifteen scope cases were rerun in the pool-free
process (`target/755-blackbox-jdk21-scope-final.log`). No test is skipped or aborted. The complete main run returns a failure status because the recovery
regression remains enabled; the targeted passing reruns do not turn that baseline green.

The complete final Java 11 run is recorded in `target/755-blackbox-jdk11-final.log`, with copied XML and child summaries under
`target/755-baseline-evidence/jdk11`. Surefire reports 96 invocations, including seven child-JVM launchers and the 26 existing checks. Its single failure is the
launcher surfacing the one direct recovery regression, not a second defect. Child results add 57 actual direct-send scenarios. No test was skipped or aborted.

**Review checkpoint:** Stage A is complete with the confirmed red test preserved. Stage B has not started. Review the new contracts and this baseline before
changing recovery state, route boundaries or upstream claim identity.

### What still needs narrower verification

This suite protects representative user-visible timelines, not every permutation of the recovery model. The following remain explicit Stage B obligations:

- Exact backoff arithmetic, old-owner closure, stale observation fencing and replacement-registration identity need deterministic model/upstream tests.
- One pooled registration cannot reproduce overlapping physical allocations because its allocator is serialized. Shared named-definition instances cover
  overlapping success/failure; the cancelled-owner/independent-success reproduction remains direct-only. The future claim-identity plumbing also needs its own
  upstream and batch-engine tests.
- The supplemental provider tests inject direct-close and retained-scope cleanup failures. They do not claim to inject every poisoned-lease invalidation or
  pool-release failure over an SMTP socket; existing lower-level cleanup regressions remain necessary.
- The certificate suite proves real TLS rejection and independently configured routing permission. It does not replace the existing successful-TLS conformance
  lane or claim that a local acceptance guarantees onward delivery.
- Rate-wait cancellation is observed through public execution/completion. The separate deadline case additionally requires a measured rate-limit wait in the
  terminal diagnostics, so an active worker alone is not treated as proof that the limiter was reached.

### Reproduction

From the repository root, using a real JDK 11:

```powershell
mvn -pl modules/simple-java-mail -am test `
  '-Dtest=Failover*ContractTest,FailoverDirectClasspathTest,RecordingSmtpServerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.forkCount=1' `
  '-Dlicense.skip=true' '-Denforcer.skip=true' '-Dspotbugs.skip=true' '-Djacoco.skip=true' `
  '-Dlog4j.configurationFile=log4j2-failover-contract.xml'
```

This intentionally returns a failing exit status while a confirmed red regression remains. Child JVM failures are surfaced by the parent test and detailed in
their individual text reports. Count child scenario results separately from the parent launcher's test count to avoid double counting.

This pass does not claim a full reactor, new public API compilation, website build, live-account test, benchmark or SpotBugs run. Those broader verification
obligations belong to the post-refactor stage. Existing SMTP conformance tests and lower-level clock/lease tests remain complementary, not replaced.

The coding-guide pass keeps the test narrative at the public operation level and confines protocol mechanics to reusable test fixtures. The new suites contain
no implementation-package imports, reflection hooks, mocked module discovery or disabled cases. Touched Java stays within 160 columns; scoped whitespace and
relative-document-link checks pass. Builds skip header generation, and no generated license headers are present in the Stage A sources.

### Provenance

- Root branch: `codex/10.0.0`, HEAD `77ccadaa30b8f4955852ce46d659ad0d55411c57`, with the already-reviewed uncommitted failover implementation.
- SMTP pool: local `4.4.0-SNAPSHOT`, checkout HEAD `0e12e3925ba61af98034377429569ae877041cf7` plus existing work.
- Clustered pool: local `4.3.0-SNAPSHOT`, checkout HEAD `cd55bb3572591c5426b66daa2a95c301ca36d620` plus existing work.
- Generic pool: local `2.6.0-SNAPSHOT`, checkout HEAD `c9b0cb7a0a1dad637dfdf50838a951e843b26a42` plus existing work.

These dependencies are locally verified snapshots, not newly published releases. No GitHub state, commits, remote branches, website or Journal files are changed
by this Stage A pass.
