# MIME pipeline and mail-provider improvement plan

This plan prepares Simple Java Mail 10.0.0 for [OpenPGP/MIME support (#704)](https://github.com/bbottema/simple-java-mail/issues/704) while removing provider-specific SMTP behavior from the MIME and cryptography pipeline. Each implementation step has its own Markdown file so it can be implemented, reviewed, and completed independently.

Baseline: `develop`, 17 August 2026.

## Desired end state

- MIME construction, Message-ID handling, signing, encryption, parsing, and conversion depend only on Jakarta Mail APIs and provider-neutral Simple Java Mail contracts.
- Envelope sender, DSN requests, transport recipients, and submission-response extraction are delivery metadata, not `MimeMessage` subtype responsibilities.
- Content that has been signed or encrypted has an explicit byte-finalization boundary and cannot be rewritten by a later `saveChanges()`, transfer-encoding conversion, or provider optimization.
- A narrow mail-provider SPI owns the final send operation and any implementation-specific capabilities.
- Angus remains the officially supported default provider through an isolated optional adapter module; it is not a compile-time or transitive JPMS requirement of the provider-neutral modules.
- DKIM and S/MIME no longer require Angus `SMTPMessage` subclasses.
- Incoming security processing can inspect original MIME bytes before the generic parser materializes the multipart tree.
- The optional OpenPGP module implements RFC 3156 on these seams without introducing another message-subclass matrix.

## Locked boundaries

- Simple Java Mail does not implement SMTP. Sending still requires a Jakarta Mail provider at runtime.
- Angus-specific inheritance may remain inside the Angus adapter where Angus requires `SMTPMessage`, but it must not be the canonical message model or leak into MIME/crypto modules.
- The existing specialized producers for plain, alternative, related, and mixed bodies remain responsible for MIME structure; this plan adds a pipeline after that construction rather than duplicating those producers.
- S/MIME and OpenPGP are alternative end-to-end protection families in the first release. Configuring both on one email is rejected clearly.
- Within either family, signing precedes encryption. DKIM is applied after content protection, against the final outgoing representation.
- Cryptographic validity and application trust remain separate concepts.
- Provider-specific features such as per-message envelope sender, DSN, and enhanced submission receipts fail explicitly when the selected adapter cannot provide them; they are never silently ignored.
- Java 8 compatibility, classpath use, JPMS use, proxying, batching, governance, logging, and receipt behavior remain release gates.

## Target pipeline

```text
Email
  -> provider-neutral MIME body construction
  -> controlled header and Message-ID finalization
  -> S/MIME or OpenPGP signing/encryption (optional)
  -> immutable/repeatable protected MIME entity
  -> DKIM final-wire signing (optional)
  -> PreparedMail + DeliveryEnvelope
  -> selected MailTransportAdapter
  -> Jakarta Mail Transport
```

Incoming messages use the reverse separation: protection handlers inspect and, where possible, verify/decrypt the original entity first; the ordinary MIME parser then receives the effective clear entity plus security metadata.

## Working method

1. Work through the steps in order unless a step explicitly says it can overlap.
2. Change a step's `Status` from `Planned` to `In progress` before production changes.
3. Preserve a green build at every step. A temporary compatibility bridge is acceptable only when identified in the step and covered by tests.
4. Check every acceptance criterion and record concrete test or artifact evidence.
5. Mark the step `Done` and check it off below only after its focused gate passes.

Status vocabulary: `Planned`, `In progress`, `Blocked`, `Done`.

## Steps

- [x] [1. Lock current behavior and wire invariants](01-lock-current-behavior-and-wire-invariants.md)
- [x] [2. Separate MIME content from delivery metadata](02-separate-mime-content-from-delivery-metadata.md)
- [x] [3. Introduce the mail-provider adapter SPI](03-introduce-mail-provider-adapter-spi.md)
- [x] [4. Move Angus behavior into its adapter](04-move-angus-behavior-into-adapter.md)
- [x] [5. Establish an explicit MIME finalization boundary](05-establish-mime-finalization-boundary.md)
- [x] [6. Refactor DKIM and S/MIME around composition](06-refactor-dkim-and-smime-around-composition.md)
- [x] [7. Add a pre-parse inbound protection pipeline](07-add-inbound-protection-pipeline.md)
- [x] [8. Make provider-neutral packaging real](08-make-provider-neutral-packaging-real.md)
- [x] [9. Implement OpenPGP/MIME on the new seams](09-implement-openpgp-mime.md)
- [x] [10. Complete compatibility, migration, and release gates](10-complete-release-gates.md)

## Completion definition

The plan is complete when all ten steps are `Done`; provider-neutral artifacts compile and run conversion tests without Angus; the Angus adapter preserves existing sending behavior; protected wire bytes survive the complete transport path unchanged; DKIM, S/MIME, and OpenPGP fixtures verify independently; and the 10.0.0 migration documentation states exactly which runtime provider dependency users need.

Completed on 17 August 2026. The individual step files contain the implementation and verification evidence.
