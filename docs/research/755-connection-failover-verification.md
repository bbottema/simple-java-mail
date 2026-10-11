# Connection failover verification

Implementation: [#755 plan](../plans/755-smtp-connection-failover.md). Clustered template correction: [#757](https://github.com/bbottema/simple-java-mail/issues/757).

This is local verification of the uncommitted implementation, not release evidence. The root and website changes remain available for review. Upstream publication is a separate approval gate.

## Build and integration checks

| Check | Result |
| --- | --- |
| Generic Object Pool full suite, local 2.6.0-SNAPSHOT | Passed, 94 tests |
| Clustered Object Pool full suite, local 4.3.0-SNAPSHOT | Passed, 52 tests |
| SMTP Connection Pool full reactor, local 4.4.0-SNAPSHOT | Passed, 106 tests across core, Jakarta provider, Camel and demos |
| Final focused routing, ownership, recovery, diagnostics, Spring and CLI verification | Passed: 83 tests with test-failure ignoring disabled, plus Javadocs and classpath/JPMS consumers |
| Spring Boot 2.7.18 / Spring 5.3.39 / SLF4J 1.7.36 / JDK 11 | Passed |
| Spring Boot 3.0.13 / Spring 6.0.14 / SLF4J 2.0.9 / JDK 17 | Passed |
| Spring Boot 3.5.16 / Spring 6.2.19 / SLF4J 2.0.18 / JDK 21 | Passed |
| Classpath and JPMS consumers | Passed, including a third-party provider without Angus or pooling |
| Java 11 library reactor | Completed: 1,895 tests, one skipped; only the host-intercepted TLS fixture below failed |
| Modern-JDK non-live reactor and Javadocs, including CLI | Completed: 2,087 tests, one skipped; the host TLS fixture and two subsequently corrected test assumptions are explained below |
| Existing SMTP conformance matrix | Passed: 24 Postfix/Exim scenarios and 10 independent content checks; embedded fixtures also ran in the library reactor |
| Website checks, clean build and internal links | Passed: 78 checks and 3,936 links across 66 pages |
| Downloadable workload field-guide examples | Compiled with real JDK 11 and `--release 11` |
| Socket-factory TLS characterization inside Linux Docker | Passed: all 34 tests |
| Coding-guide audit and license cleanup | Completed; generated license headers removed in the root and all three upstream checkouts |

The root reactor uses `-Denforcer.skip=true` while it consumes the deliberately unpublished upstream snapshots. This is not a declaration that those dependencies have been released. Existing Maven test selection excludes live-account demos. No benchmarks or SpotBugs were run.

## Windows TLS interception

The Windows host's Norton mail scanner replaces the self-signed loopback certificate in the positive implicit-TLS fixture. The received issuer is Norton's self-signed-root variant, which is not trusted by the fixture or the configured Windows trust store. The client therefore correctly rejects the certificate. Switching to Windows-ROOT does not make that particular certificate trusted.

The same socket-factory characterization class passes inside an isolated Linux container with no external network. The tests use the same compiled production artifacts; no trust-all setting or host-security change was made. One negative fixture was made portable across JSSE implementations: the scripted peer accepts either a handshake-specific TLS exception or another TLS alert, while the client assertion still requires the original empty-trust-store rejection. This does not relax production verification.

The pinned test image is `eclipse-temurin@sha256:9d8dcf999b0bce2453e913823595a5ff2a4e8e9e5d5241b45280d0ff069818ec`.

## Deterministic concurrency checks

The first version of the new concurrent-template regression tried twelve sends within a wall-clock threshold. It passed in isolation but timed out during the modern-JDK reactor. The revised test warms both pooled connections, then uses barriers to overlap two sends of the same original Email through different selected Mailers. It verifies each destination's own templates and the untouched original Email without depending on batch throughput. All three selected-Mailer tests pass with this version on both JDK 11 and JDK 21.

The modern-JDK run also exposed an old assertion that asynchronous validation had already completed when `sendMail` returned. Executor-side preparation deliberately removes that guarantee. The test now awaits exceptional completion with a bounded wait and verifies the original validation exception before checking the completed state.

The final focused Maven verification rebuilt the corrected test sources and passed all 83 selected tests, with `maven.test.failure.ignore=false`. No production behavior was relaxed to satisfy either test. Supplemental coverage also passed for cancellation during the connection-retry delay and both execution modes with pooling unavailable.

## Review-time coding-guide cleanup

The production-only review was paused for a second pass over the changed Java code, including the upstream pool additions and the observer demo.
The pass used the root coding guide's narrative-orchestration rules rather than introducing another abstraction layer:

- `prepareSelectedEmail` now names the four steps: verify CustomMailer lock compatibility, apply selected governance, apply traversed message locks, and validate.
- Destination sending separates allowance reservation, connection reporting and closure execution; the closure still starts only after admission and allowance.
- Route traversal separates stage selection, finite candidate visits and failover reporting. Retained scopes separately acquire, replace and close their connection.
- Connection acquisition separates pooled and direct paths while retaining their cancellation registrations, observation boundaries and cleanup suppression.
- Named configuration parsing, graph construction/rollback and Spring alias handling expose their process steps instead of mixing loops into orchestration.
- Pool candidate traversal keeps its existing selection lock while separating captured ordering from private-collection maintenance.
- Comments explain selected-versus-traversed ownership, cancellation versus outage evidence, retained-connection replacement, and overflow-safe backoff.
- Removed the misleading `markCurrentSendStarted` wrapper, named the diagnostic-history bound, and checked new production lines against the 160-column limit.

The changes are readability refactoring, not a change to the accepted API, routing rules or resource ownership. They remain uncommitted.

The final post-cleanup modern-JDK run passed 105 focused tests, including the 22-test batch-executor suite, plus Javadocs and classpath/JPMS consumers.
Real-JDK-11 library verification also passed 54 focused tests, including selected-template ownership, recovery, diagnostics, batch and Spring paths,
plus Javadocs and the provider-neutral consumers.
The refactored upstream candidate traversal passed all 52 Clustered Object Pool tests and was installed as a local snapshot only.
Its inherited build initially invoked SpotBugs unexpectedly; the successful clean rerun explicitly disabled SpotBugs and coverage instrumentation.
The final root reruns explicitly disabled SpotBugs. License removal and whitespace checks were repeated after verification.

## Review correction: callback-confined senders

The open-connection sender now checks callback-thread identity before delegation and becomes unusable in `finally`, before connection cleanup. Ordinary and failover callbacks share this guard; transport wrappers retain execution-confined cleanup rather than adding a monitor around network work.

The modern-JDK focused run passed all 96 tests across `ScopedMailSenderTest`, `ConnectionFailoverTest`, `MailerTest`, `MailSendObserverTest` and `ResultHandlingTest` (`target/755-scoped-sender-test-final.log`). The new regressions cover both send methods, direct and pooled failover, rejected cross-thread calls without observer/send work, expiry after normal and throwing callbacks, exact receipt/exception identity, and invalidation before direct physical cleanup. The initial new fixture incorrectly expected physical closure of a healthy pooled lease; it was corrected to distinguish release for reuse from direct closure.

Website checks passed, including 83 helper tests. License removal and focused whitespace/style checks passed. This correction remains uncommitted; it does not claim another full-reactor or real-JDK-11 verification run.

## Coverage anchors

- `SelectedMailerPreparationTest`: A-only cluster invocation selects B's sender, headers and recipients; concurrent use of the original Email does not mix templates; asynchronous validation runs on executor execution.
- `ConnectionFailoverTest`: connection-only recovery, nested relationships, mandatory archive plus selected maintainer BCC, attachment-read parity, retained batch/open scopes, failure classification, capacity waiting, disposal, delegated shutdown and construction rollback.
- `ConnectionRecoveryTest`: fixed/increasing intervals, one due recheck, revision fencing, policy isolation and background-allocation permission, using a controlled monotonic clock.
- `SmtpConnectionFailoverWireTest`: actual loopback greeting/EHLO failures and mandatory-STARTTLS stopping, not only synthetic exceptions.
- `NamedMailerConfigTest`, `SpringFailoverConfigurationTest`, CLI configuration/profile tests: isolated definitions, provenance/redaction, policy validation, captured locks and daemon profile identity.
- Diagnostics tests: constructor/serialization compatibility, preparation accumulation, bounded connection history and the unobserved fast path.
- Upstream tests: finite pinned traversal, retirement/replacement, background allocation gates and physical connection observations.

These tests exercise controlled concurrency interleavings; they do not prove that every application callback or third-party provider is deadlock-free.

## Reproduction and local evidence

Use the project JDKs and normal non-live commands, with the unpublished-snapshot enforcer override and `-Dtest.forkCount=2`. The actual property is `test.forkCount`, not `forkCount`; the latter does not override this module's configured eight forked JVMs.

The final full-reactor runs deliberately use `-Dmaven.test.failure.ignore=true` to collect every module's results despite the host TLS fixture. Their process exit code is therefore **not** an all-tests-passed claim. Review the test summaries and the separate unchanged production TLS checks in Docker.

Local logs are retained under ignored `target/755-*.log`; the conformance runner records its own source/artifact identity and sanitized evidence under `target/smtp-conformance/`. Generated artifacts and local logs are not part of the implementation commits.

The real-server run used a frozen copy of the completed Java 11 build while the modern-JDK reactor was running. Only the runner's artifact-copy/build step was replaced; its server lifecycle, scenarios, independent signature/content checks and evidence generation were unchanged. The evidence records this compiled-input boundary rather than claiming that a second Maven build ran concurrently.

## Holistic review corrections: recovery fencing and retained cleanup

The six-seam review found two missing cases. `ConnectionRecovery` now captures the active recheck visit with each observation, so cancellation fences every pending retry even after an earlier retry advanced the revision. A retired visit cannot invalidate its replacement's observations. `AcquiredMailTransport` retains send failure only for lease-health decisions; its owning resource scope handles suppression onto the exception actually escaping that scope. A callback that catches a send failure cannot hide a later cleanup error.

Ten new regression cases cover late success/failure after multi-attempt cancellation, direct and pooled callback cleanup after a handled failure or another send, and exact exception/receipt identity when the callback or send exception escapes. Against the pre-fix implementation, nine of these cases failed; the pooled escaping-failure case already passed against the pre-fix behavior.

The corrected implementation passed all 141 focused tests on JDK 21 and on real JDK 11.0.16.1, with no failures, errors or skipped tests. The Java 11 `verify` run also completed Javadocs and the existing classpath/JPMS consumer checks. Evidence is in `target/755-review-fixes-jdk21.log` and `target/755-review-fixes-jdk11.log`; the two pre-fix regression summaries are retained separately under `target/755-review-*-before.txt`.

This is focused verification, not another full-reactor, Spring, website or real-server conformance run. No public API, routing permission, submission replay boundary or resource layer changed. The concurrency catalogue and failover architecture now explain the recheck-visit fence and the distinction between lease health and an escaping exception.

An initial license-formatting attempt hit a Windows mapped-file error and emptied five source files before compilation. Their contents were restored before testing, including the previously uncommitted factory-runtime builder wiring. Subsequent test runs disabled header formatting. Final `mvn license:remove` used one worker, and comparison against safety copies confirmed that cleanup preserved every source body. No empty source files or generated license headers remain. The changes are still uncommitted.

## Final review corrections: opt-in wiring and recovery backoff

The final review caught a behavior regression in the style cleanup: the builder enabled coordinated background-allocation gating even without a failover relationship. The existing helper-level test bypassed that builder. The restored guard keeps ordinary pools' background recovery enabled; configured route participants still defer replenishment until operation-led recovery.

Recovery backoff now advances once per failed recheck visit, rather than once per physical setup failure. Three immediate failures therefore leave a configured 30-second first recheck at 30 seconds. Retries within a later recheck count once, and successful recovery resets the schedule. Existing revision and cancelled-visit fences remain in place.

Ten new regression cases cover managed and caller-owned Session builders, clearing a relationship before building, nested participants and an unrelated Mailer, actual background pool recovery without a foreground send, configured-route suppression/resumption, direct and pooled multi-attempt routing, capped/reset backoff, and cancelled late retries. Eight cases failed against the pre-fix implementation. The first pool-reuse assertion incorrectly used the deliberately dedicated `withOpenConnection` path; it now performs an ordinary pooled send only after independently observing background replacement.

The corrected runs on JDK 21 and real JDK 11.0.16.1 each passed all 153 focused tests with no failures, errors or skips (`target/755-final-review/fixes-jdk21-final.log` and `target/755-final-review/fixes-jdk11-verify.log`). The Java 11 `verify` run also passed Javadocs and the classpath/JPMS consumers. The pre-fix evidence is retained in `target/755-final-review/regressions-before-fix.log`. This is focused regression verification, not another full-reactor or SMTP conformance run.

The two stale website claims about caller-thread asynchronous preparation were corrected. Website checks passed all 84 tests, the clean build succeeded, and 3,948 internal links passed across 66 pages. The policy Javadoc and concurrency documentation explain visit-based backoff; the API expansion workflow now requires opt-in tests through real builder wiring. No public signatures, resource layers, routing permissions or submission replay boundaries changed.

The corrections were checked against the coding guide and passed focused whitespace checks. License removal ran with one worker; SHA-256 comparisons confirmed it changed no Java source files, and no generated headers remain. No commits, pushes or GitHub changes were made.

The final rendered-Javadoc check found that the incremental build had reused earlier HTML. A fresh core-module Javadoc run with a separate stale-data file succeeded, and the generated `FailoverPolicy.Builder.html` was checked for the updated per-visit backoff contract (`target/755-final-review/core-javadoc-final.log`).

## Recovery overlap and public-boundary corrections, 2026-10-11

The next holistic pass reproduced two runtime gaps and one documentation mismatch. Internal connection-setup evidence escaped the documented `MailException` family. A failed connection advanced the same revision used to validate a concurrent successful connection, leaving the primary marked unavailable after it had demonstrably recovered. Failover-enabled batches and callbacks retained pool leases while their public documentation still described all such scopes as non-pooled.

`ConnectionSetupFailure` now extends `MailException`, without changing its cause, routing classification or receipt-free setup semantics. Recovery uses an observation generation separately from the owning recheck's lifetime. Failure keeps a window open for a concurrent success; success and a deliberate due recheck retire older results. Each observation publishes once. Successful visits still finishing a send neither own later observations nor block the next incident's recheck. Cancellation continues to fence the whole visit, including pending retries.

Added 29 regression cases cover both start/completion orders, cancelled and replacement observations, work begun after recovery, exact public exception/observer identity through real sync/async and direct/pooled sends, and retained connection close versus pool release/reuse. The initial pre-fix run reproduced 14 failures (`target/755-final-review/overlap-regressions-before-fix.log`). The pooled exception test initially assumed the provider exception was the immediate cause; it now preserves and verifies the existing upstream setup wrapper as well as the provider's exact exception.

Verification for this pass:

- Real JDK 11.0.16.1 `verify`: all 182 focused tests passed, with no failures, errors or skips; Javadocs and the existing classpath/JPMS consumers also passed (`target/755-final-review/overlap-fixes-jdk11-verify.log`). Generated `Mailer` and `Mailer.Sync` HTML contains the corrected retained-lease contracts.
- JDK 21 ran the same 182 cases. Its only four failures were the overly strict pooled-cause assertions described above; all eight public exception-contract cases passed after correcting that assertion (`overlap-fixes-jdk21.log` and `overlap-contract-jdk21.log`). No production change was needed after that first run.
- Website checks passed all 84 tests; the clean build succeeded, and 3,949 internal links passed across 66 pages. The pooling and failover guides explain retained capacity, release versus close, and nested-send waiting risks.
- Verified 125 local links across the edited architecture, concurrency, ADR and workflow documents. The two recovery state diagrams and two related sequence diagrams rendered in a real browser; this also caught and removed an invalid semicolon in an existing sequence label.

The architecture guide and concurrency catalogue now contain the recovery publication table. The design keeps routing, connection observations and resource cleanup under separate owners, without a general rules engine, new public state API or another executor. The API expansion workflow requires completion-order tests alongside real sending-boundary regressions. Resource layers and the shared infographic remain unchanged.

The changed code passed the coding-guide and whitespace checks. Single-worker `mvn license:remove` succeeded; all 660 Java source hashes stayed unchanged, with no empty sources or generated license headers remaining.

This is focused correction verification, not another full-reactor, Spring, upstream-pool or real-server conformance run. The changes remain uncommitted; no GitHub state or publication was changed.
