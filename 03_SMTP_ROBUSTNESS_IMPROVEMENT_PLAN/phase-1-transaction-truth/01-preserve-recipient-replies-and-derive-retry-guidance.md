# Step 1: Preserve per-recipient replies and derive retry guidance

- Status: Complete: implemented, verified and accepted on 9 September 2026; unreleased
- Depends on: Existing #710 submission-result foundation
- Child issue: [#723 - Expose per-recipient SMTP replies and retry guidance](https://github.com/bbottema/simple-java-mail/issues/723), part of [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- Proposed classification: `major feature`, never also `enhancement`
- Release sensitivity: Public result shape should be decided before the 10.0 API freeze if this step is accepted for 10.0
- Primary modules: `core-module`, `angus-mail-provider-module`, `simple-java-mail`, `batch-module`

## Goal

Represent what happened to every envelope recipient, retain the replies that the provider can prove, and derive a conservative retry recommendation without ever retrying automatically.

This step also fixes the confirmed `mail.smtp.reportsuccess=true` behavior. Angus deliberately reports successful recipient replies through exceptions; a transaction in which all recipients and the final message were accepted must not become a failed or `PARTIALLY_ACCEPTED` Simple Java Mail result merely because that reporting mode was enabled.

## Public contract to design

Use working names until the API review settles them. The model needs:

- one immutable recipient result in original envelope order;
- the original address and normalized envelope address where available;
- whether RCPT was attempted;
- the primary three-digit reply code and text when observed;
- an optional parsed enhanced status such as `5.1.1`;
- a recipient outcome that distinguishes accepted, temporarily rejected, permanently rejected, not attempted, and unknown;
- a transaction-level retry disposition derived only from facts the result actually contains.

The retry vocabulary should cover at least:

- safe to retry the whole envelope;
- safe to retry only the known unaccepted recipients;
- duplicate risk;
- caller policy required;
- do not retry when the available facts justify that conclusion.

These names are illustrative. The design must explain whether retry guidance belongs directly on `MailSubmissionReceipt`, on a separate immutable value, or on a helper that consumes a receipt. It must not imply SMTP idempotency.

Existing accepted, valid-unsent, and invalid recipient getters remain available. Prefer deriving those compatibility views from the new model so two recipient representations cannot drift.

## Provider and SPI behavior

1. Characterize Angus with `mail.smtp.reportsuccess` for complete success, mixed RCPT responses, temporary rejection, permanent rejection, final DATA rejection, and final-reply loss.
2. Parse `SMTPAddressSucceededException`, `SMTPAddressFailedException`, and the enclosing `SMTPSendFailedException` structurally, never by message text.
3. Normalize a provider-generated reporting exception into an accepted result when the final reply is positive and no recipient remains unaccepted.
4. Preserve the exact provider exception as the cause for genuine caller-facing failures.
5. Extend `MailTransportResult` in a provider-neutral way. Third-party adapters may report complete, partial, or no recipient reply details without fabricating them.
6. Never turn a missing final DATA reply into rejection merely because Angus labels recipients valid-unsent after losing the connection.
7. Keep response data attempt-local across pooled transports and asynchronous sends.

## Tests first

1. Extend the scripted SMTP peer to emit distinct successful and failed replies for each RCPT command.
2. Reproduce the current `reportsuccess=true` all-success misclassification before changing production code.
3. Cover all accepted, all rejected, and mixed 2xx/4xx/5xx recipient sets with and without partial sending.
4. Cover a valid RCPT sequence followed by final 2xx, final 4xx, final 5xx, reset, timeout, and EOF.
5. Prove enhanced-status parsing for valid, absent, malformed, multiline, and mismatched reply text.
6. Prove deterministic recipient ordering even when provider exception chains use another order.
7. Prove old recipient-list getters remain immutable and agree with the new results.
8. Repeat failure then success, success then failure, and concurrent attempts with a pool size of one and greater than one.
9. Cover ordinary sync/async sends, simple batches, open connections, observers, logging-only mode, custom mailers, and third-party adapters with incomplete detail.
10. Compile the public surface from classpath and JPMS provider-neutral consumers.

## Documentation and release work

- Explain the difference between the RCPT reply and final message acceptance.
- Show a mixed-recipient example with one temporary and one permanent rejection.
- Explain enhanced status codes without promising that every SMTP server supplies one.
- Show retry guidance as input to caller policy, not as an automatic resend command.
- Update the existing #710 result documentation rather than creating a second conflicting explanation.

## Acceptance criteria

- [x] A fully accepted `reportsuccess=true` transaction returns normal success.
- [x] Every observed recipient reply is retained without parsing exception messages.
- [x] Missing replies remain absent rather than becoming synthetic zero or EOF responses.
- [x] Compatibility recipient lists agree with the richer recipient model.
- [x] Retry guidance is conservative for partial and unknown outcomes.
- [x] No public value claims mailbox delivery or exactly-once behavior.
- [x] Pooling and concurrency tests prove that recipient replies cannot cross attempts.
- [x] Custom and provider-neutral transports remain usable without Angus-specific types.

## Stop condition

If Angus cannot expose successful recipient replies reliably without a global Session behavior change, stop and decide whether the adapter may enable reporting on its own transport, whether the feature is opt-in, or whether an upstream Angus seam is required. Do not publish a complete-recipient promise from incomplete provider evidence.

## Implemented API decisions

- `MailSubmissionReceipt.getRecipientResults()` returns immutable `MailRecipientResult` values in envelope order. Its existing accepted/unsent/invalid views are derived from those values. Older constructors and serialized receipts remain readable with absent RCPT details.
- `MailRecipientDisposition` describes final submission knowledge; `SmtpRecipientStatus` describes only RCPT TO. This avoids calling a positive RCPT reply a successfully submitted message.
- `SmtpServerResponse.getEnhancedStatusCode()` extracts an optional consistent enhanced code; classification still uses structured primary replies, not exception prose.
- `MailRetryDisposition` lives directly on the receipt. `getRetryableRecipients()` is populated only for the safe-to-retry dispositions. There is no automatic retry, backoff, CLI option, or configuration property.
- Angus reporting is enabled only on the transport being used, under its monitor, with the previous flag restored in `finally`. Shared Session configuration is not changed.
- Custom and logging-only sends retain their envelope recipients with unknown SMTP facts. Third-party adapters may enrich `MailTransportResult` without depending on Angus, or keep returning the original result factories.
- Missing final replies retain ambiguous acceptance; missing RCPT chains on some provider I/O failures remain missing. Complete duplicate-mailbox reply chains retain individual replies, but a mailbox both accepted and retryable produces duplicate-risk advice. A direct abort at a repeated mailbox does not identify its occurrence reliably: individual RCPT facts stay unknown, the exact provider exception remains available, and retrying requires caller policy. Address groups use Angus' expanded envelope order for reporting without changing message headers or the send call.

## Verification and review handoff

- The complete non-live reactor verification passed on Java 21 with `publish-cli`; the library verification passed on Java 11 excluding the Java 17+ CLI module. Final focused verification passed all 86 recipient, fault-boundary, observer and pooling tests, including classpath and JPMS consumer checks.
- The standard Java 21 Javadoc goal reported a modular source-path error despite Maven's successful exit (`failOnError=false`). Core and main-module Javadocs were subsequently generated successfully with `mvn -pl modules/simple-java-mail -am package -DskipTests=true -DlegacyMode=true -DexcludeLiveServerTests=true`, without changing the POM.
- Every touched Java class was audited against `CODING_STYLE_GUIDE.md`. Generated source license headers were removed with `mvn license:remove`.
- Website checks and the clean build passed. The whole-site link check reports five broken links in the unrelated Journal article `the-libraries-behind-simple-java-mail.html`; none originate in the changed phase-one documentation. Concurrent Journal work was preserved.
- The regenerated `therapi.data` has the same 163 cached descriptions as before. Only its stale API fingerprint changed, now matching the existing `cli.data`; no CLI options were added.
- At the original handoff, implementation and documentation were left uncommitted on the existing 10.0 branches, with the child issue open and In Progress. No later phase or benchmarking had been executed.

## Review acceptance, 9 September 2026

The implementation and documentation are accepted. Review also covered immutable provider snapshots, receipt compatibility and historical serialization fixtures, the distinct roles of recipient and transaction statuses, conservative retry advice, and the worked website examples. The holistic review found no outstanding phase-one defect. Completion here means accepted implementation, not a published 10.0.0 release or GitHub issue closure.

## Separate dependency finding

**Release follow-up, 7 September 2026:** The defect below is fixed in [Generic Object Pool 2.4.3](https://github.com/bbottema/generic-object-pool/releases/tag/2.4.3) and propagated through [Clustered Object Pool 4.0.4](https://github.com/bbottema/clustered-object-pool/releases/tag/4.0.4), [SMTP Connection Pool 4.0.2](https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/4.0.2), and [Simple Java Mail 9.3.4](https://github.com/bbottema/simple-java-mail/releases/tag/9.3.4). All twelve loopback SMTP recovery scenarios passed against that published chain. The 9.x release is tracked in [#724](https://github.com/bbottema/simple-java-mail/issues/724).

This 10.0 checkout now adopts SMTP Connection Pool 4.1.0, retaining all eight mixed-failure waiter-recovery regression cases from the patch. See [step 2's integration verification](../phase-2-execution-control/02-bound-asynchronous-submission-and-expose-backpressure.md#pool-410-integration-and-javadoc-verification-8-september-2026). The original finding below is retained as context, not as an unresolved upstream defect.

The mixed concurrent pool-size-one fault test exposed a pre-existing `generic-object-pool` 2.4.2 wake-up gap. Invalidating the only claimed object does not signal already-waiting claimers; the core auto-allocator can add a replacement without signalling them either. New claims can use that replacement while existing claims wait until their claim timeout. This is independent of the new reporting flag and result extraction.

An isolated probe using only the released pool JAR (no Simple Java Mail or SMTP classes) confirmed:

```text
Already-waiting claim received replacement: false
Fresh claim received replacement: true
```

Reproducer against 2.4.2: create a core/max-size-one `GenericObjectPool`, claim its object, start a second timed claim, wait until pool metrics report one waiting claimer, invalidate the first object, and await the second claim. It times out; a fresh claim succeeds. No pool dependency or lifecycle behavior was changed in the initial phase-one implementation. The execution-control phase subsequently adopted the released fix and retained its regression coverage.

Repository tests cover concurrent SMTP success at pool sizes one and three, failure/recovery sequences at both sizes, and callback-after-release reentrancy. Existing overlapping success/failure tests also verify attempt isolation. Those initial phase-one results did not establish mixed-failure waiter liveness for the old dependency; step 2 records verification of the upgraded chain.
