# ADR 0010: Adapt authenticated SOCKS through a Mailer-owned loopback bridge

- Status: Accepted (retrospective record of implemented design)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Authenticated SOCKS sending, including automatic bridge ports in unreleased 10.0.0
- Implementation status: Implemented; anonymous remote SOCKS continues to use the direct provider path

## Context

JavaMail's SOCKS integration did not provide the authenticated proxy connection the library needed. [#38](https://github.com/bbottema/simple-java-mail/issues/38) describes the architectural workaround: let JavaMail connect anonymously to a local SOCKS listener, then relay through a client that can authenticate to the real proxy. It also explicitly rejects ballooning the library's dependencies for the proxy feature. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/38#issuecomment-225968895) records adapting a minimal part of sockslib for authenticated SOCKS; its broader initial HTTP ambition is not the implemented bridge contract.

Fixed local ports introduced a second problem: independent authenticated-proxy Mailers could collide even though their SMTP/proxy configurations were otherwise independent. The listener's lifetime also differs from accepted sockets that a pooled transport may continue using.

## Decision

Keep authenticated proxy support in its optional module. Configure the selected Session's SOCKS settings to connect to a loopback-only anonymous bridge, and let that bridge authenticate to the configured remote SOCKS proxy. Bind the generated local hop to IPv4 loopback `127.0.0.1`, as clarified by the 29 October protocol-boundary decision below; this listener is an internal adapter, not an externally reachable proxy service. Destination addresses and connections to the remote proxy can still use IPv6.

Each Mailer owns its bridge and tracks in-flight SMTP requests. Synchronize bridge start/stop, start the listener before the transport connection, and stop accepting when the last request finishes. Stopping the listener must allow already accepted bridge sockets to continue carrying pooled SMTP connections until those transports close.

In the current implementation, `MailerImpl` supplies the `AtomicInteger` request counter and start/stop synchronizes on the proxy server instance. `AuthenticatedSocksHelper` assembles `AnonymousSocks5ServerImpl` with an `AuthenticatingSocks5Bridge`; a fixed thread pool serves accepted SOCKS sessions, each piping the local and remote sockets until forwarding stops.

Use configured bridge port `0` by default so the operating system selects an available port at each start. Read the bound port and publish it to the effective Session before connecting. Positive configured ports remain an explicit compatibility option. A later listener restart may choose another port; refresh the Session again. The public configured-port getter continues to report the requested value, not a changing runtime allocation.

## Rationale and evidence

- [d61baa63](https://github.com/bbottema/simple-java-mail/commit/d61baa63f4d90e2c776ef1f327f1d49b49657776) completed anonymous and authenticated SOCKS support following #38's bridge design. [ADR 0006](0006-optional-modules.md) records the later optional-module organization.
- [4d554280](https://github.com/bbottema/simple-java-mail/commit/4d5542800b75821d2aca3a575b0cb1608524b864) explicitly changed binding to loopback and aligned Session routing with that address. Keeping the listener local follows its internal-adapter role; the commit and code establish the scope, without claiming it authenticates other local processes.
- [PR #563](https://github.com/bbottema/simple-java-mail/pull/563) introduced `getLocalPort()` to expose an OS-allocated port. Its initial rationale was reuse of the server in other projects; it did not by itself change the Mailer default.
- [38b119d9](https://github.com/bbottema/simple-java-mail/commit/38b119d9633434256e403900f5ee209e778a4ced) changes the default from `1081` to `0`. Its migration text explicitly explains independent Mailer port collisions and updating the Session after a restart. The same change's server shutdown path preserves accepted sockets because pooled SMTP transports still use them.

The counter and synchronized start/stop make request lifetime the listener's ownership boundary. That explanation is derived from the implementation and its concurrency tests; the original issue mainly explains why the bridge exists, not every later synchronization choice.

## Alternatives

**Use JavaMail's anonymous SOCKS support directly** remains the right path for an unauthenticated remote proxy, but did not meet #38's authenticated requirement. **Import a large general-purpose proxy stack** was explicitly disfavored there. **One fixed default bridge port** was the previous design and is superseded for 10.0.0 by automatic allocation. **Close all accepted sockets when stopping the listener** would break pooled transport reuse, as documented beside the current graceful shutdown.

## Consequences

Callers retain the normal Mailer, pooling, and proxy APIs without implementing another transport. The adaptation costs a local listener, socket forwarding, and threads. Loopback limits network exposure but the local hop remains anonymous; it is not a per-process access-control boundary.

Automatic allocation removes routine port coordination between Mailers. An explicitly fixed port can still fail to bind, which must surface before an SMTP attempt uses an unusable bridge. Runtime diagnostics must distinguish configured port `0` from the actual selected Session port: inspect `mail.smtp.socks.port` or `mail.smtps.socks.port` on the effective Session for the current allocation. Listener shutdown and pooled transport shutdown must remain separate responsibilities.

## Implementation anchors

[MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java) configures routing, [AbstractProxyServerSyncingClosure](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/AbstractProxyServerSyncingClosure.java) coordinates request lifetime and port publication, and [AnonymousSocks5ServerImpl](../../modules/authenticated-socks-module/src/main/java/org/simplejavamail/internal/authenticatedsockssupport/socks5server/AnonymousSocks5ServerImpl.java) owns binding and graceful listener shutdown. [Proxy closure tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/AbstractProxyServerSyncingClosureTest.java) and [SOCKS integration tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerSocksIntegrationTest.java) are existing verification anchors. They were inspected, not rerun, for this documentation record.

## Connection conflict policy (accepted 24 October 2026)

ADR 0038 resolves compatible raw SOCKS endpoints and SJM credentials into one remote proxy configuration before deriving bridge routing. This extends previous SJM-only endpoint inheritance. Compare user declarations before creating generated loopback routing; retain the listener, socket, optional-module and allocated-port lifecycle described here. See [ADR 0038](0038-connection-configuration-conflicts.md), accepted under [#867](https://github.com/bbottema/simple-java-mail/issues/867). Implementation is tracked separately from design acceptance.

## Authentication logging and reply boundaries (accepted 29 October 2026)

Keep username/password authentication packet contents out of logging, including TRACE output. The bridge sends the supplied credentials to the remote proxy, but diagnostics may describe only the authentication phase and non-sensitive response facts. Hexadecimal encoding does not make a credential safe to publish. This correction is tracked under [#901](https://github.com/bbottema/simple-java-mail/issues/901).

Treat the two-byte username/password authentication response as one complete protocol frame, independently of socket read boundaries. Require subnegotiation version 1 and the actual success status before issuing CONNECT; reject malformed versions, authentication rejection and EOF before either byte. Consume no bytes from the following exchange. Close the current socket after a rejected, malformed or truncated authentication response, retaining the original failure and suppressing any secondary close failure. This applies the bridge's existing protocol responsibility without changing public configuration or listener/socket ownership; [#902](https://github.com/bbottema/simple-java-mail/issues/902) tracks the correction.

## Established tunnels have no independent timeout (accepted 29 October 2026)

Keep the local socket's ten-second read guard while handling the anonymous SOCKS handshake. After a successful CONNECT, remove that guard before starting transparent forwarding. An established bridge tunnel has no independent read or idle timeout: Angus enforces the application's configured SMTP client timeout from its end of the connection.

This preserves the separation between listener and established-socket lifetimes, including retained and pooled connections. It adds no public configuration and does not change pending remote-proxy setup ownership. [#907](https://github.com/bbottema/simple-java-mail/issues/907) tracks this correction.

## Pending setup belongs to one tunnel (accepted 29 October 2026)

Own each accepted local socket and its raw remote proxy socket through `PENDING`, `FORWARDING` and `CLOSED`. Publish the remote handle before constructing the remote proxy address or starting its TCP connect, method selection, authentication or CONNECT. A new handle first published after closure is rejected and closed; an already owned handle remains with its single closer. Close both peers idempotently, preserve the first non-null operational failure even when peer EOF retired the sockets first, and suppress secondary cleanup failures. Failure writing the local success response also retires the remote handle.

After parsing the local SOCKS command, one local-input reader observes peer EOF while the handler performs remote setup. That same reader continues into forwarding, without a competing socket reader or a port-based lookup. Buffer at most 8 KiB of premature application bytes; overflow fails that tunnel instead of blocking the only EOF observer or growing without a bound. Write the local CONNECT success before switching to `FORWARDING`, then flush the buffered bytes once, in order, before later client bytes. Network reads, writes and socket closure stay outside the lifecycle monitor. A separate output-order lock cannot prevent closure.

The SMTP client's existing socket-connect budget remains authoritative across the entire SOCKS negotiation. Its failed-connect cleanup or explicit physical abort closes the client-to-loopback peer, which closes that tunnel's exact pending remote handle. A zero connect timeout remains unlimited even when an ordinary SMTP read timeout is configured. Do not add another bridge timer, renew a budget per phase, or apply the ordinary SMTP read timeout to proxy setup. Native DNS resolution remains subject to the non-cooperative-code boundary in ADR 0017; socket ownership does not promise to interrupt the resolver itself.

This refines the local guard timing described above: the ten-second parsing guard ends once the local request is parsed, before pending remote setup begins. Pending peer observation and established forwarding have no independent read or idle timer. Both forwarding sockets have read timeout zero; the SMTP client still enforces its own configured read settings.

Each listener generation registers accepted owners before worker submission. Ordinary listener retirement closes its pending owners while preserving established pooled or retained tunnels. Restart uses a separate registry; an old tunnel cannot close a newer generation. An unexpected listener failure closes its generation's owned sockets before interrupting its workers. The handler worker reads remote-to-local traffic and one client reader carries the opposite direction; the old polling handler and additional pair of forwarding threads are no longer on the bridge path.

This is an internal lifetime correction under [#909](https://github.com/bbottema/simple-java-mail/issues/909). It introduces no public timeout option, Session-global cancellation authority, new configuration interpretation, or automatic abort support for otherwise unsupported providers. IPv6, hostname resolution policy, CONNECT response validation and credential length handling remain separate decisions.

## SOCKS destination and frame boundaries (accepted 29 October 2026)

Preserve the address kind supplied by the local SOCKS client through the internal bridge. Numeric IPv4 and IPv6 requests retain their address bytes; a domain-form request retains its hostname for resolution by the remote proxy. Do not resolve that domain again in the bridge or require an `InetAddress` before dispatching it. The provider/JDK's initial destination lookup remains unchanged, so this restores forwarding of unresolved names without introducing an always-remote-DNS policy. Keep the original SMTP hostname for TLS identity and existing configuration decisions. [#911](https://github.com/bbottema/simple-java-mail/issues/911) tracks this correction.

Recognize address type 4 and serialize complete four- or sixteen-octet addresses with their matching type and two-byte port. This applies independently to the requested SMTP destination and the bound address returned after connecting to the remote proxy. The generated local listener and Session route both use numeric IPv4 loopback `127.0.0.1`: Angus 2.0.5 interprets a colon in its SOCKS-host property as an inline port separator, so a JVM-selected IPv6 literal cannot safely describe that internal hop. This clarifies the earlier JVM-loopback wording while retaining loopback-only binding, automatic/fixed bridge ports and IPv6 for destination and remote-proxy connections. It adds no public address-family setting. [#910](https://github.com/bbottema/simple-java-mail/issues/910) tracks this correction.

Treat a CONNECT reply as a complete protocol frame before entering forwarding. Require version 5, reserved byte zero, a recognized address type and every mandatory address, length and port byte. Reject premature EOF, preserve the proxy's failure status and consume no following application data. This applies the authentication framing principle to the next protocol exchange without making any SMTP-acceptance claim. [#912](https://github.com/bbottema/simple-java-mail/issues/912) tracks this correction.

Effective authenticated SOCKS usernames and passwords each require 1 through 255 UTF-8 octets under RFC 1929. Reject unframeable effective credentials at Mailer construction before connection effects; supplied-choice builders and getters remain passive under [ADR 0031](0031-public-api-and-internal-construction.md). Retain the same defensive bound before serializing credential bytes. Keep the existing UTF-8 encoding and original valid credentials. Length-validation diagnostics identify the member and permitted bound without echoing its value; authentication packet contents remain excluded from logging under the earlier decision. [ADR 0038](0038-connection-configuration-conflicts.md) continues to own source composition, conflicts and locks. [#913](https://github.com/bbottema/simple-java-mail/issues/913) tracks this correction.

These protocol corrections retain the pending setup owner, setup budgets, cancellation and established-tunnel handoff under #909. Listener shutdown remains separate from established pooled/retained tunnel lifetime. They do not add BIND, UDP, proxy chaining or a general-purpose SOCKS service. The issues track implementation and delivery for unreleased 10.0.0.
