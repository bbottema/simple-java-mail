# ADR 0042: Use Angus transport sockets for authenticated SOCKS

- Status: Accepted, 2026-10-30; retained-bridge clarification, 2026-11-03; published snapshot input and final-baseline bridge retirement, 2026-11-05
- Target: Unreleased 10.0.0
- Tracking: [#922](https://github.com/bbottema/simple-java-mail/issues/922)
- Implementation status: In development on the dedicated branch; released dependency integration remains pending.
- Complements: [ADR 0010](0010-authenticated-socks-bridge.md) for the retained bridge and [ADR 0017](0017-deadlines-and-physical-cancellation.md) for physical ownership and cancellation.

## Context

Authenticated SOCKS currently adapts an anonymous local SOCKS connection into a remotely authenticated connection. That local listener and relay add resource lifetimes. An established forwarding reader can also be blocked writing upstream, delaying its observation of local client EOF. Closing the SMTP client's local socket does not directly dispose the upstream proxy socket in that case.

Angus now provides an independent transport socket setting through its accepted [transport/TLS composition contribution](https://github.com/eclipse-ee4j/angus-mail/pull/228). Application-provided connection code can therefore perform the SOCKS login while Angus retains SMTP, TLS and native timeout handling. This capability is present in merged source before a compatible final dependency release is available.

## Decision

Keep the authenticated SOCKS module optional. Add a direct client path through Angus's `mail.<protocol>.socketFactory.transport` hook, while retaining the existing bridge, listener, forwarding workers and bridge-port configuration for Angus versions without the hook. Bridge-port choices continue to apply when a bridge is used. Do not retire their Java, file, environment, Spring or CLI inputs as part of this development work.

Retain foreground-only connection setup for authenticated destinations in both routes; this change does not enable pre-send pool allocation during Mailer construction.

Choose the route before connecting, based on the selected Angus provider's actual support for the transport hook. Capability inspection must not connect, resolve names, start networking workers or invoke application factories. A failure after direct setup begins is a connection failure; it must not trigger a retry through the bridge or a route that skips the configured proxy. Anonymous SOCKS and HTTP proxy precedence remain unchanged.

On the direct path, create and capture the unconnected physical socket before proxy DNS, TCP connection or SOCKS negotiation. The socket facade performs method selection, username/password authentication and CONNECT with one remaining setup budget; zero retains unlimited setup. Restore the ordinary socket read timeout before TLS and SMTP. Retain the original SMTP endpoint and address kind, destination permission checks, options, binding behavior and passive metadata. Closing the facade disposes the same physical socket, with idempotent ownership and first/suppressed cleanup failures. Control writes do not acquire a separate timer.

Preserve the application's transport, ordinary socket and TLS factory choices, including supported instance and class forms. Use the independent transport hook for physical creation; leave Angus's TLS selection, trust precedence, original SNI/hostname and custom trust callbacks intact. Do not use deferred SSL facades to evade native write-timeout wrapping, replace TLS with defaults or change connection properties around a connect call. No new networking dependency or JVM-global Authenticator/factory state is introduced.

The Angus lifecycle adapter exposes this behavior through a core-internal configuration capability. An explicitly selected authenticated proxy permits that narrow decoration on the same caller-supplied Angus Session. It does not permit replacing its Session or provider. Other mail providers remain outside the agreed direct integration; CustomMailer owns its route. Connectivity through a supplied Session or opaque factory does not itself create cancellation or total-deadline support. Owned Sessions preserve tracked physical handles and the existing exclusive-lease fencing.

Resolve typed/raw declarations and locks under [ADR 0038](0038-connection-configuration-conflicts.md). Preserve original delegate identity when a managed decorator is compared with an application factory lock. Direct routing exposes the remote proxy endpoint; bridge routing retains its generated local endpoint. A dedicated probe receives fresh factory and socket state and does not inherit a send's cancellation ownership.

Preserve an explicit SOCKS username/password rejection as authentication evidence on that exact direct connection. Apply the existing
authentication-failure permission from [ADR 0029](0029-smtp-submission-failover.md), including when provider/pool wrappers or cached failures
carry the evidence. Malformed replies do not acquire that permission, certificate permission remains independent, and failed cleanup still
prevents another destination from starting. Do not infer authentication rejection from an exception message or shared bridge failure state.

Provide an explicit development lane that resolves a pinned, timestamped Angus snapshot from its public Maven repository. The normal dependency remains the released baseline. Development build settings and their Mail/Activation API choices must be visible and reproducible; do not present snapshots as supported production dependencies. Local Angus source builds and their provenance markers are no longer required. A compatible released Angus artifact must be checked before replacing this development input or delivering the release-dependent feature.

## Consequences

Direct connections need no local listener, second SOCKS exchange or forwarding worker. Supported cancellation and deadlines can reach the captured upstream connection directly. Pooled and retained transports keep ordinary physical ownership; stale stop requests cannot affect later borrowers.

The bridge remains available for the released dependency baseline. Its existing established-write backpressure and proxy-rejection classification limitations remain distinct from the direct connection; selecting the new route does not repair that old relay path. The proposed connection-to-tunnel association in ADR 0017 is superseded for the direct path by ownership of the upstream socket itself.

Keeping both paths costs additional implementation and coverage work. The maintainer accepted retiring the bridge and its port settings together with adoption of the compatible final Angus baseline on 2026-11-05. It remains only for the interim released-Angus lane. Local physical disposal, the remote peer's later EOF observation and SMTP acceptance remain different events.

The independent transport creator requires a plain unconnected socket. Legacy factory class settings can require a separate lazy lookup for physical creation while native Angus retains TLS selection. A getter that returns a different factory on each call cannot provide a precise single-result association between those roles; an explicit transport factory makes that choice unambiguous. Module-path factory access must also be granted to the component resolving the class.
