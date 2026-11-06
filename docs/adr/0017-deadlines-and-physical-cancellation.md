# ADR 0017: Give cancellation and deadlines transport meaning

- Status: Accepted, recorded retrospectively
- Decision recorded: 2026-09-16; not the original decision date
- Applies to: `codex/10.0.0`
- Implementation status: Implemented, unreleased 10.0.0 behavior

## Context

Timing out or cancelling a completion future does not necessarily stop the queued work, pool acquisition or SMTP I/O behind it. That distinction matters when a notification becomes obsolete or a batch job is stopped. Reporting cancellation while the background send continues can lead an application to retry a message already accepted by SMTP.

Ordinary Angus `SMTPTransport.close()` is not a reliable abort mechanism: the selected implementation synchronizes connection, sending and close operations on the same transport. A thread blocked inside a send can prevent the closing thread from reaching the socket. A pooled connection adds another hazard: a delayed abort from one attempt must never affect its next borrower.

## Decision

Expose `MailSend<T>` with two independent operations: `getCompletion()` creates a detached future view, and `requestCancellation()` records an idempotent stop request. Mutating a completion view cannot change the actual send. Neither a cancellation request nor deadline expiry is an acknowledgement that SMTP work has finished or that the message was unsent.

Use one control with monotonic elapsed-time accounting from preparation through admission, queueing, pool acquisition and transport cleanup. The total-send timeout is disabled by default. The first stop reason wins, and an already observed failure is frozen before cleanup so later expiry cannot relabel it. Simple batches share one budget and pause it around per-email observer notification/handoff. Open-connection setup and each scoped send have separate budgets; arbitrary application gaps and final scope cleanup have no send deadline.

Separate deadline signalling from terminal reporting. A lazy deadline worker invokes short internal stop actions, while a separate completion worker retires unstarted operations and runs their terminal reporting. Running operations unwind and finish on their execution thread. This keeps a slow observer or future continuation from holding up unrelated deadline signals. A cancelled wrapper remaining in an external executor becomes inert and releases its captured work.

Discover physical-abort support through the provider lifecycle SPI. For compatible library-owned Angus Sessions, install a managed transport and supported socket factory that captures the socket before connection setup. Abort closes that handle without the SMTP monitor; an abort latch also closes a socket published after cancellation. Preserve Angus's protocol, proxy and TLS responsibilities. Do not use reflective access or mutate caller-owned Sessions to manufacture support.

Carry cancellation and the remaining budget into pool claims. Fence acquisition handlers before handoff, and bind abort authority to the exclusive lease generation. Fence/detach stop registrations before successful reuse; invalidate an aborted lease and await invalidated disposal on the ordinary send cleanup path before publishing its terminal outcome. A late handle cannot abort a new borrower.

Preserve protocol facts independently of the stop reason. If the final commit may have reached SMTP but its reply was lost, report unknown acceptance and duplicate risk. Preserve observed acceptance when cancellation arrives during later cleanup. Socket closure is not an SMTP rollback.

Accepted 20 October 2026: timeout and cancellation remain meaningful subclasses of core `MailException`, classified as `TIMEOUT` and
`CANCELLED`. Optional receipts are read on the common root, so stop translation retains facts from every library-owned failure without a
`MailSendException`-specific cast. Translation does not relabel programmer errors or application callback RuntimeExceptions merely because
a stop request races their propagation. Existing first-failure, cleanup and physical-stop ordering remain binding.

## Recorded reasoning and implementation trail

| Evidence | What it establishes |
| --- | --- |
| [#726](https://github.com/bbottema/simple-java-mail/issues/726) | Explicitly rejects future-only cancellation and documents the original Angus monitor deadlock gate. It requires queue/pool/I/O reach, truthful accepted/unsent/ambiguous results and an honest unsupported-provider boundary. The issue retains both the original gate and its later resolution. |
| [Supporting-library release comment](https://github.com/bbottema/simple-java-mail/issues/726#issuecomment-5584872823) | Cancellable pool claims, cleanup acknowledgement and per-lease abort were delivered before SJM integration. Releasing these dependencies alone did not complete SJM cancellation. |
| [ce6825be](https://github.com/bbottema/simple-java-mail/commit/ce6825be2af2b6758481692466c9ccac017e77e6) | Adds detached completion handles, stop arbitration and managed Angus socket-abort hooks, including connection and TLS coverage. |
| [9b4d1118](https://github.com/bbottema/simple-java-mail/commit/9b4d1118b589926baff592d2d1c3d77d2ba6e050), [completion comment](https://github.com/bbottema/simple-java-mail/issues/726#issuecomment-5636193851) | Integrates admission, leases, SMTP, timeout configuration and observer cleanup ordering; documents cancellation as a request rather than a recall. |
| [Stop-control catalogue](../concurrency/04-stop-and-deadline-control.md), [registration catalogue](../concurrency/05-stop-registration.md), [Angus abort catalogue](../concurrency/07-angus-transport-abort.md) | Source-derived explanations of first-stop/failure arbitration, resource fencing, monitor-independent socket closure and the SMTP commit boundary. These describe concrete implementation invariants rather than inferred historical intentions. |

## Alternatives and consequences

Future-only cancellation and ordinary transport close were explicitly investigated and found insufficient in #726. The issue initially required an upstream hook if no safe provider integration could be demonstrated; the accepted managed socket-factory integration resolved that gate without an Angus patch. Replacing Angus with a wholly separate SMTP client was unnecessary to obtain this capability; that is an architectural inference, not a documented rejected proposal.

Caller-owned Sessions, custom socket factories, alternate providers and `CustomMailer` do not automatically acquire physical-abort support. Unsupported sending integrations retain untimed sending with cooperative cancellation but reject a configured total deadline before sending; logging-only mode is exempt. Timed pooled routing must validate every Session it could select, including subsequent registrations into that cluster.

The [managed custom-factory handling](../concurrency/07-angus-transport-abort.md) keeps that boundary. Owned custom-factory Sessions
disable omitted fallback and close a connected socket on a failed TLS handoff, but those decorators do not manufacture abort support.
The managed write-timeout scheduler retains its connection reference through abort, releasing it only after complete physical disposal
or failed acquisition cleanup. A socket timeout and the total-send deadline remain different controls.

This design adds control state, registration fences and pool/provider cooperation because completion must follow real cleanup. It deliberately does not promise a hard wall-clock completion bound for arbitrary DNS resolution, attachment sources, user callbacks or other non-cooperative code. Stop requests may take effect before the public result completes. The observer/cleanup order for a whole ordinary send also differs from a per-email notification inside a still-open batch or connection scope.

The former authenticated SOCKS relay could delay observing client EOF while its forwarder was blocked writing upstream. [ADR 0042](0042-direct-authenticated-socks-sockets.md) supersedes that relay and its proposed connection-to-tunnel association with direct ownership of the physical upstream socket. Supported abort closes that handle during setup or established SMTP I/O; the remote peer's later EOF observation remains a separate event.

## Implementation and regression anchors

[ADR 0030](0030-safe-bounded-mail-send-retries.md) records the retry extension implemented under #756. Backoff consumes
the same deadline, cancellation retires delayed continuations, and intermediate retryable failures must not freeze the logical send
as terminal. Existing terminal-failure and acceptance/cleanup arbitration remains authoritative; the operation now explicitly fences waiting and resumption.

- [MailSend](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSend.java), [MailSendControl](../../modules/core-module/src/main/java/org/simplejavamail/internal/util/concurrent/MailSendControl.java), [MailSendOperation](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailSendOperation.java)
- [MailTransportLifecycleAdapter](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/MailTransportLifecycleAdapter.java), [pool claims and leases](../concurrency/06-pool-claims-and-leases.md), [cross-machine regression evidence](../concurrency/08-cross-machine-contracts.md)

The catalogue links existing blocked-I/O, queue retirement, pool-size-one, stale-lease and acceptance-boundary tests. Those are finite regression evidence, not exhaustive scheduling proof; this documentation change did not rerun them.

## Pending authenticated SOCKS setup (accepted 29 October 2026)

The original bridge correction assigned each pending upstream handle to a tunnel owner under [ADR 0010](0010-authenticated-socks-bridge.md#pending-setup-belongs-to-one-tunnel-accepted-29-october-2026). The direct implementation in ADR 0042 now captures the upstream socket before DNS, TCP or SOCKS I/O, so supported physical closure needs no local peer, reader or forwarding handoff.

Keep one client connect budget across TCP, SOCKS authentication and CONNECT, separate from the established SMTP read timeout. Failed setup or a supported abort closes the exact owned handle. No independent proxy timer or ordinary SMTP read timeout is added to setup. Unlimited connect settings retain their meaning, and this does not strengthen native-DNS, callback, supplied-Session or custom-provider support limits. [#909](https://github.com/bbottema/simple-java-mail/issues/909) records the original pending-ownership correction.

## Established authenticated SOCKS physical disposal (accepted 29 October 2026)

The original association proposal is superseded by [ADR 0042](0042-direct-authenticated-socks-sockets.md), clarified for immediate bridge retirement on 2026-11-06. Each supported physical SMTP connection owns the captured upstream socket itself.

For the supported library-owned Angus route, cancellation, deadline expiry and physical disposal close that exact handle without waiting for the SMTP monitor. Connection ownership and exclusive lease fencing remain authoritative; they cannot be inferred from endpoint/port matching, FIFO order or a Session-wide stop. A stop before publication remains effective against the late handle, while a stale stop cannot reach another connection or a subsequent borrower.

Retain idempotent physical closure and original failure/suppressed-cleanup semantics. No listener, relay worker, independent write/idle timeout or public setting is added. Caller-supplied Sessions, custom factories, providers and TLS trust retain their existing support boundaries; this decision does not authorize replacing them to obtain an abort hook.

Socket closure still cannot recall SMTP acceptance. Preserve accepted, partial and unknown results, including duplicate risk and the distinction between a stop request and terminal completion. [#922](https://github.com/bbottema/simple-java-mail/issues/922) tracks direct integration and final dependency adoption; ADR 0010 remains a historical record of the removed bridge.
