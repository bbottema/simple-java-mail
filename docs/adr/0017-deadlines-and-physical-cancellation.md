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

This design adds control state, registration fences and pool/provider cooperation because completion must follow real cleanup. It deliberately does not promise a hard wall-clock completion bound for arbitrary DNS resolution, attachment sources, user callbacks or other non-cooperative code. Stop requests may take effect before the public result completes. The observer/cleanup order for a whole ordinary send also differs from a per-email notification inside a still-open batch or connection scope.

## Implementation and regression anchors

- [MailSend](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSend.java), [MailSendControl](../../modules/core-module/src/main/java/org/simplejavamail/internal/util/concurrent/MailSendControl.java), [MailSendOperation](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailSendOperation.java)
- [MailTransportLifecycleAdapter](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/MailTransportLifecycleAdapter.java), [pool claims and leases](../concurrency/06-pool-claims-and-leases.md), [cross-machine regression evidence](../concurrency/08-cross-machine-contracts.md)

The catalogue links existing blocked-I/O, queue retirement, pool-size-one, stale-lease and acceptance-boundary tests. Those are finite regression evidence, not exhaustive scheduling proof; this documentation change did not rerun them.
