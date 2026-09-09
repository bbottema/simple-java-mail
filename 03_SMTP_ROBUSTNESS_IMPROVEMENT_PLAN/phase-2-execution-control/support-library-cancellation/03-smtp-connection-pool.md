# 3. SMTP Connection Pool: acquisition cancellation and per-lease abort

- Status: Implemented and released as [4.1.0](https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/4.1.0) on 8 September 2026; [#31](https://github.com/simple-java-mail/smtp-connection-pool/issues/31) closed. Built-in Angus physical abort remains unsupported.
- Repository: `simple-java-mail/smtp-connection-pool`; planning baseline 4.0.2
- Depends on: [Clustered Object Pool propagation](02-clustered-object-pool.md)
- Main implementation boundary: `SmtpConnectionPool`, `TransportAllocator`, `SmtpTransportLease`, optional transport capability, Jakarta provider integration and tests

## Keep two operations distinct

Cancelling a pending `claimTransport(...)` means the caller no longer wants a connection. Cancelling an operation on a claimed connection means stopping physical transport work while the caller owns that lease. A caller must not have to destroy the whole pool for either operation.

The existing `SmtpTransportLease` has exactly-once release/invalidation state. Preserve that ownership contract, its existing methods and their return types. `invalidate()` currently schedules disposal; it is not an immediate socket abort or proof that disposal has finished. This pool does not own Simple Java Mail receipts and must not invent SMTP acceptance/retry conclusions.

## Slice A: cancellable acquisition

1. Add `claimTransport(Session, ClaimOptions)` and retain `claimTransport(Session)` unchanged. Accept the shared `ClaimControl` through `ClaimOptions.withClaimControl(...)`; `requestCancellation()` controls acquisition only. Reuse the generic cancellation contract and the effective configured cluster timeout. Document the new route's cancellation and timeout mapping explicitly; retain the legacy route's existing exception behavior.
2. Pass the allocation context into `TransportAllocator` before obtaining or connecting a transport, including reconnect/readiness work in `allocateForReuse`. Obtain any optional provider abort registration before the first blocking connection operation, not after a connected lease is returned.
3. Check the request before and after OAuth token lookup, transport creation, connection and reuse checks. An arbitrary OAuth `Supplier` or Jakarta authenticator is not forcibly cancellable. If it returns after cancellation won, do not proceed into SMTP; clean up any newly created transport.
4. On connection failure or cancellation, retain ownership of every partially created transport until disposal has been scheduled and acknowledged as required. Do not leave a transport unowned merely because `connect()` threw before `SessionTransport` could be constructed.
5. Preserve the exact caller-owned Session and provider-selection behavior. No request-scoped property mutation on shared Sessions and no `ClaimControl` or internal cancellation state stored as global Session configuration.

This slice promises cancellation of pool-controlled waiting and cooperative connection setup only. With a non-cooperative physical provider, document that a request during `connect()` is observed when that call returns; never describe this as confirmed immediate abort.

## Slice B: optional per-lease transport cancellation

Use a small provider-neutral `TransportCancellationSupport` capability, supplied explicitly when configuring a pool that needs it. Do not add an Angus dependency to the pool core or require every transport to implement a new interface.

The support object creates connection-owned abort control before connect/reconnect and can bind an exclusive, detachable registration to a particular allocation/lease generation. The provider-specific implementation owns how sockets are obtained or replaced; the pool owns which attempt is allowed to abort them.

Expose this as secondary lease API, provisionally `getCancellation()` returning an optional cancellation capability with `request()`, following the accepted mail-send pattern. A request means an abort was requested, not that a message was unsent. Unsupported providers return an absent capability, not a successful no-op. Keep `ClaimControl` scoped to acquisition: once a claim has handed off a lease, its acquisition registration no longer controls that lease. A send coordinator can bind its separate, still-live send request to the lease capability with a registration that immediately observes any request made during handoff. This does not extend the lifetime or scope of `ClaimControl`.

Required semantics:

- An effective request atomically prevents healthy reuse before asking the provider to abort. An aborted or uncertain connection is invalidated, never released as healthy by a racing `close()`.
- The abort action must not wait behind connect/send's monitor or block the requesting thread on ordinary provider cleanup.
- Cover connect, greeting, TLS negotiation/socket replacement, authentication, reuse probes and active sending wherever the capability claims support. Explicitly exclude uncooperative name resolution, credential callbacks or custom factories from any hard deadline promise unless separately proven.
- Detach the active attempt registration before successful reuse. A detached callback that is already racing must be fenced by its generation; checking only a boolean and then closing a shared socket is insufficient.
- Preserve release/invalidation exactly-once behavior and cleanup failures. Add secondary disposal acknowledgement by delegating to the generic pool object's completion, without changing `invalidate()` from its existing boolean result. It must not be mistaken for email completion.
- The caller/provider must acknowledge active operation exit as well as disposal before reporting confirmed abort. A raw `Transport` borrowed by a third-party caller cannot let the pool infer when arbitrary caller code has finished.

## Local Angus feasibility gate; no upstream PR dependency

Before implementing Slice B for Angus, extend the existing loopback characterization into a bounded local investigation of supported integration points:

1. A per-connection socket/socket-factory integration, including STARTTLS and implicit TLS wrapping, failed connects, proxy routing and caller-owned Sessions.
2. A locally controlled provider integration only if the first route cannot meet ownership requirements. Determine its real maintenance surface before proposing it; do not start a fork or replace Angus implicitly.
3. Verify the exact boundary for DNS, TLS, AUTH, message writes and final replies. Closing a reference to an old socket, cancelling a future or calling synchronized `Transport.close()` is not an acceptable positive result.

No reflection, global socket registry/factory, shared-Session mutation, disabled TLS checks or copied SMTP engine is allowed as a shortcut. If a safe supported integration cannot be demonstrated, record the unsupported cases, retain the independently useful acquisition API, and keep Simple Java Mail's full in-flight cancellation gated. The existing [BDAT PR #210](https://github.com/eclipse-ee4j/angus-mail/pull/210) is unrelated to providing these hooks and is not on this plan's delivery path.

## Required tests

- Queued pool claims, pool-size-one contention and different Sessions/clusters; cancel only the intended job's work.
- Slow/failing token provider, initial connection, reconnect and `isConnected()` readiness probe; partial transports disposed exactly once.
- A capability-bearing fake transport proves the SPI independently of Angus, including registration before connect, immediate prior requests and TLS-like socket replacement.
- Actual supported provider tests block at greeting, EHLO, STARTTLS, AUTH, MAIL, RCPT, DATA writing and final reply. Each test observes real operation exit, not just a cancelled wrapper future.
- Lease release/abort races and a delayed old callback after a new borrower claims the same physical connection; the new borrower must survive.
- Accepted final reply before a late request stays available to the caller; missing final reply after possible submission remains a concern for the downstream receipt layer, not a fabricated pool result.
- Healthy send after cancelled allocation, failed reuse, invalidation and disposal failure; preserve all eight released mixed-failure waiter-recovery regressions in downstream integration.
- Disposal acknowledgement, active-operation exit and shutdown ordering; no new abandoned background task or untracked socket.
- Plain SMTP, STARTTLS, implicit TLS, authenticated proxy routing, caller-owned Sessions and unsupported/custom providers, according to the advertised capability matrix.
- Direct leases and the Jakarta `smtppool` provider keep their distinct ownership paths; provider-owned acquisition cancellation may propagate thread interruption, but do not invent a per-send control property in Session or stack another pool around it.
- SMTP core/provider Java 8 and full JDK 21 verification, existing Camel/Spring/demo integration lanes, source/binary compatibility and module-name checks.

## Downstream handoff and delivery

Document acquisition-only versus physical-abort support in README/Javadocs with a small capability table and a stopped-job example. Add a bounded fake/local-server demo, not a production SMTP dependency in tests. Follow `MAINTAINER_WORKFLOW.md` and `RELEASING.md`, including the original core artifact's compatibility checks and all module baselines. Keep this as independently reviewable slices if the provider gate remains closed.

For Simple Java Mail, route the same send request through queue admission, pooled acquisition and a supported leased operation; unregister it before reuse and await operation exit plus required cleanup before the observer and completion. Preserve `MailSend<T>.getCompletion()` and secondary `getCancellation().request()` as the accepted public direction, without extra send overloads. Preserve exact final receipts, failure identity, batch laziness, shared-connection ownership and unknown outcomes after possible SMTP acceptance. Resume the full [existing step 3 plan](../03-give-deadlines-and-cancellation-protocol-meaning.md) only after the relevant capability is proven; a pool-only release does not make that public send handle release-ready.
