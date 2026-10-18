# Angus prototype parity and SSLEngine integration

Local implementation follow-up, 2026-10-15. This supersedes the test-only scope of
the [standalone engine experiment](699-angus-sslengine-prototype.md), not its
historical measurements. See the [prototype plan](../plans/699-angus-pipelining-prototype.md)
and [contribution guide](699-angus-contribution-conventions.md).

Update, 2026-10-18: #218 is merged and both alternatives now use
`a1d842dd46debe84fb05f7343151974ba78ca5b4` as their base. See the
[upstream refresh report](699-angus-upstream-refresh.md) for the new test runs,
patch exports and review state. Measurements below describe the earlier parity
round unless explicitly updated.

## Request and implementation

The user requested two independently usable alternatives for the same feature,
not one feature PR and one transport experiment. The user separately approved
an engine hook with sequential fallback for existing custom TLS factories.

At the time of this parity round, both Angus worktrees started at upstream
`a7bd3fed964d9c9bc2981d4b2c9867358453c571`:

| Worktree | Branch | Implementation |
| --- | --- | --- |
| `C:\workstation\angus-mail-pipelining-prototype` | `213-pipelining-prototype` | Existing streams and one envelope reply reader |
| `C:\workstation\angus-mail-sslengine-prototype` | `213-sslengine-prototype` | Nonblocking channel and SSLEngine, integrated into SMTPTransport |

The engine branch now contains the complete envelope implementation from the
reader branch: properties, command construction, final-command validation,
recipient classification, exception/event handling, subclass hooks and
capability invalidation. It does not invoke the independent test client's SMTP
implementation. SMTPTransport differs between the alternatives only at the
I/O selection, envelope collector, TLS-state reporting and associated comments.

`SMTPPipeliningTest`, `SMTPPipeliningTlsTest` and the `TestServer` changes are
identical between branches, after normalizing line endings. There is no
cross-worktree production dependency or new Maven dependency. The optional
reader-comparison test arm still accepts an external compiled provider, but
neither branch needs that arm to build or test its own implementation.

## Shared feature contract

| Requirement | Implementation and witness |
| --- | --- |
| Explicit opt-in, default off | `mail.smtp.pipelining` and `mail.smtps.pipelining`, read when the Transport is constructed; switch/default/fallback tests |
| Fresh capability permission | EHLO, reconnect, HELO fallback and post-STARTTLS tests; the shared stale-cache prerequisite remains separate upstream work |
| Envelope only | MAIL/RCPT groups of 32 commands or 32 KiB; oversized commands alone; gated replies prove the boundary before DATA/BDAT |
| Original submission contract | Shared recipient state, `sendpartial`, `reportsuccess`, null/empty address distinctions, exception chains and listener assertions |
| Subclass compatibility | Same `sendEnvelope()` and `isPipeliningCompatible()` hooks; old subclasses sequential; callbacks on the caller holding the transport monitor |
| Failure and reuse | Sender rejection remains primary, drain before RSET, invalidate on terminal failure, no replay, healthy reuse and interruption tests |
| Content and security | Exact message-content comparison, DATA/BDAT, command injection checks, hostname-verifier rejection and TLS configuration tests |

The shared test class has 22 methods, including the opt-in latency comparison.
Normal runs execute 21 and skip only that benchmark. A separate property,
`angus.pipelining.contract.starttls=true`, runs its ordinary envelope cases over
STARTTLS. The explicit SMTPS cases always run.

## Engine-specific connection policy

`SocketFetcher` has an opt-in overload. Existing callers and disabled pipelining
retain the original socket path. `DuplexSocket` owns the channel, selector and
buffers. One caller drives reads, writes and the engine. Its envelope input
handler drains through Angus's existing tracing streams without invoking
transport hooks; complete replies are later classified by SMTPTransport.
Delegated TLS tasks use a connection-owned worker, not another SMTP reader or
connection pool. Closing and failure paths release the channel/selector and
stop the worker; interruption is preserved.

The optional `mail.smtp.ssl.engineFactory` / `mail.smtps.ssl.engineFactory`
property accepts a `BiFunction<String, Integer, SSLEngine>` instance via
`Properties.put`. It receives the actual destination host/port and must return
a fresh engine for that peer, backed by the caller's required key/trust managers.
A matching `SSLContext::createSSLEngine` method reference is sufficient.
Angus then applies client mode, protocol and cipher configuration, the existing
LDAPS endpoint-identification policy, and any configured hostname verifier.
An invalid or failing hook fails connection setup, not a retry with different
trust. The hook explicitly supplies TLS context instead of `ssl.socketFactory`
or `ssl.trust`; it does not replace the raw socket/proxy factory.

Default JSSE and the exact stock MailSSLSocketFactory work without a hook. Its
key/trust managers and trusted-host restriction are retained. Arbitrary TLS
socket factories cannot be converted back to their SSLContext; without a hook
they stay on the original sequential implementation. Non-channel sockets,
including SOCKS and caller-supplied sockets, also remain sequential. HTTP proxy
setup still uses SocketFetcher's existing CONNECT code; it was inspected but
not newly exercised through a real proxy in this round.

Thus parity means the same SMTP feature and delivery contract, not identical
connection eligibility: the engine implementation has an explicit conservative
fallback for configurations it cannot safely adapt. There is no fallback or
automatic reconnect after a transaction stalls or fails.

## Verification

Logs below are under `C:\workstation\simple-java-mail\.local\angus-pipelining`.
They are local evidence, not files in either Angus patch. Package builds use the
aligned private Maven cache, without installing over the cached reader provider
or changing SJM's production dependency pin.

| Check | Result |
| --- | --- |
| Reader final shared STARTTLS contract + reader unit tests, JDK 11/17/21 | 28 methods per JDK, zero failures/errors, one optional benchmark skipped; `reader-final-contract-jdk*.log` |
| Engine shared contract and factory tests, JDK 11 | 28 methods, zero failures/errors, one optional benchmark skipped; `engine-final-contract-jdk11.log` |
| Engine integrated pressure matrix, JDK 11/17/21 | Six successful connection profiles per JDK, two messages each; ten expected negative outcomes per JDK. Initial integration logs: `engine-integrated-pressure-jdk11.log`, `engine-parity-starttls-pressure-jdk17.log`, `engine-final-contract-pressure-jdk21.log` |
| Broader SMTP regression selection, JDK 11 | 53 methods, zero failures/errors, six optional stress tests skipped; `engine-smtp-and-benchmark-jdk11.log`. Explicitly excluded the known baseline `SMTPIOExceptionTest` and ran the process-isolated engine matrix separately |
| Explicit SpotBugs, High threshold | Baseline 205 classes, engine 214 classes in the XML summaries, zero findings, errors or missing classes in each XML. `baseline-parity-spotbugs.log`, final clean-class run `engine-clean-spotbugs.log`; this does not prove concurrency safety |
| Copyright comparison | The same 15 pre-existing findings in baseline and engine; none in the new/changed contribution files. Neither run is green |
| Shared fixture/source comparison | Contract test, ordinary TLS test and TestServer match between branches; no dependency/POM changes |
| Clean engine package and generated Javadoc, JDK 11 | 28 contract/policy methods, zero failures/errors, one benchmark skipped; `engine-clean-reactor-javadoc.log`. Generated properties and protected-hook links inspected |

The final frozen-source rerun is recorded separately in
`engine-final-matrix-jdk11.log`, `engine-final-matrix-jdk17.log` and
`engine-final-matrix-jdk21.log`. It runs the 22 shared methods, six engine-policy
methods and sixteen actual-Transport pressure/failure cases on each runtime.
All three runs passed: 44 methods per JDK, zero failures or errors and only the
optional benchmark skipped. Across the runtimes, the pressure arm completed
18 successful connections carrying 36 messages, plus 30 expected negative
outcomes. These are six positive profiles repeated on three JDKs, not eighteen
different profiles.

The integrated positive pressure cases cover STARTTLS and implicit TLS with
small buffers, frequent TLS 1.3 key updates, 32/64 KiB MAIL replies and a large
first RCPT reply. The server checks command order, DSN/envelope parameters,
authentication and exact content. Each successful connection sends twice and
never reconnects. Negative cases check disconnect, silence/read timeout,
unacknowledged content, an engine hook identifying the wrong peer and an
untrusted certificate. No negative case replays a message.

The integrated wrong-peer case is a hook validation test before the handshake,
not an independent certificate-hostname mismatch test. The older standalone
client still supplies the latter experiment. Shared transport tests check that
a hostname verifier can reject STARTTLS/SMTPS; engine-specific tests also
assert the applied LDAPS policy and negotiated TLS 1.2/1.3 protocol/cipher.

The ordinary `SMTPPipeliningTlsTest` custom-factory configurations deliberately
use sequential fallback in the engine branch. They are not counted as proof of
engine pipelining. Gated shared TLS tests and the `transport` pressure arm supply
that proof. The standalone `engine` arm's metrics are not attributed to the
integrated Transport.

The first shared run found that a terminal 421 received in the same input turn
could hide a preceding MAIL rejection. The collector now returns already
completed earlier replies before its terminal failure. The existing shared
sender-rejection test caught the bug and passes after the fix. Both branches
also pass a new test that a fully acknowledged message remains successful when
the server closes immediately, including an abrupt post-acknowledgment TLS close.

### Generated sources and documentation

A standalone combined-provider Javadoc command resolved the cached reader
source JARs and generated the wrong property page. The subsequent engine
reactor build restored its sources, but left the unused reader helper in the
generated directory. A clean reactor `package javadoc:javadoc` removed that
leftover, rebuilt the engine with its own sources and reran its contract/policy
tests successfully. No canonical source was overwritten, and no engine
artifact was installed over the reader cache.

The final engine output contains `DuplexSocket` and `SMTPEnvelopeExchange`, not
`SMTPReplyReader`. The generated package page includes both opt-in properties,
the engine hook, defaults, resource costs and sequential-fallback policy. The
generated Transport page documents both protected hooks, and the package's
`isPipeliningCompatible()` link resolves to it. The reader's separately
regenerated page documents its reader
thread and the same opt-in contract. Existing Javadoc warnings remain; this is
not a warning-free documentation build.

Inspected pages:

- Reader: `providers/smtp/target/site/apidocs/org.eclipse.angus.mail.smtp/org/eclipse/angus/mail/smtp/package-summary.html`
- Engine: `providers/angus-mail/target/site/apidocs/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html`

### Early latency comparison

Reused plain-SMTP connections, two warmups and five measured sends per setting;
medians, not throughput. The effective artificial reply delay is 10-15 ms.

| Recipients | Sequential | Engine pipelining |
| --- | ---: | ---: |
| 1 | 31.389 ms | 15.495 ms |
| 10 | 170.359 ms | 15.217 ms |
| 100 | 1,563.774 ms | 62.460 ms |

Without added delay: 0.819/0.592 ms, 1.439/0.800 ms and 5.783/2.623 ms
(sequential/engine) for 1/10/100 recipients. These small samples do not establish
a general performance advantage for SSLEngine over the reader implementation,
and do not measure TLS throughput. The earlier reader benchmark remains
separately recorded; neither draft mentions whole-suite timing.

## Reproduction

Use JDK 11, 17 or 21. From either Angus worktree, run the shared contract:

```text
mvn -B -pl providers/angus-mail -am -Poss-release,staging,build-only -Dgpg.skip=true -Denforcer.skip -Dtest=SMTPPipeliningTest -Dsurefire.failIfNoSpecifiedTests=false -Dangus.pipelining.contract.starttls=true package
```

From the engine worktree, include its policy and process-isolated tests:

```text
mvn -B -pl providers/angus-mail -am -Poss-release,staging,build-only -Dgpg.skip=true -Denforcer.skip -Dtest=SMTPPipeliningTest,SMTPPipeliningEngineTest,SMTPSSLEngineTest -Dsurefire.failIfNoSpecifiedTests=false -Dangus.pipelining.contract.starttls=true -Dangus.sslengine.prototype=true -Dangus.sslengine.modes=transport package
```

In PowerShell, quote the comma-separated `-P` and `-Dtest` arguments. Locally add
`-Dmaven.repo.local=C:/workstation/simple-java-mail/.local/angus-pipelining/m2`
and the existing Windows-root Maven trust configuration. First builds need
`package`, not just `test`, because the combined provider unpacks dependency
source JARs. The test-resource README in each branch is the portable entry point.
For a fresh combined-provider documentation check, use `clean package
javadoc:javadoc` in that same reactor, not a standalone module invocation against
cached sources from the other prototype. Do not run a source-unpacking build
concurrently with another build in the same worktree.

## Review boundaries

- The capability-cache prerequisite was merged separately in
  [PR #218](https://github.com/eclipse-ee4j/angus-mail/pull/218), closing
  [bug #217](https://github.com/eclipse-ee4j/angus-mail/issues/217). Both
  alternatives now inherit it from upstream; it is absent from their feature
  diffs. The [standalone report](699-angus-stale-capabilities.md) preserves its
  independent regression evidence.
- Reader-thread TLS stalls and the failure/recovery-policy decision remain.
  Engine success under these profiles is not a universal no-deadlock proof.
- No SSLEngine implementation can infer an arbitrary socket factory's hidden
  trust or client credentials. The explicit hook/fallback is a compatibility
  policy, not a test-only exception.
- The engine helper is a larger change to shared I/O infrastructure. Review
  unusual providers, proxies, delegated TLS task callbacks, resource limits and
  TLS state transitions before release. In particular, this round does not
  establish every exotic custom-factory/proxy combination or bounded memory for
  arbitrarily long SMTP replies.
- Full CI, the SJM consumer suite against the engine, production deployment
  compatibility and release-readiness coverage have not been claimed complete.
- No research files, raw logs or PR drafts are in the Angus patches. Neither
  feature branch nor its PR draft has been published. SJM's production pin is
  unchanged.
