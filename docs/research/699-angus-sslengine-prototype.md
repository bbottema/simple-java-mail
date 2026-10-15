# Separate SSLEngine prototype: result and limits

Local experiment, 2026-10-15. This tests option 3 from the
[progress-comment draft](../plans/699-angus-pipelining-progress-comment.md).
The draft itself was left unchanged. No production Angus/SJM sources, dependencies,
commits or GitHub comments were changed during the original experiment. Its files
and results were initially Git-excluded. The packaging follow-up below records
the new worktree; research can now be committed in SJM at the user's request.

## Conclusion

The engine client completed all eighteen comparison trials, including the
conditions that stalled the Angus reader-thread client. It also continued
decrypting envelope replies and producing TLS control records while its
nonblocking writes returned zero. This supports option 3 as a working way to
avoid the reproduced stall, rather than only aborting it and reconnecting.

It remains a separate client experiment, not a drop-in Angus TLS implementation.
The result does not establish that every server, JDK provider or possible traffic
pattern will make progress, or that the integration will preserve every existing
socket-factory and Transport contract.

## Angus worktree and test entry point (2026-10-15)

The separate experiment is now in `C:\workstation\angus-mail-sslengine-prototype`,
on `213-sslengine-prototype`. It starts from
`a7bd3fed964d9c9bc2981d4b2c9867358453c571`, the same upstream commit as the
reader-thread worktree. It does not include the latter's production changes.

The six-file patch contains `SMTPSSLEngineProbe.java`, the parameterized
`SMTPSSLEngineTest.java`, a diagnostic security-properties file, the shared public
test keystore, its README and one copyright-exclusion entry. All Java and fixture
files live under `providers/angus-mail/src/test`. No production source, dependency,
POM, public API or release notes changed. Neither Angus patch contains research
reports, raw logs or PR drafts. SJM now tracks the research at the user's request.

The engine algorithm is unchanged from the measured source. Packaging adds the
normal Angus license header, a class rename, missing-fixture/argument checks,
runtime/provider identification and a guard against accidentally running the
reader comparison with upstream's non-pipelining provider.

From the new Angus worktree root, with JDK 11, 17 or 21 selected for Maven:

```text
mvn -B -pl providers/angus-mail -am -Poss-release,staging,build-only -Dgpg.skip=true -Denforcer.skip -Dtest=SMTPSSLEngineTest -Dsurefire.failIfNoSpecifiedTests=false -Dangus.sslengine.prototype=true package
```

`package` is intentional: the combined module unpacks dependency source JARs
produced by the earlier reactor modules. Local validation additionally sets
`-Dmaven.repo.local=C:/workstation/simple-java-mail/.local/angus-pipelining/m2`
and uses the Windows-root trust store for Maven downloads. This is a package
build, not an install, so the reader prototype's installed artifacts are not
replaced. The entry point itself contains no workstation-specific paths.

Each case runs in a fresh child JVM, with tracing, the existing 16 KiB diagnostic
key limit, a 45-second process deadline and a separate log under the module's
`target/sslengine-prototype/run-*/`. The normal matrix runs 24 cases and skips
six optional reader comparisons. Adding
`-Dangus.sslengine.readerClasses=C:/workstation/angus-mail-pipelining-prototype/providers/angus-mail/target/classes`
enables those comparisons and uses that same provider for the sequential arm.
The new client still runs independently. The wrapper accepts a recognized stall
only for the reader comparison; it is reported as a stall, not a delivery pass.
Unexpected failures or watchdog termination fail the test. Blocked-write counts
and stall frequency are recorded, not imposed as OS-independent assertions.

The original source and all earlier raw results remain under `.local` with their
original hashes. Packaging reruns are separate evidence, not additions to the
original 18-per-arm comparison or a new performance benchmark. The user-edited
progress-comment draft remains unchanged. The two PR descriptions are held in
SJM and drafted in chat; nothing has been posted.

### Packaging verification

| Runtime | Completed exchanges | Expected failure cases | Optional reader cases | JUnit result |
| --- | ---: | ---: | --- | --- |
| Oracle 11.0.16.1 | 14 | 10 | 6 skipped | 30 tests, 0 failures/errors, 6 skipped |
| Microsoft 17.0.20 | 14 | 10 | 6 skipped | 30 tests, 0 failures/errors, 6 skipped |
| Temurin 21.0.12.1 | 20 | 10 | 6 completed, included in the 20 | 30 tests, 0 failures/errors, 0 skipped |

Each row's engine portion includes eight successful two-message connections
and ten expected failures. Sequential controls account for six successful
connections; the final row also includes the reader comparison. All three
reactor package builds passed. Logs are in the new worktree's
`target/sslengine-package-jdk11.log`, `-jdk17.log` and `-jdk21.log`.
The original engine source did not change. The JDK 11 run preceded the runner's
small interrupt-preservation adjustment; JDK 17/21 ran the final wrapper, and
the final wrapper's default-off check was also run on JDK 11.

No watchdog terminated a child, no probe JVM survived, and the successful cases
recorded key updates in both directions. The fragmented-I/O engine cases again
showed incoming application data and TLS control output while writes were
blocked. The six reader comparisons did not stall this time. One recorded a
single matching stack snapshot but then completed, which is not a stall.
These new results are retained alongside the earlier failures, not substituted
for them or combined into a population-level rate.

The default-off JUnit check created no result directory and ran no cases. A
direct comparison attempt using upstream's provider failed on the missing
prototype hook before connecting, confirming the wrong-provider guard.

The copyright checker reported the same fifteen findings in this worktree and
the untouched baseline after normalizing checkout paths. None names a new file;
this is not a green repository-wide copyright check. `git diff --check` passes.
The two new Java files and security overlay have matching full license/SPDX
headers. The fixture key is identical to the reader prototype's public test
key. Compiler output includes three AutoCloseable/InterruptedException warnings
from the standalone helper; they remain test-code limitations, not production
API changes. No full-CI or new SpotBugs claim is made for this packaging work.

Packaged source SHA-256:

- `SMTPSSLEngineProbe.java`: `63d07cd74b268bf8bb4d32a6dd55a19293f66b87c3f120399239707e4c0ed4d8`.
- `SMTPSSLEngineTest.java`: `6c8ecd455279dc10017a758784ba37a027221bd8aacff42648962bccd84dd33b`.

The reader-thread worktree and SJM integration source changes were not edited
by this packaging task. Both Angus prototypes remain uncommitted local changes;
only the SJM research/planning snapshot is being committed.

## Original matching comparison

All three clients used the same in-process server implementation and the same
validated SMTP commands, replies, authentication and message content. Each trial
used one fresh connection and attempted two sends on that connection.

| Client | Trials completing both sends | Confirmed stalls |
| --- | ---: | ---: |
| Existing Angus prototype, pipelining disabled | 18/18 | 0 |
| Existing Angus prototype, reader-thread pipelining | 9/18 | 9 |
| Separate SSLEngine client, pipelined envelope | 18/18 | 0 |

The sequential control is the same prototype provider with its flag disabled,
not a fresh build of unmodified upstream. Previous upstream-baseline comparisons
remain in the earlier reports. The existing compiled provider was not rebuilt
or modified for this experiment.

| Runtime | TLS mode | Large reply | Trials per client | Angus pipelined stalls | Engine completions |
| --- | --- | --- | ---: | ---: | ---: |
| 21 | STARTTLS | MAIL, 64 KiB | 4 | 3 | 4 |
| 21 | STARTTLS | MAIL, 32 KiB | 2 | 2 | 2 |
| 17 | STARTTLS | MAIL, 64 KiB | 2 | 0 | 2 |
| 11 | STARTTLS | MAIL, 64 KiB | 2 | 2 | 2 |
| 21 | Implicit TLS | MAIL, 64 KiB | 2 | 0 | 2 |
| 17 | Implicit TLS | MAIL, 64 KiB | 1 | 0 | 1 |
| 11 | Implicit TLS | MAIL, 64 KiB | 1 | 0 | 1 |
| 21 | STARTTLS | First RCPT, 64 KiB | 2 | 2 | 2 |
| 21 | Implicit TLS | First RCPT, 64 KiB | 2 | 0 | 2 |

All sequential controls passed. Every confirmed stall in this round occurred
before DATA. The JDK 17 comparison did not reproduce a stall in its two STARTTLS
trials; earlier experiments did. These small, deliberately selected samples
are not estimates of production failure rates. One implicit-TLS Angus run had
a single matching stack snapshot but completed successfully; it was not counted
as a stall.

Conditions carried over from the earlier experiments:

- TLS 1.3, 512-byte requested socket send/receive buffers on both sides, TCP_NODELAY.
- 100 legal long recipient addresses; MAIL is 374 octets and RCPT is 295 octets,
  including CRLF and the same DSN/ENVID parameters.
- A 100 ms processing delay after the server consumes the complete MAIL command.
- MAIL replies of 32,782 or 65,550 wire octets, built from legal 512-octet
  continuation lines and a 14-octet final line. Ordinary RCPT replies are 526
  octets. The RCPT variant moves the large reply to the first recipient and uses
  a 526-octet MAIL reply.
- Diagnostic 16 KiB TLS key limits. Traces show KeyUpdate production and
  consumption in both directions; protocols and key updates were not disabled.
- Fresh EHLO after STARTTLS, synthetic AUTH PLAIN, certificate trust and hostname
  checks. Each body is checked against the same expected SHA-256, including a
  dot-stuffed line. Recipient order and parameters are checked before DATA.
- Envelope groups of 32 commands or 32 KiB, all replies consumed before DATA.

The new engine uses explicit JDK endpoint identification for this localhost
fixture. That is not proof of equivalence with every Angus hostname-checking
rule or factory option. OS buffer settings, TLS record boundaries and scheduling
remain part of the measured environment; these are matching configurations,
not identical packet-level schedules.

Angus uses a 1,500 ms write timeout with its existing caller-owned executor option,
as in the recovery experiment. The engine has an 8-second absolute exchange
deadline. A 25-second external process watchdog protects each trial. The engine
never used recovery or reconnection, and no process watchdog fired. Timings in
the raw results include JVM startup, tracing, handshake and cleanup; this is not
a performance benchmark.

## Evidence of continued progress

A send completing could result merely from favorable timing. The engine also
records what happens after a real nonblocking channel write returns zero while
ciphertext is still queued. The blocked interval ends when that queue drains.

In `final21-starttls-mail64/3-engine.stdout.log`, the engine decrypted **49,018
bytes of envelope replies** and generated **five TLS control outputs** during
such intervals, then finished both messages on the same connection. The JDK 11
STARTTLS run `final11-starttls-mail64/2-engine.stdout.log` recorded 47,994 reply
bytes and six control outputs. The JSSE traces identify the key updates.

This is evidence about the mechanism: TLS can produce its control record into
an ordered memory buffer and continue consuming input while the operating
system cannot yet accept the pending output. Ciphertext order is preserved.

The largest pending ciphertext observed across the ordinary comparison cases
was 8,273 bytes. With forced 37-byte I/O fragments it reached 16,429 bytes. Both
are below the fixed 64 KiB output capacity; the design did not solve the test by
growing an unbounded write queue.

## Additional checks

Two JDK 21 cases limited every engine network read/write operation to at most
37 bytes, one STARTTLS and one implicit TLS. Both completed two messages and
clean TLS shutdown. They exercised partial records and BUFFER_UNDERFLOW. During
blocked envelope writes they decrypted 109,244 and 139,816 bytes respectively,
with twelve and thirteen TLS control outputs.

Five failure cases ran in each TLS mode on JDK 21, ten cases total:

| Case | Result |
| --- | --- |
| Disconnect after MAIL | Reported failure, no DATA, one connection |
| Peer accepts commands but sends no envelope reply | Exchange deadline fired, channel closed, no DATA |
| Peer receives the body but closes before final acknowledgement | Reported failure, one body received, no replay |
| Wrong hostname | TLS handshake rejected before AUTH or DATA |
| Untrusted certificate | TLS handshake rejected before AUTH or DATA |

All ten produced the expected failure and exited normally. They are not counted
as successful delivery. The two post-content cases deliberately have an unknown
delivery outcome from the client's perspective.

Across the comparison and fragment cases, the engine acknowledged **40 messages
on 20 connections**, without stalls or retries. The total final matrix contains
54 comparison runs, two fragment runs and ten failure checks. Every expected
engine/control result was observed; nine Angus pipelined runs retained the known
stall. No helper JVM survived, and owned channels, selectors and task/fixture
executors were closed. Successful engine runs verified both TLS close directions.

## Shape of the experiment

`EngineTlsProbe.java` is 918 lines including the common server, control clients,
reply parser, message checks, monitoring and failure scenarios. Its EngineClient
is about 360 lines. It uses JDK SSLEngine, SocketChannel and Selector; no Netty,
NioSmtpClient or other TLS implementation was added.

One calling thread owns the channel, engine and SMTP state. It services readable
input and pending output together. Delegated TLS tasks run on an owned executor;
the I/O loop can still move encrypted bytes while waiting, but it does not call
wrap/unwrap until those tasks finish. It owns the TLS connection from STARTTLS
or implicit handshake through the message bodies and close-notify exchange.

Application wrapping pauses above an 8 KiB output high-water mark, leaving room
for control records behind already-generated ciphertext. Three fixed 64 KiB
buffers hold encrypted input, encrypted output and decrypted records. Queued
application data is limited to 32 KiB. Large multiline SMTP replies are parsed
incrementally, with a 512-octet line limit and an explicit per-reply limit.

The prototype rejects buffer overflow rather than growing indefinitely. Production
code would need a considered sizing/bounds policy for all supported providers.
An input bound or deadline can still terminate a connection that cannot progress;
the experiment does not prove progress for arbitrarily large peer output.

The common fixture checks identical message bytes. The separate client nevertheless
has its own small SMTP state machine; it does not exercise Angus subclass hooks,
exception/event compatibility, partial-recipient behavior, BDAT, cancellation
races, pooling, proxies, custom SocketFactory/SSLSocketFactory implementations,
client certificates, other TLS providers or real MTAs. It also does not establish
the interruption behavior of a production synchronous bridge. These remain
integration work, not covered features.

## Reproduction and provenance

Files under `.local/angus-pipelining/`:

- `EngineTlsProbe.java`: standalone implementation and shared fixture.
- `run-engine-probe.ps1`: compiles with `--release 11`, launches a fresh JVM for
  each arm/trial, retains stdout/JSSE traces/results and an external watchdog.
- `run-engine-matrix.ps1`: the remaining matrix after the first four-trial
  JDK 21 MAIL-64 comparison. It accepts a fresh result-name prefix.
- `tls-engine/final*/`: final results. Earlier `pilot*` directories are retained
  but excluded from the counts above.

To reproduce all final cases, use a fresh prefix:

```powershell
& '.local/angus-pipelining/run-engine-probe.ps1' -Name 'repeat21-starttls-mail64' -Repetitions 4 -MailReplyBytes 65536
& '.local/angus-pipelining/run-engine-matrix.ps1' -Prefix 'repeat'
```

Runtimes: Temurin 21.0.12.1+1, Microsoft 17.0.20+8 and Oracle 11.0.16.1.
Mail/Activation dependencies match the previous aligned prototype. The provider
comes from `C:\workstation\angus-mail-pipelining-prototype`, based on
`a7bd3fed964d9c9bc2981d4b2c9867358453c571` with its existing prototype diff.

All 66 final records identify the same hashes:

- Probe source SHA-256: `386265D9D6CCC85000A3CB8FC204D51FEC4E31045B589D52B475463732BD01A5`.
- SMTPTransport.class SHA-256: `7956B308D71197F4187B21FFD88CC4B07A76601D55627EA12F3E01BA1773C41B`.

The earlier pilots passed their engine cases but used the preceding source
revision. Before the final matrix, the harness armed the implicit-TLS greeting
parser before handshake completion, deferred EOF interpretation until buffered
TLS work was processed, and added envelope-specific progress metrics. Those
pilots are not pooled into the final counts.

The [SSLEngine API](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLEngine.html)
defines the transport-independent wrap/unwrap and delegated-task model used here.
The implementation was written locally; no external library implementation was
copied. See the separate [reuse survey](699-angus-reusable-tls-io.md) for existing
implementations that could reduce the eventual maintenance burden.

## What this gives the maintainer discussion

Option 2 now has evidence for successful bounded recovery. Option 3 now has
evidence for completing the affected exchange while preserving pipelining. The
choice can therefore be discussed with working prototypes for both directions.
The scope and ownership of the eventual Angus integration, including whether to
reuse an existing TLS I/O library, are still open.
