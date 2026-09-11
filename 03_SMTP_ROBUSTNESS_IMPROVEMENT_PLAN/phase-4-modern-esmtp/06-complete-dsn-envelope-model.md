# Step 6: Complete the DSN envelope model

- Status: Proposed
- Depends on: Existing delivery-status notification support; coordinate recipient results with Step 1 and internationalized forms with Step 8
- Proposed child issue: `Add DSN envelope identifiers and original-recipient support`
- Proposed classification: `enhancement`, never also `major feature`
- Release sensitivity: Additive, but envelope and Email API shape should be reviewed before the 10.0 API freeze if scheduled for 10.0
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, `cli-module`

## Goal

Complete Simple Java Mail's first-class RFC 3461 delivery-status notification request model by adding the transaction envelope identifier and per-recipient original-recipient data that are not covered by the existing NOTIFY and RET options.

DSN requests ask downstream systems to produce status notifications. They do not prove delivery and must remain separate from the immediate SMTP submission receipt.

## Contract to design

Model:

- one optional ENVID for the SMTP transaction;
- one optional ORCPT value per envelope recipient;
- the existing per-recipient NOTIFY choices;
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

- [ ] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including envelope preparation and outcome reporting.
- [ ] ENVID, RET, NOTIFY, and ORCPT can be expressed together without positional ambiguity.
- [ ] Values are validated before SMTP resources are acquired.
- [ ] Unsupported DSN behavior follows an explicit policy.
- [ ] Exact message content is unaffected by envelope DSN options.
- [ ] Recipient-level outcomes remain correlated with their DSN envelope metadata.
- [ ] CLI, classpath, JPMS, and provider-neutral adapter boundaries are covered.
- [ ] Documentation does not present DSN as proof of delivery.

## Stop condition

If Angus cannot send ENVID or ORCPT through a supported API, stop and decide whether a narrow provider extension or upstream change is appropriate. Do not construct SMTP commands from Simple Java Mail outside the transport provider merely to complete the surface.
