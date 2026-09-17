# ADR 0011: Transport-neutral submission outcomes and conservative retry guidance

- Status: Accepted; retrospective record of implemented behavior
- Decision recorded: 2026-09-16; the source dates below are the historical evidence
- Applies to: 10.0.0, including unreleased recipient results and retry guidance
- Implementation baseline: `codex/10.0.0` at `a4eda9e6`; concurrent ENVID work is excluded from this decision

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

Angus success-reporting mode uses exceptions even after successful submission. Its adapter enables recipient reporting only during the exclusively owned send, restores the previous Transport setting in `finally`, and does not mutate shared Session properties. Normalization to success requires a real final 2xx reply, all recipients accepted and no rejection. Other exceptions retain their original failure meaning.

Missing provider replies remain absent. EOF or return code zero is not a real SMTP rejection, and a provider can lose its RCPT exception chain during an I/O failure. Do not reconstruct missing recipient replies from the connection's last transaction response. Custom mailers and logging-only mode can complete successfully with `UNKNOWN` because this library has not observed their SMTP acceptance. Third-party adapters can supply fewer facts than Angus.

Cleanup is separate from SMTP knowledge. A failed submission normally invalidates the pooled transport; compatibility failure before submission can release a healthy connection. A cleanup failure is suppressed onto an existing primary failure. The direct-transport path also preserves observed acceptance when a stop request causes close failure. This is not a promise that every independent close/release failure carries an accepted receipt: a later cleanup error can still become a generic caller-facing failure. Absence of a receipt must never be interpreted as proof that no message was accepted. [ADR 0012](0012-terminal-send-observation.md) describes whole-attempt reporting.

Recipients and raw server replies can contain personal information and untrusted text; they are not redacted configuration diagnostics. SMTP acceptance is not mailbox delivery. DSNs, bounces and application reconciliation remain separate concerns. Pending ENVID work adds correlation metadata but does not change these meanings.

Existing regression landmarks include [AngusSubmissionResultTest](../../modules/angus-mail-provider-module/src/test/java/org/simplejavamail/internal/mailprovider/angus/AngusSubmissionResultTest.java) and [MailSubmissionReceiptTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailSubmissionReceiptTest.java). This retrospective documentation pass did not rerun Java tests.
