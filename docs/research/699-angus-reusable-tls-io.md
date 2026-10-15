# Reusable TLS I/O for Angus

Local investigation, 2026-10-15. This accompanies the separate
[SSLEngine experiment](699-angus-sslengine-prototype.md). Nothing in this survey
was added to Angus or SJM's dependencies. The candidates below were inspected,
not run against the SMTP reproducer.

## Recommendation

There are existing implementations worth evaluating before maintaining a new
general-purpose TLS adapter. The most focused candidate is **TLS Channel**;
**Netty SslHandler** is the stronger framework reference. Both can retain the
JDK's SSLEngine rather than introducing another cryptographic implementation.

Neither should be described to maintainers as a proven fix for our reproducer.
Using SSLEngine is only part of the answer: the surrounding scheduler must still
read while encrypted output remains queued. TLS Channel's source makes that
qualification particularly relevant.

| Candidate | What could be reused | Cost or constraint | Assessment |
| --- | --- | --- | --- |
| TLS Channel | A small ByteChannel adapter around a caller-configured SSLEngine, with blocking/nonblocking and asynchronous APIs | One library, no declared runtime dependencies in 1.0.0; handshake scheduling still needs our regression case | First focused dependency to evaluate |
| Netty SslHandler | TLS record processing, pending writes, delegated tasks, STARTTLS and shutdown on an event loop | Several Netty modules and channel/buffer lifecycle integration | Best broad reference or dependency-backed alternative |
| Apache MINA SslFilter | SSLEngine integrated with an asynchronous session/filter framework | MINA sessions, filters, buffers, and SLF4J; not a standalone socket adapter | Viable framework alternative, not the smallest addition |
| Eclipse Jetty SslConnection | Nonblocking encrypted and decrypted endpoint handling | Jetty endpoint/callback/buffer infrastructure; current Jetty 12 requires Java 17 | Useful Eclipse-owned reference, poor direct fit for Angus's Java 11 target |
| Apache HttpCore SSLIOSession | TLS layered over a reactor I/O session | HttpCore's reactor/session contracts rather than Angus sockets | Additional reference for separating TLS work and readiness |

## TLS Channel

Repository: [marianobarrios/tls-channel](https://github.com/marianobarrios/tls-channel).
The latest GitHub release returned by the API was
[1.0.0, published 2026-03-28](https://github.com/marianobarrios/tls-channel/releases/tag/1.0.0).
The published [Maven POM](https://repo.maven.apache.org/maven2/com/github/marianobarrios/tls-channel/1.0.0/tls-channel-1.0.0.pom)
declares no dependencies. Current source targets Java 8 and uses JDK logging;
its logging bridge and JUnit dependencies are test-only. The license is MIT.

Inspected checkout: `8e36cbc4da92200ba950a85704ce011d73905fc4`, retained locally
under `.local/angus-pipelining/references/tls-channel`. The latest commit was a
Gradle update on 2026-10-14. This establishes current repository activity, not
evidence about response times for TLS defects.

The API supports an explicitly configured engine. We would still configure
hostname verification, trust, protocols and client credentials ourselves.
Nonblocking operations expose separate NeedsRead, NeedsWrite and NeedsTask
conditions. Its buffer allocation/release and plaintext clearing are useful
existing work. See the [project description](https://github.com/marianobarrios/tls-channel/blob/8e36cbc4da92200ba950a85704ce011d73905fc4/README.md)
and [build](https://github.com/marianobarrios/tls-channel/blob/8e36cbc4da92200ba950a85704ce011d73905fc4/build.gradle).

The important open question is scheduling. In
[TlsChannelImpl](https://github.com/marianobarrios/tls-channel/blob/8e36cbc4da92200ba950a85704ce011d73905fc4/src/main/java/tlschannel/impl/TlsChannelImpl.java),
`read()` can enter `writeAndHandshake()` for NEED_WRAP/NEED_UNWRAP. That method
acquires both locks and flushes pending encrypted output before doing more
handshake work. In nonblocking mode, a blocked flush throws NeedsWriteException.
This is a concrete reason to test pending application output plus KeyUpdate;
the README's general full-duplex claim alone does not settle that case.
It is not a demonstrated library bug or a claim that every use stalls.

The inspected interoperability fixture defaults to TLS 1.2, and its full-duplex
test explicitly runs without renegotiation. Searches of `src/test` found no
KeyUpdate/keyLimits test. This is a coverage observation about this checkout,
not proof that TLS 1.3 is unsupported. Sources:
[SslContextFactory](https://github.com/marianobarrios/tls-channel/blob/8e36cbc4da92200ba950a85704ce011d73905fc4/src/test/java/tlschannel/helpers/SslContextFactory.java),
[InteroperabilityTest](https://github.com/marianobarrios/tls-channel/blob/8e36cbc4da92200ba950a85704ce011d73905fc4/src/test/java/tlschannel/InteroperabilityTest.java).

## Netty

[SslHandler](https://netty.io/4.1/api/io/netty/handler/ssl/SslHandler.html) accepts
an SSLEngine and manages TLS on a channel pipeline. It supports STARTTLS,
delegated task execution and close-notify handling. Using the supplied JDK engine
does not require Netty's optional native TLS provider.

The inspected [implementation](https://github.com/netty/netty/blob/5bfab2c69cff9b9a58734a3437d11d1509862095/handler/src/main/java/io/netty/handler/ssl/SslHandler.java)
handles NEED_WRAP during unwrap by producing control output through the channel,
then continuing inbound processing. This is a useful reference for the specific
separation our experiment exercises. It is a substantial implementation tied to
Netty buffers, futures and event-loop state, not a helper class to transplant.

The [handler POM](https://github.com/netty/netty/blob/5bfab2c69cff9b9a58734a3437d11d1509862095/handler/pom.xml)
depends on multiple Netty modules. NioSmtpClient already takes this route;
using Netty directly would avoid its SMTP layer but retain the networking
framework dependency. The
[4.2 migration guide](https://github.com/netty/netty/wiki/Netty-4.2-Migration-Guide)
sets the minimum Java version to 8, compatible with Angus's Java 11 target.
Netty's source uses Apache-2.0 licensing.

## Other references

MINA's [TLS internals](https://mina.apache.org/mina-project/technical-documentation/ssl-tls-internal.html)
describe separate engine handling and queued messages in an I/O filter.
The inspected 2.2.X revision was
`010abe2f643a6d2032c7ab99d3758ea7c9237423`. Its
[build](https://github.com/apache/mina/blob/010abe2f643a6d2032c7ab99d3758ea7c9237423/pom.xml)
targets Java 8 for main code and includes SLF4J. This is another way to reuse an
existing framework, with integration work comparable in kind to Netty rather
than a tiny TLS-only dependency. It uses Apache-2.0 licensing.

Jetty's
[SslConnection](https://github.com/jetty/jetty.project/blob/3f9a6f93aa12c2d5e5a9e56901ef41caf91f5a6a/jetty-core/jetty-io/src/main/java/org/eclipse/jetty/io/ssl/SslConnection.java)
documents nonblocking fill/flush operations separately from callbacks that
resume them. Its encrypted/decrypted endpoints are a particularly useful
integration model. The source is EPL-2.0 OR Apache-2.0, but it relies on Jetty's
I/O and utility infrastructure. The current
[supported Jetty line requires Java 17](https://jetty.org/download.html);
choosing an old Java-11 line just to obtain this class would be unattractive.

HttpCore's
[SSLIOSession](https://github.com/apache/httpcomponents-core/blob/2d7160d4da1bc8ff5e471e1675427d0b3a0360b0/httpcore5/src/main/java/org/apache/hc/core5/reactor/ssl/SSLIOSession.java)
is an Apache-2.0 reactor-session decorator. Its handling of NEED_WRAP and read/write
interest is another source to compare when reviewing a smaller implementation.
It does not provide a drop-in replacement for Angus's custom socket factories.

## Dependency versus borrowed implementation

Adopting a library gives us its ongoing fixes but adds its APIs, versions and
resource lifecycle. Importing selected source avoids a runtime dependency while
making Angus responsible for maintaining that code and tracking upstream fixes.
It also retains the source's attribution and license obligations. Neither route
removes the need to test the scheduling behavior we actually use.

Replacing the cryptographic provider is a different experiment.
[Conscrypt](https://github.com/google/conscrypt), for example, supplies JSSE using
BoringSSL. That changes more than the I/O wrapper and does not by itself establish
that a blocking stream can drain replies under our pressure conditions. It is
not the first direction I would choose for this problem.

For the maintainer discussion, present the proven local engine result and these
reuse choices separately. If a dependency is acceptable, put TLS Channel and a
minimal Netty/JDK-engine client through this exact fixture before selecting one.
If it is not, use the existing implementations as design and test references,
then agree on a deliberately limited Angus adapter. No external implementation
has been copied into the experiment, and no library has been endorsed as passing
the reproducer.
