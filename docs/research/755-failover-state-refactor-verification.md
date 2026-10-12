# #755: Explicit recovery-state refactor

Dates: 2026-10-11 and 2026-10-12. Scope: Stage B of the [accepted follow-up plan](https://github.com/bbottema/simple-java-mail/blob/1faa25df4ba6a7ce4d6ce308c2458250a4e999af/docs/plans/755-smtp-connection-failover.md#accepted-follow-up-black-box-regression-gate-then-explicit-recovery-state), preserved in commit-pinned history after completion.

This report follows the [Stage A black-box baseline](755-failover-blackbox-baseline.md), which remains an unchanged historical record of the confirmed defect.
The implementation is based on local checkpoint `0594624f822809b22a4c9d0f23b435281eeab22e`. This follow-up changes internal ownership, not the public sending API,
failover permissions or the connection-only safety boundary. Dependency publication and feature release remain separate decisions.

## What changed

- `ConnectionRecovery` now holds one of three private states: `ConnectionAttemptsAllowed`, `ConnectionFailureRecorded` or `ConnectionRecheckInProgress`. A recheck owns its observation tickets explicitly;
  an unrelated connection no longer inherits whichever recheck happens to be active. Cancellation retires its owner's tickets, not independent work.
- Failed setup keeps the current observation generation open for an overlapping success. Successful usable acquisition clears the incident and fences older
  results. A due recheck starts a new generation, and a failed recheck visit advances backoff once, regardless of its configured immediate attempts.
- An opaque claim identity travels through generic, clustered and SMTP pools. The batch engine associates foreground observations with that identity until
  claim settlement, then removes the association. Background allocation keeps its own observation lifetime. No `ThreadLocal` or Session policy was added.
- Physical connection success is provisional until lease handoff and cancellation checks succeed. A reused healthy lease can establish recovery; an abandoned
  or cancelled claim cannot. Terminal observation callbacks publish once.
- `MailConnectionRoute` returns an owned, acquired destination before submission. Its continuation catch encloses only final connection acquisition, not
  governance, allowance waiting, MIME conversion or provider invocation. The old submission-started routing flag is gone: provider failures cannot re-enter
  the route through that call stack, including when diagnostics are disabled.
- `RoutedConnectionScope` shares explicit connection/proxy/participant ownership between ordinary and retained sends. `ReadyMailSend` adds one prepared email
  and reservation; `RoutedMailConnection` keeps per-email work separate for retained scopes. Cleanup preserves primary failures and already-produced receipts.

There is no general-purpose state-machine framework, rules engine, new executor, public health API or automatic submission retry. The shared infographic's
resource layers remain accurate. The [architecture guide](../failover/architecture.md), [concurrency contracts](../concurrency/10-connection-failover.md) and
[ADR 0029](../adr/0029-smtp-submission-failover.md) describe the new code-level boundaries.

## Regression discipline

Stage A's behavioral assertions and socket fixtures are unchanged. In particular,
`cancellingARecheckMustNotDiscardAnIndependentSuccessfulConnection` remains enabled and now passes in focused runs.

The unchanged complete suite passed in isolated Linux. On Windows, the capacity-release and observer-reentry fixtures' first sends sometimes outlasted the
default five-second pool expiry, making a healthy replacement connection fail their reuse assertions. Those two fixtures now configure a 60-second lifetime
explicitly. Their connection-count, submission, observer-completion and unused-failover assertions are unchanged; expiration is not what these cases test.

Additional deterministic tests exercise observation completion orders, ownership cancellation, old-generation results, healthy reuse, cancelled handoff,
cleanup failure and removal of scoped claim associations. They complement rather than replace the socket tests. Existing diagnostics tests only adapt their
pool-acquisition test doubles to the explicit observer argument; their result and exception assertions are preserved.

## Verification results

The focused runs pass. The full suite was subsequently rerun with Windows certificate-store trust inherited by the test JVMs and CLI subprocesses.
Its last Windows certificate exception was resolved after the user changed Norton's protection setting, with the original test and all 34 socket-factory
cases passing unchanged. The earlier failures and their verification remain recorded below.

### SJM and the black-box gate

- **Real Java 11.0.16.1:** clean library `verify` passed, including Javadocs and classpath/JPMS consumers. Its focused selection ran 363 parent-process test
  invocations and 57 pool-free child-process scenarios with no failures or skips. Seven parent invocations are child-suite launchers, not additional behavior
  scenarios. The root aggregator was excluded from cleaning to preserve the verification evidence; library module targets were rebuilt cleanly.
- **Java 21:** after correcting an intermediate exception-wrapping regression, the same focused selection passed except for the observer-reentry fixture's
  implicit connection expiry. With both reuse fixtures' lifetimes made explicit, all six pool-contract cases passed in another `verify`, together with
  Javadocs and public classpath/JPMS consumers. A final standalone Java 11 run of those six cases also passed.
- **Unchanged Stage A suite in Linux:** all 120 behavioral scenarios passed before either pool-fixture lifetime adjustment. This includes the original red
  cancellation/independent-success regression and the separate JVM with batch/pool artifacts absent. The same run passed 82 TLS/socket-factory cases.
  Its reports contain 152 parent invocations plus 57 child scenarios; subtracting the seven launchers gives 202 actual scenarios.
- **Real Postfix and Exim:** all 24 conformance cases passed, followed by the independent DKIM, S/MIME and OpenPGP content checks for both servers. This used
  a frozen copy of the newly built Stage B classes and dependencies, compiled for Java 11 and executed on Java 21. The existing server lifecycle, test
  scenarios and content-verification scripts were retained; the temporary launcher only avoided another simultaneous build in the shared checkout.

The focused selection includes recovery transitions and ownership, selected preparation, public failover contracts, send control/diagnostics/observers,
locked configuration, sending limits and scoped senders. Deterministic state tests cover both observation completion orders and cancelled ownership;
they do not replace the public socket-level regressions.

### Full modern-JDK run and its follow-ups

The non-live `publish-cli` reactor ran 2,238 parent-process test invocations, with 13 assertion failures, one error and one intentional performance-test skip.
It used `maven.test.failure.ignore=true` to collect all results; `BUILD SUCCESS` in that log is therefore **not** an all-passing test result.

Twelve failures exposed an intermediate wrapper that changed the identity/type of caller-facing preparation or submission exceptions. That wrapper was
removed; all affected cases passed in the subsequent Java 21 focused run and the clean Java 11 verification. The remaining results were:

- The pool observer-reentry fixture expired its connection during a slow first send on this host. The final Java 11/21 six-case runs pass with explicit
  fixture lifetimes, without relaxing reuse assertions. A later standalone run exposed the same assumption in the capacity-release fixture, which received
  the same adjustment.
- One socket-factory TLS case encountered the host's TLS-interception behavior. The unchanged TLS/socket-factory tests passed in isolated Linux; certificate
  verification was not weakened. This is not claimed as a passing Windows TLS case.

The first follow-up used targeted reruns. The final full-suite pass below replaces the earlier statement that the complete suite had not been repeated.

### Full-suite follow-up with Windows Store trust

On 2026-10-12, Maven, its test forks and nested CLI processes used `javax.net.ssl.trustStoreType=WINDOWS-ROOT`. `JAVA_TOOL_OPTIONS` supplied the property
to child JVMs, and the generated test reports confirm it. This run used the main module's configured eight test JVMs, rather than the earlier cap of two.

The first reactor segment ran 1,992 parent-process test invocations with zero assertion failures and one TLS error. The seven pool-free suite launchers
also ran all 57 child scenarios successfully. Maven's normal failure handling then skipped dependent modules. A second segment rebuilt the prerequisites
and ran all 246 Spring, starter and CLI invocations; it found one stale CLI help assertion, four CLI stderr assertions affected by the launch environment,
and the existing disabled performance case. The help assertion expected
"never to replay", whereas the approved, regenerated help says "never replays a submitted email". It now checks that wording and normalizes display
whitespace, consistent with the existing CLI probe-help test. The stderr failures contained only Java's own `Picked up JAVA_TOOL_OPTIONS` announcement.
All five plain-SMTP process tests subsequently passed with that environment entry completely removed and the Windows-store property passed directly to
Maven/test JVMs; their strict empty-stderr assertions remain unchanged. An empty environment entry still prints the announcement, so the successful command
removed the entry using PowerShell's explicit null-string value rather than leaving it empty.

After that correction, all three `CliFailoverConfigurationTest` cases passed in a further `verify -Ppublish-cli`, which also completed packaging,
Javadocs and the public classpath/JPMS consumers. At that point, the consolidated suite contained 2,238 parent invocations: zero assertion failures, the one
Windows TLS error described below and the existing disabled performance case. Those archived reports retain their original results. The successful Windows
rerun after disabling interception is recorded separately; no test source excludes the case.

The remaining TLS error is `ordinaryImplicitTlsFactoriesStillUseTheirCertificateTrustManager`. A focused JSSE handshake trace confirms that Norton replaces
the fixture's self-signed certificate with one issued by **Norton Web/Mail Shield Self-signed Root**. Windows' CurrentUser and LocalMachine trusted-root
stores contain **Norton Web/Mail Shield Root**, but not that separate self-signed root. Therefore switching to Windows Store does not authorize the substituted
fixture certificate. The unchanged test passed in the isolated Linux lane recorded above; certificate or hostname checks were not disabled.

After the user turned off Norton's Email Protection on 2026-10-12, the exact SMTPS test was rerun in a fresh Java 21 process with Windows Store trust.
It still failed: the server emitted the original fixture certificate, but the client received the Norton Self-signed Root replacement. This confirms that
interception remained active for this connection; the trace does not identify which Norton setting or component still enabled it. The rerun's JUnit report
and handshake trace are retained under `target/755-email-protection-disabled-single` and `target/755-email-protection-disabled-single.log`.

The user then followed another disable route. A fresh Java 21 process passed the exact SMTPS test, with Windows Store trust and all certificate checks
unchanged. The handshake trace now shows the original fixture certificate at both the server and client, with no Norton replacement. A second run passed
all 34 `SmtpSocketFactoryFallbackCharacterizationTest` cases, including the certificate-rejection and hostname-verification cases, in 4.174 seconds.
There was no source or assertion change for these reruns. This resolves the last known test failure; the latest coverage across the suite segments and
targeted reruns is 2,237 passing parent invocations and the one existing disabled performance case. This is combined verification evidence, not a claim
that all 2,238 invocations ran again in one final command. Evidence is retained under `target/755-email-protection-disabled-route2*`.

The first segment took **6m46s**, including **5m28s** in the main module. The second took **4m26s**, including **3m53s** in the CLI module. Their combined
Maven time is **11m12s**. The earlier two-fork run took **23m56s**, including **17m26s** in the main module and **4m58s** in CLI. These are full verification
times, including packaging and Javadocs, not measurements of email-send performance. The focused help correction and packaging verification took a further
59 seconds. The successful process-test environment rerun, including packaging, took another 2m01s; the preceding empty-environment retry took 56 seconds.
These corrective reruns are additional to the two suite segments. CLI metadata generation still logs the pre-existing Log4j/exec-plugin shutdown-hook
classloader error after successful verification; it also
appears in the initial reactor log and did not alter the test results or successful Maven exit status of the final packaging check.

The accidental generated checkout copy under `target/755-stage-b-conformance-classes/077-` was sent to the Windows Recycle Bin and verified absent from the
checkout. The source checkout and the retained verification classpath were not removed.

### Upstreams, documentation and source integrity

- Generic pool: 95 tests passed; Java 8 and Java 21 classpath/JPMS compatibility consumers, including a previously compiled consumer, passed.
- Clustered pool: 53 tests passed; the corresponding Java 8/Java 21 compatibility consumers passed.
- SMTP pool: 108 tests passed across its reactor on Java 21. Total upstream test count: 256, with no failures or skips.
- Website: 84 checks passed, clean build succeeded, and 3,949 internal links passed across 66 pages. No website or Journal source was edited by this refactor.
- Relative documentation links passed validation. Seven Mermaid diagrams in the architecture and concurrency guides parsed and rendered through headless Edge.
- The coding-guide pass covered the changed Java classes. Single-worker `mvn license:remove` succeeded in all four repositories. SHA-256 comparisons showed
  no source changes or empty files across 678 SJM, 37 generic-pool, 32 clustered-pool and 56 SMTP-pool Java files.
- The normal CLI build regenerated both metadata caches. Their source-API fingerprint is unchanged; the payload refresh contains previously accepted
  failover-help Javadoc formatting, not another CLI option or public sending API.

No live-account demos, standalone benchmarks or SpotBugs were run. The normal reactor's small performance-harness smoke checks are not a performance audit.
Local evidence is retained under ignored `target/755-stage-b-*` paths, `target/smtp-conformance/20261011T215935Z-6619bf5e`, and each upstream's
`target/755-refactor-*` logs. Generated logs and reports are not implementation source.
The final Windows-suite reports are consolidated under `target/755-stage-b-evidence/jdk21-final-windowsroot`; the replaced help assertion's original
failure report is retained separately. The certificate issuer trace is in `target/755-windowsroot-implicit-tls-diagnostic.log`.

## Boundary follow-up, 12 October 2026

A subsequent holistic review found two gaps outside the recovery-state transitions themselves:

1. A nonzero-core pool could start physical setup before an authenticated SOCKS destination's operation-owned local bridge existed. Its failure became
   a false SMTP incident and caused the first foreground send to skip a healthy primary. Destination background permission now includes immutable setup
   prerequisites. Authenticated bridges do not permit background SMTP creation, even while another operation temporarily has one running. Foreground
   acquisition still opens the bridge normally and reuses healthy leases. Anonymous proxies/direct SMTP retain normal recovery-aware allocation;
   logging-only and CustomMailer modes likewise start no background SMTP work.
2. Cancellation arriving while failed setup cleanup was blocked produced a stop exception without the caught setup failure or its suppressed disposal
   errors. `MailConnectionRoute.checkStoppedAfterConnectionFailure` now retains that complete evidence graph. It does not freeze terminal failure
   classification for a remediable connection failure, and it never authorizes another destination after a recorded stop.

Eleven new test cases cover these boundaries: three public SMTP/SOCKS cases, four public provider-fault cases, and four deterministic destination-gate
cases. The provider-fault suite also runs in the separate pool-free JVM. Its assertions cover exact observer/completion failure identity, setup and
cleanup exception retention, one close, no provider submission, no alternative connection and no invented receipt. The SOCKS tests use real local
SMTP servers and SJM's authenticated bridge; the remote fixture permits anonymous access, so they do not claim to test a remote credential policy.
They cover sync/async core-pool startup, primary lease reuse, and genuine proxied setup failure still using failover.

Before production changes, nine selected cases ran against the pre-fix compiled implementation: seven failed and two control cases passed. The failures
reproduced both defects, including all four cancellation/cleanup combinations and both healthy-proxy execution modes. After the fixes, the complete
focused provider/proxy/gate selection passed all 19 cases. Red evidence remains under `target/755-holistic-fixes/red-reports` and `red.log`.

Fresh verification for this follow-up:

- **Real Java 11.0.16.1:** library `verify` passed with 482 parent test invocations and 61 pool-free child scenarios, no failures/errors/skips. The seven
  child-suite launchers are included in the parent count. Javadocs and the classpath/JPMS consumers passed. This selection also covers recovery,
  acquisition/cleanup, selected preparation, queue/control/observation, locks, sending limits, authenticated proxies and all 34 socket-factory cases.
- **Java 21:** the full non-live `publish-cli` reactor ran 2,249 parent invocations: 2,248 passed, zero failures/errors, and the existing disabled
  performance case. All 61 pool-free child scenarios passed. The original command then failed during CLI metadata generation because Windows held
  `cli.data` in a mapped section. A packaging-only retry, with tests deliberately skipped, succeeded for the complete reactor in 1m17s without a source
  workaround, process termination or test exclusion. These are an all-passing test run followed by successful packaging, not a single uninterrupted
  all-green invocation. The full test/build command took 13m26s; Java 11 verification took 3m43s.
- **Regenerated CLI metadata:** after that successful packaging retry, 11 focused help, API-fingerprint, cold-cache concurrency and serialization cases
  passed against the regenerated resources. No option or application-facing API was added by these fixes.
- **Documentation:** refreshed the control, registration, lease, Angus, cross-machine, sending-limit and recovery guides to name the current resource
  owners. Removed obsolete disposal-join exceptions and closure-owned proxy claims. All 366 relative links/anchors across 17 documents passed, and all
  39 Mermaid diagrams parsed and rendered. The shared infographic's resource layers remain unchanged.
- **Style and source integrity:** the changed implementation/test files were checked against the coding guide. Single-worker `mvn license:remove`
  succeeded; normalized hashes confirmed no non-header changes across 680 Java files, with no generated headers or empty files remaining.

The verification commands in that follow-up used `-Denforcer.skip=true` because the approved upstream work then existed only as local SNAPSHOT dependencies. Their
publication gate had not been bypassed or represented as complete. That follow-up changed no upstream source or public application API. It did not
rerun the separate Postfix/Exim conformance lane or Spring dependency-version matrix; their earlier results above remain dated evidence.

The final review retraced acquisition-only continuation, cancellation/cleanup, observation fencing, selected governance/allowance ownership, pool
attribution and retained batch/callback scopes. No additional defect was found in that pass. The two fixes enforce resource prerequisites and preserve
terminal evidence; they do not add another state machine, scheduler or general retry mechanism. Finite regression scenarios are not a proof against
every arbitrary provider/application interleaving. Logs and copied reports are retained under `target/755-holistic-fixes`.

## Delivery boundary

### Session ownership correction, 12 October 2026

The [additional ownership review and correction](../failover/code-review-2026-10-12.md#correction-reject-duplicate-live-session-ownership) reproduced a second accepted Mailer replacing the first owner's templates through a shared Session. The accepted correction rejects duplicate live Session/Properties ownership before mutation, retains the guard through resource draining and retires only the exact owner's context. It deliberately changes released Session-sharing behavior; public Javadocs and the website migration notes explain independent Sessions and reuse after completed shutdown.

Fresh verification passed 463 Java 11 parent cases and 61 pool-free child scenarios, including 12 new ownership cases. Modern-JDK coverage completed 2,260 passing cases plus the existing disabled performance case across an initial all-tests run and corrected-fixture/integration completion runs. Five old sharing fixtures were adapted without weakening their trust/settings assertions. Classpath/JPMS consumers, Spring/starter, regenerated CLI metadata and subsequent cache/integration tests passed. A forced Javadoc refresh verified the new builder contract in generated HTML. Website checks/build/internal links and all 39 current concurrency/architecture diagrams passed. Header-only cleanup preserved normalized hashes across 681 Java files, with no generated headers or empty files.

The report records the initial Windows formatter failure and recovery, red-before-fix evidence, command boundaries and source-integrity checks. No Postfix/Exim conformance lane or dependency-version Spring matrix was rerun for this constructor/lifecycle correction; their earlier results remain dated. At that verification boundary, the changes were uncommitted; no publication, push or GitHub mutation had been performed.

At the Stage B verification boundary above, no dependency had been published, no branch had been pushed and no GitHub state had been changed. The three dependencies
were locally verified snapshots: generic pool `2.6.0-SNAPSHOT`, clustered pool `4.3.0-SNAPSHOT` and SMTP pool `4.4.0-SNAPSHOT`. Publication followed separately below.
Unrelated maintainer documentation, staged artwork and concurrent website/Journal work are outside this pass.

### Authorized supporting-library cascade, 12 October 2026

The maintainer authorized the upstream release cascade after the checkpoint and refactor. Released in dependency order:

- [Generic Object Pool 2.6.0](https://github.com/bbottema/generic-object-pool/releases/tag/2.6.0): 95 cases on both real Java 8 and Java 21, legacy-client and module-path compatibility checks, then successful release CI.
- [Clustered Object Pool 4.3.0](https://github.com/bbottema/clustered-object-pool/releases/tag/4.3.0): 53 cases on both Java baselines, compatibility probes and release CI against published Generic Object Pool 2.6.0.
- [SMTP Connection Pool 4.4.0](https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/4.4.0): 108 cases in complete Java 21 verification, 97 core/provider cases on real Java 8, a non-publishing deploy rehearsal and both successful CI gates against published upstream dependencies.

Central POMs, runtime/source/Javadoc artifacts, checksums, release-key signatures, bytecode baselines, module names and source license headers were checked. The SMTP parent and all three library modules were published together; its demo is absent from Central. Each repository is clean and aligned with its release tag and origin/master. Upstream issues and exact-version milestones are closed with availability comments and the actual release date.

The SJM batch dependency now pins 4.4.0 rather than a local snapshot. Fresh real-JDK-11 focused verification passed 193 parent-process cases and 61 pool-free child scenarios, including recovery, observation ownership, retained scopes, wire/provider contracts and duplicate Session ownership. Classpath/JPMS fixtures passed, and the dependency tree resolves exactly 4.4.0 → 4.3.0 → 2.6.0 with normal dependency enforcement; no `enforcer.skip` workaround was used. This was focused integration verification, not another full modern-JDK reactor run.

At the cascade checkpoint, SJM implementation and documentation remained uncommitted for review, and #755 remained open. No website or Journal changes were committed or pushed during that cascade. Logs and downloaded artifacts are under `target/upstream-cascade`.

### Final names and local commit gate, 12 October 2026

The eight recovery-state and observation types were renamed consistently across production code, tests, ADR/plan references and architecture/concurrency diagrams. `ConnectionAttemptsAllowed` deliberately describes permission to attempt setup, not known server health. The rename changed no behavior or public API.

Fresh real-JDK-11 focused reactor verification completed successfully against the published pool integrations, including recovery ownership, connection observations, failover scopes/wire/provider contracts, duplicate Session ownership and classpath/JPMS fixtures. All eight Mermaid diagrams in the two renamed architecture/concurrency pages parsed in the website's installed Mermaid runtime. The production-only review artifact was regenerated against checkpoint `0594624f` with the new names.

The maintainer subsequently authorized selective semantic local commits of the reviewed feature and associated documentation. This supersedes the earlier uncommitted-review gate, not the separate push or issue-closure gate. #755/#757 remain open and unrelated maintainer, artwork and Journal work remains outside the commits.

At this local commit gate, fresh website checks passed all 84 cases, all 384 relative documentation links/anchors across 18 documents passed, and all 40 current Mermaid diagrams in those documentation pages parsed and rendered successfully. These are current documentation checks, not another full Maven, SMTP conformance or Spring compatibility-matrix run.
