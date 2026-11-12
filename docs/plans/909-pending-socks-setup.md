Delivered on codex/10.0.0 at 101cfbb4ab460979dfa9d8500c43039e5229ecb7 and codex/10.0.0-content at e93b4f4b940f6278a7cd5e7e7bd108661ec63baa. Issues #908 and #909 are closed/completed and Done. Only guarded scratch/plan cleanup remains pending at the existing older node_modules junction; remaining review decisions are findings 8 and 13.

# Own pending SOCKS setup through forwarding

The maintainer approved design, execution and complete review-workflow delivery for
[#909](https://github.com/bbottema/simple-java-mail/issues/909) on 29 October 2026.
[ADR 0010](../adr/0010-authenticated-socks-bridge.md) owns the bridge boundary;
[ADR 0017](../adr/0017-deadlines-and-physical-cancellation.md) owns physical abort.

## Concrete change and preserved behavior

Before: cancelling or timing out `mailer.async().sendMail(email)` can close the
client-to-loopback socket while its remote proxy setup socket remains blocked.
After: every accepted local connection owns its exact raw remote socket before
connect/authentication. Its `PENDING -> FORWARDING -> CLOSED` lifecycle propagates
local EOF to that socket, permits closure from either live state, and rejects/closes a
new socket published after cancellation while preserving an already owned single closer.
One local-input reader spans pending setup and forwarding;
bounded early bytes retain order. Secondary close failures cannot replace the cause.

The existing SMTP client owns its one connect budget across the complete SOCKS
negotiation. Missing or zero remains unlimited; the ordinary SMTP read timeout does
not become a remote setup deadline. Client timeout/abort closes its local peer and
the owner closes the exact pending remote handle. There is no second bridge timer
or Session timeout plumbing. Local command parsing retains its handshake guard;
pending peer observation and established forwarding have no independent idle
deadline. Established local and remote sockets use timeout zero, retaining #907.
Normal listener shutdown closes only pending owners from its own generation and leaves
established pooled/retained tunnels usable. No public configuration or source migration.

## Paths and ownership

| Path | Change owner and effect |
| --- | --- |
| `core-module/.../Socks5Bridge.java` | Lead worker: internal early socket-ownership callback; no application API or timeout interpretation. |
| `authenticated-socks-module/.../AuthenticatingSocks5Bridge.java` | Lead worker: publish raw handle before connect/auth; existing client peer supplies timeout/abort authority. |
| `authenticated-socks-module/.../socks5server/SocksTunnel.java`, `Socks5Handler.java`, `AnonymousSocks5ServerImpl.java`, `SocksSession.java` | Lead worker: per-tunnel transition/cleanup, single local reader, transparent handoff and listener-generation ownership. Preserve parser behavior. |
| `AuthenticatedSocksModule`, `AuthenticatedSocksHelper`, `MailerImpl`, `SmtpProxyScope` | Unchanged: existing selected Session/provider timeout and physical client-abort path stays authoritative. |
| Existing internal bridge factory/parser tests | Lead worker: adapt only changed internal call shapes and retain controls. |
| New `AuthenticatedSocksPendingSetupTest` and its fixture | Integration subagent: actual Mailer abort/deadline/setup-budget/concurrent connection regressions. |
| New auth-module lifecycle tests | Owner-test subagent: early/late ownership, races, bounded early bytes, failure cleanup and listener stop/restart. |
| ADR 0010/0017 and relevant concurrency guidance | Lead worker: record the accepted owner/handoff/cancellation meaning. |
| Release history, ADR index, website, OpenRewrite and cleanup script | Root coordinator: reconcile shared surfaces and exact disposable paths. |

Composition owns MailerHelper/override-recipient validation. Preserve its edits and all
unrelated research, website and index state. IPv6, DNS, CONNECT frame validation and
credential length findings remain separate and unimplemented.

## Scenario and verification order

| Scenario | Required observable result |
| --- | --- |
| Remote TCP accepted; silent method/auth/CONNECT response | Real cancellation or overall deadline closes the exact remote peer, including unlimited setup timeouts. |
| Finite client connect budget, read timeout zero | Its expiry closes the pending remote peer; no renewed per-phase allowance. |
| Client connect zero, ordinary read timeout 300 ms | Proxy setup remains pending until client cancellation; no hidden read-timeout authority. |
| Peer EOF before or during raw socket publication | Current/new late handle closes once; owned TCP/authentication/CONNECT waits unblock. Native DNS remains outside a hard interruption promise. |
| EOF or write failure during local CONNECT success | Never publish a usable forwarding tunnel; retain failure and close both peers. |
| Concurrent pending failure and healthy tunnel | Closure reaches only the matching owner; healthy pooled reuse survives. |
| Pending bytes before setup completes | Bounded buffering or explicit rejection, no second reader or reordered SMTP bytes. |
| Listener stop/restart across handoff | Close old-generation pending sessions, preserve established tunnels and newer generation owners. |
| Slow established SMTP reply and configured short client timeout | Keep #907's transparent forwarding and client timeout behavior. |

Start with meaningful failing actual-Main/Mailer regressions on current source, then
implement and run focused owner/race cases. Exercise neighboring SOCKS/auth/proxy and
pooling/cancellation controls. Use real Java 11 and a modern JDK with current reactor
dependencies. All build targets live below the exact task-owned
`.local/feature-review-pending-socks-20261029` (separate lead/integration snapshots).
No installs into a shared local repository, shared target execution, commits or pushes
before combined lead review. Report evidence directly to the maintainer; external
issues and commit text contain behavior, usage and accepted decisions only.

Public API/Javadocs, property keys/defaults, Spring metadata, CLI names/caches, copying
and serialization remain unchanged. Root owns the documentation matrix reconciliation
and exact cleanup allowlist. Retire this active plan after accepted delivery.
