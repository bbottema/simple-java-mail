# Step 8: Make SMTPUTF8 and 8BITMIME requirements explicit

- Status: Proposed
- Depends on: Step 4 capability reporting and the finalized-content contract from exact EML/rehearsal
- Proposed child issue: `Make SMTPUTF8 and 8BITMIME negotiation explicit`
- Proposed classification: `enhancement`, never also `major feature`
- Release sensitivity: Capability-policy API shape should be reviewed before 10.0 freeze if accepted for 10.0
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, DKIM, S/MIME, OpenPGP

## Goal

Determine whether the actual envelope and finalized message require SMTPUTF8 or 8BITMIME, negotiate those extensions explicitly, and fail clearly when required support is unavailable rather than silently changing protected content.

## Required distinctions

- Non-ASCII in an envelope local part can require SMTPUTF8 and cannot be repaired by MIME header encoding.
- Internationalized headers and an internationalized envelope have related but different requirements.
- An 8-bit body may use 8BITMIME, while a safe 7-bit transfer encoding may avoid that requirement for composed content.
- Exact EML and cryptographically protected content cannot be re-encoded merely to fit server capability.
- SMTPUTF8 and RFC 6533 internationalized DSN parameters must be considered together.

## Contract to design

Define provider-neutral policies such as require, use if available, and disable only where each meaning is honest. Avoid one generic enum if the capabilities have materially different fallback behavior.

The API must explain:

1. whether requirements are inferred from the envelope/finalized bytes, explicitly requested, or both;
2. what happens when composed content can be encoded safely but exact/protected content cannot;
3. whether a caller can forbid an extension for compatibility testing;
4. which capability and chosen behavior appear in rehearsal, diagnostics, and receipts;
5. when failure occurs relative to connection acquisition and MAIL FROM.

## Tests first

1. Cover ASCII and non-ASCII local parts, domains, display names, subjects, headers, and bodies independently.
2. Cover servers advertising neither capability, each capability separately, and both.
3. Capture MAIL FROM parameters and the exact DATA bytes for every negotiated path.
4. Verify safe transfer encoding for ordinary composed content where allowed.
5. Verify exact EML, DKIM, S/MIME, and OpenPGP content is never rewritten after finalization.
6. Verify unsupported internationalized envelope addresses fail clearly without a lossy downgrade.
7. Cover DSN ENVID/ORCPT internationalized forms from Step 6.
8. Cover pooling so one message's capability need does not affect the next message.
9. Verify custom adapters can declare supported content requirements or fail before submission.
10. Compare output with independent parsers and strict signature verification.

## Documentation and release work

- Explain SMTPUTF8 versus encoded display names and headers.
- Explain 8BITMIME versus MIME transfer encoding.
- Provide composed and exact-EML examples.
- Document what rehearsal can know before connecting and what only the SMTP capability probe can establish.

## Acceptance criteria

- [ ] Requirements are derived from the actual envelope and finalized content.
- [ ] Required extensions are negotiated before MAIL FROM.
- [ ] Missing support produces a precise compatibility failure.
- [ ] Exact and protected bytes are never rewritten as fallback.
- [ ] Ordinary composed mail uses only documented, lossless fallback behavior.
- [ ] Rehearsal, diagnostics, transport selection, and receipts agree about the selected path.

## Stop condition

If Angus performs undocumented rewriting that cannot be disabled or observed reliably, stop and isolate that behavior behind a tested provider change. Do not claim byte preservation while leaving the provider free to transform finalized content.
