# ADR 0035: Read imperfect mail tolerantly; produce standards-compliant mail

- Status: Accepted
- Decision recorded: 2026-10-17; records the maintainer's established import philosophy, not an invented original decision date
- Applies to: Inbound MIME, EML and Outlook conversion, interpreted source facts, and their boundary with outbound production

## Context and decision drivers

Applications import mail produced by other software, not only by Simple Java Mail. Outlook sources, archived EML and received headers can be
incomplete or malformed while still containing useful bodies, addresses and report data. Rejecting the entire import because one optional field
violates a grammar makes the library less useful as an interoperability boundary. The [release history](../../RELEASE_HISTORY.md) records
[#321](https://github.com/bbottema/simple-java-mail/issues/321) in 6.6.1 as ignoring malformed recipient addresses and continuing email parsing.
Existing recipient parsing deliberately uses Jakarta Mail's non-strict header parser, with a source comment linking the compatibility history in
[#227](https://github.com/bbottema/simple-java-mail/issues/227).

Reading and producing have different responsibilities. An imported value can be useful evidence without being suitable for a new outgoing message.
Recovery must also distinguish a harmless formatting defect from conflicting evidence, unreadable content and failed cryptographic verification.

## Decision

Read external mail with leniency and produce standards-compliant mail. Preserve as much clearly interpretable source information as possible;
isolate malformed fields or optional interpretation failures instead of discarding an otherwise readable Email.

- Accept safely recoverable formatting differences when their meaning is unambiguous. Recovery changes the inspection view, not the source bytes.
- Retain usable neighboring fields and completed blocks when another field or optional interpretation fails. Use the relevant existing warning or
  result metadata to explain recovery, ambiguity or incomplete extraction. Do not make getters perform another read.
- Preserve original data through the existing source/attachment mechanisms where available. Unrecognized formats need not disappear merely because
  a typed convenience view cannot be constructed.
- Leave genuinely ambiguous facts unavailable. Conflicting delivery actions, multiple competing reports or an unknown timezone do not justify choosing
  an arbitrary result. An impossible date must not roll into another day just to produce a timestamp.
- Keep typed-value invariants: construct a typed view only after establishing its meaning. Tolerant source interpretation does not require invalid
  enums, out-of-range numeric components or misleading convenience objects.

Use Jakarta Mail and existing specialist importers for their parsing responsibilities. Add narrow recovery at the interpretation boundary, not a
second general MIME parser, global permissive JVM/Session settings, indiscriminate exception handling or an automatic repair-and-retry pipeline.

Missing attachment naming metadata must not discard an otherwise readable message. When both the filename and Content-Type header are absent,
retain the existing `UnknownAttachment` fallback without requiring content reads or changing eager/lazy ownership. Keep the established forwarded
message naming rule separate from this missing-header correction. This application was accepted on 22 October 2026 under
[#837](https://github.com/bbottema/simple-java-mail/issues/837). The correction is implemented and reviewed locally for unreleased 10.0.0;
the issue tracks delivery.

### Uninterpretable imported Return-Path (accepted 22 October 2026)

Preserve source evidence without manufacturing a parsed bounce recipient. For MIME/EML and Outlook import, a Return-Path that produces no
address or cannot be parsed remains available through the existing `Email.getHeaders()` map; `getBounceToRecipient()` remains null for that
value. Continue importing neighboring readable content. Missing/blank and explicit null return paths do not establish a bounce address;
successfully parsed input keeps the existing mailbox/name interpretation. This correction is tracked by
[#838](https://github.com/bbottema/simple-java-mail/issues/838). It is implemented and reviewed locally for unreleased 10.0.0; the issue tracks
delivery.

This distinguishes imported source interpretation from the existing lenient caller-built Recipient contract. Caller builders can still retain
nonempty unparseable input, including a successful zero-mailbox parse, for ordinary outgoing validation instead of inventing an address or
changing its chosen name. Missing-input checks remain. This decision does not normalize every inbound address field, change recipient-group
semantics, add a second source-header API or apply an outgoing validator while reading external mail.

Clarification accepted 2026-10-21: list-capable Email conveniences and plural recipient inputs accept complete mailbox lists and expand RFC
group members under [ADR 0001](0001-email-configuration-scopes-and-inheritance.md#accepted-address-list-follow-up-20-october-2026).
Use the existing Jakarta Mail grammar with narrow legacy-delimiter compatibility, then apply name and recipient-group policies. Quoted
punctuation, comments and escapes are not separators; retain order, duplicate occurrences and unparseable nonempty source without guessing,
discarding entries or checking deliverability/DNS during interpretation. JMail remains the configured outgoing address validator.

The single-mailbox argument guard belongs to From/bounce conveniences: a successfully parsed list or RFC group directs callers to a plural API,
even when a group has one member or is empty. Individual Recipient builders keep their tolerant single-value contract, including retained
malformed/plural/group source where documented; specialized extraction must not silently discard all but the first mailbox. Keep address-only
name preservation and explicit/inferred-name precedence. Prepared Recipient values and authoritative inbound MIME/Outlook interpretation retain
their separate contracts; do not apply the outgoing guard to imported parsed facts. This clarification is implemented under
[#824](https://github.com/bbottema/simple-java-mail/issues/824) for unreleased 10.0.0.

### Misplaced child headers (accepted 22 October 2026)

Tolerant reading preserves the meaning and owner of an interpretable field. A Return-Path or receipt header on an attachment does not establish
a bounce address or receipt destination for its parent email, and arbitrary child headers do not belong in the parent's `Email.getHeaders()`.
Use only the effective root message's headers for those facts, after the existing protection processing described by
[ADR 0008](0008-minimal-mime-structures-and-protection-order.md#root-message-and-child-header-ownership-accepted-22-october-2026).

Keep the readable message and existing attachment metadata/content. Ignore misplaced child fields at the message level; there is no demonstrated
need to retain arbitrary child headers in a new API, storage or warning scheme. Do not reject the message for those fields. Missing or unusable
root fields keep their own existing interpretation, independently of child values. This correction is accepted under
[#840](https://github.com/bbottema/simple-java-mail/issues/840) for unreleased 10.0.0; the issue tracks implementation and delivery.

### Explicit multipart attachment boundaries (accepted 22 October 2026)

An explicit attachment disposition on a child multipart applies to the complete resource. Preserve that container as one readable attachment,
including its valid Content-Type parameters and existing resource metadata; do not interpret its text or nested resources as the parent's content.
Traversing ordinary multipart bodies remains useful for external mail, including repeated text/HTML content. Keep that established behavior for
inline and filename-only containers and for the effective root selected after protection processing.

This is a narrow source-meaning correction under [#849](https://github.com/bbottema/simple-java-mail/issues/849), accepted for unreleased 10.0.0.
It does not add arbitrary MIME-tree preservation, alter cryptographic preprocessing or relax exact-message preservation.
[ADR 0008](0008-minimal-mime-structures-and-protection-order.md#explicit-child-multipart-attachments-accepted-22-october-2026)
records the pipeline boundary; [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#multipart-attachment-content-ownership-accepted-22-october-2026)
preserves attachment read timing and ownership.

### Calendar method interpretation (accepted 22 October 2026)

Recognize existing calendar method names and values without regard to capitalization during MIME/EML import. Use Jakarta Mail's Content-Type
parameter grammar so quoted values, whitespace and case-insensitive parameter names retain their meaning. A genuine MIME `method` parameter
takes priority over the calendar body's `METHOD`; an unrelated parameter name or quoted value must not supply that method. Map a recognized
value to the existing `CalendarMethod` enum using locale-independent case conversion while preserving the original calendar text and source bytes.

Keep existing missing/unsupported-method failures and narrow malformed-header/body fallback. This correction also retains the historical leading
method-token interpretation for a genuine parameter such as `method="REQUEST extra"`, and the first recoverable token when a method parameter is repeated.
Deciding whether to reject that suffix or repeated parameters is separate from case recognition. Recovery must still identify the genuine parameter
rather than searching other parameter names or quoted values for method-like
text. Do not add an extension enum, invent a method, rewrite calendar text or change protection preprocessing, attachments or stream ownership.
The accepted correction is tracked by [#851](https://github.com/bbottema/simple-java-mail/issues/851) for unreleased 10.0.0; its issue tracks delivery.
[ADR 0013](0013-exact-eml-submission.md) preserves the separate exact/protected-byte boundary.

### Receipt-header name recognition (accepted 22 October 2026)

Recognize `Disposition-Notification-To` and `Return-Receipt-To` by case-insensitive field names during MIME/EML import. Canonical,
lowercase, uppercase and mixed-case names identify the same receipt field; interpret its value with the existing mailbox-list parser,
retaining destination order, duplicate occurrences and mailbox/display-name spelling. A recognized usable field supplies the existing
typed recipient list and request flag rather than an additional generic-header copy.

Missing, blank, explicit null-path and unusual values retain their existing value interpretation and source-header behavior. This is a
narrow application of [ADR 0002](0002-email-defaults-and-overrides.md) header identity and [ADR 0001](0001-email-configuration-scopes-and-inheritance.md)
notification ownership. Only the effective root supplies these fields, as established above; separately importing an attached message
interprets that message's own root. Do not normalize other generic-header names or values, rewrite exact/protected source bytes, add
a header API or send a receipt during conversion. The accepted correction is tracked by
[#846](https://github.com/bbottema/simple-java-mail/issues/846) for unreleased 10.0.0; the issue tracks implementation and delivery.

### Attachment Content-Type parameters (accepted 22 October 2026)

Valid Content-Type parameters belong to the attachment's interpretable metadata. Preserve parameters such as `charset` and valid extension
parameters during ordinary MIME import and export, without an arbitrary parameter-name allowlist. An ISO-8859-1 text attachment must retain
its encoding instruction along with its unchanged bytes. Parameter preservation does not make a supplied `name`, `filename` or their encoded
forms authoritative: existing SJM resource naming still determines the outgoing filename.

Use Jakarta Mail's MIME parsing and serialization. Safely unfold valid header continuations, keep the established usable-base recovery for
malformed or injected parameter input and the binary fallback for an unusable base type, and never emit decoded line breaks from a parameter
value as header lines. Eager and lazy attachment extraction expose equivalent safe type metadata without extra content reads. This applies
the ownership boundary in [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#attachment-type-metadata-accepted-22-october-2026).
No new public metadata API, configuration switch or caller escaping rule is needed. The accepted repair is tracked by
[#842](https://github.com/bbottema/simple-java-mail/issues/842) for unreleased 10.0.0; the issue tracks implementation and delivery.

### Present empty custom headers (accepted 22 October 2026)

A custom header may be present with no value. The import requirement in [#109](https://github.com/bbottema/simple-java-mail/issues/109)
and [#123](https://github.com/bbottema/simple-java-mail/issues/123) dates to 2017/2018: an empty `X-MS-TNEF-Correlator` must not prevent
reading otherwise useful mail. The public builder also accepts null. During ordinary outgoing validation and rendering, interpret a stored
null value as an empty String and emit a present empty field, just as for an explicit empty String. Do not omit the field or emit the text `null`.

Keep stored metadata unchanged through inspection, copying and supported serialization under
[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#nullable-custom-header-metadata-accepted-22-october-2026).
Existing case-insensitive header identity, value ordering, distinct-value handling and replacement rules remain in force; null and empty
remain distinct stored choices even when they render alike. Preserve stored whitespace and existing nonempty encoding/folding. A null value never
bypasses header-name injection checks. Exact and protected source bytes retain their separate preservation boundaries. This clarification is
accepted under [#848](https://github.com/bbottema/simple-java-mail/issues/848) for unreleased 10.0.0; the issue tracks implementation and delivery.

## MIME transfer-encoding comments (accepted 23 October 2026)

A valid parenthesized comment in `Content-Transfer-Encoding` does not change the encoding. Use Jakarta Mail's existing MIME interpretation
before mapping an unambiguous source encoding to the public typed value, rather than treating the raw comment text as part of its name.
For example, `base64 (encoded for transport)` establishes the same encoding as `base64`.

Keep unsupported encodings and genuinely unreadable content on their existing failure paths. This correction does not choose between
conflicting repeated encoding declarations, introduce permissive JVM/Session settings or change eager/lazy resource ownership. Interpretation
does not rewrite authoritative exact or protected input bytes. Public methods and enum values remain unchanged.

This applies the comment grammar in [RFC 2045 section 1](https://www.rfc-editor.org/rfc/rfc2045.html#section-1), follows the existing MIME
delegation decision, and was accepted under [#856](https://github.com/bbottema/simple-java-mail/issues/856) for unreleased 10.0.0. The issue
records delivery of the implementation.

## Failure and security boundaries

An optional interpretation failing is different from failing to obtain the message content the caller requested. For example, optional delivery-report
extraction can preserve completed report blocks and warn after a read failure. A required eager attachment read can still fail conversion with its
original cause; returning a silently truncated attachment would falsely claim that its content was obtained. Structural corruption that prevents a
meaningful Email, cancellation, missing required inputs and programming failures remain errors. Never catch arbitrary runtime failures as malformed mail.

Leniency is not proof of authenticity or permission to retry. Continue verifying signatures over the original bytes, reporting inaccessible ciphertext
honestly, and preserving exact/protected representations. Do not normalize signed bytes to manufacture a successful verification result.
The unavailable S/MIME inspection clarification in [ADR 0008](0008-minimal-mime-structures-and-protection-order.md#unavailable-smime-inspection-accepted-2026-10-21)
keeps uninspected protection distinct from confirmed absence and from cryptographic failure while retaining tolerant ordinary import.
The corresponding OpenPGP distinction is implemented for unreleased 10.0.0 under [#839](https://github.com/bbottema/simple-java-mail/issues/839).
[ADR 0008](0008-minimal-mime-structures-and-protection-order.md#unavailable-openpgp-inspection-accepted-22-october-2026)
records the unknown/missing-key/failure distinction.

EML-to-Email conversion retains the original protected bytes before a MIME representation can normalize tolerated formatting, including LF or
mixed header line endings. The configured fluent route and static compatibility route have the same source-preservation obligation. A direct
MIME export remains a separate representation with its existing serialization behavior.

Required-content failures during export also need an ordinary conversion explanation and their original cause. An unavailable attachment or
a provider's serialization failure must not be described as an impossible internal state.

Outgoing composition, send-time validation, injection checks, transport requirements and explicit exact-EML submission restrictions keep their existing
contracts. Successful import does not promise successful sending. Exact submission is a preservation mode, not permission to repair or weaken its byte
or transport-safety requirements. Retain existing resource/size bounds; reading tolerantly does not authorize unbounded work on hostile input.

## Alternatives considered

| Alternative | Benefit | Reason not selected |
| --- | --- | --- |
| Reject every nonconforming source | Simple validity rules | Loses useful mail and repeats historical interoperability failures. |
| Silently repair everything | Fewer apparent import failures | Hides uncertainty and can invent dates, recipients, delivery results or verification success. |
| Make every importer strict unless callers enable a lenient mode | Explicit selection | Burdens normal interoperability with a switch and fragments established default behavior. |
| Catch every exception and return an Email | Maximizes apparent availability | Conceals programming failures, unreadable content and incomplete attachments. |
| Retain only raw bytes and leave interpretation to applications | Avoids recovery policy | Gives up the library's useful mail-domain knowledge and convenient Email read model. |

## Imported sent-date interpretation (accepted 27 October 2026)

MIME/EML source Date headers must establish a usable timestamp before populating the Email sent-date field. Leave impossible calendar values
and missing or unrecognized timezone information unavailable, while continuing to import readable neighboring content. Preserve Jakarta Mail's
unambiguous legacy syntax, including obsolete year/zone forms; changing its entire grammar to strict mode would reject useful source facts.
Calendar rollover and a fallback that silently treats an unknown timezone as UTC are not safe recovery. Trailing RFC comments remain source
formatting, while competing suffix text does not establish a date.

Keep the existing first-Date selection and ordinary filtered-header contract. This adds no raw-header or warning API. Exact inspection may expose
an absent interpreted date while retaining every authoritative source byte under ADR 0013. Ordinary reconstructed MIME keeps its existing current-date
generation when the Email has no fixed date. Outlook's separately typed source-date selection is outside this correction. [#870](https://github.com/bbottema/simple-java-mail/issues/870)
tracks implementation and delivery for unreleased 10.0.0.

## Consequences

Import remains useful for imperfect real-world sources. Applications can distinguish recovered facts from unavailable information through the existing
feature-specific metadata, while raw source representations remain authoritative. Maintaining this boundary requires malformed and partial-input fixtures,
not only well-formed round trips. Recovery rules must be narrow, explainable and tested against ambiguous neighboring cases.

This is a cross-cutting decision, not a feature inventory or a claim that every historical parser already recovers every defect. Apply it at the boundary
being changed; a broader importer rewrite or new general warning API requires its own scope and compatibility assessment.

## Verification obligations

Pair each recovered example with an ambiguous or unsafe counterpart. Cover neighboring usable fields, partial reads, immutable snapshots, warning
aggregation, unknown formats and original-cause preservation where content remains required. Preserve exact/protected bytes and signature results, and
prove that recovery adds no unrelated attachment reads or getter-side I/O. Retain outgoing validation, injection and singular From/bounce rejection controls.

## Related decisions and evidence

- [ADR 0006](0006-optional-modules.md): optional specialist conversion modules, including Outlook.
- [ADR 0007](0007-provider-neutral-mime-boundary.md): source/content and provider responsibilities.
- [ADR 0008](0008-minimal-mime-structures-and-protection-order.md): protection before interpretation and source facts through Email.
- [ADR 0013](0013-exact-eml-submission.md): authoritative finalized bytes and explicit submission restrictions.
- [ADR 0014](0014-send-time-rehearsal.md): outgoing validation remains separate from incoming interpretation.
- [API expansion workflow](../API_EXPANSION_WORKFLOW.md#4-message-conversion--processing-simple-java-mail): contributor checks for this boundary.

The delivery-report application is tracked by [#763](https://github.com/bbottema/simple-java-mail/issues/763) and documented in the
[delivery-status report guide](https://www.simplejavamail.org/delivery-reports.html).
[DeliveryStatusReportTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/converter/DeliveryStatusReportTest.java) supplies regression coverage;
dated execution evidence belongs in commit and CI history, not this architectural decision.

[OutlookAddressImportTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/converter/OutlookAddressImportTest.java) covers the import/composition
boundary for sender, Reply-To, receipt and bounce fields, including legacy single-value From/bounce interpretation, complete Reply-To/notification lists, retained source headers and neighboring message content.

## Accepted convenient list parsing (20 October 2026)

The ordinary Email recipient, Reply-To and notification String conveniences parse every mailbox rather than reject lists or select their first member. Preserve quoted punctuation, supported separators, order and duplicate occurrences; flatten address groups into members. A nonblank supplied name overrides every embedded name. Unparseable nonempty input is retained for the configured validator. From/bounce retain their singular guard, and the individual Recipient builder retains its specialized extraction contracts.


## Undecodable optional root-header text (accepted 23 October 2026)

When an optional generic root-header value cannot be decoded because its encoded-word charset is unavailable, retain the original undecoded value and safely unfold legal header continuations. Continue interpreting neighboring fields and readable message content without inventing decoded characters. This is a narrow value-recovery boundary, not permission to suppress arbitrary parser or application failures.

Typed receipt/Return-Path interpretation, required body and resource decoding, filename/description decoding and outgoing injection validation retain their existing contracts. Ordinary rendering still encodes and folds supported header values safely; recovered source text must not create a new delayed validation failure for otherwise safe input. Successful import remains distinct from successful sending. Exact and protected representations keep their authoritative bytes. No new warning API or recovery setting is introduced. [#861](https://github.com/bbottema/simple-java-mail/issues/861) tracks implementation and delivery.

## Outlook receipt route parity (accepted 23 October 2026)

Apply the receipt-header name-recognition decision to Outlook MSG import as well as MIME/EML. Disposition-Notification-To and Return-Receipt-To names are case-insensitive across the shared static/fluent, file/path/stream, builder/data-returning and nested Outlook routes. Preserve existing value interpretation, destination order and duplicates, names, unusual/empty/null-path handling, generic-header spelling and message ownership; do not broadly normalize other headers. This completes the same interpretation boundary described in [#407](https://github.com/bbottema/simple-java-mail/issues/407#issuecomment-1189155751) and the MIME/EML correction under #846. [#863](https://github.com/bbottema/simple-java-mail/issues/863) tracks this separate route correction.

## HTML character references in CID attributes (accepted 28 October 2026)

Interpret character references in HTML attribute values before decoding a CID URL's percent escapes once. Thus
`src="cid:logo&#64;example.org"` refers to `Content-ID: <logo@example.org>`. Preserve the source HTML, case-sensitive Content-ID,
literal plus signs, resource bytes and occurrences. Attribute interpretation must not apply HTML character-reference decoding to
CSS raw-text content or introduce general HTML rewriting. Existing malformed-reference and authoritative-byte boundaries remain.
[#885](https://github.com/bbottema/simple-java-mail/issues/885) tracks this accepted correction for unreleased 10.0.0.

Clarification accepted 28 October 2026 under [#893](https://github.com/bbottema/simple-java-mail/issues/893): retain the attribute's
meaning when interpreting decoded CID references. A `srcset` contains separate URL candidates and optional descriptors; neither a
descriptor nor a following candidate belongs to a Content-ID. Equivalent literal and character-reference spellings must select the
same embedded resources. Keep complete-identity handling for single-URI attributes, source HTML, resource occurrences, ownership and
authoritative exact/protected bytes unchanged. This is an import-interpretation correction, not general HTML rewriting or a new public API.

## Trailing comments in delivery-report dates (accepted 28 October 2026)

Retain a delivery report's unambiguous timestamp when well-formed comments or whitespace follow its explicit timezone. Keep strict
calendar and timezone interpretation: malformed comments, non-comment suffixes, impossible dates and missing or invalid zones do not
establish a timestamp. Existing ISO-date recovery and neighboring report facts remain unchanged. This source-fact correction is
accepted under [#886](https://github.com/bbottema/simple-java-mail/issues/886) for unreleased 10.0.0.

## Escaped Content-ID references (accepted 28 October 2026)

Recognize a referenced MIME resource by the decoded identity of its CID URL under [RFC 2392 section 2](https://www.rfc-editor.org/rfc/rfc2392.html#section-2).
For example, `cid:logo%2F1@example.org` refers to `Content-ID: <logo/1@example.org>`. Interpret percent escapes once, retain literal plus signs,
and compare the complete Content-ID with its original case. URI scheme spelling is case-insensitive. Malformed percent escapes and unrelated
or longer identifiers do not establish a reference; they must not claim a resource by a shared prefix.

This changes resource-reference interpretation without rewriting HTML, Content-IDs, metadata or content bytes. Preserve existing resource
classification, intentional occurrences, ordering and eager/lazy ownership under [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md).
Apply the same interpretation during MIME/EML import and exact-message inspection while retaining authoritative exact/protected bytes.
This does not introduce general HTML rewriting or alter outgoing validation. The correction is accepted under
[#882](https://github.com/bbottema/simple-java-mail/issues/882) for unreleased 10.0.0; the issue tracks implementation and delivery.

## Unreferenced inline resources with repeated Content-IDs (accepted 28 October 2026)

Retain every readable unreferenced inline MIME resource, even when another source occurrence has the same Content-ID. Preserve each
occurrence's filename, bytes and position among ordinary attachments; CID lookup must not be the only owner of resource occurrences.
Repeated source metadata does not justify discarding content that can be represented by the existing attachment API.

Keep eager and lazy content access, parser-opened stream ownership and authoritative exact/protected bytes unchanged under
[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md). This correction does not select a new interpretation for HTML-referenced
duplicate IDs: their existing lookup and presentation remain unchanged pending a separate decision. Ordinary referenced inline images,
disposition-free related images and explicit attachments retain their classification. [#891](https://github.com/bbottema/simple-java-mail/issues/891)
tracks implementation and delivery for unreleased 10.0.0.

## Literal imported filenames (accepted 29 October 2026)

Preserve decoded MIME filenames on attachments and embedded images, including literal angle brackets. Filenames and Content-IDs are distinct metadata: bracket removal applies only to actual Content-ID interpretation and the established missing-filename CID fallback. Import must not silently sanitize an otherwise interpretable filename.

This applies to static/fluent MIME/EML import and exact inspection. Preserve classification, content bytes, eager/lazy ownership and authoritative exact/protected bytes under ADR 0008 and ADR 0036. Issue [#899](https://github.com/bbottema/simple-java-mail/issues/899) tracks this correction to existing behavior for unreleased 10.0.0.
