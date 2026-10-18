# [Draft] Add opt-in SMTP envelope PIPELINING with a reply reader

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

A short-lived thread reads replies while the sending thread writes commands.
It does not call subclass hooks or change delivery state, and is stopped and
joined before DATA, BDAT, RSET or reuse. There is no new runtime dependency.

## Checks

The shared envelope tests pass on JDK 11, 17 and 21, including runs over STARTTLS.
They check the switches, grouping, DATA/BDAT boundaries, partial delivery,
exceptions/events, subclass callbacks, hostname-verifier rejection and cleanup.

On a reused connection with an effective 10-15 ms reply delay, median send times
fell from 31 to 15 ms for one recipient, 171 to 16 ms for ten, and 1,565 to 62 ms
for 100. This measures plain-SMTP envelope latency, not production throughput.

## Decisions before proceeding

The reader thread can still stall inside SSLSocket during TLS 1.3 key updates.
This is reproduced with valid commands under a specific combination of small
buffers and large replies, not TLS 1.3 generally. A configured write timeout
aborts the tested stall. A separate experiment recovered nine such stalls by
reconnecting once and sending sequentially, strictly before DATA/BDAT. That
recovery policy is not included here.

Both modes share command construction and reply handling, so this also refactors
the sequential implementation. I can leave its existing methods untouched and
put pipelining in a separate path if preferred, at the cost of some duplication.
CHUNKING optimization and release-readiness coverage remain separate.
