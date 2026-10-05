# ADR 0028: Per-email recipient rejection handling

- Status: Accepted
- Decision date: 2026-10-05
- Target: 10.0.0
- Implementation: Planned, not implemented
- Tracking: [#754: Choose whether to continue sending after a recipient rejection](https://github.com/bbottema/simple-java-mail/issues/754)

## Context and decision drivers

An email may have three recipients while the SMTP server rejects just one. Sometimes the application wants to stop that submission;
sometimes it wants the message submitted to the remaining recipients. Users should be able to choose without knowing a provider property name.

Simple Java Mail already exposes partial acceptance, recipient replies and conservative retry guidance through submission receipts.
The existing Angus route can exercise both behaviors through `mail.smtp.sendpartial`, but the normal Email API does not express that intent.
This addition connects the existing result model to a straightforward input choice; it does not implement a new SMTP extension.

The decision needs to balance the following:

- Express the choice using the same Email builder used for other message and envelope settings.
- Retain the distinction between an unset choice and an explicit `false`, so defaults, overrides and legacy configuration remain meaningful.
- Preserve existing exceptions, receipt facts and observer semantics when submission is only partially accepted.
- Keep one message and one submission, rather than implicitly splitting recipients or retrying.
- Preserve exact/protected bytes and isolation across reused or concurrent pooled sends.
- Honor the choice at provider boundaries or reject it clearly instead of exposing a setting that silently does nothing.

The [original cross-language comparison](../research/simple-java-mail-world-class-smtp-research.pdf) supplies the product context.
The [Angus capability-parity research](../research/ANGUS_CAPABILITY_PARITY_RESEARCH.md#4-per-message-recipient-acceptance-policy)
identifies this particular facade opportunity. Its earlier enum proposal is not the chosen API.

## Decision

### Keep the choice on Email

The planned composed and exact Email builder API is:

```java
.withSendingToAcceptedRecipients(true)
.withSendingToAcceptedRecipients(false)
.clearSendingToAcceptedRecipients()
```

Expose `@Nullable Boolean getSendingToAcceptedRecipients()` on Email and the builders:

| Value | Meaning |
| --- | --- |
| `true` | Permit submission to accepted recipients when other recipients are rejected, provided the SMTP transaction can safely continue. |
| `false` | Do not transmit message content after a known recipient rejection. |
| `null` | No local choice; retain the existing defaults and advanced-provider behavior. |

Calling the setter again replaces the local value. Clearing removes it and permits fallback; clearing is not an explicit disable.
Preserve the three states through builder reuse, copying and serialization. Older serialized Emails have an unset choice.

This controls one submission as a whole. Do not add a recipient/group setting, a feature-specific Mailer setter, an enum or a generic policy framework.
Reuse `withEmailDefaults(...)`, `withEmailOverrides(...)` and their existing suppression rules. For composed messages, this is a single-value
Email property: the applicable override, submitted value and default retain their established precedence. Explicit `false` must not be mistaken for absence.

The choice belongs to transport-only metadata at the provider boundary, not a MIME header. Exact Emails can carry it explicitly without
applying ordinary Email templates or rebuilding their authoritative bytes. Applicable factory locks retain their separate existing contract.

### Support the normal configuration routes

The planned ordinary property is:

```properties
simplejavamail.defaults.sendtoacceptedrecipients=true
```

Register it as Boolean, non-secret and grouped under `EMAIL_DEFAULTS`. Use the existing snapshot loader, Email governance,
Spring IDE-metadata model, generated CLI/help and configuration diagnostics. There is no second runtime configuration route.

Provide the corresponding locked property through the existing locking mechanism:

```properties
simplejavamail.locked.defaults.sendtoacceptedrecipients=false
```

A lock supplies the effective value, permits an equal explicit value and rejects a conflicting one. Clearing, suppression and template
replacement cannot remove it. Exact-message envelope choices can honor either value without changing content; incompatible provider
or advanced configuration must be rejected rather than bypassing the lock. Neither property shown here is implemented yet.

### Preserve partial-failure completion

Permission to continue for some recipients does not mean every requested recipient succeeded. Partial acceptance still throws
`MailSubmissionException`, or completes an asynchronous send exceptionally, retaining the existing partial receipt and recipient retry guidance.
The observer reports an unsuccessful attempt and receives those same submission facts. Do not change the result status or normalize partial acceptance
to successful completion merely because the application opted into continuation.

If all recipients are rejected, transmit no content. Malformed-address validation, connection failures and protocol errors retain their existing meaning.
A broken connection is not made usable by this flag. Batch first-failure stopping remains unchanged, including when the failing email was partially accepted.

### Illustrate the difference with three recipients

The following is planned usage, not a compiling example of the current API. Assume `mail` is the application's configured factory and `mailer` is its Mailer:

```java
Email email = mail.emailBuilder().startingBlank()
    .from("sender@example.org")
    .to("alice@example.org")
    .to("bob@example.org")
    .to("carol@example.org")
    .withSubject("Service notification")
    .withPlainText("The service window has changed.")
    .withSendingToAcceptedRecipients(true)
    .buildEmail();

try {
    mailer.sync().sendMail(email);
} catch (MailSubmissionException failure) {
    MailSubmissionReceipt receipt = failure.getSubmissionReceipt();
    // Inspect receipt.getStatus() and receipt.getRecipientResults() before deciding what to retry.
}
```

Suppose Alice and Bob receive positive RCPT replies and Carol receives a permanent rejection. For the continuation example, assume
the server also accepts the subsequently transmitted content:

| Configured choice | Message content | Completion and receipt |
| --- | --- | --- |
| `false` | Not transmitted to any recipient. SMTP envelope commands may already have been issued. | Failure with `REJECTED`; Alice and Bob are unsent, and Carol is rejected. |
| `true` | Submitted for Alice and Bob, without changing the message headers. | Failure with `PARTIALLY_ACCEPTED`; Alice and Bob are accepted for submission, and Carol is rejected. |

An abbreviated illustration of the second receipt is:

```text
status: PARTIALLY_ACCEPTED
alice@example.org: accepted for submission
bob@example.org: accepted for submission
carol@example.org: permanently rejected
operation: failed (MailSubmissionException)
```

This is explanatory output, not a new receipt renderer or an output format applications must parse. A positive RCPT reply alone is not
evidence that the content was accepted. If the final reply is lost, the existing uncertainty and duplicate-risk rules still apply.

## Provider and compatibility boundaries

With no new choice or advanced partial-sending configuration, preserve today's default behavior. When the resolved new choice is absent,
retain legacy Session/provider options, including an advanced `sendpartial=true`. An explicit new choice must be honored or rejected clearly before submission.

### Angus false-value limitation

The inspected Angus 2.0.5 `SMTPTransport.rcptTo()` first reads `SMTPMessage.getSendPartial()` and, when it is false, reads the Session's
`mail.<protocol>.sendpartial` property. Consequently, a per-message false value cannot turn off a Session-wide true value.
The [SMTP property documentation](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html)
and [SMTPMessage API](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPMessage.html#setSendPartial(boolean))
describe the existing provider controls; the fallback limitation was verified by source inspection, not by running a new test in this documentation pass.

Reject an explicit or locked false choice that conflicts with Session-wide `mail.smtp.sendpartial=true` or `mail.smtps.sendpartial=true`
before MAIL FROM. Explain that the advanced property would let Angus continue after a recipient rejection despite the Email's choice.
Tell the user which property to remove from `withProperty(...)`, extra-properties configuration or the caller-owned Session, and how to
express the reusable choice through Email defaults instead. Do not report that nothing was submitted unless the rejection really occurred before submission.

Do not temporarily mutate shared Session properties, copy Angus's recipient loop or add another SMTP state machine. Use the existing
adapter and per-attempt message facade for supported choices. Preserve relevant original provider options when the new choice is absent.

### Third-party providers and CustomMailer

Third-party adapters must explicitly support a requested choice or reject it before submission. An older adapter accepting unrelated envelope
options must not silently ignore this new requirement. Keep compatibility rejection distinct from a damaged connection so a healthy lease can be reused.

`CustomMailer` receives the choice through Email and owns honoring it, consistent with its existing envelope-option responsibilities.
Simple Java Mail cannot verify what an opaque callback actually submits and must not manufacture recipient acceptance facts. Applicable locked choices
remain subject to the existing provider/CustomMailer locking boundary; this feature does not relax it.

### Submission is not atomic delivery

Requiring all recipients means withholding content after a known recipient rejection. It cannot retract a submission, prevent later bounces,
guarantee mailbox delivery or make SMTP transactional across mailboxes. Do not split emails, retry automatically, silently discard malformed addresses,
remove recipients from visible headers, weaken DSN/TLS/content requirements or rewrite exact/signed/encrypted bytes.

## Alternatives considered

| Alternative | Benefit | Reason not selected |
| --- | --- | --- |
| Raw provider properties only | No new facade API. | Requires provider knowledge and cannot naturally express different choices on individual Emails using existing templates. |
| A recipient-acceptance enum | Names both behaviors explicitly. | Adds a concept for a binary choice already clearly expressed by a Boolean setter; absence is represented separately by `null`. |
| Two named setters | Avoids a Boolean argument. | Adds methods for one setting; the chosen setter expresses both values and follows existing Boolean builder patterns. |
| Successful completion with a partial receipt | Lets an application treat useful partial progress as success. | Changes the established failure/observer contract and makes rejected recipients easier to overlook. |
| Recipient/group-level choices | Could appear more granular. | This governs one shared submission; honoring different choices would require a separate message-splitting design. |
| Automatic splitting or retrying | Could hide rejected-recipient handling from callers. | Changes submission/content semantics and risks duplicate mail; application policy remains responsible for retries. |
| Temporarily change the Session property | Could override Angus's false-value limitation. | Mutates shared configuration and can leak behavior between sends or callers; reject the unsupported conflict instead. |

## Consequences

Users can express a recognizable sending choice through the ordinary Email API and inspect the existing receipts afterward. Defaults,
overrides, locks, properties and CLI use the same choice without a second Mailer configuration model.

The cost is explicit propagation and capability handling. Nullable state must survive governance, copies and serialized forms; provider
support cannot be inferred from a generic envelope capability. Existing raw properties remain useful for advanced callers, but conflicting
explicit choices are rejected rather than silently ignored.

Partial sends still require application handling. The sender can observe both a failed operation and useful SMTP acceptance; retrying the
whole Email without inspecting the receipt may duplicate mail. Nothing in this decision adds delivery guarantees or automatic recovery.

## Related decisions

| Record | Relationship |
| --- | --- |
| [0001: Email configuration scopes](0001-email-configuration-scopes-and-inheritance.md) | One submission-wide choice belongs on Email, not each recipient or a duplicate Mailer API. |
| [0002: Email defaults and overrides](0002-email-defaults-and-overrides.md) | Reuse single-value precedence, suppression and clearing; preserve explicit false separately from absence. |
| [0007: Provider-neutral MIME boundary](0007-provider-neutral-mime-boundary.md) | Carry the choice outside MIME and let the adapter honor or reject it without taking over transport lifecycle. |
| [0011: Submission outcomes](0011-transport-neutral-submission-outcomes.md) | Keep receipt, uncertainty and conservative retry meanings unchanged. |
| [0012: Terminal observation](0012-terminal-send-observation.md) | Partial acceptance retains unsuccessful completion and the existing terminal callback contract. |
| [0013: Exact EML submission](0013-exact-eml-submission.md) | Explicit envelope metadata must not rewrite bytes or enable ordinary template governance. |
| [0025: Locked configuration](0025-factory-scoped-locked-configuration.md) | Apply compatible locked choices at their existing owner; reject conflicts without a separate policy layer. |

Related implementation foundations are [#710](https://github.com/bbottema/simple-java-mail/issues/710) and
[#723](https://github.com/bbottema/simple-java-mail/issues/723). This is separate follow-up work under #754; it does not reopen those issues or #722.

## Implementation obligations and verification

Follow the [API expansion workflow](../API_EXPANSION_WORKFLOW.md) and [coding guide](../CODING_STYLE_GUIDE.md). Future implementation must cover:

- Unset/true/false, repeated setters, clearing, defaults/overrides, suppression, builder reuse, copying and old/new serialization.
- Locked true/false values, equal/conflicting customization, replacement templates, exact envelopes and selected configuration ownership.
- Mixed and entirely rejected recipients, temporary/permanent rejection, actual content-transmission boundaries and lost final replies.
- Exact receipt/exception identity, observer failure semantics and conservative retry targets; no new success normalization for partial acceptance.
- Both execution modes, reused/concurrent pooled transports, lazy batches, first-failure stopping and open connections without choice or fact leakage.
- Raw SMTP/SMTPS conflicts, caller-owned Sessions, explicit third-party capability support and CustomMailer responsibilities.
- Byte-identical exact/protected content, actual envelope overrides and duplicate recipient occurrences without extra serialization for this choice.
- Property precedence/isolation, diagnostics, Spring metadata, generated CLI/help and classpath/JPMS consumers.
- Clear public Javadocs and current website examples for both behaviors. Keep the interface contract authoritative and implementation Javadocs linked to it.

Record implementation verification separately. This pass records the accepted decision and checks documentation links and tracking only;
it does not run runtime tests or implement any of the planned APIs. Add the eventual feature to release history under Enhancements.
Migration notes describe only genuine changed behavior against released versions, not this additive API or intermediate unreleased implementations.
