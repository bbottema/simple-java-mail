# Step 7: Add per-message REQUIRETLS

- Status: Completed and accepted on 17 September 2026; unreleased
- Depends on: Step 4 capability reporting and Step 5 TLS/authentication policy
- Child issue: [#741](https://github.com/bbottema/simple-java-mail/issues/741), under [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- Classification: `enhancement`, never also `major feature`
- Target: 10.0.0; additive
- Architecture decision: [ADR 0023](../../docs/adr/0023-per-message-requiretls.md)
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, `cli-module`

## Goal

Allow a caller to request RFC 8689 REQUIRETLS for one SMTP submission and report clearly whether that requirement was advertised, applied, rejected, or unavailable.

REQUIRETLS is an SMTP envelope requirement for subsequent relay behavior. It is not S/MIME, OpenPGP, end-to-end encryption, or proof that the final recipient read the message. It is also distinct from requiring TLS on the client-to-submission-server connection.

## Accepted contract

1. Keep connection TLS on `Mailer` / `TransportStrategy`; put onward REQUIRETLS on `Email`. Add `withTlsRequiredForOnwardDelivery()` and `clearTlsRequiredForOnwardDelivery()` to composed and exact builders, and `boolean isTlsRequiredForOnwardDelivery()` to Email. No recipient setter, new strategy, policy enum, boolean setter overload or best-effort mode.
2. Reuse Email defaults/overrides and their suppression controls, including `simplejavamail.defaults.requiretls=true`. Preserve internal unset state: an empty template is not an explicit false. Clearing restores fallback rather than disabling an applicable default or override. Copying and `toBuilder()` retain the choice.
3. Check the actual send connection for TLS, certificate trust and server identity validation, plus REQUIRETLS advertised over that secured connection. Reject missing prerequisites before MAIL FROM. Never rely on a previous probe, silently downgrade, or mutate shared Session configuration for one message.
4. Mandatory connection TLS does not automatically enable REQUIRETLS. Opportunistic SMTP can satisfy the requirement when the actual connection passes the same checks. A false default does not request plaintext or `TLS-Required: No`.
5. Exact EML carries the choice as envelope metadata without changing bytes or its existing bypass of defaults/overrides.
6. Add `boolean isRequireTlsUsed()` to the receipt. Report true only for an actually issued MAIL FROM parameter, including rejected commands; local pre-command rejection and logging-only mode report false. False is no confirmed use, not proof about an opaque provider. Preserve acceptance and uncertainty separately.
7. Establish provider/custom-mailer support explicitly. An integration that cannot honor a required real send must not silently succeed. Unsupported paths fail with a concrete explanation before MAIL FROM.

Enterprise enforcement remains entirely under [#740](https://github.com/bbottema/simple-java-mail/issues/740). This step does not change suppression rules or introduce an enforcement API. `TLS-Required: No` remains separate research under [#739](https://github.com/bbottema/simple-java-mail/issues/739).

## Provider investigation

- Confirm whether the supported Angus version exposes REQUIRETLS through `SMTPMessage`, a Session property, or no public API.
- Inspect how it adds the MAIL FROM parameter and how it behaves when the server lacks the extension.
- Prefer an Angus-supported path or upstream contribution over rewriting SMTP command execution in the facade.
- Establish trustworthy TLS/trust/identity checks for STARTTLS and implicit TLS, including caller-owned Sessions and custom socket factories. Do not equate a TLS diagnostic snapshot or a configured strategy with verification.
- Characterize third-party adapters, `CustomMailer`, raw MAIL extensions, null reverse paths and RFC 8689's DSN handling before claiming supported combinations.

## Tests first

1. Capture MAIL FROM against servers that advertise and omit REQUIRETLS.
2. Cover explicit/default/override/suppressed/cleared settings, empty templates and copying. Ordinary sends without the requirement remain unchanged; no use-if-available mode is added.
3. Verify required-but-unavailable or unverified fails before MAIL FROM. Cover local pre-command failures with zero MAIL commands and truthful receipt values.
4. Cover final acceptance, explicit rejection, and ambiguous final reply while REQUIRETLS is active.
5. Cover pooled reuse across one message requiring it and another not requiring it.
6. Verify exact EML bytes remain unchanged because REQUIRETLS is envelope metadata.
7. Cover custom adapters, custom mailers, caller-owned Sessions, sync/async sends, batches, open connections, cancellation, deadlines, logging-only mode and observers. Preserve established cleanup and completion contracts.
8. Follow the API expansion workflow for property schemas, diagnostic grouping/sensitivity, Spring metadata, generated CLI options and classpath/JPMS consumers. Verify the direct exact-builder option without applying templates to exact EML.

## Documentation and release work

- Explain client-to-server TLS and onward delivery REQUIRETLS side by side.
- Include one usage example and one unsupported-server failure example.
- Cross-link S/MIME and OpenPGP for users who actually need message-level confidentiality.
- State that REQUIRETLS cannot prove the entire downstream route complied or that delivery occurred.
- Include property defaults and receipt examples; explain clearing versus suppressing a default. Add migration notes only for actual compatibility or semantic changes, not merely the new API.

## Acceptance criteria

- [x] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including capability enforcement and per-message transport state.
- [x] A message can request REQUIRETLS without changing its MIME bytes.
- [x] Required-but-unavailable behavior is explicit and occurs before MAIL FROM.
- [x] Capability diagnostics and submission outcomes report only observed facts.
- [x] Messages sharing a connection do not leak per-message REQUIRETLS state.
- [x] Custom transport support is explicit without Angus types; opaque or unsupported handling cannot silently satisfy a required send.
- [x] Documentation distinguishes REQUIRETLS from end-to-end encryption.

## Stop condition

If the supported Angus release has no stable way to emit REQUIRETLS, park this child behind an upstream issue. Do not bypass the provider's SMTP state machine for one MAIL FROM parameter.
