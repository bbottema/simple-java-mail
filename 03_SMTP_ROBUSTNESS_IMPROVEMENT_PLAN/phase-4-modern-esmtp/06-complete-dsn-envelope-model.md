# Step 6: Complete the DSN envelope model

- Status: Complete and accepted on 17 September 2026 under #736 and #738; unreleased 10.0.0 work
- Depends on: Existing delivery-status notification support; coordinate recipient results with Step 1 and internationalized forms with Step 8
- ENVID child issue: [#736 — Add DSN envelope identifiers for send correlation](https://github.com/bbottema/simple-java-mail/issues/736), milestone 10.0.0, complete
- ENVID classification: `enhancement`, never also `major feature`; research resolved
- Recipient follow-up: [#738 — Add recipient DSN NOTIFY preferences and automatic ORCPT](https://github.com/bbottema/simple-java-mail/issues/738), milestone 10.0.0, `enhancement`, complete; research resolved
- Release sensitivity: Additive, but envelope and Email API shape must be reviewed before the 10.0.0 API freeze
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, `cli-module`

## Goal

Complete Simple Java Mail's first-class RFC 3461 delivery-status notification request model by adding the transaction envelope identifier and per-recipient original-recipient data that are not covered by the existing NOTIFY and RET options.

DSN requests ask downstream systems to produce status notifications. They do not prove delivery and must remain separate from the immediate SMTP submission receipt.

## Provider checkpoint: 15 September 2026

The [Angus 2.0.5 characterization](06a-dsn-provider-characterization.md) confirms that a transaction identifier can travel through the supported per-message MAIL extension hook, but DSN capability checks and xtext validation/encoding remain our responsibility. There is no equivalent public per-recipient extension or ORCPT option. The current NOTIFY value is message-wide, not independently configurable for each recipient.

The user approved the [ENVID-only implementation](06b-envid-implementation.md) on 15 September 2026. At that checkpoint, ORCPT and recipient-specific notification settings were deferred; they are not acceptance criteria for #736. The subsequent implementation is tracked separately in [#738](https://github.com/bbottema/simple-java-mail/issues/738) and the 16 September checkpoint below. The broader original design below remains historical planning, not the final API contract.

## API decision: 16 September 2026

[ADR 0001: Email configuration scopes and inheritance](../../docs/adr/0001-email-configuration-scopes-and-inheritance.md) governs the remaining API design. Email stays the canonical message-configuration surface; Mailer policy uses Email defaults/overrides. Only NOTIFY preferences belong on recipient/group builders. RET and ENVID stay transaction-wide, and ordinary-submission ORCPT is derived from the actual envelope recipient when supported rather than exposed as a configurable address override.

[ADR 0002: Email defaults and overrides](../../docs/adr/0002-email-defaults-and-overrides.md) separately records why reusable policy uses Email templates and how defaults, explicit values, overrides, and suppression interact. Do not introduce a DSN-specific Mailer defaulting path.

Automatic ORCPT and configurable per-recipient NOTIFY are implemented in separate local slices under [#738](https://github.com/bbottema/simple-java-mail/issues/738). Recipient precedence is settled: explicit recipient values beat the governance-resolved Email fallback, including Email overrides. The implementation and its provider-support boundaries still need review; #736's ENVID-only acceptance criteria are unchanged. Where the earlier roadmap below suggests user-configured ORCPT, the ADR supersedes that suggestion. Progress is tracked in the [homogeneous configuration implementation slices](06c-homogeneous-email-configuration.md).

The later implementation approval is now reflected in that slice file: recipient NOTIFY and automatic ORCPT use the managed Angus command hook, preserving the provider's RCPT loop and result handling. This is separate uncommitted review work, not a change to the historical 15 September checkpoint or an assertion that the whole robustness phase is complete.

## Completion: 17 September 2026

The ENVID and recipient NOTIFY/ORCPT slices passed production review, holistic review and the final coding-guide pass. Step 6 is complete; the earlier checkpoints below retain their historical scope. Both slices use the existing transport owner and result path, so the approved concurrency infographic remains accurate. Phase 4 still has REQUIRETLS, SMTPUTF8/8BITMIME requirements and finalized SIZE handling to implement.

## Original contract to design (superseded where noted above)

Model:

- one optional ENVID for the SMTP transaction;
- one optional ORCPT value per envelope recipient;
- the existing message-wide NOTIFY choices, with per-recipient overrides to be designed if the provider path is approved;
- the existing RET choice;
- internationalized DSN forms where RFC 6533 and negotiated SMTPUTF8 permit them;
- validation and encoding rules for each value before a connection is opened.

The design must keep header recipients and envelope recipients separate. A recipient's ORCPT belongs to its envelope entry, so a positional list that can silently drift from the recipients is not acceptable. Prefer one immutable recipient-envelope value or an address-keyed builder operation whose duplicate-address behavior is explicit.

Exact EML submission must remain possible because the DSN values are SMTP envelope metadata rather than MIME headers.

## Provider behavior

1. Determine which DSN fields Angus supports directly through `SMTPMessage` or transport APIs.
2. Add provider-neutral fields to `DeliveryEnvelope` or a cohesive replacement without exposing Angus constants.
3. Send each parameter only when the server advertises DSN and the selected requirement policy allows it.
4. Define require-versus-best-effort behavior instead of silently dropping requested envelope metadata.
5. Preserve DSN data through ordinary, async, pooled, simple-batch, open-connection, exact-EML, and adapter paths.

## Tests first

1. Capture the exact MAIL FROM and RCPT TO commands for ENVID, RET, NOTIFY, and ORCPT separately and in combination.
2. Cover multiple recipients with distinct ORCPT and NOTIFY values, including duplicate mailbox addresses.
3. Cover escaping, maximum lengths, prohibited characters, null/empty values, and internationalized forms.
4. Cover a server with DSN support, without DSN support, and with malformed DSN advertisement.
5. Cover require and use-if-available policies once their shared shape is agreed.
6. Verify no DSN value is added as a MIME header.
7. Verify exact EML bytes remain unchanged.
8. Verify CLI conversion from explicit string forms and exclusion of shapes that cannot be represented safely.
9. Verify recipient receipt data from Step 1 remains correlated with the same envelope recipient values.

## Documentation and release work

- Expand the DSN example to show ENVID and two different ORCPT values.
- Explain immediate SMTP acceptance versus a later DSN message.
- State server-support and downstream-support limitations.
- Include exact-EML and CLI examples where supported.

## Acceptance criteria

- [x] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including envelope preparation and outcome reporting.
- [x] ENVID, RET, NOTIFY, and ORCPT can be expressed together without positional ambiguity.
- [x] Caller-supplied identifiers and recipient alignment are validated during preparation; negotiated capability checks and automatic encoding remain at the provider boundary before submission.
- [x] Unsupported DSN behavior follows an explicit policy.
- [x] Exact message content is unaffected by envelope DSN options.
- [x] Recipient-level outcomes remain correlated with their DSN envelope metadata.
- [x] CLI, classpath, JPMS, and provider-neutral adapter boundaries are covered.
- [x] Documentation does not present DSN as proof of delivery.

## Stop condition

If Angus cannot send ENVID or ORCPT through a supported API, stop and decide whether a narrow provider extension or upstream change is appropriate. Do not construct SMTP commands from Simple Java Mail outside the transport provider merely to complete the surface.
