# Angus PIPELINING prototype: evidence

Local prototype, updated 2026-10-15: **envelope PIPELINING and a separate SJM integration are implemented and tested locally**. The user now permits this research to be committed in SJM; it remains outside both Angus patches. Raw results stay Git-excluded. No publication is authorized. [Plan](../plans/699-angus-pipelining-prototype.md), [contribution guide](699-angus-contribution-conventions.md).

## Latest follow-up: separate SSLEngine prototype

The [engine/channel experiment](699-angus-sslengine-prototype.md) completed all eighteen matching pressure trials, while the existing reader-thread client stalled in nine and the sequential control passed all eighteen. Two additional fragmented-I/O cases passed, and ten failure checks produced the expected results. The engine acknowledged forty messages on twenty connections without retry, with measured inbound progress during blocked writes. This is a separate client experiment, not an Angus TLS adapter or release-ready integration. An accompanying [reuse survey](699-angus-reusable-tls-io.md) examines TLS Channel, Netty and other established implementations; none was added as a dependency or claimed to pass the reproducer. The user-edited comment draft remains unchanged.

## Earlier follow-up: bounded reconnect and sequential recovery

The [recovery spike and I/O-model review](699-angus-tls-recovery-and-io-models.md) is complete. Nine confirmed STARTTLS stalls recovered on JDK 11/17/21 by reconnecting and sending sequentially once; each recovered connection also handled another send. Six negative/control checks passed, including no replay after content and no third attempt after retry failure. This is a local outer-controller experiment, not an Angus production policy. A default write-timeout executor cleanup concern remains separate; passing runs explicitly own their timeout scheduler. The review also explains why a different arrangement of blocking SSLSocket threads is insufficient, and identifies an SSLEngine/nonblocking-channel loop as a separate unimplemented candidate. The user-edited public-comment draft was left unchanged.

## Earlier follow-up: repeated TLS measurements

The [repeated-measurement report](699-angus-tls-stall-rate-measurements.md) separates ordinary configurations from deliberate pressure. Pipelining completed 740 messages on fifty fresh connections across the ordinary fixture, Postfix and Exim, without a stall. The established small-buffer/large-reply reproducer stalled in six of ten pipelined trials; both sequential controls passed all ten. A 32 KiB MAIL-reply variation stalled in all three pipelined trials. These are rates for specified connection windows, not production probabilities. Exim also explicitly rejected excessive diagnostic key updates in all three implementations; those shared failures and the shorter passing reuse runs are retained in the report. No recovery policy or production code was changed.

## Earlier follow-up: valid-command TLS pressure

**The valid-command comparison reproduced a pipelining regression under TLS 1.3 pressure.** STARTTLS sends stalled on JDK 11/17/21 while unmodified Angus and prototype-sequential controls completed the corresponding valid exchange. The final paired matrix, repeated runs, legal command/reply sizes, watchdog stacks and limitations are in [the detailed pressure report](699-angus-valid-command-tls-pressure.md). Implicit-TLS cases passed in this experiment; not every pipelined TLS send stalls.

The original 4 MiB MAIL command was invalid. That earlier result alone did not establish a valid-traffic defect. The new tests use a 374-octet MAIL command, 295-octet RCPT commands, fully consumed commands before replies, and legal multiline replies. The reader can itself block writing a TLS key update while the server is blocked writing its reply. A 1,500 ms write timeout terminates the send and cleans up in about 1.68 seconds; it does not make the valid send succeed.

This supersedes the provisional timeout-only direction below. Consider a bounded reconnect-and-sequential-retry policy strictly before DATA/BDAT as a separate option; it is not implemented or verified. An SSLEngine rewrite remains an alternative, not the preferred next step. The progress comment is held pending this result and must be corrected before publication. Normal focused tests still pass (35 tests, seven optional skips), while the new pressure cases are separately recorded failures under the external watchdog.

## Earlier follow-up: actual TLS transport and full-suite comparison

The 2026-10-14 results below are historical. The original raw logs/private repositories under `target/angus-pipelining-prototype` no longer exist. New scripts, logs, runtime downloads and private Maven artifacts live in `.local/angus-pipelining`, which is locally ignored and is not removed by Maven clean.

### TLS finding corrected and narrowed

The original standalone reader fixture put `WriteTimeoutSocket` **outside** TLS. Angus's ordinary `SocketFetcher` path puts that wrapper **below** TLS. The original lock finding was real, but its timeout-close finding was not evidence that a normally configured Angus write timeout also hangs.

`SMTPPipeliningTlsTest` now exercises `SMTPTransport.sendMessage()` through `SocketFetcher`, including actual STARTTLS/implicit-TLS setup, certificate and hostname verification, capability negotiation, grouped envelopes, body transfer and reuse. Its factory records the handle supplied to TLS and asserts that a configured write-timeout wrapper is below TLS. It only chooses small socket buffers; it does not replace Angus's wrapper order. The standalone fixture's Javadoc now makes its narrower scope explicit.

Results on Temurin 21.0.12.1+1 (downloaded from the official Adoptium GitHub release, SHA-256 verified):

| Case | Result |
| --- | --- |
| Valid envelopes, plain/STARTTLS/implicit TLS | Pass. Three sends on one connection, 100 recipients per send. TLS trace confirms repeated TLS 1.3 KeyUpdate messages with the diagnostic 16 KiB key limit. Normal multiline MAIL replies are 64 KiB, with legal line lengths. |
| Adversarial early reply, STARTTLS/implicit TLS, 1,500 ms write timeout | Pass as controlled failure, about 1.62-1.65 seconds after submission starts. The connection closes, no content is submitted and the reader exits. |
| Same stress, no write timeout | Both TLS modes stall; the external watchdog captures stacks and stops only its owned JVM after 15 seconds. The reader again waits in `OutputRecord.changeWriteCiphers` while writes are blocked. |
| Same stress, no write timeout, raw-handle cancellation | Both TLS modes terminate around 1.55 seconds, with socket failure and reader cleanup. This validates the abort primitive, not a new production cancellation policy. |
| Plain stress control | Progresses through the large write/reply, then reaches the fixture's missing subsequent reply and the 10-second read timeout. Do not count this as a successful write-timeout test. |

The stress still uses a deliberately invalid 4 MiB MAIL extension and a peer replying before consuming the command. It is not evidence that normal SMTP envelopes hang. The actual-transport timeout cases also pass on the previously used JDK 11.0.16.1 (about 1.64-1.70 seconds); JDK 17.0.20 passes the valid STARTTLS/key-update/reuse case. This narrows the finding independently of upgrading the JDK.

Provisional direction: first assess whether the existing finite write timeout is a sufficient prerequisite for opt-in pipelining. A separately bounded envelope/abort policy is another option. The ability to fail and clean up is distinct from successful forward progress. Custom factories, supplied sockets and timeout/executor combinations are not yet an exhaustive matrix. No new timeout policy, TLS downgrade, replay or SSLEngine implementation has been added.

Reproduce with `.local/angus-pipelining/run-tls.ps1`; each case writes JSON metadata, stdout, TLS trace and (on watchdog expiry) a thread dump under `.local/angus-pipelining/tls`. Stress methods are opt-in so the ordinary test suite does not start an intentionally hanging case.

### Full SJM default-on/default-off experiment

Both arms are complete. The experiment used the isolated SJM integration worktree and one instrumented Angus artifact. A temporary constructor default read a JVM property; explicit Session properties, capability negotiation and subclass compatibility still took precedence. A locally excluded helper counted selected envelope paths and groups, emitting one small metrics file at JVM shutdown. The contribution source has been restored to default false and the helper/counter calls removed. Both runs used the same fixed instrumented artifact; the final checks rebuild the ordinary artifact afterward.

The instrumented combined-provider JAR has SHA-256 `e8e1442b2828e0191110185da13dcc7388772d500bd1b8f69328bffcb3fd6d69`; its SMTP-provider JAR has `f25181909f841fc41ffb1bab930d21f163254982b6a5dfccc42800aa722dbbd8`. Both JARs and the exact temporary sources are retained in `.local/angus-pipelining/instrumentation`. This is measurement scaffolding, not part of either contribution diff.

The runner is `.local/angus-pipelining/run-suite.ps1`: full default Maven `test` reactor, JDK 17.0.20, four main-module forks and the same aligned dependency graph. External opt-in integration-test profiles are not implied. Test failures are collected across modules rather than stopping at the first one. Maven exit status alone therefore does not establish a passing suite.

The initial `off-warmup` run was stopped deliberately, with its partial reports retained, after source inspection showed the shared SubEthaSMTP fixture did not advertise PIPELINING. A broad default-on run against that fixture would largely measure fallback. Only the owned runner process tree was stopped; no other Java processes or user files were touched.

For the meaningful comparison, the private copy of SubEthaSMTP 7.2.2 has one fixture-only change: its EHLO response advertises PIPELINING. Its existing command loop reads and handles commands in order, sends responses in order and flushes each response; no mail-processing code is changed. This is a test-fixture variant, not a claim that the published dependency advertises the feature or has exhaustive RFC conformance. The original JAR and extracted source are retained under `.local/angus-pipelining/server-fixture`, outside Git. The standard/global Maven cache is unchanged. The same fixture variant is used for both complete runs (`on-capable-1`, then `off-capable-1`). Hand-scripted capability-negotiation tests remain unchanged.

Fixture artifact SHA-256: original `5d7b71d59bd70963c7c5bddee33149b546e75d6521632070e093d395c8386947`; local capable variant `df30e4c8b5fd79e84485b5e430e0f63dd271858ead4b9d5c029d6fff44780b3e`. Full-suite wall time is observational, not a statistically powered benchmark. JVM startup, intentional waits, crypto and non-SMTP tests dominate much of it. Report activation counts and the paired test inventory alongside timing.

| Measured run | Default on | Default off |
| --- | ---: | ---: |
| Test cases / report files | 2,380 / 233 | 2,380 / 233 |
| Passed / skipped | 2,367 / 7 | 2,367 / 7 |
| Failures / errors | 5 / 1 | 5 / 1 |
| End-to-end wall time | 1,117.489 s (18m 37s) | 1,099.249 s (18m 19s) |
| Sum of test-class elapsed times, overlapping forks | 2,918.845 s | 2,881.901 s |
| Observed pipelined envelope attempts | 119 | 9 |
| Pipelined commands / groups | 257 / 119 | 28 / 9 |

The exact test inventory and every per-test outcome match; `compare-suite.ps1` reports no outcome differences. Default-on was 18.240 seconds (1.66%) slower in this single pair. There is no demonstrated macro-level speedup. This order was on then off, and the on run also compiled the remaining Spring/CLI modules; timing does not isolate helper cost or establish a statistically reliable slowdown either. The delayed-reply microbenchmark is the evidence for reduced envelope waiting, not these suite totals.

All counted pipelined attempts used `ManagedAngusTransport`. The nine in the default-off arm are the dedicated tests that explicitly enable pipelining. Default-on also counted 721 fallbacks for unadvertised capability and 4 explicitly disabled attempts; default-off counted 845 disabled-path attempts. Counters are emitted on JVM shutdown, so killed subprocesses may not contribute, and concurrency/cancellation tests need not make identical numbers of attempts.

Four failures in each arm are an instrumentation artifact: `CliProbeProcessTest` expects empty stderr, but the JVM prints `Picked up JAVA_TOOL_OPTIONS` before running the CLI. Those tests only probe connections and never send an envelope. All five cases in that class pass in the subsequent clean-environment rerun, without changing any assertion. The original failures remain in the report. The other two failures are described below. The full runs are not represented as green.

Both measured arms expose the same deliberate shared-path behavior changes: `SmtpCapabilityProbeCharacterizationTest.failedPostTlsEhloLeavesAngusPreTlsExtensionsBehind` expects stale pre-TLS SIZE to remain after failed EHLO. The prototype clears it. `SmtpAuthenticationTlsCharacterizationTest.failedPostTlsEhloStillUsesThePreTlsAuthListButNotAPlaintextSocket` expects AUTH LOGIN from the stale list, but receives MAIL FROM after the prototype clears that list. Neither assertion has been changed to make the experiment green. These are observable shared-path compatibility differences, not failures unique to enabled envelope batching. Clearing AUTH information has a real authentication-flow effect; do not dismiss it as merely an obsolete test.

### Repeated small latency comparison

Temurin 21.0.12.1, the same optional reused-connection loopback comparison, two warmups and five samples per case. This was run while the initial suite warmup was active, so do not treat the small no-delay differences as precise helper overhead. The deliberately delayed case reproduces the earlier order-of-magnitude result.

| Recipients | Sequential, no delay | Pipelined, no delay | Sequential, delayed replies | Pipelined, delayed replies |
| --- | ---: | ---: | ---: | ---: |
| 1 | 1.297 ms | 1.505 ms | 30.682 ms | 15.232 ms |
| 10 | 2.643 ms | 1.498 ms | 171.041 ms | 15.734 ms |
| 100 | 8.863 ms | 3.928 ms | 1564.849 ms | 61.612 ms |

The requested reply delay is 10 ms; Windows scheduling makes the actual delay closer to 15 ms here. This models reply waiting, not production throughput. Raw output: `.local/angus-pipelining/tls/current21-envelope-latency.out.log`.

### Direction choices, not additional implementation

If maintainers prefer lower shared-path risk, offer a separate pipelined envelope implementation while retaining the original sequential builders/classifier. Capability-cache clearing can also be reviewed separately, or PIPELINING permission can be tracked independently of the legacy extension map. The trade-off is duplicated logic and parity tests; preserving a stale map is not a proposed correctness improvement. Keep those as direction choices; no such refactor has been made before discussion. Direction approval comes before exhaustive release-readiness coverage.

The full-suite findings make the narrower capability choice particularly concrete: fresh PIPELINING permission is required, but clearing the entire legacy extension map is not the only way to provide it. A separate fresh-capability flag would keep this feature safe from stale permission without also changing SIZE/AUTH behavior in the first patch. That is an option for discussion, not an implemented workaround or a reason to leave the broader capability behavior unexamined.

### Clean-artifact confirmation and diff hygiene

After both timing runs, `run-final-checks.ps1` restored the original SubEthaSMTP JAR, rebuilt Angus from the default-off sources with no audit helper, and verified:

- JDK 11 clean Maven install with upstream release/staging/build-only profiles: 39 selected tests, zero failures/errors, 4 opt-in cases skipped. This includes the envelope, reader, actual-transport TLS, BDAT, unusual-code and UTF-8 classes, not the full Angus reactor inventory.
- JDK 17.0.20 and current Temurin 21.0.12.1: the three reader/envelope/TLS classes pass (32 methods, including the four assumption-skipped optional cases).
- Current JDK 21, diagnostic KeyUpdate frequency, actual STARTTLS and implicit TLS with 1,500 ms write timeout: both controlled failures pass again on the clean artifact, around 1.640 and 1.641 seconds from submission. No content submission or surviving reader is observed.
- SJM JDK 17 clean follow-up: 2 provider integration tests, 5 pool tests and all 5 CLI probe cases pass. No stderr assertion was weakened. The two full-suite capability characterizations were not changed or silently rebaselined.

Logs remain in `.local/angus-pipelining/logs`; final XML reports were copied to `final-reports` outside the disposable build directories. The clean combined-provider JAR has SHA-256 `a1e35ad4e3340c5b119a34f946624eb00cb23d220783dc328dfc854ab1388613` and contains no `SMTPPipeliningAudit` class. The private SMTP fixture's hash again matches the original dependency. The global Maven cache and SJM production pins were not changed.

The current Angus patch has 12 files. Compared with the earlier prototype, the substantive addition is `SMTPPipeliningTlsTest` (409 lines), with actual wrapper-order assertions and explicit raw-handle test cleanup. `SMTPReplyReaderTest` now documents the standalone fixture's narrower scope. Compatibility notes state both the shared capability-reset change and the remaining no-timeout TLS concern. SJM's five-file diff retains only implementation, tests, the explicit build profile and normal lifecycle documentation; its local research chapter was removed. No production TLS I/O redesign or alternative sequential implementation was added.

Both diffs pass `git diff --check`. Java 11, JUnit 4, existing license/SPDX style, private fixture helpers, sender/reader ownership, timeout cleanup and no-new-dependency constraints were reviewed against the contribution guide. This follow-up did not rerun full CI, SpotBugs, copyright checks or Javadoc generation; earlier scoped results remain separately labeled below. Release-readiness coverage waits for direction approval.

Current local exports (not commits or PRs):

| Artifact under `.local/angus-pipelining` | SHA-256 |
| --- | --- |
| `angus-prototype.patch` | `ed7b559edfd4eff2b9a35f2d0a67d7d2cd39e6e9db24f678dc2640b97afb71a6` |
| `sjm-integration.patch` | `0fad0f0a08acc915ff98597a05182234d08556b703f90f6877e753f851fc8cc0` |

The #699 research/plan files and `.local/angus-pipelining` are excluded through repository-local Git exclusions, with none of this investigation's research files staged. Other ongoing work in the main SJM checkout was left alone. Nothing was posted to GitHub, committed or pushed. Any future maintainer communication must first be drafted in the chat.

## Current implementation

- Angus reads the protocol-specific `pipelining` property, default false. It also requires a current PIPELINING advertisement and a compatible transport class. Capability state is cleared on reconnect, EHLO, HELO, STARTTLS and close.
- `sendEnvelope()` is the common post-conversion entry point. The sequential path still calls `mailFrom()` and `rcptTo()`. Exact stock SMTP/SMTPS transports permit pipelining; subclasses explicitly opt in.
- Command construction and recipient classification are shared. The sender invokes subclass command hooks, validates the final encoded command, and writes groups of at most 32 commands or 32 KiB. An oversized command travels alone.
- The helper uses the existing traced input, reserves reply slots before writes, validates complete multiline replies, and never calls subclass hooks or changes transport delivery state. The sender consumes the replies in order.
- Every written group is drained before ordinary rejection handling. A rejected MAIL stays primary. The helper is joined before reset, DATA or BDAT. Terminal I/O/protocol failure closes the connection without replay.
- SJM moves SIZE preparation to the common envelope hook and replaces its one pending-recipient slot with an ordered queue covering every response-bearing command. Per-recipient options, duplicate occurrences, receipts and the final submission boundary stay on the sender under the transport monitor.
- Neither implementation adds a connection pool, runtime dependency or public asynchronous API. CHUNKING scheduling is unchanged.

The command-byte bound requires one prepared-command lookahead when the next customized command would exceed the group budget. That command may never be written after a rejection. The opt-in hook contract must make this explicit. SJM clears leftover prepared-command tracking before RSET and at attempt cleanup.

## Earlier focused verification (2026-10-14)

The focused JDK 11 build passes. The final envelope suite has 19 mandatory test methods plus one optional benchmark; the reader suite has six tests. Existing BDAT, unusual-code and UTF-8 regressions also pass. The envelope tests cover actual grouped writes, the DATA/BDAT boundary, both limits, customized-command injection, missing capability/default-off behavior, legacy and opted-in subclasses, sender rejection, partial results/events, multiline replies, terminal failure, interruption, reconnect and failed EHLO. Sender rejection remains primary after a later 421 or a failed reset.

Plain SMTP, STARTTLS and implicit TLS envelope tests pass. The new wire fixture uses a test-only localhost certificate and keeps certificate trust and hostname checks enabled. Angus's older fixture certificate has CN=Unknown; it cannot establish a successful hostname-verified localhost connection. A small TestServer constructor overload accepts the new fixture's SSLContext. The test private key is public fixture data, not a deployment credential.

The 213 existing SJM provider tests pass both against unmodified Angus and against the prototype, with the same Mail/Activation 2.2 dependency graph. Two additional SJM wire tests pass with pipelining enabled:

1. Duplicate recipients keep distinct NOTIFY/ORCPT parameters and replies. SIZE, ENVID and final-response receipts survive a second send on the same connection, including partial acceptance.
2. A raw-socket abort while envelope replies are withheld returns known-unsent, not unknown acceptance; no DATA or reset is sent.

Five additional pool characterizations pass on JDK 11 and 17. Healthy pipelined sends reuse one physical connection; a 421 forces a replacement. Partial acceptance also forces a replacement under SJM's current failure policy, **both with pipelining on and off**. The first desired-reuse assertion failed and is retained in `sjm-pipelining-pool-v3-jdk17.log`; the final tests explicitly characterize the existing policy instead of claiming the desired behavior is implemented. `TransportRunner.sendUsingLease()` invalidates on every non-compatibility submission exception. No pool-policy change was made.

| Verification | Result |
| --- | --- |
| Angus focused Maven build, JDK 11 | Clean install passes for the combined provider and prerequisites. |
| Angus broader SMTP/socket verification, JDK 17, upstream profiles | 91 tests, one error, five skips. The error is the known SMTPIOExceptionTest reverse-DNS timeout before message submission; all new tests pass. This is not a green full-CI claim. |
| Angus focused verification, JDK 21, upstream profiles | 80 tests, zero failures/errors, five skips; verify and generated Javadoc succeed. Selection includes SocketFetcherTest and WriteTimeoutSocketTest, not the whole reactor's test inventory. |
| Final helper/envelope classes on actual JDK 11, 17 and 21 | Clean Maven build on 11 and direct JUnit runs on 17/21 pass. The suites contain 26 methods; the optional benchmark is assumption-skipped unless enabled. |
| Aligned SJM baseline/provider comparison, JDK 17 | 213 existing provider tests pass against each Angus artifact. |
| SJM broader prototype run, JDK 17 | 215 provider tests and 123 receipt/SIZE/REQUIRETLS/cancellation/ownership tests pass: 338 total. Existing broad tests generally retain default-off behavior; enabled behavior is covered by the new dedicated tests. |
| SJM enabled integration and pool characterizations, JDK 11 | Seven cases pass. |
| SpotBugs explicitly enabled, High threshold | 27 prototype classes, zero findings, zero missing classes; unmodified baseline was 22 classes, zero findings. No concurrency-safety claim follows from this. |
| Copyright checks | Same five existing SMTP resource findings and seven existing combined-provider findings on Windows. The new binary fixture has its own narrow exclusion. Java changes are not flagged. The overall checker is still not green. |
| Javadoc inspection | Generated property table includes Boolean/default/fallback/resource cost and a valid hook link. Both protected hooks, sender ownership and queued/unwritten timing appear in generated SMTPTransport HTML. |

Useful logs: `envelope-final-build-jdk11.log`, `envelope-verify-jdk17.log`, `envelope-verify-jdk21.log`, `envelope-final-source-jdk17.log`, `envelope-final-source-jdk21.log`, `sjm-baseline-provider-jdk17.log`, `sjm-integration-jdk17.log`, `sjm-pipelining-pool-policy-jdk17.log`, `sjm-pipelining-final-jdk11.log`, `spotbugs-envelope-final.xml`, `copyright-envelope-fixture-excluded.log` and `envelope-final-hygiene.log`. The final hygiene run generated Javadoc successfully, with 21 existing-style missing-comment warnings, and explicitly ran SpotBugs with no analysis errors or missing classes.

The historical standalone helper run failed the bounded JDK 21 key-update stress. `reader-keyupdate-20261014-143729-435.threads.txt` showed `OutputRecord.changeWriteCiphers` blocking the reader. The watchdog captured the dump and stopped its own process after 20 seconds. The actual-transport follow-up above corrects the timeout-wrapper assumption; this older result must not be presented as evidence that Angus's normally layered write timeout also hangs.

## Earlier optional latency comparison (2026-10-14)

JDK 21, loopback, one reused connection per case, two warmups followed by five measured sends; median elapsed time. The server withholds each group's replies, then waits a requested 10 ms before returning them. This is a synthetic reply-delay model, not a measured Internet RTT or a real-MTA throughput claim. Windows scheduling makes the actual sleep longer than the requested 10 ms in this run. There are no timing assertions in CI.

| Recipients | Sequential, no added delay | Pipelined, no added delay | Sequential, delayed replies | Pipelined, delayed replies |
| --- | ---: | ---: | ---: | ---: |
| 1 | 0.662 ms | 0.774 ms | 31.038 ms | 15.287 ms |
| 10 | 1.431 ms | 0.953 ms | 170.518 ms | 15.543 ms |
| 100 | 7.077 ms | 3.003 ms | 1565.071 ms | 62.015 ms |

The no-delay one-recipient difference is about 0.11 ms, an initial aggregate overhead estimate for this implementation, not an isolated thread-creation cost. The delayed 100-recipient case uses four envelope groups rather than 101 command/reply waits. The recorded run is `envelope-latency-jdk21.log`; it runs the optional `SMTPPipeliningTest.testOptionalLatencyComparison` with `-Dangus.pipelining.benchmark=true`. These measurements do not resolve the TLS concern.

## Concerns to present with the implementation

| Concern | Current evidence and next decision |
| --- | --- |
| TLS 1.3 key-update progress | A helper reading SSLSocket is not independent of the writer's TLS lock. The actual-transport follow-up passes normal key-update/reuse cases and bounds the adversarial stall with Angus's configured write timeout, but still stalls without it. Decide whether to require a finite write timeout, add a separately bounded envelope policy, or pursue a larger I/O design. Neither disabling key updates nor replaying a transaction is acceptable. |
| Portable abort | Actual Angus timeout layering and raw-handle cancellation both clean up the tested TLS stress. Zero linger plus a short read timeout also works in the tested reader-failure cases. None establishes a guarantee for every custom socket arrangement. SJM has a separately tracked raw socket; a general Angus deadline design must establish which handle it owns and can abort. |
| Subclass contract | Queued command callbacks and deferred replies are materially different timing. Legacy subclasses remain sequential. The two protected hooks and prepared-but-unwritten lookahead need maintainer review. |
| Resource cost | One thread per active pipelined envelope, not per idle connection. The queue limits command count and bytes, not multiline reply size. Measure overhead and consider whether reply-size limits belong in a separate hardening change. |
| Dependency alignment | The prototype uses Angus master and Mail/Activation milestones only in isolated worktrees/private repositories. This does not resolve SJM's independent upgrade/binary-compatibility concerns or change production pins. |
| SJM pool health policy | The physical connection remains usable after ordinary recipient rejection, but SJM currently invalidates after any non-compatibility submission exception. On/off pool tests establish that this predates pipelining. Keep outcome and connection health separate in a later SJM change, without altering the Angus patch or adding a second pooling owner. |
| Verification scope | Controlled tests establish these cases, not general deadlock freedom. Full CI, custom socket coverage and production-network/server benchmarking must not be inferred from a focused pass. |

The original stage-one evidence below is retained for provenance. Its stop decision was superseded by the user's instruction to continue the substantial local prototype.

## Baseline and isolation

- Angus control and prototype start at `a7bd3fed964d9c9bc2981d4b2c9867358453c571` (fresh upstream master).
- SJM integration starts at `ff73530421bd1c4766f7c3232d766705d61244c9`.
- Worktree paths and branches are in the plan. This work does not change production source or dependency pins in the main checkouts. Concurrent SJM work, including changes to the same transport class in the main checkout, is left untouched; the isolated integration has not been rebased onto that work.
- The additional SJM control checkout is `C:\workstation\simple-java-mail-pipelining-baseline`, detached at the same `ff735304` commit. Only its explicit local dependency-alignment profile differs from that commit; production Java sources remain unmodified.
- Baseline Maven repository: `target/angus-pipelining-prototype/m2-baseline`. Prototype repository: `target/angus-pipelining-prototype/m2-prototype`, seeded from the existing Maven cache before final verification. Builds install only into their respective private repository.
- Logs: `target/angus-pipelining-prototype/logs`. Windows root trust store is used for Maven HTTPS; TLS verification is not disabled.

The three external Jakarta/Activation JARs have identical SHA-256 hashes in the two private repositories:

```text
jakarta.mail-api 2.2.0-M1:       6e3dc87ea2664f13a8a3b3707293462d73b767543b182b56ff01823d98c5ae5f
jakarta.activation-api 2.2.0-M1: 4e0ce921719d90fe099df8795a53eeb786ff79b02e36c61384674178f94b3b15
angus-activation 2.1.0-M1:      92db458acb0380af3e3a65fc79d349f1dd29dc1a990abf60f5b5cf977d3b77bb
```

## Reader gate, first finding

The first JDK 11 run exposed an actual abort deadlock, before any envelope semantics were implemented. A peer sent `421` while refusing to read the remaining large client write. The reader received the complete reply, published the original failure, then called `Socket.close()` through Angus's existing `WriteTimeoutSocket`.

The thread dump shows the sending thread blocked in `SocketOutputStream.socketWrite0`, while both the reader's abort and test cleanup wait in JSSE `SSLSocketImpl.closeNotify` for the output record lock. A plain call to close the TLS wrapper is therefore not an adequate abort primitive in this setup. The hung test JVM was stopped after retaining `reader-gate-tls-close-deadlock-jdk11.txt`; `reader-gate-jdk11.log` consequently reports a terminated fork, not a passed test.

This matches [OpenJDK's close implementation](https://github.com/openjdk/jdk11u/blob/jdk-11.0.16.1-ga/src/java.base/share/classes/sun/security/ssl/SSLSocketImpl.java): without SO_LINGER, close waits unconditionally for the writer lock. The next bounded experiment sets SO_LINGER to zero only on terminal abort, then closes. This uses existing socket methods; it is not yet evidence that every JSSE error path or third-party socket can be aborted safely. No change to SocketFetcher or normal TLS setup has been made.

SO_LINGER alone was insufficient in the follow-up run: TLS close also spent time draining input. Combining zero linger with a one-millisecond read timeout on terminal abort passed the six basic tests. These options are confined to the experimental helper; normal transport configuration is untouched. This is not a portable abort guarantee for every socket provider.

## Decisive finding: TLS 1.3 KeyUpdate blocks the reader

I reran the same test with a diagnostic security-property overlay that triggers key updates after 16 KiB. [JSSE documents this setting](https://docs.oracle.com/en/java/javase/11/security/security-developer-guide.pdf) as a way to limit bytes encrypted under one set of keys. It changes test frequency, not certificate checking, TLS protocol selection or key-update semantics. It is never installed as an application default.

On both JDK 11.0.16.1 and JDK 21, the STARTTLS backpressure case stopped making progress:

1. The client writer held the TLS output-record lock while blocked writing to the socket.
2. The peer was also blocked writing replies and was not reading the remaining client data.
3. The helper received a KeyUpdate. JSSE attempted to produce a response, then waited for that same output lock in `OutputRecord.changeWriteCiphers`.
4. The helper could no longer drain the peer's replies. It also had not returned an exception that the helper's abort code could handle.
5. In the original 30-second write-timeout runs, Angus's existing timeout task also waited in TLS `closeNotify` behind that lock.

The important stacks, retained in full in the logs directory, are:

```text
writer: TimeoutOutputStream.write
        SSLSocketImpl$AppOutputStream.write
        SSLSocketOutputRecord.deliver -> socket write

reader: SMTPReplyReader.readReply
        SSLSocketImpl$AppInputStream.read
        KeyUpdate$KeyUpdateConsumer.consume
        KeyUpdate$KeyUpdateProducer.produce
        OutputRecord.changeWriteCiphers -> output-record lock

timeout: TimeoutOutputStream$1.call
         SSLSocketImpl.close
         SSLSocketImpl.closeNotify -> output-record lock
```

Evidence: `reader-gate-tls-keyupdate-deadlock-jdk11.txt`, `reader-gate-tls-keyupdate-deadlock-jdk21.txt`, and the independently repeated watchdog run `reader-keyupdate-20261014-134347-014.threads.txt`. The latter was stopped automatically after 20 seconds; its log is intentionally a failed gate, not a passing test. Diagnostic Java processes were terminated after capturing their stacks; none was left running.

This disproves the assumption that a separate thread using the existing TLS input stream will always remain able to drain replies during a blocked write. Setting SO_LINGER during an abort does not solve a reader that cannot reach its abort code. A raw-socket handle plus a deadline could bound failure, but that alone would not prove successful TLS progress.

The test is an I/O stress case with a 4 MiB write and a large multiline reply, deliberately exceeding socket buffers. It is not a benchmark of normal 32-command SMTP groups, a complete SMTP conversation, or evidence that ordinary sends routinely hang. It does exercise the proposed oversized-command/backpressure safety requirement. A bounded group is still not proof of the TCP receive window, particularly with a peer that responds before consuming an oversized command.

**Initial decision, since superseded:** I stopped before envelope semantics. The user then explicitly asked to continue locally and collect the concerns. The TLS finding still prevents a production-readiness claim, but does not prevent implementing and testing the envelope/SJM behavior. Do not weaken TLS, disable key updates or silently fall back after a partially written transaction.

## Stage-one checks (historical, before envelope integration)

| Check | Result and limits |
| --- | --- |
| Guide and isolation | Guide written before code. Three worktrees created at the recorded commits. Original dirty SJM docs and nested website left intact. |
| Unmodified Angus build | Private-repository package/install succeeds with tests explicitly skipped, using the same Mail API 2.2.0-M1, Activation API 2.2.0-M1 and Angus Activation 2.1.0-M1 graph as the prototype. |
| Full unmodified JDK 11 run | Not green. `SMTPIOExceptionTest` exceeded its five-second limit while resolving a local reverse-DNS name. A later `MimeMultipartParseTest` fork spent several minutes in the same hostname path and was stopped after a thread dump. Maven continued its remaining test classes and ultimately failed. The summary reports 993 tests and one reported test error, plus the terminated fork. This is not a PIPELINING regression or a completed baseline test pass. |
| Build retry | An overlapping packaging retry encountered a Windows file lock on a JAR still used by the baseline test run. Once that run ended, a clean packaging retry passed. No source change was used to hide this orchestration error. |
| Basic helper tests, JDK 11 | Six tests pass: normal backpressure and terminal `421` while writing, each over plain SMTP, STARTTLS and implicit TLS. Maven clean install passes for the combined provider and its prerequisites. |
| Same compiled tests, JDK 17.0.20 and 21 | Six tests pass on each JDK. These are focused JUnit executions, not full CI-equivalent Maven lanes. |
| TLS 1.3 key-update stress | Failed on JDK 11 and 21. Stacks show the reader/output-lock dependency. Repeated through the retained bounded watchdog script on JDK 21. |
| SpotBugs | Explicit `spotbugs.skip=false`, High threshold, plugin 4.8.3.1, JDK 17. Analysis XML confirms 22 baseline classes / 24 prototype classes, zero high-priority findings and no missing classes in either run. This does not establish concurrency safety. |
| Copyright checker | Run explicitly with `copyright.ignoreyear=true`. SMTP check fails on the same five existing META-INF resource paths in baseline and prototype. The combined-provider check also flags seven existing resource/data paths; neither new Java file is flagged. An explicit upstream exclusion-file path did not resolve the SMTP findings on this Windows setup. No green copyright check is claimed and no unrelated headers were altered. |
| Whitespace and scope | `git diff --check` passes for the new Angus files. No existing Angus production source, dependency, public API, property table or release note was changed. |
| Downstream validation | Not performed because the gate failed. SJM integration worktree is prepared but unchanged. Production pins remain untouched. |

Logs include `baseline-jdk11-install.log`, `baseline-jdk11-dns-delay.txt`, `baseline-package-clean-jdk11.log`, `reader-gate-abort-options-jdk11.log`, `reader-gate-final-jdk11.log`, `reader-gate-abort-options-jdk17.log`, `reader-gate-abort-options-jdk21.log`, `spotbugs-baseline.log`, `spotbugs-prototype.log`, `copyright-baseline.log`, `copyright-prototype.log` and `copyright-prototype-combined.log`.

At the end of stage one, the focused rebuild passed again after source review. SpotBugs was rerun after that clean build (`spotbugs-prototype-final.log`); copies of both analysis XML files are retained. At that point `SMTPTransport.class` was still byte-identical in baseline and prototype. The historical reader-only diff is `target/angus-pipelining-prototype/angus-reader-spike.patch`; it is not the current feature diff.

The existing SJM upgrade/binary compatibility concerns are recorded in [the Angus compatibility research](ANGUS_RELEASE_COMPATIBILITY_RESEARCH.md). They were not repaired or reclassified as PIPELINING issues. The aligned downstream test results now appear above.

## Earlier reproduction commands (2026-10-14)

These commands used the original `target` repositories. For the retained 2026-10-15 runs, use `.local/angus-pipelining/run-suite.ps1`, `run-tls.ps1`, `compare-suite.ps1` and `run-final-checks.ps1` instead. The suite runner needs the retained instrumented artifact and capable fixture variant described above; the final-check script deliberately restores normal artifacts afterward.

Use Maven 3.9.10 with JDK 11 and the usual Windows-root trust store, from the prototype worktree:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-11.0.16.1'
$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
& 'C:\standalone programs\apache-maven-3.9.10\bin\mvn.cmd' -B -ntp `
    -pl providers/angus-mail -am clean install `
    '-Dmaven.repo.local=C:/workstation/simple-java-mail/target/angus-pipelining-prototype/m2-prototype' `
    '-Dtest=SMTPPipeliningTest,SMTPReplyReaderTest,SMTPBdatTest,SMTPUknownCodeTest,SMTPUtf8Test' `
    '-Dsurefire.failIfNoSpecifiedTests=false' `
    '-Denforcer.skip' '-Dgpg.skip=true'
```

Then, from the main SJM checkout:

```powershell
# Ordinary control, expected to pass all six tests.
& ./docs/research/699-angus-pipelining/reproduce-reader-gate.ps1

# Key-update stress, expected to fail the watchdog on the tested JDKs.
# Saves a thread dump and stops only the diagnostic Java process it started.
& ./docs/research/699-angus-pipelining/reproduce-reader-gate.ps1 -KeyUpdates -DeadlineSeconds 20
```

The script accepts `-JdkPath`, `-AngusPath` and `-EvidencePath`. The JDK 17 installation here is `C:\Program Files\Java\ms-17.0.20\jdk-17.0.20+8`. It reads the classpath from the successful focused Maven report; rerun that build if sources or private repository paths change. The key-update property overlay is [kept alongside the script](699-angus-pipelining/keyupdate.security), outside the upstream patch.

For an explicit SpotBugs comparison, run in each Angus worktree after its build, selecting its own private Maven repository:

```powershell
& 'C:\standalone programs\apache-maven-3.9.10\bin\mvn.cmd' -B -ntp -pl providers/smtp `
    com.github.spotbugs:spotbugs-maven-plugin:4.8.3.1:spotbugs `
    '-Dspotbugs.skip=false' '-Dspotbugs.threshold=High' `
    '-Dmaven.repo.local=<baseline-or-prototype-private-repository>' '-Denforcer.skip'
```

Inspect `providers/smtp/target/spotbugsXml.xml`, including its errors, missing classes and class count, rather than inferring that the goal ran from a green lifecycle build.

From the SJM integration worktree, after installing the Angus prototype:

```powershell
& 'C:\standalone programs\apache-maven-3.9.10\bin\mvn.cmd' -B -ntp `
    -pl modules/simple-java-mail -am test '-Pangus-pipelining-prototype' `
    '-Dmaven.repo.local=C:/workstation/simple-java-mail/target/angus-pipelining-prototype/m2-prototype' `
    '-Dtest=AngusPipeliningIntegrationTest,SmtpPipeliningPoolTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' '-Denforcer.skip' `
    '-Dlicense.skip=true' '-Dmaven.javadoc.skip=true'
```

The local alignment profile is required: this integration calls provisional hooks that do not exist in the production Angus 2.0.5 pin. It is not a production-ready dependency upgrade. For the 213-test control comparison, run `-pl modules/angus-mail-provider-module -am test` without the test filter in each SJM worktree, using its matching private Maven repository and the same profile. Use JDK 17 for the broader SJM reactor.

To repeat the optional latency comparison, select JDK 21 and run the focused Angus command above with `test` instead of `clean install`, `-Dtest=SMTPPipeliningTest`, and `-Dangus.pipelining.benchmark=true`. Keep the private repository and other flags. Results are printed to the test output; timing is never asserted.

To regenerate the inspected Javadoc, run the explicit SpotBugs command above with `javadoc:javadoc` as an additional goal. The final output is under `providers/smtp/target/site/apidocs/org.eclipse.angus.mail.smtp/org/eclipse/angus/mail/smtp/`; inspect `package-summary.html` and `SMTPTransport.html`.

## Earlier diffs and hygiene review (2026-10-14)

The earlier working-tree diffs were exported separately, including new test sources and the test keystore. Those `target` files are no longer available; these hashes identify the historical artifacts, not the current patch exports. No commit or publication was made:

| Artifact under `target/angus-pipelining-prototype` | SHA-256 |
| --- | --- |
| `angus-envelope-prototype.patch` | `9c380a2c7db24403d1f9b9d72c2a9501161581b5ad3572ea0c19ea763f690720` |
| `sjm-pipelining-integration.patch` | `a5e4e3ca67d0e8560d24f118d30cf10ee2c3ee09ff089e7ac377d59e7db4eb04` |

The Angus diff has 11 files; the SJM diff has five. Final review against the contribution guide:

- `SMTPTransport.java`: shared command construction and recipient classification, default-off property, provisional protected hooks, sender-owned state and callbacks. Existing recipient classification and null/empty conventions retained; command-byte accounting uses `long`. Monitor assertions also apply to the queued paths. Existing license and attribution retained, with attribution for the new work.
- `SMTPReplyReader.java`: package-private Java 11 helper, immutable replies, existing traced input, no transport monitor or callbacks. Failure cause and interrupt handling reviewed. Tested cleanup is not a claim that the unresolved TLS lock case terminates safely.
- `package-info.java`: Boolean/default/fallback, SMTPS prefix, helper cost and both group bounds documented. BDAT's lack of pipelining is distinguished from the envelope. Generated links and hook text inspected.
- `SMTPPipeliningTest.java` and `SMTPReplyReaderTest.java`: JUnit 4, explicit assertions, controlled real socket fixtures, bounded normal test waits and an opt-in benchmark. No mocking dependency. The deliberately hanging TLS diagnostic remains process-isolated outside the upstream patch.
- `TestServer.java`: small SSLContext overload for tests; the existing constructor keeps its behavior and the existing header is preserved.
- `pipelining.jks`, its `README.md` and `copyright-exclude`: public localhost fixture, generation command and passwords documented; certificate and hostname checks stay enabled. Only this binary fixture is newly excluded from the copyright checker.
- `CHANGES.txt` and `COMPAT.txt`: existing plain-text format, local unreleased heading, subclass timing and unresolved TLS limitation. Final release heading and `@since` remain pending the upstream target.
- SJM `ManagedAngusTransport.java`: common SIZE preparation hook and ordered response bookkeeping only; raw-socket ownership and cancellation boundaries retained. No pool-policy change or new public implementation type.
- SJM's two new test classes: enabled duplicate-recipient/DSN/SIZE/receipt/abort tests and explicit on/off pool-policy characterization. `pom.xml` contains an opt-in local alignment profile; `07-angus-transport-abort.md` documents the changed bookkeeping and experimental scope.

`git diff --check` passes in both implementation worktrees. No generated sources, dependency additions, unrelated formatting or copied NioSmtpClient implementation enter the Angus patch. Git normalizes new text files to LF; existing file styles and encodings are retained. No commit, push or upstream post was made. The [draft explanation](../plans/699-angus-pipelining-prototype-pr-draft.md) presents the implementation and TLS concern together, with AI assistance disclosed and no internal research links in the proposed public body.

## Stage-one diff review (historical)

- `SMTPReplyReader.java`: canonical SMTP module; package-private helper and immutable holders; Java 11; no new dependency. It does not access the transport monitor or invoke callbacks. It is experimental and unused by SMTPTransport. Reservations cap reply slots at 32; the 32 KiB constant is not an implemented command-grouping path yet. TLS progress and portable abort remain unresolved. Do not promote it as production-ready.
- `SMTPReplyReaderTest.java`: combined provider's test tree, JUnit 4 and explicit existing assertions. Real sockets and the existing keystore/write-timeout wrapper, no mocking framework. New fixtures isolate wire-pressure behavior without changing shared fixtures. Watchdog process isolation is needed for the deliberately failing key-update diagnostic.
- Both new Java files have an accurate 2026 Benny Bottema copyright line and the neighboring EPL/GPL Classpath SPDX header. Existing headers/encodings were not changed. New files use UTF-8, four-space indentation and LF in Git.
- No property, protected API or release change was made, so generated property Javadoc, release notes and `@since` tags are deferred with the feature. Full JDK 11/17/21 CI, exhaustive response semantics, interruption/custom-socket coverage, SJM integration and latency measurements remain outstanding.
- Research, security overlay, watchdog script and [draft explanation](../plans/699-angus-pipelining-prototype-pr-draft.md) stay in SJM. Nothing has been committed, pushed or posted upstream.

## NioSmtpClient checklist

Reviewed local `58c521bb6485a577409fc85b4a63ca5c87c9d4dd`, notably `SmtpSession.sendPipelined`, `startTls`, `ResponseHandler` and `ResponseCollector`.

- Reserve expected responses before the first write and collect complete responses in order.
- Keep a single response owner; handle terminal close and read timeout while a response group is pending.
- Validate permitted commands and group boundaries; preserve the final-command distinction.
- Repeat EHLO after TLS; do not reuse pre-TLS capabilities.
- Separate response collection from delivery classification and preserve partial information on failure.
- Do not transfer Netty, Guava, its executor model or its own API into Angus. No NioSmtpClient code was copied.

## Pool coordination

Read the physical-transport section of sibling `smtp-connection-pool/PRODUCT-VISION.md` and its #699 coordination requirements. That repository remains read-only. This experiment changes the physical SMTP delegate only: one exclusive connection, one outer pooling owner, synchronous Jakarta API, physical-delegate capability negotiation. Existing SJM receipt and lifecycle SPIs remain the boundary; no concrete implementation types should enter public batch results. The pool release does not depend on this work.
