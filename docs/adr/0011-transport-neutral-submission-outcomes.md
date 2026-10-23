# ADR 0011: Transport-neutral submission outcomes and conservative retry guidance

- Status: Accepted; retrospective record of implemented behavior
- Decision recorded: 2026-09-16; the source dates below are the historical evidence
- Applies to: 10.0.0, including unreleased recipient results and retry guidance
- Implementation baseline: `codex/10.0.0` at `a4eda9e6`; concurrent ENVID work is excluded from this decision
- Attachment I/O addendum: Accepted 21 October 2026; implemented and reviewed for unreleased 10.0.0 under [#821](https://github.com/bbottema/simple-java-mail/issues/821).

## Provider failure facts (accepted 22 October 2026)

The basic transport-result failure factory may infer negative SMTP evidence only from an actual 4xx/5xx reply. Intermediate, missing and unusable response codes do not establish rejection; preserve unknown acceptance and conservative retry advice unless independently known recipient facts establish a more specific result. Keep captured raw responses available for inspection. Adapters that know the final response was lost continue to use the explicit unknown-acceptance factory. [#831](https://github.com/bbottema/simple-java-mail/issues/831) tracks this correction.

When deriving recipient groups from a Jakarta Mail failure, search the complete standard next-exception chain with identity-based cycle protection, preserving the original outer failure and snapshot ownership. Additional wrappers must not discard known recipient facts. Ordinary Java cause traversal remains a separate boundary. [#832](https://github.com/bbottema/simple-java-mail/issues/832) tracks this correction.

## Managed key-material reads (accepted 22 October 2026)

Apply the owned-operation exception idiom to complete DKIM, S/MIME and PKCS12 content reads used to construct configuration values. Known opening, reading and closing I/O failures use MailException with failure kind PREPARATION, preserving the original cause and suppression chain. Close streams opened from paths/files; leave supplied streams open for their owner. Preserve argument errors and arbitrary application runtime exceptions, and leave cryptographic validation at its existing feature boundary. [#834](https://github.com/bbottema/simple-java-mail/issues/834) tracks this application of the existing rule.

## Context

Completing a send call answers whether the library's operation succeeded, but does not tell the application what the SMTP server accepted. A message may be accepted for some recipients, rejected for others, or accepted without the client ever receiving the final reply. Retrying every exception as a wholly unsent message can duplicate mail.

Applications also need server response details without acquiring the Transport that the library or pool owns. Earlier receipts exposed Angus's last response, but a nullable response alone could not express provider-neutral acceptance, partial failures, or uncertain submission. A pooled transport's last response is particularly dangerous if it belongs to the preceding borrower.

## Decision

Represent submission knowledge explicitly at the provider boundary and preserve it in the public API. `MailTransportAdapter` returns `MailTransportResult`; `TransportRunner` translates it into a `MailSubmissionReceipt`, or a `MailSubmissionException` retaining the receipt and original Jakarta Mail failure. Ordinary, asynchronous, pooled, batch and open-connection sends share this translation.

The transaction status describes knowledge of submission, independently of final delivery:

| Status | Meaning |
| --- | --- |
| `ACCEPTED` | All submitted recipients are known accepted for submission. |
| `PARTIALLY_ACCEPTED` | At least one recipient was accepted, but the attempt did not complete cleanly for every recipient. |
| `REJECTED` | The transport reports no recipient accepted, based on known unsent/invalid recipients or rejection facts. An explicit negative SMTP reply is not required. |
| `UNKNOWN` | Available facts cannot establish acceptance. |

Keep recipient facts as immutable, ordered envelope occurrences. Separate the RCPT command's reply from the recipient's final submission disposition. A positive RCPT reply is not evidence that DATA was accepted. Preserve accepted, valid-unsent and invalid recipient getters as compatibility views, while exposing richer facts through `MailRecipientResult`.

Derive conservative `MailRetryDisposition` guidance from structured replies and recipient facts. Only the safe-to-retry dispositions expose retry candidates. Exclude permanent recipient rejection, prevent advice to resend a permanently rejected transaction unchanged, and flag duplicate risk when acceptance is ambiguous or the same mailbox occurs in both accepted and retryable positions. This API does not retry, choose backoff or promise idempotency.

Provider observations belong to one attempt. Copy mutable provider data before returning it, snapshot replies before releasing the transport, and do not attach an unchanged response from a previous pooled send. When the final DATA reply is lost, discard speculative unsent claims while retaining earlier recipient rejections that are actually known.

[ADR 0028](0028-per-email-recipient-rejection-handling.md) records the input choice for continuing after a recipient rejection under #754.
It preserves this result contract: partial acceptance still produces failed completion with the existing receipt and retry guidance,
even when the application permits submission to accepted recipients. It does not equate a positive RCPT reply with final content acceptance.

## Recorded rationale and evidence

- In [#336's working workaround](https://github.com/bbottema/simple-java-mail/issues/336#issuecomment-920992474), the application captured Jakarta Mail debug output to obtain server feedback. This establishes the historical need; it does not establish a portable server queue-ID format.
- [#654](https://github.com/bbottema/simple-java-mail/issues/654) explicitly chose a high-level submission receipt instead of exposing Transport ownership or calling the result delivery. [Commit `ed49cccd`](https://github.com/bbottema/simple-java-mail/commit/ed49cccddc1e85f126be0df680b510e5bc0277b2), 2026-07-11, introduced receipts and Angus response capture.
- [#710](https://github.com/bbottema/simple-java-mail/issues/710) records why the nullable Angus response was too narrow: future adapters, partial recipient groups, original failure causes and ambiguous acceptance all need one stable contract. [Commit `207a7ece`](https://github.com/bbottema/simple-java-mail/commit/207a7ece137705e1bb4a8dea29a5983f241177ea), 2026-08-26, implemented that result model.
- [The final-reply correction](https://github.com/bbottema/simple-java-mail/issues/710#issuecomment-5567257523) explicitly states that a lost final DATA reply is `UNKNOWN`, not rejection. [Commit `97b986f1`](https://github.com/bbottema/simple-java-mail/commit/97b986f1b921eff8744149bbbde7b364f2aa32e9), 2026-09-07, implements the fault boundary.
- [#723](https://github.com/bbottema/simple-java-mail/issues/723) records the retry-safety motivation and the separate Angus success-reporting exception problem. [Commit `f79c66c6`](https://github.com/bbottema/simple-java-mail/commit/f79c66c667e8c02a54b9804848cbb9f3fc59ae7d), 2026-09-09, preserves recipient replies and retry guidance. Its message explicitly records immutable facts, historical receipt compatibility and genuine receipts from implementations. The issue's still-open state and older statement that changes are uncommitted do not override this checked-in evidence.

The public/private result split and the precise retry rules above are also verified in the [result implementation](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/MailTransportResult.java), [receipt](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSubmissionReceipt.java), [recipient result](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailRecipientResult.java), [retry dispositions](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailRetryDisposition.java), [Angus normalization](../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusSubmissionResult.java) and [translation/cleanup boundary](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportRunner.java).

## Alternatives and consequences

The following comparisons are retrospective analysis, except where the linked issues explicitly discuss the alternative.

- **Return only success/failure or the final response:** smaller surface, but insufficient for partial acceptance, reply loss and providers that cannot expose a response. Call completion and server acceptance must remain different facts.
- **Expose Jakarta Mail exceptions and listeners directly:** retains provider details but requires applications to understand provider chains, pooled ownership and success-reporting exceptions. The chosen model retains the original cause while supplying a stable interpretation.
- **Treat every failed DATA read as rejection:** gives a convenient retry policy at the cost of duplicate mail. Unknown is a necessary result, not a missing implementation branch.
- **Parse response prose to infer delivery or a universal queue ID:** provider-specific text is not a portable acceptance contract. Primary reply codes and typed exception structures determine classification; optional enhanced codes enrich the observed response.
- **Retry inside the library:** would require application-specific duplicate protection, recipient selection and policy. Exposing facts and conservative guidance keeps those choices with the caller.

The cost is a richer result model and provider-specific normalization. The benefit is that callers can distinguish a failed library operation, known SMTP acceptance and absent information without taking ownership of the transport.

## Implementation boundaries and current limits

[ADR 0030](0030-safe-bounded-mail-send-retries.md) records the opt-in retry extension.
It requires temporary failure as well as safe full-envelope replay; retry guidance alone does not trigger a resend. The result meanings
and the prohibition on replay after confirmed, partial or uncertain acceptance remain unchanged.

The additive SIZE facts introduced by [#748](https://github.com/bbottema/simple-java-mail/issues/748) follow the same evidence rule. `getMessageSize()` reports a completed prepared-content count, not successfully transmitted bytes; `getServerMaximumMessageSize()` reports the reliable positive maximum on the selected connection. Unknown values remain `null`. A local oversized rejection can therefore retain both values without an SMTP reply, consumed ENVID or used REQUIRETLS. Immutable result enrichments and receipt serialization preserve these fields; non-reporting adapters leave them absent. A separate declared-SIZE field was deliberately omitted: an advanced caller estimate is not the measured content size and adds little to the ordinary diagnosis.

[ADR 0031](0031-public-api-and-internal-construction.md) makes the receipt a core read contract with main-module internal construction.
It deliberately supersedes historical concrete-report serialization compatibility; it does not change acceptance, recipient facts or retry guidance.

Angus success-reporting mode uses exceptions even after successful submission. Its adapter enables recipient reporting only during the exclusively owned send, restores the previous Transport setting in `finally`, and does not mutate shared Session properties. Normalization to success requires a real final 2xx reply, all recipients accepted and no rejection. Other exceptions retain their original failure meaning.

Missing provider replies remain absent. EOF or return code zero is not a real SMTP rejection, and a provider can lose its RCPT exception chain during an I/O failure. Do not reconstruct missing recipient replies from the connection's last transaction response. Custom mailers and logging-only mode can complete successfully with `UNKNOWN` because this library has not observed their SMTP acceptance. Third-party adapters can supply fewer facts than Angus.

Cleanup is separate from SMTP knowledge. A failed submission normally invalidates the pooled transport; compatibility failure before submission can release a healthy connection.
A cleanup failure is suppressed onto an existing primary failure. The direct-transport path also preserves observed acceptance when a stop request causes close failure.
[ADR 0026](0026-actual-send-diagnostics.md) extends observed attempts under #750 to retain their already captured receipt across independent close/release failures.
The accepted 19 October API revision also makes these facts available to the caller without an observer. Public `MailSendException` exposes
`Optional<MailSubmissionReceipt>`; submission, timeout, cancellation and cleanup failures share that contract. `MailSendCleanupException` retains
the receipt when submission returned normally but per-email cleanup failed. An existing primary submission failure keeps its original subtype,
provider cause and receipt; secondary cleanup errors are suppressed. Shared connection-scope cleanup does not invent one receipt for an entire batch.
Absence of a receipt must never be interpreted as proof that no message was accepted. [ADR 0012](0012-terminal-send-observation.md) describes whole-attempt reporting.

### Common failure and explicit acceptance contracts (accepted 20 October 2026)

Library-owned operational failures use the existing core `MailException` root, including asynchronous admission and local message-size
rejection. The root exposes a typed `MailFailureKind`, the original cause, and optional submission facts without requiring a subtype cast.
Keep useful specific exception types and normalize each owned failure at its producing boundary, before the same instance reaches caller,
completion and observer. Do not replace arbitrary application callback exceptions, programmer errors or JVM Errors with library failures.
Invalid API arguments remain argument errors; this is not a promise to catch every Throwable with `MailException`.

Failure kind describes the reported problem, not retry safety, SMTP non-acceptance or final delivery. In particular, cleanup can fail after
acceptance, and timeout/cancellation do not undo submission. Missing receipts mean unknown facts. An existing failure keeps its subtype and
cause while secondary cleanup failures remain suppressed. Shared-scope cleanup cannot attach one email's receipt to a whole batch.

Replace the ambiguous receipt predicate `isAcceptedByServer()` with `isAcceptedForAtLeastOneRecipient()` (the same any-recipient semantics),
and add `isFullyAcceptedForSubmission()` (true only for `ACCEPTED`). For `PARTIALLY_ACCEPTED` only the first is true; for `REJECTED` and
`UNKNOWN` both are false. The latter false values are not equivalent evidence of rejection. Partial submission still fails the operation.
These predicates are submission facts, independent of successful operation completion and final mailbox delivery.

Recipients and raw server replies can contain personal information and untrusted text; they are not redacted configuration diagnostics. SMTP acceptance is not mailbox delivery. DSNs, bounces and application reconciliation remain separate concerns. Pending ENVID work adds correlation metadata but does not change these meanings.

[ADR 0008's interpreted-source-facts decision](0008-minimal-mime-structures-and-protection-order.md#interpreted-mail-facts-remain-part-of-email-accepted-2026-10-16)
adds reading of a supplied delivery notification through its converted Email. This is distinct from the original send result: a received report does
not mutate a receipt, authenticate delivery or authorize automatic replay. Mail retrieval, correlation and business reconciliation remain application-owned.

Existing regression landmarks include [AngusSubmissionResultTest](../../modules/angus-mail-provider-module/src/test/java/org/simplejavamail/internal/mailprovider/angus/AngusSubmissionResultTest.java) and [MailSubmissionReceiptTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailSubmissionReceiptTest.java). This retrospective documentation pass did not rerun Java tests.

### Exception idiom for owned operations and raw resources

Accepted 21 October 2026: choose the public failure contract according to who owns the operation. A complete SJM-managed operation
reports its known operational failures through `MailException`. A deliberate handoff of a raw JDK resource retains that resource's native
exception contract for acquisition and subsequent caller-managed use. Failure timing within one operation must not select a different
exception family.

For attachment convenience reads, SJM opens, reads and closes the stream. Opening, reading and closing I/O failures all become
`MailException` with `MailFailureKind.PREPARATION`, whose meaning includes content access. Retain the original `IOException` as the cause,
including its suppressed closing failures when reading also failed. A closing failure after a successful read still fails the convenience
operation. No new public exception subtype or submission receipt is needed for this content-access boundary.

For caller-managed attachment streaming, `openStream() throws IOException` acquires and returns an ordinary `InputStream`. Opening,
reading and closing retain Java's checked-I/O contract; the application owns consuming and closing the stream. The acquisition name makes
the potential I/O and resource ownership explicit. Do not wrap the returned stream merely to replace its normal exception contract.

The [attachment access decision in ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#attachment-access-and-stream-ownership)
applies this idiom to the 10.0.0 API. It deliberately replaces the former mixed contract without preserving obsolete signatures or aliases.
Preserve ordinary argument errors, arbitrary application runtime exceptions and JVM Errors rather than blanket-wrapping them. Borrowed
DataSources retain their identity and ownership; this exception decision introduces no eager content reads or new lifetime ownership.


## Local preparation before provider entry (accepted 23 October 2026)

SJM's local MIME/envelope preparation occurs before entering provider submission, even when a connection is already acquired. A checked failure at that local boundary, including a mismatch between final envelope recipient occurrences and typed recipient policies, is PREPARATION and carries no submission receipt. Apply this consistently across ordinary and retry-enabled sending. Connection acquisition alone supplies no submission facts and must not turn a local preparation error into a transport result.

Preserve genuine provider compatibility-result normalization after entering the provider boundary, including known unsent facts before MAIL FROM, and retain partial/uncertain/accepted submission and cleanup receipts. This does not broaden automatic retry, change arbitrary application exception identity or transfer resource ownership. Terminal observation follows the same receipt-free local preparation classification under ADR 0012. [#865](https://github.com/bbottema/simple-java-mail/issues/865) tracks implementation and delivery for unreleased 10.0.0.
