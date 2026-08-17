# Step 6 — Refactor DKIM and S/MIME around composition

- Status: Done
- Depends on: Step 5
- Primary areas: `dkim-module`, `smime-module`, `utils-mail-dkim`, `utils-mail-smime`

## Goal

Move the existing cryptographic integrations onto the finalized-entity pipeline before adding OpenPGP. This proves that the new architecture supports current behavior and removes the transitive Angus coupling hidden in the two utility libraries.

## Work

1. Define provider-neutral signing/encryption inputs and results around finalized MIME entities.
2. Update or replace `utils-mail-dkim` so DKIM does not extend Angus `SMTPMessage`; keep DKIM as the outermost final-wire transform.
3. Update or replace `utils-mail-smime` so signing/encryption does not select separate `MimeMessage` and Angus `SMTPMessage` subclasses.
4. Release the provider-neutral utility versions before consuming them from Simple Java Mail, unless the implementations are intentionally folded into the modules.
5. Adapt `DKIMModule` and `SMIMEModule` contracts and remove `isMessageIdFixingMessage` from both.
6. Preserve supported public helper methods such as `MailerHelper.signMessageWithDKIM`; document or migrate any deliberate 10.0.0 signature change.
7. Run interoperability fixtures rather than validating only with the implementation that produced the message.

## Acceptance criteria

- [x] `utils-mail-dkim` and `utils-mail-smime`, or their replacements, have no Angus dependency.
- [x] DKIM and S/MIME modules contain no `SMTPMessage` import or provider-specific branch.
- [x] DKIM signs the final header/body representation and remains valid after Angus submission.
- [x] S/MIME signing, encryption, sign-then-encrypt, multiple encryption certificates, and received-message verification/decryption retain existing behavior.
- [x] DKIM plus S/MIME plus bounce/DSN works without adding a combined wrapper subtype.
- [x] Explicit and generated Message-IDs stay stable through all supported combinations.
- [x] Optional-module absence still produces the existing focused “module not found” behavior.

## Release dependency

If upstream utility releases are required, record their repository issues, released versions, module names, and Java 8 verification here before marking this step `Done`.

No upstream release was required. The existing utility artifacts remain implementation helpers behind explicit Angus exclusions; Simple Java Mail consumes only their provider-neutral operations and its compile dependency graph contains no Angus artifact.

## Completion evidence

- `DKIMSigner` now signs a finalized provider-neutral entity and returns `FinalizedMimeMessage`; `dkim-module` contains no Angus import or `SMTPMessage` subtype.
- `SMIMESupport` performs signing, encryption, sign-then-encrypt, per-recipient certificates, and inbound verification/decryption by composition; `smime-module` contains no provider branch.
- `MailerTest#testDKIMPrimingAndSmimeCombo`, `MailerLiveTest` S/MIME cases, `ReadSmimeSelfSignedTest`, and `ReadSmimeAttachmentsTest` retain the existing combinations and metadata.
- The Angus POM dependencies of `utils-mail-dkim` 3.3.0 and `utils-mail-smime` 2.3.12 are excluded, and the Java 8 compile dependency tree for the facade contains no Angus artifact.
- Optional-module loader tests retain focused missing-module errors, while final Message-ID and DKIM state assertions replace subtype checks.
