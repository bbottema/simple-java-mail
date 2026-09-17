# Step 6 characterization: which DSN fields the provider can send

- Date: 15 September 2026
- Status at this checkpoint: Characterization complete; provider/scope decision required before public API implementation
- Tracking: [#736](https://github.com/bbottema/simple-java-mail/issues/736), In Progress, milestone 10.0.0, child of [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- Baseline: `codex/10.0.0` at `a4eda9e6`, after the accepted Phase 3 work
- Provider inspected and tested: Angus Mail 2.0.5, Jakarta Mail API 2.1.5 and SMTP Connection Pool 4.1.0
- Parent plan: [Complete the DSN envelope model](06-complete-dsn-envelope-model.md)

## Result

Follow-up on 15 September: the user approved the [ENVID-only implementation](06b-envid-implementation.md), leaving ORCPT and per-recipient NOTIFY deferred at that checkpoint. Their separately approved local implementation is now recorded in the [homogeneous configuration slices](06c-homogeneous-email-configuration.md). The findings below retain the original test-only evidence.

ENVID has a supported route through `SMTPMessage.setMailExtension(...)`. ORCPT and independently chosen per-recipient NOTIFY values do not have corresponding provider APIs. The original step's assumption that NOTIFY was already independently configurable per recipient was incorrect: the current setting is shared by every recipient in the message.

This triggers the parent plan's provider-extension decision. No production class, public API, website source, dependency or upstream checkout was changed. The new work is a runnable characterization suite and this implementation boundary.

## Supported paths and limitations

The source audit used the actual `angus-mail-2.0.5-sources.jar` matching the dependency, not an uncommitted upstream checkout. The equivalent tagged sources are [SMTPMessage](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPMessage.java) and [SMTPTransport](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java).

| Field | Angus 2.0.5 route | Observed boundary |
| --- | --- | --- |
| RET | `SMTPMessage.setReturnOption(...)` | Added to MAIL FROM only when DSN is advertised. |
| NOTIFY | `SMTPMessage.setNotifyOptions(...)` | One value added to every RCPT TO when DSN is advertised. |
| ENVID | `SMTPMessage.setMailExtension(...)` | Raw MAIL FROM suffix; the caller must check support and supply valid encoded text. |
| ORCPT | No dedicated option or recipient-extension callback | Putting it in the MAIL extension puts it on the wrong command. |
| Different NOTIFY values for different recipients | No corresponding message API | `rcptTo()` resolves one notify string before iterating the addresses. |

The [public extension-hook documentation](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPMessage.html#setMailExtension(java.lang.String)) explicitly leaves capability checks to the caller. Our wire tests confirm that an ENVID suffix is sent even when DSN is absent, and that raw `+` or `=` characters are not validated or encoded. This hook is sufficient for implementing ENVID deliberately, not for exposing arbitrary command text as a new public escape hatch.

## Existing Simple Java Mail behavior

- `DeliveryStatusNotification` holds one return option and one notification-option set. The Email builder, exact builder and governance paths already carry this value.
- `DeliveryEnvelope` keeps those options outside MIME content. `AngusMailTransportAdapter.AngusSmtpMessage` maps them onto the provider message facade.
- Both sync and async pooled sends preserve the current behavior. A following message without DSN options does not inherit the previous message's RET or NOTIFY.
- A Session-level `mail.smtp.mailextension` value applies again on each transaction. A fixed ENVID there is therefore not a per-attempt correlation solution.
- Exact EML keeps its supplied bytes, even when envelope recipients differ from visible headers. Duplicate envelope entries remain separate wire commands and separate receipt entries.
- `MailRecipientResult.getOriginalAddress()` is the input transport address, possibly with a display name. It is not an ORCPT field and must not be repurposed as one.

## Rules that affect the API design

[RFC 3461 section 4.2](https://www.rfc-editor.org/rfc/rfc3461.html#section-4.2) requires ORCPT to match RCPT TO for an initial submission. An arbitrary original-address override is therefore not the normal application-sending model; forwarding and gateway behavior would need a separate, explicit contract.

[ENVID](https://www.rfc-editor.org/rfc/rfc3461.html#section-4.4) identifies a transmission, not the MIME Message-ID. It needs xtext encoding and length validation. [RFC 6533](https://www.rfc-editor.org/rfc/rfc6533.txt) adds internationalized original-recipient forms; ASCII-only encoding must not be presented as complete support for that extension.

Notifications still arrive separately. This work does not receive them, guarantee their arrival, prove mailbox delivery, or retry a message automatically.

## Provider decision before implementation

`SMTPTransport.rcptTo()` owns the recipient iteration, commands, replies, partial-send classification and address arrays used for exceptions. Replacing it just to append ORCPT would also take ownership of those behaviors. Using a generic command hook to rewrite RCPT text would likewise introduce a new provider-specific policy beyond the accepted plan's supported-API path.

The existing `ManagedAngusTransport.sendCommand(...)` hook observes command boundaries for cancellation and receipt capture. It does not currently rewrite recipient commands. That observation hook is not evidence that recipient-option rewriting has been designed or approved, particularly for caller-owned Sessions and duplicate recipients.

Two directions remain for review:

1. **ENVID first:** add transaction identifiers using the supported message hook, including validation, same-connection DSN checks and an agreed unsupported-server policy. Retain existing message-wide NOTIFY/RET; keep ORCPT and recipient-specific notification choices explicitly deferred in the parent tracking. This can proceed without an Angus change once the smaller API is agreed.
2. **Full recipient metadata:** authorize a narrowly scoped provider extension or upstream change first. Specify how per-recipient parameters attach to the exact envelope entries, and how the normal provider retains reply handling, partial results and cleanup. Do not promise the full SJM surface while that prerequisite is missing.

Recommended next slice: ENVID first. This is a scope recommendation, not an implemented public API or a decision to close #736 with its original acceptance criteria unmet. No upstream issue or pull request was created.

## Tests and verification

[SmtpDsnCharacterizationTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpDsnCharacterizationTest.java) adds 13 loopback cases:

- Four combinations of DSN present/absent and sync/async sending, using two messages on one pooled connection.
- Two raw ENVID sends showing that MAIL extensions are not capability-gated by Angus.
- Four raw-value cases showing the absence of automatic xtext encoding or validation.
- One rejected attempt to put ORCPT on MAIL FROM, before any recipient or DATA command.
- One Session-level ENVID repeated across a sync/async pair on one pooled connection.
- One exact-EML send with duplicate envelope recipients and existing DSN options, checking content and receipt entries.

The peer reuses the existing `Conversation` fixture and asserts exact commands. All sockets are loopback-only and ephemeral; messages contain synthetic data. Socket reads, accept, futures, server shutdown and message/NOOP loops are bounded. No live SMTP, benchmarks or SpotBugs run.

The new suite passed on Java 11. The focused set, including existing DSN model/conversion, authentication/TLS and probe coverage, then passed **132 tests on Java 11 and 132 on Java 21**, with no failures, errors or skips. Logs: `tmp/phase4-dsn-characterization-java11.log`, `tmp/phase4-dsn-focused-java11.log`, and `tmp/phase4-dsn-focused-java21.log`.

The new Java class was audited against `CODING_STYLE_GUIDE.md`. `mvn license:remove` completed successfully; the test remains header-free. Whitespace checks and the changed Markdown files' relative-link checks passed.

This is test-only characterization, so it does not claim a new full-reactor, CLI, Spring matrix or website verification run. Those belong to the eventual production change. Nothing is committed or pushed; unrelated staged research, delivery artifacts and Journal work remain separate.
