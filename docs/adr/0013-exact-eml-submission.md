# ADR 0013: Keep finalized EML authoritative within the ordinary Email API

- Status: Accepted; retrospective record of implemented behavior
- Decision recorded: 2026-09-16; not the original introduction date
- Applies to: Unreleased 10.0.0 exact EML construction, conversion and submission
- Implementation baseline: `codex/10.0.0` at `a4eda9e6`; pending ENVID additions are separate
- Related decisions: [ADR 0002: Governance](0002-email-defaults-and-overrides.md), [ADR 0011: Submission outcomes](0011-transport-neutral-submission-outcomes.md), [ADR 0014: Rehearsal](0014-send-time-rehearsal.md)

## Context

Parsing an EML file into a composed Email and sending it again is a reconstruction. Simple Java Mail selects an appropriate MIME structure and can generate new boundaries, dates and identifiers. This is useful for editing but cannot preserve an archived or externally signed message. An upstream DKIM or S/MIME signature may fail even when the reconstructed mail looks equivalent.

Callers with finalized RFC 822 bytes still need the Mailer's pooling, proxying, batching, observers and submission receipts. They also need an SMTP envelope independent of visible headers: Bcc recipients may be absent from the message, and relay destinations can intentionally differ from To/Cc.

## Decision

Keep `Email` as the public message type. `startingFromExactEml(...)` returns a constrained `ExactEmailBuilder` that accepts explicit envelope recipients, an optional envelope sender and supported envelope delivery options. It does not expose ordinary composition methods. The resulting Email works through the existing sending and rehearsal APIs.

Make the input bytes the sole authority for MIME content. The parsed Email getters are an inspection view; they are not the recipe used to reconstruct the message. Internally, `InternalEmail` owns an `EmailSource`: composed sources apply governance and render MIME, while `ExactEmlSource` returns a `FinalizedMimeMessage` from copied bytes and declares `PRESERVE_ALL_BYTES`. Keep this distinction at the source boundary rather than scattering exact/composed checks through every send mode.

Exact submission bypasses Email defaults/overrides, composed-message validation, embedded-resource resolution and DKIM/S/MIME/OpenPGP transformations. Content that is already final must not silently acquire headers, signatures or encryption. Envelope invariants and the configured maximum byte size still apply. Copying an exact Email through the ordinary composition builder intentionally creates a composed source and relinquishes the preservation contract.

Require explicit envelope recipients; do not infer them from parsed To/Cc/Bcc. Each configured value is one mailbox, repeated calls append and duplicate occurrences remain. The optional explicit sender is an envelope choice, not a request to rewrite From. When omitted, the provider derives its default; omission is not a separate guarantee of an SMTP null reverse path.

## Recorded rationale and evidence

- [#460](https://github.com/bbottema/simple-java-mail/issues/460), 2023, reports upstream DKIM failure after EML reconstruction changes MIME boundaries. The [maintainer's architectural explanation](https://github.com/bbottema/simple-java-mail/issues/460#issuecomment-1532548653) distinguishes composing suitable MIME from relaying an original message and proposes a separate relay entry point. This is the direct historical predecessor, not a claim that the eventual API used that proposed method name.
- [#706](https://github.com/bbottema/simple-java-mail/issues/706) records exact-byte preservation through `FinalizedMimeMessage` for detached S/MIME verification. It establishes the reusable byte-preservation foundation, rather than a general sending API.
- [#713](https://github.com/bbottema/simple-java-mail/issues/713) records the transport reuse, explicit-envelope and no-reconstruction requirements. It explicitly requires clear failure when a provider cannot preserve bytes and identifies raw byte length as the appropriate size boundary.
- [Commit `77002b8e`](https://github.com/bbottema/simple-java-mail/commit/77002b8e3de0532c2b5698bfa4d206a1bc94b8d9), 2026-08-27, implements the feature. The [implementation comment](https://github.com/bbottema/simple-java-mail/issues/713#issuecomment-5440994893) settles the public shape: Email remains canonical, getters expose the parsed message, and copied EML bytes remain authoritative through the existing APIs.

The source-strategy rationale is an architectural interpretation of the checked-in [EmailSource](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailSource.java), [ExactEmlSource](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/ExactEmlSource.java) and [ComposedEmailSource](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/ComposedEmailSource.java). No separate historical discussion was found that explicitly compares this implementation pattern with every alternative below.

## Input and transport boundaries

Copy byte-array input. Consume InputStream input immediately but leave that caller-owned stream open. Require nonempty input, canonical CRLF line endings, a terminating CRLF and successful Jakarta Mail parsing. These are preservation and usability checks, not a promise of exhaustive RFC validation. Do not require a visible From, recipient, Date, Subject, body or Message-ID, and never synthesize a missing Message-ID.

The generated CLI exposes the byte-array construction overload; its existing converter reads the argument from a file. It does not expose the caller-owned InputStream overload. Existing third-party adapters default to `NORMAL` content support until they opt in to stronger requirements; the deprecated boolean `PreparedMail` API remains a compatibility shim.

Declare preservation capability explicitly through `PreparedMail`/`ContentRequirement`. The bundled Angus adapter supports exact messages by suppressing save/rewrite behavior, disabling 8-bit rewriting and delegating serialization to the authoritative message. Exact serialization includes headers normally excluded by the provider, including Bcc and Content-Length. An adapter that has not opted into the required preservation capability must fail before submission; do not quietly downgrade to generic transport sending.

This ADR chooses the source authority and public API boundary. The provider adapter and protected-content boundary applies more broadly than exact EML. A compatibility rejection does not itself damage a pooled connection, so the normal transport runner can release that healthy lease for a subsequent compatible message. A CustomMailer receives the same Email and finalized MimeMessage and owns preservation after that delegation boundary.

## Alternatives and consequences

These comparisons are retrospective analysis; the relay-MimeMessage proposal and reconstruction problem are explicitly recorded in the historical discussion.

- **Parse and rebuild Email while preserving selected headers or boundaries:** cannot establish full preservation because other headers, encodings, ordering, dates or protected bytes can change. A parsed view is useful, but cannot replace the authoritative source.
- **Introduce another public sendable message type and duplicate send overloads:** separates the concepts clearly but expands every execution, batch, observer and custom-mailer surface. A constrained construction mode plus internal source strategy preserves the ordinary Email pipeline.
- **Accept a mutable MimeMessage as the sole public authority:** would require new ownership and mutation rules across asynchronous and pooled calls. Copied bytes give a stable source and defensive ownership.
- **Infer recipients from headers:** cannot represent hidden recipients or deliberate relay routing reliably. Explicit envelope data makes that choice reviewable.
- **Normalize line endings or strip Bcc automatically:** would be convenient but contradict exact submission. Reject unusable input and leave preparation of a safe outbound representation to the caller.

The caller gains byte-preserving submission but assumes responsibility for the already-final outbound content. Existing Bcc/Resent-Bcc, content metadata and signatures are preserved even when their presence is undesirable. Governance does not enforce a Mailer's content policy over exact messages. The API stores copied EML in memory; it is not a streaming spool or durable queue.

## Implementation limits and verification landmarks

[ExactEmailBuilderImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/ExactEmailBuilderImpl.java), [ExactEmlValidator](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/ExactEmlValidator.java) and [AngusMailTransportAdapter](../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusMailTransportAdapter.java) implement these boundaries. Rehearsal retains the original bytes in both modes; full rehearsal additionally checks their raw size. The CLI's byte-array argument uses its existing file converter, rather than introducing a second submission engine.

Pending ENVID work in the working tree extends envelope metadata. It does not authorize MIME mutation or governance of exact input. This record does not claim broader DSN recipient support, automatic delivery correlation or support from adapters that have not opted into preservation.

Existing regression landmarks are [ExactEmailBuilderTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/email/internal/ExactEmailBuilderTest.java) and [ExactEmailSendingTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/ExactEmailSendingTest.java). The documentation pass verified the implementation paths and historical references without rerunning Java tests.
