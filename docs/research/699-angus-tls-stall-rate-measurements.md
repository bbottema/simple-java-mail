# TLS 1.3 PIPELINING: repeated local measurements

Local evidence, 2026-10-15. Excluded from Git. Nothing published or committed.
This supplements the [valid-command pressure investigation](699-angus-valid-command-tls-pressure.md).

## What we learned

The stall is easy to reproduce under particular buffer, reply-size and scheduling
conditions, but it did not occur in the ordinary configurations tested here.
Neither observation gives us a production-wide probability.

- Ordinary fixture: no stalls in 30 pipelined connection trials, completing 600 messages.
- Real Postfix and Exim: no stalls in 20 pipelined connection trials, completing 140 messages.
- The established small-buffer reproducer: stalls in 6 of 10 pipelined trials.
  Both sequential controls completed all ten corresponding trials.
- A 32 KiB MAIL-reply variant stalled in all three pipelined trials. Its sequential
  controls passed. Other selected variations passed, including larger buffers.

All frequency measurements used valid SMTP commands. The earlier invalid-command
case was not included. No production code, timeout policy, fallback or TLS layer
was changed in this round.

## Method and denominators

Each trial starts a fresh JVM and connection, then reuses that connection for a
fixed number of messages. A trial passes only if every planned message succeeds
and the fixture's cleanup assertions pass. A failed trial stops at its first failure.
The rates below are therefore **per connection window**, not per email. For
example, 6/10 does not mean 60% of emails failed.

Three implementations are compared against the same dependencies:

1. Unmodified upstream Angus, commit `a7bd3fed964d9c9bc2981d4b2c9867358453c571`.
2. The prototype with pipelining disabled.
3. The prototype with pipelining enabled.

Measurements use Temurin 21.0.12.1+1 on Windows, TLS 1.3, and a diagnostic 16 KiB
key-update limit. JSSE traces confirm actual KeyUpdate messages, not just a
configured threshold. Certificate and hostname verification remain enabled.
These are not production key-update thresholds. Frequent updates deliberately
increase exposure to the interaction we are investigating.

Fixture trials have no write timeout. An external watchdog takes stacks near five
and fifteen seconds and terminates only its own JVM if it still has not finished.
A confirmed stall requires the reader's TLS key-update write and the server's
reply write to remain blocked in both snapshots. Other watchdog expirations and
ordinary test errors are recorded separately; neither occurred in these batches.
Representative stacks were also inspected directly.

The fixture verifies complete MAIL and RCPT commands before replying. Long commands
remain 374 and 295 octets respectively, including CRLF. Long replies use legal
512-octet continuation lines. Reply sizes in the tables are complete wire sizes,
not the runner's requested continuation-byte counts.

Variant order rotates between fixture repetitions. The normal and reproducer
batches overlapped, and later boundary and real-server work also overlapped.
This was not a randomized population study or an idle-machine timing benchmark.

## Ordinary fixture

STARTTLS, platform-default socket buffers (getters reported 65,536 bytes), short
addresses, ordinary single-line replies, no server processing delay, and twenty
messages per connection. DSN parameters remain present and valid.

| Recipients per message | Unmodified Angus | Prototype off | Prototype on |
| --- | --- | --- | --- |
| 1 | 10/10 trials passed | 10/10 passed | 10/10 passed |
| 10 | 10/10 trials passed | 10/10 passed | 10/10 passed |
| 100 | 10/10 trials passed | 10/10 passed | 10/10 passed |

Each implementation completed 600 messages across thirty connections. There were
1,800 successful messages overall. KeyUpdate trace lines total 6,400 for each
sequential control and 4,800 for pipelining; these count trace events, not messages
or unique key-update exchanges.

## Established pressure configuration

STARTTLS, 512-byte raw send/receive buffer settings at both ends, 100 long recipient
addresses, a 65,550-byte MAIL reply, 526-byte RCPT replies, and a 100 ms processing
delay after the complete MAIL command is received. Three messages are planned per
connection. Buffer getters are recorded; they do not establish the actual TCP
window or a portable threshold.

| Implementation | Trials passed | Confirmed stalls | Sends started | DATA commands |
| --- | --- | --- | --- | --- |
| Unmodified Angus | 10/10 | 0 | 30 | 30 |
| Prototype off | 10/10 | 0 | 30 | 30 |
| Prototype on | 4/10 | 6 | 19 | 13 |

Five pipelined trials stalled on their first message and one on its second.
The failed attempts had not reached DATA. Counts include earlier messages on the
same connection. The stalled reader is itself writing a TLS KeyUpdate while the
server is blocked writing its MAIL reply; the sending thread waits for a reply.

## One-variable comparisons

Each row otherwise retains the pressure configuration above. Three trials per
implementation, with three messages planned per trial. All 54 sequential-control
trials passed. For pipelining:

| Changed setting | Trials passed | Confirmed stalls |
| --- | --- | --- |
| Buffers 1,024 bytes | 3/3 | 0 |
| Buffers 4,096 bytes | 3/3 | 0 |
| Buffers 8,192 bytes | 3/3 | 0 |
| Platform-default buffers | 3/3 | 0 |
| MAIL reply 4,110 bytes | 3/3 | 0 |
| MAIL reply 16,398 bytes | 3/3 | 0 |
| MAIL reply 32,782 bytes | 0/3 | 3 |
| Ten recipients | 3/3 | 0 |
| Short addresses | 3/3 | 0 |

This is not a monotonic size limit: the 32 KiB variant failed more often in its
small sample than the 64 KiB reproducer. Scheduling, TLS record boundaries and
buffer pressure matter. These observations do not prove that a particular group
size or buffer size makes the implementation safe.

## Real SMTP servers

Existing locally cached Postfix 3.10.13 and Exim 4.98.2 container images were used.
Ports were published only on loopback. Mail was authenticated, addressed only to
the fixture domain and discarded locally. No external mail was sent. Both servers
advertise PIPELINING. TLS 1.3 and certificate/hostname validation were required.

Each implementation used five fresh connections per server and TLS mode, ten
recipients per message, short addresses, default buffers and a roughly 25 KiB body.
The same diagnostic 16 KiB client key-update threshold remained enabled. Ordinary
connect/read/write timeouts were configured; none expired in the completed runs.

| Server | TLS mode | Messages per connection | Result for each implementation |
| --- | --- | --- | --- |
| Postfix | STARTTLS | 10 | 5/5 trials; 50/50 messages |
| Postfix | Implicit TLS | 10 | 5/5 trials; 50/50 messages |
| Exim | STARTTLS | 4 | 5/5 trials; 20/20 messages |
| Exim | Implicit TLS | 4 | 5/5 trials; 20/20 messages |

These completed runs total sixty connections and 420 messages across all three
implementations. TLS traces contain key updates for every case. Reader cleanup
assertions passed. Updates triggered during content transmission are useful
interoperability coverage, but do not recreate the fixture's large-envelope-reply
pressure.

Two Exim findings are retained, not silently counted as successes:

- Initially its unprivileged worker could not read the mounted fixture certificate
  and key. Every implementation failed TLS setup. The fixture now copies them to
  its own configuration directory with appropriate ownership and permissions.
- With ten messages per connection and extremely frequent diagnostic key updates,
  all three implementations completed five sends, then Exim/GnuTLS closed the
  sixth with `Too many handshake packets have been received`. This was an explicit
  connection rejection, not a deadlock. Thirty trials failed identically, with
  150 messages completed before rejection. Server protection was not disabled.
  The four-message runs above deliberately stay within that observed limit while
  retaining reuse and key-update traffic. They are a different, clearly recorded
  test configuration, not replacements for the failed evidence.

Logs, exact container/image metadata and the failure records were preserved before
removing the two owned temporary SMTP containers. Unrelated containers were left
alone.

## What this means for the proposal

The evidence does not support disabling pipelining for all TLS 1.3 connections.
It also does not justify dismissing the stall as an invalid-command corner case:
valid exchanges fail repeatedly in the pressure configuration.

My preferred next discussion is a bounded policy for an envelope that cannot make
progress. That could report a send failure after closing the connection, or
reconnect and retry sequentially once where credentials, socket ownership and a
proven no-DATA/no-BDAT boundary allow it. A timeout cannot reliably identify this
particular cause. Retry would have to be safe for any covered pre-content timeout,
not depend on JSSE stack inspection. No such policy is implemented or tested here.

An SSLEngine-based I/O design remains an alternative if maintainers require
successful forward progress through these conditions without reconnecting. This
round neither implements it nor proves that it is the only solution. The results
favor discussing a narrow recovery policy before committing to a broad rewrite.

We can report that ordinary tested cases worked and the deliberate reproducer
failed frequently. We cannot honestly report that production stalls are rare or
likely: server reply shapes, actual network pressure, connection lifetimes and
key-update schedules in deployment were not sampled. Zero failures in fifty
ordinary pipelined connection windows is useful evidence, not a low-probability
guarantee or a portable safety proof.

## Reproduction and artifacts

Local, Git-excluded runners:

- `.local/angus-pipelining/run-valid-tls.ps1`: one configuration, own JVM, logs,
  metadata and watchdog stacks. Existing result names cannot be overwritten.
- `.local/angus-pipelining/run-rate-sampling.ps1`: rotated baseline/off/on batches.
- `.local/angus-pipelining/prepare-rate-mtas.ps1`: owned real-server fixtures from
  already cached images; no image download or TLS-validation bypass.
- `.local/angus-pipelining/run-real-mta-rate.ps1` and `RealMtaTlsRate.java`:
  finite authenticated loopback comparisons and helper-leak checks.

From the main SJM checkout, use fresh names:

```powershell
& '.local/angus-pipelining/run-rate-sampling.ps1' -Group normal -Repetitions 10 -Name 'fresh-normal'
& '.local/angus-pipelining/run-rate-sampling.ps1' -Group repro -Repetitions 10 -Name 'fresh-repro'
& '.local/angus-pipelining/run-rate-sampling.ps1' -Group boundary -Repetitions 3 -Name 'fresh-boundary'

& '.local/angus-pipelining/prepare-rate-mtas.ps1'
# Use the new fixture.json path printed above, not the removed containers.
& '.local/angus-pipelining/run-real-mta-rate.ps1' -Manifest '<new fixture.json>' -Server postfix -Name 'postfix' -Trials 5 -Sends 10
& '.local/angus-pipelining/run-real-mta-rate.ps1' -Manifest '<new fixture.json>' -Server exim -Name 'exim' -Trials 5 -Sends 4
```

Preserved results under `.local/angus-pipelining`:

- `tls-rate/rate21-normal-10/{results,summary}.json`
- `tls-rate/rate21-repro-10/{results,summary}.json`
- `tls-rate/rate21-boundary-3/{results,summary}.json`
- `tls-valid/rate21-*`: individual compiled fixtures, stdout, TLS traces, result
  JSON and thread snapshots for persistent stalls.
- `mta/37a8930e/`: successful Postfix cases, initial Exim setup failures,
  `exim-certificate-fix/` rejection cases, `exim-four-send-connections/` passing
  cases, container metadata and server logs.

The batch JSON records provider class and fixture-source SHA-256 values. At
measurement time:

| Input | SHA-256 |
| --- | --- |
| Baseline SMTPTransport.class | `E5EBAE6D369D330AC77C8FF2F5A56A9DF31D90FB17A8E6350EC287D1E800466D` |
| Prototype SMTPTransport.class | `56CAE40857C13848C35F259C62EB5ECBFA3E9EF8CA19422ACE99E533AD20CD4E` |
| SMTPPipeliningTlsTest.java | `0151F1FE6E14050ADED482C855EB5E3334AC767EB72C445DF05E89C5CF1D4108` |
| RealMtaTlsRate.java | `163C7FD3CE0719535B2D4155BF3945BB594A67D8F66E213FB226C16DA9C41EF5` |

The canonical fixture gained optional recipient-count, address-length and
platform-buffer settings plus progress counters. Defaults preserve the previous
pressure setup. No runtime dependency or production source changed. Focused Maven
verification of `SMTPPipeliningTest`, `SMTPPipeliningTlsTest` and
`SMTPReplyReaderTest` passed on JDK 11, 17 and 21: 35 tests discovered, zero
failures/errors, seven optional tests skipped in each run. These ordinary Maven
runs are separate from the intentionally failing pressure measurements; see
`rate-focused-jdk*.log`. `git diff --check` found no whitespace errors; Git did
report the worktree's existing LF-to-CRLF normalization warnings for new files.
