# Received Email: inbound inventory, 10 November 2026

Owned by inbound-model worker; pre-code checkpoint for the approved received-Email packet. No production changes at this checkpoint.

## Actual paths and selection boundary

`ConfiguredEmailConverterImpl.fromEml` captures byte[]/String input immediately, or acquires caller/path streams at the terminal. Its fluent EML reader uses `readPreservedEml` and `FinalizedMimeMessage` with the actual consumed bytes. Static EML conversion also uses that route. A caller-supplied MimeMessage is interpreted without inventing a raw original. `SourceReadException` distinguishes fully captured bytes from prefixes; cancellation is rethrown through `InterruptedIoGuard`. Path streams are closed by the owner; fluent borrowed streams remain caller-owned. MSG input is acquired once before `loadOutlookMessage`, but its captured array currently dies after conversion; the new original resource must retain that exact MSG storage.

The MIME conversion currently calls OpenPGP preprocessing once, then S/MIME only if PGP was not recognized. It parses one effective message, attaches source graphs, and calls `applySmimePreprocessingResult`/`decryptAttachments`. S/MIME's generated EML artifact is reparsed by `emlAttachmentToEmailBuilder`; a constructor then merges the two Emails. These branches are replaced by one explicit same-message layer/representation selection. `InputInspection` and `MimePartCapture` remain source grammar/capture owners; `ImportContext` and `ImportTraversal` remain one operation's shared allowances, not retained Email state.

`MimeMessageParser` distinguishes non-attachment body parts, root resource representation, ordinary attachment slots and CID resources. Its `plainSources`/`htmlSources` and `resourceSources` are already captured; expose immutable source references for selected plain/HTML/calendar and indexed resource occurrences. Current resource-source matching uses DataSource identity, which can conflate reused identities; the new mapper must bind each parsed occurrence by its traversal slot. `attachmentList` is currently a LinkedHashSet (Lombok MimeDataSource equality can collapse equal metadata); use ordered occurrence storage. The final HTML/resources classification must preserve unused collisions as attachment occurrences.

## Exact surfaces to remove or replace

- Email's `getDecryptedAttachments`, `getSmimeSignedEmail`, `getOriginalSmimeDetails`, `getOriginalOpenPgpDetails` and `SmimeContentState`/`wasMergedWithSmimeSignedMessage` constructor machinery. The latter OpenPGP getter and current source APIs are unreleased target-only refinements.
- EmailPopulatingBuilder's decrypted/detail/retained-inner getters and single-S/MIME merge controls (`notMergingSingleSMIMESignedAttachment`, `mergingSingleSMIMESignedAttachment`, `isMergeSingleSMIMESignedAttachment`, retained-inner clear). Exact released JVM descriptors belong to migration-owner's published baseline.
- Main InternalEmailPopulatingBuilder's flattened incoming detail/inner/decrypted setters and EmailPopulatingBuilderImpl's corresponding state, copied-content state and materialization helper.
- `EmailStartingBuilderImpl.copying` is output-worker-owned: remove legacy restoration and restore the final immutable field/occurrence snapshot at the end, after ordinary setters. Helper-output requirements are a separate immutable state and must also be restored at the end.
- EqualsHelper drops only the two removed flattened protection comparands. It keeps its selective represented-value/configuration purpose, performs no payload reads and does not compare entire source/inspection graphs.

## Proposed minimum assembly contract

Core owns immutable `EmailInspection`/`ValueInspection`/family/signature/decryption result contracts and internal immutable assembly. Main owns an import selection accumulator and builder mutation tracking. The accumulator contains a finite internal field membership, ordered attachment/image occurrence metadata and originals; it stores no parser, parent Email, provider object, callback or private key. `Email.getInspection()` is passive. Missing snapshots restored from older streams explicitly report unknown history.

`AttachmentResource.getEmail()` is passive/nullable; internal `ParsedAttachmentResource` can attach a parsed child through a protected metadata-copy constructor plus library-internal factory, without a public builder setter or stream reads. `toBuilder()` selects a replacement resource and drops parsed-child association; ordinary Email copies share the unchanged resource. Child content is never promoted to parent fields or files. The original attachment remains distinct when the usable payload was transformed.

One-layer crypto results expose recognition, opened effective content, real protected authored-header ownership, immutable signature/decryption observations and encountered content failure information. Main owns repeated/cross-family recursion under shared traversal limits and binds observations to actual source occurrences. Independent resources use an explicit attachment role, even when the only attachment. A generated EML adapter is never proof of authored headers or an independent message.

## Header roles and authority

| Role | Read selection | Reconstructed output |
| --- | --- | --- |
| Subject; From/Sender; To/Cc/Bcc; Reply-To | One real authored view; protected omissions meaningful; body-only adapters do not replace native authored fields. Header read view agrees with available typed values. | Typed current authority emitted once. Explicit typed setter removes stale generic active value. |
| Message-ID, Date, References, In-Reply-To | Same selected authored identity; no generated wrapper/carrier substitution. Header raw fallback retained when interpretation fails. | Current ID/date/thread fields only, under producer/defaults behavior. Reply identity distinct from selected parent. |
| Return-Path | Readable raw occurrences; best-effort `getBounceToRecipient` preserved. | Raw delivery field omitted by role; typed Bounce-To remains normal SMTP envelope intent. |
| Received, Resent-*, Authentication-Results, DKIM/ARC, prior content checksums/status | Readable carrier/native history and exact source observations, not joined across independent messages. | No automatic new delivery/authentication claims. Explicit reserved choices must be characterized and rejected clearly when incompatible. |
| MIME-Version, Content-Type/Disposition/Transfer-Encoding/ID and naming metadata | Useful source values remain readable; component values stay component-owned. | Producer owns current MIME structure and resource metadata. No stale source structural override. |
| Additional/custom names | Complete selected authored list wins case-insensitively; nonconflicting carrier values can remain. Preserve per-name occurrence order. | Emit appropriate current additional values; don't blanket-copy them into a newly constructed reply/forward. |

`MimeMessageEmailMapper` currently uses `HeadersToIgnoreWhenParsingExternalEmails` to discard many useful values; remove the read-side suitability filter. Producer filtering is output-worker-owned and must act by role rather than global imported/composed provenance. The current Email header snapshot is LinkedHashMap; change to an immutable case-insensitive read map and reconcile typed/generic setter behavior.

## Vertical acceptance and lifecycle

First create a real eager EML containing a same-message protected plain representation, native HTML and an independently protected message attachment. Assert usable fields/files, each source/coverage, passive child identity, complete exact originals and one aggregation of child Problems. Copy it, replace one body and one occurrence (including equal text/resources), and check old source facts remain historical while current replacements become caller-supplied/unknown. Output worker then protects an interpreted forward and checks emitted attachment bytes against its ordinary selected bytes; original forwarding compares exact retained payload bytes. Pair ordinary unprotected, missing-module and exact-relay controls.

Follow with native MSG source/RTF/date variants, calendar pairing, selected authored omissions, protected IDs/threading, duplicate/CID collision and unused-image cases, unavailable/failed/deferred processing and import stops, source read/close failures, child replacement, snapshots including uncaptured older state, equal/reused occurrences without payload reads, two factory scopes and bounded retention/copy measurements. Full gates belong to lead's final combined sources.
