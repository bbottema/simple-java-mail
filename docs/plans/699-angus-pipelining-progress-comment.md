## Prototype progress

Thanks for confirming `master` and explicit opt-in for subclasses. I now have a local prototype of opt-in SMTP envelope PIPELINING. In the artificial-delay benchmark below, median send time dropped by about 51% for one recipient and 96% for 100 recipients, equivalent to roughly 2x and 25x speedups respectively.

Before opening the implementation PR, I'd like your views on the design choices below. Specifically Issues 2 and 3 block this PR from going ahead.

### What changed

- Changes mainly in `SMTPTransport` and a new package-private `SMTPReplyReader`. No new runtime dependencies; `sendMessage()` stays synchronous.
- `mail.smtp.pipelining` and `mail.smtps.pipelining` default to `false`. Pipelining requires a successful current EHLO advertising support, checked again after STARTTLS or reconnecting; otherwise sending stays sequential.
- Only `MAIL FROM` and `RCPT TO`, grouped at 32 commands or 32 KiB; oversized commands sent alone. DATA/BDAT wait for all envelope replies. CHUNKING optimization stays separate.
- One temporary reader thread per envelope exchange. Subclass callbacks and delivery state stay on the sending thread; the reader exits before DATA/BDAT, reset or reuse.
- Two proposed protected methods: `sendEnvelope()` sends and checks the envelope in either mode; `isPipeliningCompatible()` lets adapted subclasses opt in. Existing subclasses stay sequential; opted-in subclasses must handle deferred replies.
- The toggle does not fully isolate the legacy code path today. See [Issue 1: isolating the legacy path](#issue-1-isolating-the-legacy-path) for the option of leaving the original sequential envelope code untouched.

### Tests and early benchmark

Focused tests pass on JDK 11, 17 and 21. They cover grouping, waiting for envelope replies before DATA/BDAT, partial delivery, rejections, malformed replies, disconnects, timeouts, subclass compatibility and thread cleanup. Normal TLS exchanges pass with repeated key updates and connection reuse, including certificate and hostname verification. The pressure tests below expose an exception.

For additional regression testing, I also ran the separate 2,380-test application-level (Simple Java Mail) suite with pipelining off and on. The outcomes matched, with no additional failures from enabling pipelining. Both runs had two failures from tests expecting the old capability-cache behavior described below.

The latency benchmark uses a reused plain-SMTP connection, with an effective delay of 10–15 ms before replying to each command group. Median send times from five measurements after two warmups:

| Recipients | Sequential | Pipelined |
| --- | ---: | ---: |
| 1 | 31 ms | 15 ms |
| 10 | 171 ms | 16 ms |
| 100 | 1,565 ms | 62 ms |

The 100-recipient case sends 101 commands: one `MAIL FROM` and 100 `RCPT TO` commands. Sequential sending waits for each reply before continuing; the prototype sends four groups of up to 32 commands. Reducing those reply delays explains the drop from 1,565 ms to 62 ms in this benchmark.

### Issue 1: isolating the legacy path

Disabling pipelining preserves sequential command ordering, but the prototype shares refactored command-building and reply-handling code between both modes. This is an acceptable route provided the shared code is tested sufficiently to earn our trust. However, if complete isolation is a hard requirement, this is still possible.

I see two options:

1. Keep the shared implementation, with regression tests for both modes.
2. Separate the execution paths. Keep the original sequential envelope code unchanged and route only eligible pipelined sends through the new implementation. This limits changes to legacy code, but duplicates some logic and requires tests to keep both paths consistent.

**Please let me know which option you prefer.**

### Issue 2: stale EHLO capabilities

Angus can retain capabilities advertised before STARTTLS when EHLO fails afterward. That can leave it using outdated SIZE and AUTH information. PIPELINING must not trust those stale capabilities, so this bug blocks its PR.

I'll open a separate issue and PR to fix the cache first. Clearing it also affects SIZE handling and whether AUTH is attempted even with pipelining disabled, so that behavior needs its own review. Fixing the shared cache is my preference; the PIPELINING PR will wait for a decision there.

If that fix needs more time, I can offer a separate workaround here: a boolean recording whether the latest EHLO succeeded and advertised PIPELINING, cleared on connection and negotiation changes. It would prevent use of stale PIPELINING information without changing the existing cache, but leave the SIZE/AUTH problem unresolved. This is a rather ugly workaround involving extra state management, but it remains an option.

### Issue 3: TLS, which direction?

The new reader thread is meant to keep receiving replies while Angus sends commands. TLS 1.3 complicates this: reading can itself require a write to update encryption keys. If that write blocks while the server is blocked sending its reply, neither side can continue reading.

I first reproduced this with an invalid 4 MiB MAIL command, then confirmed it with valid commands and legal multiline replies over STARTTLS on JDK 11, 17 and 21. Repeated tests on JDK 21 give a clearer picture:

- Ordinary configurations, including Postfix and Exim over STARTTLS and implicit TLS, completed 740 pipelined messages across 50 fresh connections without a stall, despite frequent key updates.
- With 512-byte buffers, 100 long recipient addresses and a 64 KiB MAIL reply, 6 of 10 pipelined connection trials stalled. Both sequential controls passed all ten. A 32 KiB reply variation stalled in all three pipelined trials; larger-buffer variations passed.

This points to a very specific combination of buffer pressure, envelope size, server replies and key-update timing, not TLS 1.3 generally. It leaves sequential fallback for affected connections viable as a direction to investigate.

I see three choices:

1. **Fail the send.** Use a write timeout or envelope deadline to close stalled connections and report failure, even for valid sends that would succeed sequentially.
2. **Degrade to sequential sending.** Close the stalled connection and retry once on a new connection with pipelining disabled, only if no DATA/BDAT was sent. Other connections can keep using pipelining. Authentication, caller-supplied sockets, callbacks and cancellation need investigation; the deadlocked connection cannot be reused.
3. **Change TLS I/O.** Investigate `SSLEngine` or another design that keeps reading while writes are pending. This larger change needs its own prototype.

A timeout cannot reliably identify this particular cause, so fallback must also be safe for other pre-content stalls. I favor investigating option 2 first: recover affected connections without disabling pipelining for TLS 1.3 as a whole. TLS I/O changes remain an alternative; no recovery policy is implemented or tested yet. Which direction would you prefer?
