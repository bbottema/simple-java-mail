# TLS recovery spike and alternatives to the reader thread

Local investigation, 2026-10-15. Git-excluded. Nothing published or committed.
The user-edited [progress comment](../plans/699-angus-pipelining-progress-comment.md)
was read but not changed in this round. Previous [conditional stall measurements](699-angus-tls-stall-rate-measurements.md)
remain separate from this recovery experiment.

Subsequent work: the proposed [separate SSLEngine experiment](699-angus-sslengine-prototype.md)
is now complete. Its results and the [external reuse survey](699-angus-reusable-tls-io.md)
supersede the unimplemented-candidate status recorded below, without changing
the recovery experiment or its limitations.

## Result

Bounded reconnect-and-sequential recovery worked in this experiment. Nine
confirmed STARTTLS stalls recovered across JDK 11, 17 and 21. Every recovered
connection then completed another sequential send. No failed attempt reached
DATA, and no stalled connection was reused.

This is a test-side controller creating a fresh Transport and physical connection
to the same endpoint. It is not an automatic retry implementation inside Angus,
not a pool integration, and not proof that every Transport configuration can
reconnect transparently.

## What ran

New local files, outside the Angus diff:

- `.local/angus-pipelining/BoundedTlsRecoveryProbe.java`
- `.local/angus-pipelining/run-bounded-recovery.ps1`
- `.local/angus-pipelining/tls-recovery/`: per-trial stdout, JSSE traces, results
  and watchdog dumps from the earlier pilots.

The probe compiles with `--release 11`. It uses the unchanged prototype provider
classes and the same Mail/Activation dependencies as the earlier measurements.
The class hash differs from the earlier sampling report because focused Maven
verification subsequently rebuilt the provider. No canonical Angus source was
changed for this round.

Pressure settings: 512-byte raw socket buffer settings on both ends, 100 legal
long recipient addresses, a 32,782-octet MAIL reply, 526-octet recipient replies,
100 ms MAIL processing delay, TLS 1.3 and diagnostic 16 KiB key-update limits.
Certificate and hostname verification stay enabled. These tests also authenticate
with synthetic fixture credentials on every connection.

The 1,500 ms Angus write timeout aborts the stalled physical connection. A monitor
samples the existing reader's key-update-write stack and the server's reply-write
stack every 400 ms. A recovery is counted as a confirmed stall only with at least
two matching snapshots. An independent 18-second process watchdog remains in
place. All final cases exited normally without that watchdog firing.

The first connection advertises PIPELINING; the second deliberately does not.
The controller also disables pipelining for the new Transport. Assertions verify
fresh EHLO after reconnect and STARTTLS, authentication, TLS 1.3, the actual Angus
timeout wrapper beneath TLS, MAIL/RCPT parameters and recipient ordering.

| Runtime and mode | Pressure trials | Needed recovery | Recovered | No recovery needed |
| --- | ---: | ---: | ---: | ---: |
| JDK 21 STARTTLS | 8 | 5 | 5 | 3 |
| JDK 17 STARTTLS | 3 | 1 | 1 | 2 |
| JDK 11 STARTTLS | 3 | 3 | 3 | 0 |
| JDK 21 implicit TLS | 4 | 0 | Not exercised | 4 |

Across eighteen pressure trials there were nine confirmed stalls and nine
successful pipelined sends that did not require fallback. Each recovery sent the
failed message once on the new sequential connection and a second message to
prove reuse; the two successful wire-message hashes match. The pressure trials
therefore completed 27 acknowledged messages. Recovery trials took about
3.3-3.8 seconds end to end, including timeout, reconnect, authentication and both
sequential sends. This is not a latency benchmark.

Six additional checks passed, one per case in each TLS mode on JDK 21:

| Case | What was checked |
| --- | --- |
| Both attempts disconnect before content | Exactly two connections, no third retry, no DATA, original exception retained with retry failure suppressed |
| Server reads the complete body, then disconnects before its final reply | One connection only; ambiguous content delivery is not replayed |
| First attempt succeeds | One connection only; no unnecessary sequential degradation |

All 24 final probe runs passed. Implicit-TLS recovery from the TLS stall itself
was not demonstrated because those pressure runs did not stall. Its deterministic
pre-content retry/failure and post-content no-retry branches were exercised.

These are mechanism checks, not a new production failure-rate estimate. The
fixture now includes authentication, monitoring and a configured timeout, and
uses different connection windows from the previous sampling round.

## Important limitations and cleanup finding

The initial pilots are retained separately and excluded from the passing counts:

- `pilot-recovery21`: fixture PLAIN-auth authorization-ID mismatch, before sending.
- `pilot-recovery21-auth`: the server fixture omitted NOOP support during the
  connection-reuse check; that fixture error prevented a valid result.
- `pilot-recovery21-noop`: recovered and completed both sequential sends, but the
  JVM remained alive with a non-daemon scheduled-executor thread after main exited.
  Its watchdog dump records that failure; it is not counted as a clean pass.

The last pilot used Angus's default per-socket write-timeout executor. Inspection
of `WriteTimeoutSocket` shows that it owns that executor and shuts it down in
`close()`, whereas its timeout task closes the underlying output stream. The
observed remaining scheduler is consistent with incomplete wrapper cleanup after
the timed-out TLS connection. The precise cleanup defect still needs a focused
baseline comparison before assigning it to Angus or JSSE or preparing a fix.

Final runs use the existing `mail.smtp.executor.writetimeout` / smtps equivalent
with an explicitly caller-owned scheduler. The fixture shuts it down and verifies
termination. This changes executor ownership, not TLS, buffer pressure or timeout
semantics. It is not a fix for the default-executor finding. A production recovery
implementation must account for the entire physical connection's resources.

The controller marks the content boundary before calling either `data()` or
`bdat()`. Only DATA transfer was exercised; the BDAT guard is not separately
verified. The test checks that server-side DATA counters did not change on the
failed attempt before allowing retry. Production cannot consult server counters;
its own send state must supply that guarantee.

TransportListener notifications are checked, not hidden: the failed Transport
reports non-delivery, then the replacement reports success. An internal Angus
retry must decide how to preserve one logical send's public events and exception
semantics. This outer-controller experiment does not solve that problem.

Partial recipient rejection, `reportsuccess`, cancellation races, caller-supplied
sockets, expiring credentials, all authentication mechanisms, BDAT, connection
listener and outer-pool lifecycle behavior are not covered by this quick spike.
It catches pre-content MessagingExceptions in controlled scenarios; that is not
a proposed production classification rule for retrying every such exception.

## Is the reader thread the right abstraction?

The thread is useful at the SMTP layer, but it is not independent at the TLS
layer. Local JDK 21 source (`lib/src.zip`) confirms:

- `SSLSocketImpl.readApplicationRecord`, around lines 1517-1520, can call
  `tryKeyUpdate()` as records are read. That calls `startHandshake()`.
- `KeyUpdate.KeyUpdateConsumer`, around lines 246-249, can produce a response
  while consuming an incoming key update.
- `KeyUpdateProducer`, around line 324, calls `OutputRecord.changeWriteCiphers`,
  which takes the output lock and writes the control record to the socket.

The failure is therefore not solved simply by assigning application read and
write calls to different threads. In the observed case, the reader itself is
blocked writing TLS control data while the server is blocked writing its reply.

### Alternatives

| Model | Assessment |
| --- | --- |
| Swap reader and writer roles; use futures or a larger executor | Same blocking SSLSocket operations remain. No structural remedy. Virtual threads would not remove the dependency either and are outside Java 11. |
| One thread owns SSLSocket and alternates SMTP reads/writes | A write can block before the thread can service a reply. This loses the concurrent-drain property the helper was introduced to provide. |
| Smaller groups, or send MAIL sequentially and pipeline only RCPT | Worth measuring as exposure reduction, not a general guarantee. Large legal replies to RCPT can recreate the same pressure; one command at a time is sequential delivery. |
| Raw encrypted-input/output pumps beneath SSLSocket | A possible intermediate experiment: independently drain network input while TLS is blocked. Queues add memory and lifecycle costs; finite queues can still fill. Not implemented, not a proven remedy. |
| Nonblocking SocketChannel plus SSLEngine-driven I/O loop | The strongest candidate for removing the observed blocking-read/write dependency. TLS produces/consumes bytes in memory; network reads and writes are scheduled separately. Still requires its own prototype and bounded-resource/ordering tests. |

The last assessment is an architectural inference, not a result of a new engine
prototype. The [SSLEngine API](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLEngine.html)
explicitly separates TLS processing from transport. A network write that cannot
complete can leave ciphertext queued while the I/O owner services readable input,
rather than blocking that owner inside a socket write. The engine's status and
buffer requirements must still be obeyed; replacing the type alone is insufficient.

NioSmtpClient at commit `58c521bb6485a577409fc85b4a63ca5c87c9d4dd` uses a supplied
SSLEngine inside Netty's SslHandler, on its channel pipeline. Its SMTP response
futures sit above that I/O layer. See [SmtpSession.performTlsHandshake](https://github.com/HubSpot/NioSmtpClient/blob/58c521bb6485a577409fc85b4a63ca5c87c9d4dd/src/main/java/com/hubspot/smtp/client/SmtpSession.java#L184-L203).
That is the relevant architectural lesson, not evidence that copying its SMTP
scheduling code would fix an SSLSocket implementation. No code was copied and no
Netty/NioSmtpClient dependency was added.

## Can key updates become a separate pipeline step?

Not reliably as an SMTP step. Either peer can initiate key updates during the
connection. A manual `startHandshake()` before a group cannot prevent another
update arriving or being required while that group is in flight. SSLSocket does
not expose a public pending-KeyUpdate queue for Angus to drain at selected times.
In the inspected JDK, `finishPostHandshake()` also does not dispatch the ordinary
handshake-completion listeners, so that callback is not a per-KeyUpdate scheduler.

[TLS 1.3 section 4.6.3](https://www.rfc-editor.org/rfc/rfc8446.html#section-4.6.3)
allows either peer to update after the handshake and requires a requested response
before subsequent application data. That leaves TLS-implementation scheduling
choices, but it does not give an SMTP client control over the peer's timing.

There is, however, a useful version of the idea at the TLS layer. An engine-driven
I/O loop can service the engine's [NEED_WRAP, NEED_UNWRAP and NEED_TASK states](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLEngineResult.HandshakeStatus.html)
alongside SMTP work. New application wrapping must respect control work and
backpressure. Already produced ciphertext must remain in order; a key-update
record cannot simply jump ahead of previously encrypted records.

This is not writing cryptography or replacing Java's TLS protocol implementation.
It is taking responsibility for TLS I/O scheduling, buffers, deadlines and cleanup
that SSLSocket currently manages. It is nevertheless a wider compatibility change
than an envelope helper: an engine must own the TLS session from STARTTLS or
implicit connect through body transfer and shutdown. There is no supported public
handoff of a live SSLSocket's session state to SSLEngine just for the envelope.

## Suggested next isolated experiment

Keep the current prototype and recovery evidence intact. If we challenge the
design further, build a separate minimal engine/channel client against the same
fixture, rather than rewriting SMTPTransport first:

1. Own a nonblocking channel and engine from TLS establishment onward. Use one
   serialized I/O owner, ordered ciphertext buffers and explicit resource bounds.
2. Exercise the known MAIL-reply pressure and equally large RCPT replies. Show
   that inbound records continue being consumed while outbound writes are pending,
   with frequent updates in both directions. Compare with the existing reader.
3. Cover verification, delegated TLS tasks, partial records, timeout/abort and
   clean shutdown before connecting this design to Angus. No additional pool or
   runtime dependency is needed for that experiment.

Passing that experiment would justify discussing a contained TLS I/O adapter with
maintainers. It would not establish compatibility with arbitrary SocketFactory /
SSLSocketFactory implementations, Angus's identity checks, proxy paths or custom
trust/key managers without further work. Sender-owned callbacks and synchronous
sendMessage semantics must remain unchanged.

For presenting the current prototype, bounded sequential recovery is now an
experimentally supported direction, with the limitations above. A dependency-free
engine/channel loop is a distinct candidate for avoiding recovery in the first
place, not something implemented or proven in this round.

## Reproduction

From the main SJM checkout, choose fresh result names:

```powershell
& '.local/angus-pipelining/run-bounded-recovery.ps1' -Name 'fresh-recovery21' -Scenario pressure -Repetitions 8
& '.local/angus-pipelining/run-bounded-recovery.ps1' -Name 'fresh-implicit21' -Scenario pressure -Implicit $true -Repetitions 4
& '.local/angus-pipelining/run-bounded-recovery.ps1' -Name 'fresh-bounded-failure' -Scenario fallback-failure
& '.local/angus-pipelining/run-bounded-recovery.ps1' -Name 'fresh-no-replay' -Scenario post-content
& '.local/angus-pipelining/run-bounded-recovery.ps1' -Name 'fresh-no-degradation' -Scenario success
```

Final run directories: `recovery21-starttls-8`, `recovery17-starttls-3`,
`recovery11-starttls-3`, `recovery21-implicit-4`, and the six
`recovery21-{False,True}-{fallback-failure,post-content,success}` directories.
Every result records the source and provider hashes:

- Probe SHA-256: `3A9777062FCB815C9AA6E8F5F1D14A27352BBB8506D9731BD2FFC46FFC6CE50C`.
- Provider class SHA-256: `7956B308D71197F4187B21FFD88CC4B07A76601D55627EA12F3E01BA1773C41B`.

All experiment JVMs finished; owned peer/monitor/timeout executors are explicitly
closed in the final harness. No server containers were created in this round.
