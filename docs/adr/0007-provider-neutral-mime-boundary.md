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

### Provider discovery lifetime (#749)

Discover submission, lifecycle and probe adapter factories once per thread-context class loader. Keep the factory list, not a shared `ServiceLoader` or adapter instance: every operation still constructs its own adapters and checks the actual transport or provider. Abort actions and send state remain operation-owned. Discovery failures are retried, and constructor failures do not poison the factory list. JPMS provider methods remain supported alongside classpath constructors.

The cache belongs to the application loader's lifetime. A JDK-cached proxy class for the SPI supplies a loader-local identity for `ClassValue`; no proxy instance is created. This avoids a global map retaining an application loader through its cached provider classes, which even weak loader keys alone would not prevent. If the context loader cannot see the SPI or cannot supply a proxy class, use ordinary uncached discovery instead of adding a new failure or retaining an application through an unrelated parent loader. Registrations are fixed for that loader's lifetime; replacing the loader discovers a new set. This is not an API for changing service files or installing providers into a running loader.

MIME conversion reuses Jakarta Mail's existing Session/message initialization instead of doing another availability lookup at every entry point. Conversion Session creation retains the helpful missing-implementation diagnostic. Jakarta Mail's own static MIME utilities still perform their own discovery; this change does not replace that implementation, change the context class loader or select Angus globally. See the [performance follow-up](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/performance-audit/provider-discovery-results.md).

### SMTP content negotiation (#742)

The selected Session's converter supplies its server-specific legacy-content permission in immutable `PreparedMail` data. This is operational configuration, not Email policy or a mutable Session property. The Angus adapter derives SMTPUTF8 and 8BITMIME requirements from the envelope and actual submitted content, checks the connected transport's post-TLS capabilities, and carries immutable command choices in its existing message facade. The managed command hook adjusts only SMTPUTF8 parameters after the sender path; Angus still owns SMTP sequencing, authentication, TLS and body conversion. Choices are installed and cleared under the existing transport monitor, including exceptional exit.

UTF-8 encoding capability is initialized once on library-owned stock Angus Sessions, including offline preparation, before MIME or transports exist. It does not imply a per-message declaration. Protocol hooks are independent of socket tracking: custom factories remain installed, without gaining unsupported physical abort. Caller-owned Sessions, third-party providers and `CustomMailer` retain their ownership; ordinary caller-owned Angus transports keep Session-wide declaration behavior.

The strict default requires advertised capabilities. `withLegacySmtpContentSupport(true)` permits unchanged attempts on a verified tolerant server and route without automatically declaring missing extensions. It deliberately operates outside negotiated SMTPUTF8/8BITMIME guarantees, never rewrites protected content or addresses, and does not relax TLS, REQUIRETLS, explicit DSN, internationalized ORCPT capability gates or content-safety checks. Rejection is reported without an altered-content retry. No connection upgrade, replacement pool or per-message reconnect is needed. See the [implementation plan](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/08-make-smtputf8-and-8bitmime-requirements-explicit.md).

### Connected SIZE preflight (#748)

The managed Angus transport measures the prepared content at its `mailFrom()` hook, after Angus's optional 8-bit conversion and before any SMTP submission command. It streams serialization through Angus's CRLF normalizer and a long counter, excluding transparency dots and the DATA terminator. It neither rebuilds exact/protected content nor retains another full copy. Ordinary caller-owned DataSources must remain stable and repeatable across preparation, counting and sending.

Connected SIZE checks scan the complete EHLO reply and retain only support and a reliable maximum, independently of the probe's bounded diagnostic snapshot. Both paths share numeric interpretation and preserve conflicting duplicate advertisements instead of relying on Angus's last-value map. Each connection clears its SIZE facts on reconnect, failed EHLO, HELO and close; successful post-STARTTLS discovery replaces the earlier facts. Only a reliable positive maximum permits local rejection; SIZE advertisement without a usable maximum still permits a size declaration. Rejection before MAIL FROM uses the existing compatibility-failure path and leaves a healthy lease reusable.

The count belongs to the per-attempt message facade and flows through the existing result/receipt boundary. Counting observes the existing abort flag; incomplete counts remain absent. No new lock, pool, spool file or connection probe is introduced. Caller-owned ordinary Angus transports are not replaced, and other providers can supply reliable size facts through their adapter without inheriting Angus-specific behavior. See [step 9's accepted contract](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/09-enforce-size-against-finalized-transmitted-bytes.md) and [RFC 1870](https://www.rfc-editor.org/rfc/rfc1870.html#section-5).

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
