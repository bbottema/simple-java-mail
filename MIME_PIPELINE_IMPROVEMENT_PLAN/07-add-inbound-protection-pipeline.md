# Step 7 — Add a pre-parse inbound protection pipeline

- Status: Done
- Depends on: Step 5; may overlap with Step 6
- Primary areas: `EmailConverter`, MIME parsing, optional security modules, result metadata

## Goal

Allow security modules to inspect original MIME bytes before the generic parser recursively materializes multipart content. Verification/decryption returns an effective entity for normal parsing while preserving the original protected message and security result.

## Work

1. Introduce an ordered inbound protection-handler contract with MIME recognition that is specific enough to prevent PGP/S/MIME confusion.
2. Pass a repeatable original entity to the matching handler before `MimeMessageParser.parseMimeMessage`.
3. Return a result containing the original entity, effective clear entity, protection mode, verification/decryption status, identifiers/algorithms, and a safe failure reason.
4. Parse accessible clear content even when signature verification is invalid or a verification key is missing.
5. Keep encrypted content intact when no decryption key is available.
6. Migrate current S/MIME receive processing onto this hook without losing its compatibility behavior and attachment metadata.
7. Define a nesting/depth limit and reject ambiguous or recursive protection structures safely.

## Acceptance criteria

- [x] Protection recognition and verification happen before generic multipart traversal.
- [x] The parser receives decrypted content through its normal path rather than a second partial parser.
- [x] Original protected bytes and relevant headers remain available after success or failure.
- [x] Valid, invalid/tampered, key-missing, unsigned, decrypted, key-unavailable, and decryption-failed states remain distinct.
- [x] Cryptographic validity is reported separately from caller-defined trust.
- [x] A message recognized as OpenPGP is never offered to the S/MIME handler, and vice versa.
- [x] With a security module absent, conversion remains predictable and does not discard MIME parts.
- [x] Existing EML and Outlook S/MIME fixtures remain covered.

## API boundary

Security metadata belongs on the resulting `Email`/builder model through provider-neutral interfaces. Provider libraries, key-ring implementations, and Bouncy Castle result types must not leak into `core-module` APIs.

## Completion evidence

- `EmailConverter` invokes the OpenPGP and S/MIME preprocessing handlers before `MimeMessageParser`; the effective clear `MimeMessage` then follows the ordinary parsing path.
- `InboundProtectionHandler`, `InboundProtectionResult`, `OpenPgpParseResult`, and `SmimePreprocessingResult` expose only provider-neutral state and retain the original protected representation.
- `OpenPgpMimeTest` distinguishes valid, invalid, verification-key-missing, decryption-key-missing, wrong-passphrase/decryption-failed, and module-absent outcomes while retaining readable or encrypted MIME data as appropriate.
- OpenPGP processing enforces a bounded protection depth, and recognition ordering prevents a recognized OpenPGP entity from falling into S/MIME handling.
- `ReadSmimeSelfSignedTest`, `ReadSmimeAttachmentsTest`, and the Outlook live tests cover signed, encrypted, sign-then-encrypt, nested signature details, and attachment metadata.
