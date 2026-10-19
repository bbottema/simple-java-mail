# Mail-send progress: supported provider hooks

This research supports [ADR 0032](../adr/0032-live-mail-send-progress.md) and
[ADR 0033](../adr/0033-detailed-mail-send-progress.md), implemented under
[#760](https://github.com/bbottema/simple-java-mail/issues/760) and
[#761](https://github.com/bbottema/simple-java-mail/issues/761).
The source baseline is Angus 2.0.5, inspected in October 2026. Recheck these hooks when changing providers or upgrading Angus.
Current ownership, retirement rules and regression landmarks live in the [progress concurrency guide](../concurrency/12-live-send-progress.md).

## Source-grounded hook boundaries

The pinned [Angus SMTPTransport](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java)
exposes protected greeting-read, EHLO/HELO, STARTTLS, envelope, DATA/BDAT and command hooks. Its private authentication implementation still enters
observable command paths. SJM recognizes only the AUTH prefix; it does not decode payloads, challenges or credentials.

[SocketFetcher](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java) combines address
resolution, connect, proxy and TLS work. Resolution can precede `Socket.connect`; factory return is not proof of a completed verified handshake.
Socket tracking therefore cannot honestly separate all those phases. SJM delegates protocol work unchanged and makes no extra network requests.

| Detail | Supported observation | Boundary |
| --- | --- | --- |
| DNS / TCP / proxy | Inclusive acquisition | Precise independent phases need supported upstream hooks; bridge startup is not remote proxy negotiation. |
| Greeting | First physical-setup response read | No historical replay for a later borrower. |
| EHLO / HELO | Existing protected methods | Categories only, without response text. |
| STARTTLS | Existing upgrade, handshake and verification | Implicit TLS remains in acquisition where no isolated hook exists. |
| Authentication | AUTH prefix on String and byte-array command paths | No payloads, mechanism transcript or private-method interception. |
| Sender / recipients | Actual MAIL FROM and RCPT command paths | No addresses or per-recipient audit history. |
| DATA permission | Actual DATA permission exchange | Not a BDAT stage. |
| Upload / final reply | Existing content-stream and final-read hooks | Input counts exclude framing, dot escaping and TLS; intermediate chunk replies are not final acceptance. |

| Sending path | Available detail |
| --- | --- |
| New managed Angus connection caused by this email | Supported setup categories, followed by current submission/upload work. |
| Reused managed connection | Current submission/upload only; no synthetic setup replay. |
| Background-created connection | No setup attributed to its later borrower. |
| Caller-owned ordinary Angus transport | Coarse progress; its transport is not replaced. |
| Third-party provider | Whatever optional setup/submission hooks it supports, with coarse fallback elsewhere. |
| CustomMailer | Opaque `SUBMITTING` callback. |
| Shared batch/open-connection setup | Outside individual-email progress; current per-email submission detail remains available. |

The existing pool callback supplies physical transport identity. SJM's internal bridge forwards it without requiring a progress-specific upstream API.
Generation-scoped setup/submission bindings retire reporting authority on cancellation or completion and detach provider references in cleanup.
This is distinct from logical acquisition/recovery evidence.

## Remaining research and related work

Isolated DNS, TCP, remote proxy negotiation and implicit TLS need separately agreed supported hooks with privacy, attribution and generation
contracts. They remain under coarse acquisition; their research is not a prerequisite for the implemented stages. No reflection, debug-log scraping,
socket replacement or extra lookup/probe is an acceptable substitute.

[#699](https://github.com/bbottema/simple-java-mail/issues/699) owns separate PIPELINING/CHUNKING work. Categories must not imply a strict alternating
command/reply sequence, and BDAT intermediate replies must not become final acceptance. The pinned Angus 2.0.5 control recorded dot escaping on BDAT;
observed and unobserved streams matched, and progress counts input before that transformation. Preserve that control when investigating provider
changes; do not quietly change protocol behavior as part of observation.

The optional [OpenTelemetry integration](../adr/0034-optional-opentelemetry-integration.md), implemented under
[#762](https://github.com/bbottema/simple-java-mail/issues/762), observes authoritative operation boundaries.
Lossy progress cannot be the authoritative source for span completion or send counts.

The [observer demo](../../modules/simple-java-mail/src/test/java/demo/MailSendObserverDemoApp.java),
[memory runner](../../tools/memory/README.md) and [progress concurrency guide](../concurrency/12-live-send-progress.md) provide maintained usage,
procedures and regression coverage. The separate [memory-pressure measurements](mail-send-memory-pressure-results.md) retain the measured
profiles, controls and limitations; individual implementation test-run diaries remain in Git history.
