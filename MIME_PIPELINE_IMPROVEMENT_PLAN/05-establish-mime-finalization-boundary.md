# Step 5 — Establish an explicit MIME finalization boundary

- Status: Done
- Depends on: Steps 2–4
- Primary areas: Message-ID handling, `saveChanges()`, serialization, size/logging, protected-content state

## Goal

Replace subtype-based “message is safe” checks with an explicit lifecycle. Every signing operation must consume a finalized, repeatable MIME entity, and no later stage may mutate bytes covered by that signature.

## Target lifecycle

```text
mutable MIME assembly
  -> finalize headers, encodings, boundaries, date, and Message-ID
  -> content-protection transform(s)
  -> finalized repeatable entity
  -> optional final-wire DKIM transform
  -> provider adapter send
```

Each protection transform receives a finalized input and returns a new finalized output. A standard `MimeMessage` facade may still be needed by Jakarta Mail, but its Java subtype is not used to infer lifecycle state.

## Work

1. Introduce explicit mutable/finalized representations or state-bearing pipeline results.
2. Finalize Message-ID before the first cryptographic transform; preserve an explicit ID and publish the generated ID back to `Email` exactly once.
3. Remove `messageIsProperlyWrappedForCustomMessageId` and module-specific `isMessageIdFixingMessage` hooks.
4. Centralize every permitted `saveChanges()` call and prohibit it after final-wire signing.
5. Make size governance and MIME logging observe the effective outgoing representation.
6. Use a repeatable byte source for signed entities. If buffering is required, define a bounded memory threshold, temporary-file fallback, deterministic cleanup, and failure handling.
7. Add byte-identity assertions between the protected input, repeated serialization, and the SMTP server capture.

## Acceptance criteria

- [x] No production code uses message subtype recognition to decide whether Message-ID or finalization is safe.
- [x] Repeated serialization of a finalized protected entity produces identical protected bytes.
- [x] No `saveChanges()` occurs after DKIM or against an already signed inner entity.
- [x] Generated and explicit Message-IDs remain stable for plain, S/MIME, DKIM, and combined messages.
- [x] Maximum-size enforcement measures the representation that will actually be submitted.
- [x] Large protected messages do not require an undocumented unbounded duplicate in heap.
- [x] Temporary serialization resources are removed on success, failure, cancellation, and Mailer shutdown.
- [x] The `allow8bitmime=true` regression from Step 1 now preserves signature-covered bytes.

## Completion evidence

- `FinalizedMimeMessage` carries explicit lifecycle state instead of inferring safety from crypto/provider subtypes; obsolete Message-ID subtype hooks and wrappers were removed.
- `RepeatableMimeEntity` uses a 1 MiB in-memory threshold, spills larger entities to a temporary file, and is closed through `FinalizedMimeMessage`/`PreparedMail` ownership.
- `FinalizedMimeMessageStorageTest` verifies repeatable bytes, spill-to-disk behavior, and cleanup; OpenPGP tests verify repeated exact serialization of signed entities.
- `MailerTest` covers generated and explicit Message-IDs through DKIM and S/MIME combinations, and the maximum-size tests operate on the prepared outgoing representation.
- The real Angus `8BITMIME` regression verifies the signature after capture, proving that no provider rewrite crossed the finalization boundary.
