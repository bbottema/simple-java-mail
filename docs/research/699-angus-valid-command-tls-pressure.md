# Valid-command TLS 1.3 pressure comparison

Local investigation, 2026-10-15. This report and its logs are excluded from Git. Nothing has been published. Related: [prototype results](699-angus-pipelining-prototype-results.md), [prototype plan](../plans/699-angus-pipelining-prototype.md).

## Finding

**Valid SMTP commands can stall the prototype over STARTTLS with TLS 1.3. Both sequential controls complete the same exchange.** This supersedes the earlier uncertainty about whether only the invalid-command fixture could trigger the problem.

The original reproduction used an invalid 4 MiB MAIL extension and a server replying before reading the complete command. That was an adversarial cleanup test, not evidence of valid SMTP progress. The new fixture sends valid commands, reads and validates the complete command before replying, and uses legal multiline replies. Stalls occurred on JDK 11, 17 and 21. Other repeats passed, so the result depends on scheduling and buffer pressure. This is evidence that the problem is possible, not a measurement of its frequency in production.

Implicit TLS passed all of the new pressure cases run here. That establishes a passing comparison under these conditions; it does not establish that implicit TLS is immune. Plain SMTP, larger buffers, smaller replies, and default key-update thresholds also passed their selected controls.

## What was compared

Both Angus checkouts start at upstream master `a7bd3fed964d9c9bc2981d4b2c9867358453c571`:

- Unmodified Angus, sequential.
- The prototype with `mail.smtp.pipelining` / `mail.smtps.pipelining` disabled.
- The same prototype with pipelining enabled.

All three use the same test source, Jakarta Mail API 2.2.0-M1, Activation API 2.2.0-M1 and Angus Activation 2.1.0-M1. No production dependency pin or production Java source was changed for this investigation. The expanded test is `SMTPPipeliningTlsTest` in the combined provider module. It uses the ordinary transport provider and actual SocketFetcher path, with test factories selecting buffers and recording sockets. Certificate trust and hostname verification remain enabled.

The valid pressure exchange has:

- Three messages on one connection, with 100 recipients each. The fixture checks delivery counts, recipient ordering, connection reuse and reader cleanup on completion.
- A 254-octet mailbox with a 64-octet local part and DNS labels below the limit. Angle brackets make the SMTP path 256 octets.
- A 374-octet MAIL command, including CRLF, with advertised DSN parameters `RET=HDRS` and a valid ENVID. RCPT commands are 295 octets including CRLF and `NOTIFY=SUCCESS,FAILURE,DELAY`.
- No arbitrary padding parameter, unadvertised extension, command injection, or command above the base 512-octet limit. The server checks the complete expected command and its ASCII length before replying.
- A MAIL reply of 128 continuation lines of 512 octets, followed by `250 accepted` and CRLF: 65,550 octets total. Each RCPT reply has one such continuation line plus the final line: 526 octets. These are unusually verbose but legal multiline replies. The server writes replies in command order.
- Small raw send/receive buffer settings on both ends, set before connection/accept and logged after TLS setup. The final matrix uses 512 bytes for JDK 17/21 and 128 bytes for JDK 11. Socket getters report those sizes. These are socket buffer settings, not a packet capture or proof of the negotiated TCP window size.
- Diagnostic `jdk.tls.keyLimits` of 16 KiB for the applicable AES/GCM and ChaCha20 ciphers. TLS traces confirm actual key updates and use of the envelope reader. These thresholds are test settings, not production defaults.
- A 100 ms server processing delay after reading a complete MAIL command in the final matrix. Stalls also occurred without this delay. The delay allows different legal client/server schedules; it does not withhold a command fragment or depend on pipelining being enabled.
- No write timeout in the progress comparison. An external watchdog takes thread dumps at roughly 5 and 15 seconds, then terminates only the JVM it started. The ordinary 10-second read timeout does not stop a thread blocked writing a TLS key update.

[RFC 5321 section 4.5.3.1](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.5.3.1) defines the address, command and reply-line limits and permits multiline replies. [RFC 3461](https://www.rfc-editor.org/rfc/rfc3461.html#section-4) defines the advertised DSN parameters. [RFC 2920 section 3.1](https://www.rfc-editor.org/rfc/rfc2920.html#section-3.1) requires clients to handle multiline replies and warns about flow-control deadlocks. The 32-command / 32 KiB grouping bound alone does not provide that guarantee.

## Results

The final 18-case matrix uses identical settings within each JDK and TLS-mode comparison. Each passing case completes all three messages and 300 recipients.

| Runtime / transport | Unmodified sequential | Prototype sequential | Prototype pipelined |
| --- | --- | --- | --- |
| JDK 11.0.16.1, STARTTLS, 128-byte buffers | Pass | Pass | Stalled; watchdog |
| JDK 11.0.16.1, implicit TLS, 128-byte buffers | Pass | Pass | Pass |
| JDK 17.0.20, STARTTLS, 512-byte buffers | Pass | Pass | Pass in this matrix; stalled in the earlier matching no-delay case |
| JDK 17.0.20, implicit TLS, 512-byte buffers | Pass | Pass | Pass |
| JDK 21.0.12.1, STARTTLS, 512-byte buffers | Pass | Pass | Stalled; watchdog |
| JDK 21.0.12.1, implicit TLS, 512-byte buffers | Pass | Pass | Pass |

The earlier JDK 17 no-delay stall has separate successful unmodified/prototype-sequential controls. Some JDK 11/21 pipelined repeats also passed. Do not turn these exploratory runs, which vary timing and settings, into a failure-rate estimate.

Additional controls on JDK 21:

| Case | Result |
| --- | --- |
| Same STARTTLS pressure, key-update trace disabled but 16 KiB key limit retained | Stalled with the same reader/write stack. Logging is not required to trigger it. |
| Same STARTTLS pressure with a 1,500 ms write timeout | Send fails in about 1.68 seconds. Assertions confirm the connection is closed, no DATA command was sent, and the reader has stopped. The test remains a delivery failure, not a successful send. |
| Same STARTTLS pressure with default key-update settings | Three messages pass. TLS 1.3 remains enabled; this is a diagnostic control, not a proposed configuration workaround. |
| Same pressure with plain SMTP | Three messages pass. |
| STARTTLS with 8,192-byte buffers | Three messages pass. |
| STARTTLS with the MAIL reply reduced to 526 octets | Three messages pass. |

An exploratory 4 MiB *legal multiline reply* also stalled both sequential controls. That exposes a broader pressure limit and must not be used to attribute all such TLS stalls to pipelining. The 65,550-octet comparison above is the narrower case where sequential sending succeeds and pipelining can stall.

## What the threads show

In the new valid-command stall, the sender has written its group and is waiting for the reader's first complete reply. The server has read the complete MAIL command and is writing that command's large reply before processing the queued recipients. While consuming the reply, JSSE needs to send a key update. The reader blocks in that socket write. The server is also blocked writing, and neither side resumes reading.

The two watchdog dumps show the same blocked operations. On JDK 17/21 the reader is in `SSLSocketOutputRecord.flush` through `OutputRecord.changeWriteCiphers` and `KeyUpdateKickstartProducer`. On JDK 11 it is responding through `KeyUpdateConsumer`. The sender is in `SMTPReplyReader.take`. The server is in `SSLSocketOutputRecord.deliver` while writing a legal reply line.

This is different from the earlier invalid-command snapshot where the reader waited for a lock held by the blocked sending thread. Here the reader itself blocks on the network write needed for TLS. Both findings show why a separate thread does not guarantee that TLS reads can always make progress.

## Fallback and design implications

Timeout-only handling cannot now be justified as a policy solely for invalid inputs. Maintainers could still choose bounded send failure as an explicit compatibility tradeoff, but they must know it can affect valid SMTP traffic that currently completes sequentially.

A graceful sequential fallback deserves investigation before considering a broad TLS rewrite. There are two different cases:

1. If all queued replies have been drained and the connection still works, the client can choose sequential sending for later commands or transactions. That does not rescue the demonstrated deadlock.
2. After an envelope stall, abort the physical connection, establish a new TLS session, and retry that message's envelope sequentially once. This is a possible design only when Angus can prove that **no DATA or BDAT was sent for the failed attempt**. It must preserve TLS verification, authentication, recipient results, callback/event behavior, cancellation and the caller's connection ownership. Reconnecting a caller-supplied socket or obtaining credentials again is not automatically available in every Transport use case.

The second option starts a new SMTP transaction. It does not continue a partly written command on the damaged connection. [RFC 5321 section 4.1.1.10](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.1.1.10) requires cancellation of the pending transaction when the connection fails. The prototype's existing envelope/body boundary provides a useful starting point for proving that no message content was submitted, but automatic retry is not implemented or tested here. Once content submission begins, a generic replay policy can duplicate mail and is outside this proposed fallback.

A timeout is not proof of this particular deadlock. Slow peers, packet loss and other write stalls can produce the same observable timeout. Portable runtime recovery would need a policy for a timed-out, known-pre-content envelope, with a bounded retry and explicit opt-in or maintainer agreement. Diagnosing a JSSE internal lock or parsing thread stacks is not a portable production discriminator. A failed sequential retry must terminate rather than loop.

Changing TLS I/O, potentially with SSLEngine, remains an alternative if a narrower solution cannot satisfy the requirements. It is not the preferred next step and has not been implemented. Smaller fixed groups may reduce exposure, but these tests do not prove any fixed byte cap safe for arbitrary TCP pressure and TLS control messages.

## Reproduction and retained evidence

Scripts and results live in `.local/angus-pipelining` in the main SJM checkout:

```powershell
# Runs baseline, prototype-off and prototype-on for both TLS modes.
& '.local/angus-pipelining/run-valid-tls-matrix.ps1' `
    -JdkPath 'C:\workstation\simple-java-mail\.local\angus-pipelining\runtimes\jdk-21.0.12.1+1' `
    -Name 'another-matrix21'

# Timing-sensitive single reproduction, without a write timeout.
& '.local/angus-pipelining/run-valid-tls.ps1' `
    -Name 'another-starttls-pressure' -BufferBytes 512 -ReplyDelayMillis 100
```

Each run has its own compiled test, stdout, TLS trace, JSON settings/result and, if it persists, thread dumps. The JSON includes production class and test-source hashes. Successful final matrix cases show 144 produced/consumed KeyUpdate trace lines; prototype-on cases also show reader-thread trace entries. A killed JVM is recorded as watchdog failure, never as a passing test. Reruns need fresh names to retain earlier evidence.

Useful directories under `tls-valid/`:

- `matrix11-*`, `matrix17-*`, `matrix21-*`: the final paired matrix.
- `jdk17-starttls-on-512-64k`, `jdk17-starttls-off-512-64k`, `jdk17-baseline-starttls-512-64k`: the earlier JDK 17 differential failure and controls.
- `final-starttls-on-512-no-trace`: independent confirmation without JSSE tracing.
- `final-starttls-on-512-timeout-cleanup`: failed delivery with verified cleanup and no DATA.
- `final-plain-on-512`, `jdk21-starttls-on-512-64k-defaultkeys`, `jdk21-starttls-on-512-small-replies`, `jdk21-starttls-on-8192-delayed`: selected diagnostic controls.

Provider class hashes in the runs: baseline `E5EBAE6D369D330AC77C8FF2F5A56A9DF31D90FB17A8E6350EC287D1E800466D`; prototype `56CAE40857C13848C35F259C62EB5ECBFA3E9EF8CA19422ACE99E533AD20CD4E`.

The ordinary focused Maven test run on JDK 11 passes: 35 tests, zero failures/errors, seven optional skips. The three new pressure methods are among those skips and were exercised separately in the watchdog-controlled matrix. This ordinary test result does not cancel the failing pressure evidence. Log: `logs/valid-tls-focused-jdk11.log`.

The change adds test coverage and local reproduction tooling only. Production code, retry policy, TLS implementation and the public comment draft have not been changed as part of this comparison. The draft must be revised with this result before publication.
