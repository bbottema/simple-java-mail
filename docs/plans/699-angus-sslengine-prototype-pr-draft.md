# [Draft] Add opt-in SMTP envelope PIPELINING with SSLEngine

Related to #213. This is a working prototype for discussion, not a ready-to-merge
change.

SMTP currently waits for each MAIL FROM and RCPT TO reply before sending the next
command. With many recipients, those waits can dominate sending time. This draft
allows those commands to travel in groups when the server supports PIPELINING.

## Behavior

- Set `mail.smtp.pipelining=true` or `mail.smtps.pipelining=true` to enable it;
  both default to `false` for sequential sending. Set these before creating the Transport.
- Require fresh EHLO support, including after reconnect and STARTTLS. Missing
  support or HELO fallback means sequential sending.
- Group MAIL FROM and RCPT TO only, up to 32 commands or 32 KiB. An oversized
  command travels alone. Process every envelope reply before DATA or BDAT;
  BDAT chunks remain sequential.
- Keep synchronous `sendMessage`, envelope parameters, partial-recipient results,
  `sendpartial`, `reportsuccess`, exceptions and listener events.
- Existing subclasses remain sequential unless they opt in through
  `isPipeliningCompatible()`. `sendEnvelope()` provides common preparation;
  command/reply callbacks stay on the sending thread, with deferred replies.
- Validate customized commands, drain before resetting healthy connections,
  close broken connections and never automatically replay a message.

## Implementation

Under specific backpressure conditions, SSLSocket can stall when a TLS 1.3 key
update needs to write while output is blocked. This uses Java's SSLEngine with
a nonblocking channel, so incoming replies can still be processed.

One calling thread owns the connection and SMTP state. There is no separate SMTP
reader thread to coordinate or join. Delegated TLS tasks use a worker. This is
a larger change to Angus's I/O handling, but a simpler SMTP concurrency model,
not a new TLS or cryptographic implementation. There is no new runtime dependency.

The default TLS context and stock MailSSLSocketFactory work directly. Custom
TLS factories retain sequential sending unless `mail.smtp.ssl.engineFactory`
(or `mail.smtps.ssl.engineFactory`) supplies a matching engine. Sockets without
a channel, including SOCKS and caller-supplied sockets, also remain sequential.
Existing factories and trust settings are never silently replaced. Disabling
pipelining retains the original socket implementation.

## Checks

The shared envelope tests pass on JDK 11, 17 and 21, including runs over STARTTLS.
They check the switches, grouping, DATA/BDAT boundaries, partial delivery,
exceptions/events, subclass callbacks, hostname-verifier rejection and cleanup.
Additional tests check TLS settings, the engine hook and legacy-factory fallback.
The actual SMTPTransport completed all eighteen pressure test runs across those
JDKs without stalls or reconnects; thirty negative cases failed as expected.
These results cover the tested conditions, not every possible TLS exchange.

On a reused connection with an effective 10-15 ms reply delay, median send times
fell from 31 to 15 ms for one recipient, 170 to 15 ms for ten, and 1,564 to 62 ms
for 100. This measures plain-SMTP envelope latency, not production throughput.

## Decisions before proceeding

I propose reviewing whether the broader I/O change is worth pursuing. Reusing
TLS Channel or Netty remains an option, but neither has been tested here.

Both modes share command construction and reply handling, so this also refactors
the sequential implementation. I can leave its existing methods untouched and
put pipelining in a separate path if preferred, at the cost of some duplication.
CHUNKING optimization and release-readiness coverage remain separate.
