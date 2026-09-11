# Step 3: Give deadlines and cancellation protocol meaning

- Status: Complete: implemented, verified and accepted; holistic-review corrections completed on 11 September 2026. Unreleased.
- Depends on: Steps 1 and 2
- Child issue: [#726](https://github.com/bbottema/simple-java-mail/issues/726), under [#722](https://github.com/bbottema/simple-java-mail/issues/722), milestone 10.0.0
- Classification: `major feature`, never also `enhancement`
- Release sensitivity: Any new send handle or terminal outcome contract should be settled before the 10.0 API freeze
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, `batch-module`, authenticated proxy support
- Supporting-library continuation: [Cancellation in the supporting pool libraries](support-library-cancellation/README.md) - released as Generic Object Pool 2.5.0, Clustered Object Pool 4.1.0 and SMTP Connection Pool 4.1.0; now integrated into Mailer sends

## Goal

Make a deadline or cancellation request reach the work that is actually waiting or performing SMTP I/O, while preserving honest submission outcomes at every protocol boundary.

Calling `CompletableFuture.cancel(...)` changes the Java future but does not prove that queued work, pool acquisition, socket I/O, or the SMTP transaction stopped. The implementation distinguishes a cancellation request from the eventual receipt or failure. The following design outline and gate findings record how that contract was reached; the implemented contract is summarized at the end.

## Contract to design

Define:

- one total deadline covering preparation, queue wait, pool wait, connection, TLS, authentication, commands, message transfer, final response, and cleanup;
- whether optional phase-specific timeouts are needed beyond existing connect/read/write properties;
- how sync and async callers request cancellation;
- how a terminal receipt or exception remains available when a cancelled future alone cannot carry it;
- whether the existing future API can be extended safely or needs an additional send-control handle;
- what thread invokes the observer and when cleanup completes.

The terminal semantics must distinguish at least:

- cancelled before scheduling or preparation;
- cancelled while queued or waiting for a pool lease;
- cancelled before MAIL;
- cancelled during RCPT;
- cancelled while message bytes are being written;
- cancellation requested after the final DATA terminator but before the final reply;
- cancellation requested after final acceptance was observed.

Before the commit boundary, cancellation may be known to have prevented submission. After the commit boundary, return accepted if final success was observed and unknown if it was not. Never relabel a potentially accepted transaction as unsent.

## Internal design questions

1. Determine how an in-flight adapter exposes a safe cancellation hook without leaking Angus transport types into core.
2. Determine whether closing the socket is the only reliable Angus interruption mechanism and how that interacts with proxy bridges.
3. Ensure a cancelled or ambiguous pooled transport is invalidated rather than returned for reuse.
4. Use a monotonic time source for elapsed deadline calculations; use wall-clock `Instant` only for public timestamps.
5. Define races among normal completion, cancellation, timeout, observer invocation, Mailer close, and executor shutdown.
6. Preserve exact caller-facing failure identity where the provider completed with a real exception before cancellation won the race.

## Tests first

1. Add a scripted peer that can block at greeting, EHLO, STARTTLS, AUTH, MAIL, each RCPT, DATA content, and final reply.
2. Cancel or expire a deadline at every blocked phase and assert the terminal status and retry guidance.
3. Cover queued work and pool acquisition without opening a socket.
4. Race cancellation against a final 250 reply repeatedly and assert only valid terminal outcomes.
5. Verify cancelled transports are invalidated and a subsequent pool-size-one send succeeds on a fresh transport.
6. Verify observers run once and only after release/invalidation.
7. Verify Mailer shutdown cannot strand a cancellation completion.
8. Cover authenticated SOCKS bridging and caller-owned Sessions.
9. Cover sync sends interrupted from another thread and async sends cancelled by their owner.
10. Bound every test so the suite cannot hang when interruption fails.

## Acceptance criteria

- [x] A total deadline reaches queue, pool, and SMTP waits rather than merely timing out the caller.
- [x] Cancellation has one deterministic winner against normal completion.
- [x] Post-commit ambiguity remains `UNKNOWN` and retains duplicate-risk guidance.
- [x] Every cancelled or ambiguous connection is disposed safely.
- [x] Observer, future, receipt, and thrown-exception behavior agree.
- [x] Existing timeout properties remain documented and are not confused with the new total deadline.
- [x] Library-controlled waits are bounded and tests cannot hang. Non-cooperative DNS, application callbacks, data sources, and unsupported provider work may delay completion or shutdown; this limitation is documented rather than masked by a completed wrapper future.

## Stop condition

If Angus cannot be interrupted without unsafe reflective access or global state, stop and define the required provider SPI or upstream change. Do not advertise cancellation when only the wrapper future is cancellable.

## Historical gate finding, 8 September 2026

The accepted public direction is `MailSend<T>`, `getCompletion()`, and secondary `void requestCancellation()`, without extra send overloads. The completion accessor returns a detached `CompletableFuture<T>` view, not a new status DTO. Cancellation is an idempotent request without a boolean acknowledgement or a separate public control object. The final receipt or failure reports what actually happened. This API must not be advertised as delivered before end-to-end verification passes.

`AngusCancellationBoundaryTest` characterizes Angus 2.0.5 against a real loopback SMTP peer, blocked both at MAIL FROM and while awaiting the final DATA reply. In both cases:

1. Cancelling the wrapper future reports cancellation but does not stop the running SMTP operation.
2. Concurrent `SMTPTransport.close()` waits for the transport's monitor, already held by `sendMessage(...)`; it cannot interrupt that blocked send.
3. After the peer resumes, SMTP returns success even though the future remains cancelled.

These tests disprove cancellation through the current future/Transport.close path, not every possible Angus integration. Angus socket-factory and custom-provider approaches need a separate design: they must preserve caller-owned Session behavior, TLS socket replacement, proxy routing, connection reuse, and phase/outcome facts. No reflective socket extraction, globally mutable factory, or per-request mutation of a shared Session was introduced.

### Required internal/provider boundary

The following capabilities must be agreed before implementing the public handle:

- **Monitor-independent abort:** a provider-owned handle that closes the active socket without waiting for the send/connect monitor. Register it before the first blocking connect/greeting/TLS/authentication step, not only when the submission adapter receives an already-connected Transport. Follow socket replacement during TLS and dispose failed connection attempts safely.
- **Attempt identity:** bind that abort handle to the current connection attempt or exclusively held lease. Unregister it before reuse; an old request must never close the next email's connection.
- **Protocol facts:** snapshot whether the DATA terminator may have been transmitted and whether final acceptance was actually observed. Abort alone cannot turn a potentially accepted send into an unsent one. A final observed success wins over a late cancellation request; a missing final response after possible commit remains UNKNOWN.
- **Pool cooperation:** propagate the attempt control through pool waiting, allocation and reuse checks, not just leased submission. Cancellation must wake a waiter and invalidate an interrupted lease. Pool claim timeout does not bound synchronous allocation callbacks, which include connecting and possibly application token-provider work.
- **Custom boundaries:** optional, explicit support for `CustomMailer` and alternative providers. Arbitrary user callbacks and caller-owned Session providers cannot be forcibly stopped safely; cooperative cancellation or an explicitly unsupported boundary is required, not a universal total-deadline claim.
- **Terminal ordering:** cleanup/lease invalidation completes before publishing the observer and completion result. Cancellation request state must remain separate from the immutable final SMTP receipt, with one terminal winner.

### Continuation decision, 8 September 2026

An Angus PR is not a viable scheduling dependency for this work. Plan the cancellation APIs in the supporting libraries we control first: [Generic Object Pool](support-library-cancellation/01-generic-object-pool.md), [Clustered Object Pool](support-library-cancellation/02-clustered-object-pool.md), and [SMTP Connection Pool](support-library-cancellation/03-smtp-connection-pool.md). Pending-claim cancellation is independently useful; it must not be held back by the separate physical-SMTP-abort gate.

The SMTP step includes a bounded local feasibility investigation of supported socket/provider integration, not authorization to fork Angus or replace it. If no safe integration can be demonstrated, ship only the proven supporting-library capabilities and retain this step's stop condition. The existing BDAT PR #210 remains unrelated to cancellation and is not on the delivery path.

The queue controls in #725 can still be reviewed independently. #726 is not release-ready. A request/deadline must not be reported as completed cancellation while non-cooperative callbacks or cleanup continue; document those limitations rather than promising an unconditional wall-clock return deadline.

## Implementation continuation, 9 September 2026

- Keep the negative Angus future/close characterization. The supported socket-factory route now has passing loopback abort probes at greeting, EHLO, AUTH, MAIL, RCPT, DATA, final response, QUIT, implicit TLS/STARTTLS handshake, SOCKS negotiation, and before socket creation.
- Install tracking only on compatible SJM-owned Sessions and leave caller-owned/custom settings alone. Preserve TLS, proxies, provider selection, and pooled-generation fencing; no reflection or upstream PR dependency.
- Add positive `withMailSendTimeout(Duration)` plus reset/getters, disabled by default, with ISO-8601 property/Spring/CLI support. Count library-controlled preparation, admission, acquisition, SMTP and cleanup; exclude observers and application work between open-connection sends.
- Keep inline observer ordering by default. Add `withMailSendObserver(observer, Executor)` for application-owned dispatch. Log rejected notifications without changing send results; never silently retry or run them inline. Distinguish send completion, observer handoff, and downstream processing.
- Use a separate daemon control-completion worker for unstarted cancellations, never the deadline watcher or an unrelated SMTP worker. Slow application code may delay reporting but must not prevent physical aborts.
- Add Configuration / Sending and execution, preserve old anchors, and cross-link Pool orchestration and Analyzing send results without conflating their responsibilities.
- Complete the expanded concurrency/provider/configuration coverage, Java 11 and JPMS/classpath/CLI/Javadoc verification, Spring matrix, full non-live verification, license removal, coding-guide audit, and website checks. Preserve unrelated Journal/research work. Leave changes uncommitted for review.

## Implemented contract

- `MailSend<T>` is returned by async, default-mode, boolean-mode, receipt-returning and simple-batch sends. `getCompletion()` returns a fresh detached `CompletableFuture<T>` view; mutating that view cannot alter the send. `void requestCancellation()` is an idempotent request, not an acknowledgement. Explicit sync methods and non-send operations keep their return types.
- `withMailSendTimeout(Duration)` has no default deadline. Reset/getters, ISO-8601 properties, CLI conversion, Spring configuration/IDE metadata and grouped diagnostics use the same typed value. Cancellation/timeout exceptions retain the exact available submission receipt; there are no new SMTP statuses or automatic retries.
- One internal operation owns preparation, admission, execution and terminal completion. Deadline signalling never invokes an observer or completes a future. Accepted queued cancellations reclaim an owned queue slot and use a separate lazy daemon completion worker; running sends finish on their execution thread. Caller-owned executor wrappers become inert if retired before execution.
- Supported Angus socket factories capture the physical socket before connect and close it independently of the SMTP monitor. Abort is latched before socket creation and fenced before pooled reuse. TLS, proxy and provider selection remain intact; custom/caller-owned Sessions are not rewritten. Timed shared clusters validate every member's abort capability.
- Receipt facts remain attempt-local, including partial RCPT facts. A possible DATA terminator or BDAT LAST with no final reply remains `UNKNOWN` / `DUPLICATE_RISK`; a genuinely observed final acceptance wins over a late request. A failure already recorded before cleanup is not replaced by a later cancellation.
- Ordinary sends notify after required release/invalidation/disposal. Simple batches notify per email while their intentionally shared transport remains open; they stay lazy and stop at the first failure. A simple batch shares one send budget, excluding observer work. Open-connection establishment and individual sends have separate budgets; application gaps and final scope cleanup are outside those budgets.
- Inline observers still run before result completion. `withMailSendObserver(observer, executor)` opts into application-owned dispatch: handoff is attempted before completion, without waiting for callback execution. Rejection is logged without fallback/retry or changing the send result. Direct executors, caller-runs policies, blocking admission and silent discard are documented explicitly. Reconfiguring either overload replaces the complete registration.
- Mailer shutdown drains its accepted sends and observer handoffs, including operations submitted to an application executor. It does not await external callback/downstream work or close application executors. It rejects self-waiting close calls and preserves resource cleanup on failure.
- Documentation separates Configuration / Sending and execution, standalone Pool orchestration, and Diagnostics / Analyzing send results. Existing anchors forward to the new guide. README, migration/release notes, mechanisms catalogue and a manual `MailSendExecutionDemoApp` describe the API and its limits.

## Verification record

- Java 11 clean, full non-live Maven verification passed (CLI excluded, as in the library compatibility lane), including Javadocs and provider-neutral classpath/JPMS consumers. A subsequent Java 11 focused `verify` also passed the latest elapsed-deadline regression and the new managed-Angus JPMS consumer, which constructs SMTP/SMTPS transports through Jakarta Mail and discovers their abort SPI without network access.
- The checked-in Spring compatibility matrix passed: Boot 2.7.18 / Spring 5.3.39 / Java 11; Boot 3.0.13 / Spring 6.0.14 / Java 17; Boot 3.5.16 / Spring 6.2.19 / Java 21, with each lane's declared SLF4J version.
- Focused coverage includes detached completion views, stop arbitration/fencing, queue retirement/admission budgets, observer dispatch/isolation, 27 end-to-end execution-control cases, 12 Angus socket-abort probes, retained negative Angus characterization, concurrent/fresh pooled sends, non-cooperative data sources, batch/open-connection budgets, configuration, CLI and Spring surfaces.
- Website checks and clean build passed. Internal-link verification reports only five existing links in the unrelated Journal article `the-libraries-behind-simple-java-mail`; no moved/new documentation link failures remain. Journal/research work is untouched.
- Java 21 `clean verify -Ppublish-cli -DexcludeLiveServerTests=true -Dlicense.skip=true` passed: 869 tests passed, no failures/errors, and the one opt-in daemon benchmark was skipped. This also built Javadocs, generated CLI metadata, packaged the CLI and ran the packaged classpath/JPMS probes.
- `mvn license:remove` passed; no generated license headers remain in changed Java sources. The touched classes were audited against the coding guide, including explicit lifecycle ownership, builder-supplied defaults, interface-linked implementation Javadocs, imports, and removal of obsolete prepared-send methods. Root and scoped website diffs pass whitespace checks.
- The initial implementation pass left changes uncommitted for review. Review is now accepted; semantic commits and issue completion cover this step and the already accepted queue controls, not later phases or a release.

## Final review, 11 September 2026

- Fixed exceptional lease cleanup so disposal still settles after release/invalidation failure, retaining the first failure and suppressing subsequent cleanup failures. The invalidation-plus-disposal regression failed before the correction for both runtime exceptions and errors; all 12 lease-cleanup cases now pass on Java 11, including abort failure and self-suppression.
- Updated the concurrency catalogue's stale exceptional-cleanup warning and linked the regression coverage. Reviewed the shared infographic unchanged: the correction enforces its existing cleanup-before-outcome boundary without changing the pictured responsibilities. The website PNG matches the canonical image.
- Checked the website against the final API: completion views, queue configuration, cancellation, time budgets, provider limitations, observer dispatch, shutdown, configuration surfaces, migration, and demos are covered. Website checks and clean build passed; the internal-link scan still finds only the five unrelated Journal failures recorded above.
- The final full-reactor run exposed timing-sensitive tests under parallel load: cold Mockito attachment consumed the operation test's safety timeout, and short SMTP budgets expired before their intended boundaries. Mock initialization now happens during fixture setup; the affected SMTP tests have more scheduling headroom while retaining blocked-protocol, expiry, first-failure and over-budget application-gap assertions.
- Final Java 21 full-reactor `verify -Ppublish-cli -DexcludeLiveServerTests=true -Dlicense.skip=true` passed: 888 tests passed, no failures/errors, and the opt-in daemon benchmark was skipped. This includes Javadocs, CLI archives, Spring/starter tests, and the packaged classpath/JPMS consumers.
- The final Java 11 focused run passed all 51 control, operation, lease-cleanup and end-to-end execution-control cases.
- Final `mvn license:remove` passed without changing committed Java sources; the changed Java files contain no generated license headers.
- The exact selected website commit was also checked and clean-built in an isolated snapshot, without the unrelated Journal/navigation edits. Its link scan has the same five existing Journal failures and no Phase 2 documentation failures. All 168 relative links in the concurrency catalogue resolve.
