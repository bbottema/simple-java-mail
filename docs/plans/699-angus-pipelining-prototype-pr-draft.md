# [Draft] Add opt-in SMTP envelope PIPELINING with a reply reader

Related to #213. This is the smaller, stream-based implementation for discussion,
not a ready-to-merge change. The separate SSLEngine draft explores the alternative.

SMTP currently waits for each MAIL FROM and RCPT TO reply before sending the next
command. With many recipients, those waits can dominate sending time. This draft
allows those commands to travel in groups when the server supports PIPELINING.

## What changes

- `mail.smtp.pipelining` / `mail.smtps.pipelining`, disabled by default.
- Groups of up to 32 commands or 32 KiB, with a reply-reading thread during the
  envelope. The sending thread keeps ownership of callbacks and delivery state.
- All envelope replies are processed before DATA or BDAT. BDAT chunks remain
  sequential.
- Existing subclasses stay sequential unless they opt in. No new dependency.

Focused tests pass on JDK 11, 17 and 21 and cover reply ordering, partial delivery,
rejections, subclass behavior, TLS verification and cleanup. A separate
2,380-test consumer-suite comparison produced matching outcomes with pipelining
off and on; shared capability-cache differences remain, so this is not a claim
that every test passed.

On a reused connection with an effective 10-15 ms reply delay, median send times
fell from 31 to 15 ms for one recipient, 171 to 16 ms for ten, and 1,565 to 62 ms
for 100. This is a small latency benchmark, not a production-throughput estimate.

## Decisions before proceeding

The reader thread can still stall inside SSLSocket during TLS 1.3 key updates.
This is reproduced with valid commands under a specific combination of small
buffers and large replies, not TLS 1.3 generally. A configured write timeout
aborts the tested stall. A separate experiment recovered nine such stalls by
reconnecting once and sending sequentially, strictly before DATA/BDAT. That
recovery policy is not included here.

Two other boundaries need agreement: the current draft shares envelope-building
and reply-handling code with the sequential path; I can leave the original path
untouched instead. The working diff also clears stale EHLO capabilities. That
fix affects SIZE/AUTH with pipelining disabled and needs its own issue and PR
before this contribution proceeds.

I propose choosing between this smaller change with an agreed failure/recovery
policy and the SSLEngine direction before expanding either to release readiness.

Developed with AI assistance; implementation and test results reviewed locally.
