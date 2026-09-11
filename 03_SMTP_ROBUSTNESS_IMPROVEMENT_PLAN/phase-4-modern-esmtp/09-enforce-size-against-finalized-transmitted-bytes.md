# Step 9: Enforce SIZE against finalized transmitted bytes

- Status: Proposed
- Depends on: Step 4 capability reporting and the existing finalized-content/rehearsal path
- Proposed child issue: `Validate SMTP SIZE using finalized transmitted content`
- Proposed classification: `enhancement`, never also `major feature`
- Release sensitivity: Additive if introduced as explicit policy; any change to existing maximum-size semantics needs migration review
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, exact EML and security modules

## Goal

Compute the size relevant to SMTP submission from the same finalized content that will be sent, compare it with the server's advertised SIZE limit, and keep this distinct from Simple Java Mail's existing local maximum-email-size guard.

## Semantics to settle

1. Verify the exact RFC 1870 octet-count rules and the supported Angus behavior for DATA transparency, line endings, and MAIL FROM's SIZE parameter.
2. Define which size is available during local rehearsal and which comparison requires an SMTP capability exchange.
3. Ensure the bytes measured are the bytes signed, encrypted, observed, and passed to the provider before SMTP framing.
4. Define behavior when the server advertises SIZE without a numeric maximum, omits SIZE, or changes capability after STARTTLS.
5. Define require, use-if-available, and disabled behavior only where useful.
6. Decide whether the result exposes the measured size, advertised maximum, sent parameter, and rejection reason.
7. Keep the existing user-configured maximum email size as an application policy with its own terminology.

Avoid serializing a large message twice in memory merely to count it. Prefer a counting stream or retained finalized artifact consistent with the exact-content design.

## Tests first

1. Compare measured sizes with an independent byte count for composed, exact EML, DKIM, S/MIME, and OpenPGP messages.
2. Cover CRLF normalization, lines beginning with a dot, a line containing only a dot, multibyte UTF-8, attachments, and large streaming content.
3. Capture the SIZE parameter Angus actually sends and compare it with the documented count.
4. Cover advertised limits below, equal to, and above the message size.
5. Prove over-limit failure occurs before MAIL or DATA according to the final contract and sends no message bytes.
6. Cover SIZE advertised before STARTTLS but omitted or changed afterward.
7. Cover no advertised maximum and no SIZE capability.
8. Verify pooling and repeated sends do not reuse a previous message's size.
9. Measure peak memory to prevent an accidental whole-message duplicate.
10. Verify the existing local maximum-size exception remains distinguishable from a server capability mismatch or server 552 rejection.

## Documentation and release work

- Explain local maximum size, advertised SMTP SIZE, and a server's later rejection as three separate controls.
- Show how rehearsal reports finalized size before a network connection.
- Show how the capability probe adds the server maximum.
- Explain that an SMTP server may still reject a message after accepting a SIZE parameter.

## Acceptance criteria

- [ ] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including finalized-content measurement and preflight.
- [ ] One finalized representation supplies the measured content.
- [ ] The count follows verified RFC and provider semantics.
- [ ] Exact and cryptographically protected content is not rebuilt for measurement.
- [ ] Advertised limits are checked using post-TLS capabilities.
- [ ] Failure categories distinguish local policy, capability preflight, and SMTP rejection.
- [ ] Large-message memory behavior remains bounded and measured.

## Stop condition

If the provider's transmitted representation can differ from the bytes Simple Java Mail can measure, stop and close that provider seam first. Do not expose an “exact SMTP size” value that is only an estimate.
