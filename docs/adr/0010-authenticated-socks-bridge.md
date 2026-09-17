# ADR 0010: Adapt authenticated SOCKS through a Mailer-owned loopback bridge

- Status: Accepted (retrospective record of implemented design)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Authenticated SOCKS sending, including automatic bridge ports in unreleased 10.0.0
- Implementation status: Implemented; anonymous remote SOCKS continues to use the direct provider path

## Context

JavaMail's SOCKS integration did not provide the authenticated proxy connection the library needed. [#38](https://github.com/bbottema/simple-java-mail/issues/38) describes the architectural workaround: let JavaMail connect anonymously to a local SOCKS listener, then relay through a client that can authenticate to the real proxy. It also explicitly rejects ballooning the library's dependencies for the proxy feature. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/38#issuecomment-225968895) records adapting a minimal part of sockslib for authenticated SOCKS; its broader initial HTTP ambition is not the implemented bridge contract.

Fixed local ports introduced a second problem: independent authenticated-proxy Mailers could collide even though their SMTP/proxy configurations were otherwise independent. The listener's lifetime also differs from accepted sockets that a pooled transport may continue using.

## Decision

Keep authenticated proxy support in its optional module. Configure the selected Session's SOCKS settings to connect to a loopback-only anonymous bridge, and let that bridge authenticate to the configured remote SOCKS proxy. Bind to the JVM loopback address; this listener is an internal adapter, not an externally reachable proxy service.

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

[MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java) configures routing, [AbstractProxyServerSyncingClosure](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/AbstractProxyServerSyncingClosure.java) coordinates request lifetime and port publication, and [AnonymousSocks5ServerImpl](../../modules/authenticated-socks-module/src/main/java/org/simplejavamail/internal/authenticatedsockssupport/socks5server/AnonymousSocks5ServerImpl.java) owns binding and graceful listener shutdown. [Proxy closure tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/AbstractProxyServerSyncingClosureTest.java) and [SOCKS live tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerSOCKSLiveTest.java) are existing verification anchors. They were inspected, not rerun, for this documentation record.
