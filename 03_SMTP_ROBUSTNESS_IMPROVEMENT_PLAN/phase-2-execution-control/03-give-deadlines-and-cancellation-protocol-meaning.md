# Step 3: Give deadlines and cancellation protocol meaning

- Status: Provider safety gate reached; cancellation/deadline implementation deferred, not delivered
- Depends on: Steps 1 and 2
- Child issue: [#726](https://github.com/bbottema/simple-java-mail/issues/726), under [#722](https://github.com/bbottema/simple-java-mail/issues/722), milestone 10.0.0 pending this gate
- Classification: `major feature`, never also `enhancement`
- Release sensitivity: Any new send handle or terminal outcome contract should be settled before the 10.0 API freeze
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, `batch-module`, authenticated proxy support
- Supporting-library continuation: [Cancellation in the supporting pool libraries](support-library-cancellation/README.md) - released as Generic Object Pool 2.5.0, Clustered Object Pool 4.1.0 and SMTP Connection Pool 4.1.0; downstream cancellation and the Angus physical-abort gate remain separate

## Goal

Make a deadline or cancellation request reach the work that is actually waiting or performing SMTP I/O, while preserving honest submission outcomes at every protocol boundary.

Calling `CompletableFuture.cancel(...)` currently changes the Java future but does not prove that queued work, pool acquisition, socket I/O, or the SMTP transaction stopped. The implementation must distinguish a cancellation request from confirmed cancellation.

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

- [ ] A total deadline reaches queue, pool, and SMTP waits rather than merely timing out the caller.
- [ ] Cancellation has one deterministic winner against normal completion.
- [ ] Post-commit ambiguity remains `UNKNOWN` and retains duplicate-risk guidance.
- [ ] Every cancelled or ambiguous connection is disposed safely.
- [ ] Observer, future, receipt, and thrown-exception behavior agree.
- [ ] Existing timeout properties remain documented and are not confused with the new total deadline.
- [ ] No test or production shutdown path can leave a non-daemon thread indefinitely blocked.

## Stop condition

If Angus cannot be interrupted without unsafe reflective access or global state, stop and define the required provider SPI or upstream change. Do not advertise cancellation when only the wrapper future is cancellable.

## Gate finding, 8 September 2026

The accepted public direction remains `MailSend<T>`, `getCompletion()`, and secondary `getCancellation().request()`, without extra send overloads. It is **not exposed yet**: changing return types before the implementation can honor their cancellation promise would be misleading. Ordinary `CompletableFuture` APIs remain in place.

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

The queue controls in #725 can still be reviewed independently. #726 is not release-ready, and no cancellation feature is listed in release notes or website examples. A request/deadline must not be reported as completed cancellation while non-cooperative callbacks or cleanup continue; document those limitations rather than promising an unconditional wall-clock return deadline.
