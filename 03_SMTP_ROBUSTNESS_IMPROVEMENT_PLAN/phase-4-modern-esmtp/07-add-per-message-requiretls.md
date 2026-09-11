# Step 7: Add per-message REQUIRETLS

- Status: Proposed
- Depends on: Step 4 capability reporting and Step 5 TLS/authentication policy
- Proposed child issue: `Add per-message REQUIRETLS submission policy`
- Proposed classification: `enhancement`, never also `major feature`
- Release sensitivity: Additive; schedule only after the provider path and unsupported-server behavior are proven
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, `cli-module`

## Goal

Allow a caller to request RFC 8689 REQUIRETLS for one SMTP submission and report clearly whether that requirement was advertised, applied, rejected, or unavailable.

REQUIRETLS is an SMTP envelope requirement for subsequent relay behavior. It is not S/MIME, OpenPGP, end-to-end encryption, or proof that the final recipient read the message. It is also distinct from requiring TLS on the client-to-submission-server connection.

## Contract to design

1. Decide where the per-message requirement belongs without adding transport settings to unrelated MIME content.
2. Preserve one canonical `Email` abstraction; envelope policy may delegate internally but existing getters remain usable.
3. Define behavior when the server does not advertise REQUIRETLS: fail before MAIL, use ordinary delivery only through an explicit best-effort choice, or reject unsupported combinations.
4. Define interaction with null reverse paths, DSN parameters, SMTPUTF8, exact EML, retries, batches, and open connections.
5. Surface the negotiated capability and selected per-message policy in diagnostics and the submission result without implying downstream compliance was observed.
6. Determine how third-party adapters and custom mailers report unsupported or opaque handling.

## Provider investigation

- Confirm whether the supported Angus version exposes REQUIRETLS through `SMTPMessage`, a Session property, or no public API.
- Inspect how it adds the MAIL FROM parameter and how it behaves when the server lacks the extension.
- Prefer an Angus-supported path or upstream contribution over rewriting SMTP command execution in the facade.

## Tests first

1. Capture MAIL FROM against servers that advertise and omit REQUIRETLS.
2. Cover required, use-if-available if supported, and disabled/default behavior.
3. Verify required-but-unavailable fails before message bytes are sent.
4. Cover final acceptance, explicit rejection, and ambiguous final reply while REQUIRETLS is active.
5. Cover pooled reuse across one message requiring it and another not requiring it.
6. Verify exact EML bytes remain unchanged because REQUIRETLS is envelope metadata.
7. Cover custom adapters, custom mailers, caller-owned Sessions, async sends, batches, and observers.
8. Verify CLI and configuration exposure only where a per-email value can be represented unambiguously.

## Documentation and release work

- Explain client-to-server TLS and downstream REQUIRETLS side by side.
- Include one usage example and one unsupported-server failure example.
- Cross-link S/MIME and OpenPGP for users who actually need message-level confidentiality.
- State that REQUIRETLS cannot prove the entire downstream route complied or that delivery occurred.

## Acceptance criteria

- [ ] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including capability enforcement and per-message transport state.
- [ ] A message can request REQUIRETLS without changing its MIME bytes.
- [ ] Required-but-unavailable behavior is explicit and occurs before DATA.
- [ ] Capability diagnostics and submission outcomes report only observed facts.
- [ ] Messages sharing a connection do not leak per-message REQUIRETLS state.
- [ ] Custom transports can report support, lack of support, or opacity without Angus types.
- [ ] Documentation distinguishes REQUIRETLS from end-to-end encryption.

## Stop condition

If the supported Angus release has no stable way to emit REQUIRETLS, park this child behind an upstream issue. Do not bypass the provider's SMTP state machine for one MAIL FROM parameter.
