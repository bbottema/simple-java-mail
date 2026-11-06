# ADR 0042: Use Angus transport sockets for authenticated SOCKS

- Status: Accepted, 2026-10-30; direct-only development branch and immediate bridge retirement, 2026-11-06
- Target: Unreleased 10.0.0
- Tracking: [#922](https://github.com/bbottema/simple-java-mail/issues/922)
- Implementation status: In development on the dedicated branch; released dependency integration remains pending.
- Supersedes: [ADR 0010](0010-authenticated-socks-bridge.md) for the local bridge architecture and its port configuration.
- Complements: [ADR 0017](0017-deadlines-and-physical-cancellation.md) for physical ownership and cancellation.

## Context

Authenticated SOCKS previously adapted an anonymous local SOCKS connection into a remotely authenticated connection. That local listener and relay added resource lifetimes. An established forwarding reader could also block writing upstream, delaying its observation of local client EOF. Closing the SMTP client's local socket did not directly dispose the upstream proxy socket in that case.

Angus now provides an independent transport socket setting through its accepted [transport/TLS composition contribution](https://github.com/eclipse-ee4j/angus-mail/pull/228). Application-provided connection code can therefore perform the SOCKS login while Angus retains SMTP, TLS and native timeout handling. This capability is present in merged source before a compatible final dependency release is available.

## Decision

Keep the authenticated SOCKS module optional and use its direct client through Angus's `mail.<protocol>.socketFactory.transport` hook. Remove the local bridge, listener, forwarding workers, server-side protocol machinery and bridge-port API/configuration. The maintainer authorized this removal on the dedicated development branch on 2026-11-06, superseding the earlier decision to wait for a compatible final Angus baseline.

Retain foreground-only connection setup for authenticated destinations; removal does not enable pre-send pool allocation during Mailer construction.

Require the selected Angus provider's actual support for the transport hook before connecting. Capability inspection must not connect, resolve names, start networking workers or invoke application factories. Reject an unsupported authenticated-proxy combination with a useful error; no older-Angus bridge or anonymous bypass is retained. An authentication, setup or TLS failure follows the existing failure policy without changing the selected proxy. Anonymous SOCKS and HTTP proxy precedence remain unchanged.

On the direct path, create and capture the unconnected physical socket before proxy DNS, TCP connection or SOCKS negotiation. The socket facade performs method selection, username/password authentication and CONNECT with one remaining setup budget; zero retains unlimited setup. Restore the ordinary socket read timeout before TLS and SMTP. Retain the original SMTP endpoint and address kind, destination permission checks, options, binding behavior and passive metadata. Closing the facade disposes the same physical socket, with idempotent ownership and first/suppressed cleanup failures. Control writes do not acquire a separate timer.

Preserve the application's transport, ordinary socket and TLS factory choices, including supported instance and class forms. Use the independent transport hook for physical creation; leave Angus's TLS selection, trust precedence, original SNI/hostname and custom trust callbacks intact. Do not use deferred SSL facades to evade native write-timeout wrapping, replace TLS with defaults or change connection properties around a connect call. No new networking dependency or JVM-global Authenticator/factory state is introduced.

The Angus lifecycle adapter exposes this behavior through a core-internal configuration capability. An explicitly selected authenticated proxy permits that narrow decoration on the same caller-supplied Angus Session. It does not permit replacing its Session or provider. Other mail providers remain outside the agreed direct integration; CustomMailer owns its route. Connectivity through a supplied Session or opaque factory does not itself create cancellation or total-deadline support. Owned Sessions preserve tracked physical handles and the existing exclusive-lease fencing.

Resolve typed/raw declarations and locks under [ADR 0038](0038-connection-configuration-conflicts.md). Preserve original delegate identity when a managed decorator is compared with an application factory lock. Effective Session inspection and connection locks describe the actual remote proxy endpoint. A dedicated probe receives fresh factory and socket state and does not inherit a send's cancellation ownership.

Remove bridge-only Java builders/getters/constants and CLI options. Reject obsolete `simplejavamail.proxy.socks5bridge.port` inputs with guidance to remove the setting, including zero, locked, named-mailer, JVM/environment and relaxed Spring forms. Preserve remote endpoint/credential copying and serialization. Update Spring metadata, generated CLI caches and migration guidance as part of the same removal.

Preserve an explicit SOCKS username/password rejection as authentication evidence on that exact direct connection. Apply the existing
authentication-failure permission from [ADR 0029](0029-smtp-submission-failover.md), including when provider/pool wrappers or cached failures
carry the evidence. Malformed replies do not acquire that permission, certificate permission remains independent, and failed cleanup still
prevents another destination from starting. Do not infer authentication rejection from an exception message or shared bridge failure state.

Use a pinned, timestamped Angus snapshot from its public Maven repository as the dedicated branch's development dependency, with its matching Mail/Activation API choices. Normal branch builds therefore exercise the direct implementation. Do not present these prerelease inputs as a production release. Local Angus source builds and private provenance markers are not required. Selecting a compatible final Angus dependency remains the release-dependent checkpoint under #922; it no longer postpones source-code retirement of the bridge.

## Consequences

Direct connections need no local listener, second SOCKS exchange or forwarding worker. Supported cancellation and deadlines can reach the captured upstream connection directly. Pooled and retained transports keep ordinary physical ownership; stale stop requests cannot affect later borrowers.

The proposed connection-to-tunnel association in ADR 0017 is superseded by ownership of the upstream socket itself. Historical listener, setup-admission and forwarding rules remain recorded in ADR 0010; none of those actors remain in the current connection path.

The optional module now has one authenticated connection path. Applications must remove bridge-port settings and use a capable Angus provider. Local physical disposal, the remote peer's later EOF observation and SMTP acceptance remain different events. The separate logging-only/empty-batch work in #752 retains its accepted post-launch deferral.

The independent transport creator requires a plain unconnected socket. Legacy factory class settings can require a separate lazy lookup for physical creation while native Angus retains TLS selection. A getter that returns a different factory on each call cannot provide a precise single-result association between those roles; an explicit transport factory makes that choice unambiguous. Module-path factory access must also be granted to the component resolving the class.
