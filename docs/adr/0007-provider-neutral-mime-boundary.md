# ADR 0007: Separate finalized MIME from provider-specific submission

- Status: Accepted (retrospective record of implemented design)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Unreleased 10.0.0 MIME, cryptography, conversion, and transport integration
- Implementation status: Implemented; Angus is the bundled adapter, with explicit third-party extension boundaries

## Context

OpenPGP/MIME needs signatures over exact serialized MIME entities while retaining normal Mailer governance, proxying, pooling, and receipts. The existing combination of provider-specific message subclasses and security wrappers made it difficult to distinguish MIME content from SMTP envelope behavior and to know when another serialization could invalidate a signature.

The archived [MIME pipeline plan](https://github.com/bbottema/simple-java-mail/blob/127b2128a38a592f166b6ca451ef33f283963185/MIME_PIPELINE_IMPROVEMENT_PLAN/README.md) explicitly calls for removing provider-specific SMTP behavior from MIME and cryptography, avoiding another matrix of message subclasses, and preventing post-signature `saveChanges()` or transfer-encoding rewrites. [#704](https://github.com/bbottema/simple-java-mail/issues/704) supplies the initiating OpenPGP use case.

## Decision

Construct and protect MIME using Jakarta Mail APIs and provider-neutral Simple Java Mail contracts. Pass the resulting `MimeMessage`, transport recipients, a separate `DeliveryEnvelope`, and an explicit `ContentRequirement` through `PreparedMail` to a `MailTransportAdapter` discovered with `ServiceLoader`.

The adapter owns the final `Transport.sendMessage(...)` invocation and provider-specific envelope behavior. It does not acquire, connect, pool, close, or invalidate the transport. Those lifecycle responsibilities remain with the sending flow. Angus-specific `SMTPMessage` facades are allowed inside the Angus adapter, without becoming the canonical message representation or leaking into public MIME contracts.

At the content boundary:

1. Finish headers, Message-ID, encodings, and MIME boundaries before protection.
2. Each protection transform consumes a finalized representation and produces a new one; repeated writes preserve protected bytes.
3. Apply DKIM to the final protected representation.
4. Require adapter capability for `PRESERVE_PROTECTED_CONTENT` or `PRESERVE_ALL_BYTES`; ordinary messages use `NORMAL`.

Exactly one supporting adapter may handle a transport. Ambiguity fails before submission. The generic Jakarta Mail fallback is available only for ordinary content and envelope requirements it can honor. Unsupported preservation or provider-specific options fail explicitly rather than silently losing their meaning. These checks occur at dispatch on an already acquired/connected transport; “before submission” does not mean “before any network connection.”

## Rationale and evidence

[883814aa](https://github.com/bbottema/simple-java-mail/commit/883814aadcf65252f3163d3a04ed4846f2a98dd6) implemented the provider-neutral OpenPGP pipeline. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/704#issuecomment-5320304898) confirms that Angus remains the default runtime dependency but no longer belongs to the facade's compile or JPMS API. It also records independent OpenPGP interoperability and transport checks; these are historical results, not tests rerun for this ADR.

The [finalization step](https://github.com/bbottema/simple-java-mail/blob/127b2128a38a592f166b6ca451ef33f283963185/MIME_PIPELINE_IMPROVEMENT_PLAN/05-establish-mime-finalization-boundary.md) explicitly replaces subtype-based safety checks with a repeatable byte boundary. Its original temporary-file storage was subsequently removed by [fcf8c3b2](https://github.com/bbottema/simple-java-mail/commit/fcf8c3b257900a02d31df82be1dd569391227b90). Current protected storage is in memory; the archived plan's spill threshold and cleanup claims are not current behavior. The simplification in ownership is visible in that diff, but no separate contemporaneous explanation for that storage tradeoff was found.

[77002b8e](https://github.com/bbottema/simple-java-mail/commit/77002b8e3de0532c2b5698bfa4d206a1bc94b8d9) later made the content-preservation requirement explicit for exact EML. That extends this seam; [ADR 0013](0013-exact-eml-submission.md) records the exact-message contract itself.

## Alternatives

The archived plan explicitly avoids **another provider/security subclass matrix** and **implementing SMTP inside Simple Java Mail**. **Manual conversion followed by application-owned transport sending** was the #704 workaround, but would lose the desired integrated delivery path. **Generic fallback for protected messages without a capability promise** is incompatible with the selected preservation contract. These are distinct from claiming that every alternative underwent a formal historical review.

## Consequences

Conversion can run without an SMTP provider, and cryptography no longer requires Angus types. Sending still needs a Jakarta Mail provider. The default `simple-java-mail` dependency supplies Angus at runtime; another provider must supply adapters for stronger content or envelope requirements.

Protected representations cost memory because their authoritative bytes must remain repeatable. Ordinary conversion does not acquire an additional full protected snapshot merely for using this API. Public adapters must make honest preservation claims, preserve exceptions and recipient facts, and remain separate from transport ownership. The uncommitted fixed-ENVID capability extension present while this ADR was recorded follows the envelope seam but is separate work, not evidence that all DSN options or providers are complete.

## Implementation anchors

[PreparedMail](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/PreparedMail.java), [MailTransportAdapter](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/MailTransportAdapter.java), [resolver](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/MailTransportAdapterResolver.java), [FinalizedMimeMessage](../../modules/core-module/src/main/java/org/simplejavamail/internal/util/FinalizedMimeMessage.java), [Angus adapter](../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusMailTransportAdapter.java), and [migration guidance](../../MIGRATION-10.0.md) define the current implementation. The [provider-neutral classpath](../../modules/simple-java-mail/src/test/provider-neutral-classpath) and [JPMS](../../modules/simple-java-mail/src/test/provider-neutral-jpms) fixtures preserve the packaging boundary.
