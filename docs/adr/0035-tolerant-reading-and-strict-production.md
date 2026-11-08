# ADR 0035: Read imperfect mail tolerantly; produce standards-compliant mail

- Status: Accepted
- Decision recorded: 2026-10-17; records the maintainer's established import philosophy, not an invented original decision date
- Applies to: Inbound MIME, EML and Outlook conversion, interpreted source facts, and their boundary with outbound production

### Missing Outlook attachment types (accepted 1 November 2026)

An Outlook attachment with readable binary content remains importable when neither a MIME type nor either filename property is available.
At the SJM import boundary, use `application/octet-stream` only when the specialist importer supplies no usable type. Preserve supplied or
filename-inferred types, content bytes, existing missing-name/CID fallbacks, attachment classification and ownership. The same rule applies
to an inline CID resource whose type cannot be inferred. Strict outgoing attachment construction retains its required MIME-type contract.
This narrow recovery is accepted under [#946](https://github.com/bbottema/simple-java-mail/issues/946) for unreleased 10.0.0; the issue tracks delivery.

### Ordered Outlook receipt occurrences (accepted 1 November 2026)

Interpret `Return-Receipt-To` and `Disposition-Notification-To` occurrences in their raw MSG header order even when capitalization is
interleaved. Preserve destination order, duplicates, names and existing malformed/empty/null-path recovery. The specialist importer's
case-sensitive grouped map must not reorder these typed receipt facts. Use the existing raw-header representation when available while
retaining the established fallback for sources without it. Broader generic-header ordering remains outside this clarification.
This applies the Outlook receipt-parity decision below under [#812](https://github.com/bbottema/simple-java-mail/issues/812) for unreleased 10.0.0;
the issue tracks delivery. Copying, serialization and resource ownership retain ADR 0036's contracts.

### Stream-valued plain-text and HTML bodies (accepted 2 November 2026)

A provider may return an InputStream for text/plain or text/html, including Jakarta Activation's fallback when a DataSource has no content
handler. Read that stream rather than rendering its object identity. Match the ordinary text handler: use the declared MIME charset, or its
`us-ascii` MIME label when absent, through `MimeUtility.javaCharset`. Current Jakarta Mail maps that label to ISO-8859-1; do not replace that
provider interpretation with literal US-ASCII or guessed UTF-8. Already-decoded Strings and other non-stream provider values keep their existing
interpretation. Preserve empty-body presence and existing body accumulation, MIME-part classification and exact/protected source bytes.

Charset, read and close failures remain conversion failures with their causes intact. Stream cleanup follows [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md).
This is a correction of published 9.3.9 behavior under [#959](https://github.com/bbottema/simple-java-mail/issues/959), without a new API or global
content-handler setting.

## Context and decision drivers

Applications import mail produced by other software, not only by Simple Java Mail. Outlook sources, archived EML and received headers can be
incomplete or malformed while still containing useful bodies, addresses and report data. Rejecting the entire import because one optional field
violates a grammar makes the library less useful as an interoperability boundary. The [release history](../../RELEASE_HISTORY.md) records
[#321](https://github.com/bbottema/simple-java-mail/issues/321) in 6.6.1 as ignoring malformed recipient addresses and continuing email parsing.
Existing recipient parsing deliberately uses Jakarta Mail's non-strict header parser, with a source comment linking the compatibility history in
[#227](https://github.com/bbottema/simple-java-mail/issues/227).

Reading and producing have different responsibilities. An imported value can be useful evidence without being suitable for a new outgoing message.
Recovery must also distinguish a harmless formatting defect from conflicting evidence, unreadable content and failed cryptographic verification.

## Optional disposition metadata and empty protected headers (accepted 2 November 2026)

A malformed optional Content-Disposition parameter must not hide otherwise readable neighboring mail. Recover an unambiguously recognized attachment/inline token independently of malformed parameters, then use independently usable naming metadata or the existing missing-name fallback. Keep Jakarta Mail as grammar owner; preserve ambiguous facts, actual content/I/O failures, child attachment boundaries and exact/protected bytes. #953 tracks behavior present in released 9.3.9.

Apply ADR 0008's effective-root rule to single-part text, HTML and calendar (#954). Recognize empty signed/decrypted OpenPGP content headers at the initial empty line, preserving protected bytes and truthful facts (#704).

## Decision

Read external mail with leniency and produce standards-compliant mail. Preserve available source information and expose clearly interpretable
facts through convenient getters. Isolate field interpretation failures so usable neighboring information remains inspectable.

### Email as an inspection model

This restates established decisions; 3 November 2026 is the date of this cross-reference, not a new architectural decision date.
[ADR 0013](0013-exact-eml-submission.md), recorded on 16 September 2026, already calls the Email getters an inspection view.
[ADR 0008's source-facts decision](0008-minimal-mime-structures-and-protection-order.md#interpreted-mail-facts-remain-part-of-email-accepted-2026-10-16),
accepted on 16 October 2026, keeps interpreted facts on the common Email read model and separates received metadata from outgoing configuration.
The [original version of this record](https://github.com/bbottema/simple-java-mail/blob/17847b4e9665bd1d02e392453178e2cb001491c7/docs/adr/0035-tolerant-reading-and-strict-production.md)
explicitly records the maintainer's established import philosophy, with [#227](https://github.com/bbottema/simple-java-mail/issues/227) from
2019 and [#321](https://github.com/bbottema/simple-java-mail/issues/321) from 2021 as earlier applications.

Email is as much a model for inspecting received mail as a vehicle for sending through a Mailer. Its conveniences should accelerate access
to received information. Their ability to interpret a field, or its suitability for a new outgoing message, must not determine whether the
application can inspect the available source information.

Preserve available received headers and metadata, including information that is unfamiliar, obsolete, malformed or unsuitable for sending.
A field omitted from outgoing composition still has inspection value. In particular, an outgoing header exclusion is not a reason to discard
that header from the received inspection view. Preserve its source owner and repeated occurrences instead of inventing a single decoded value.

Parsed getters provide convenient interpretations over that evidence. When interpretation is unavailable, keep the available source and make
the limitation observable while retaining usable neighboring information. Do not guess decoded characters, valid addresses, dates, enum values
or cryptographic success merely to populate a convenience getter. An interpretation failure and an inability to obtain source content are
different facts; neither should be hidden behind a fabricated successful result.

Preparing and submitting outgoing content retains its own validation, resource and transport requirements. Inspecting received headers does
not automatically select every source header for a newly composed message. Exact/protected source ownership and the existing borrowed-content
read boundaries remain governed by ADRs 0013 and 0036; this principle does not require an eager read of every attachment.

There is also an existing source-header access decision. [#609](https://github.com/bbottema/simple-java-mail/issues/609) reported inaccessible
Outlook headers; [#614](https://github.com/bbottema/simple-java-mail/issues/614) retained their inspection through `OutlookEmailConversionResult`
and `OutlookMessageData`, while deliberately keeping `Email.getHeaders()` focused on normalized outgoing headers. [ADR 0006](0006-optional-modules.md#feature-specific-results-are-a-separate-exception)
records those core read contracts. Reconcile that existing API before proposing another source-header surface or changing its ownership.

The current review concerns conformance to the established inspection principle and the consistency of existing access paths. Any extension
of source access, recovery outcomes or earlier field-specific failure contracts still requires concrete design and compatibility review. This
restatement does not claim that current imports already preserve every field or tolerate every damaged input.

[ADR 0041](0041-email-source-inspection-and-problems.md), accepted 3 November and clarified 4 November 2026, makes the source/content and
root problem contracts explicit across conversion and copying, including immutable original-message identity. Under #961, known component
interpretation/read failures preserve usable neighbors and available source evidence; captured-only source reads retain existing ownership.
Email itself is the non-null inspection result, while optional result terminals provide diagnostics for non-Email artifact outputs.

Source retention is independent of problem reporting. Keep an available original that the normal Email no longer exposes as received,
such as RTF converted to HTML, without inventing a Problem for a successful expected transformation. Canonical-equivalent decoding, such
as Base64 into the same attachment content, needs no extra raw payload. Do not turn ordinary conversion differences into a problem ledger.

The older field-specific failure rules below still determine decoding, stream ownership and original causes at their local boundaries.
The shared #961 recovery boundary can record a recognized component failure and continue with neighboring content instead of propagating
it as whole-import failure. Invalid use and unrelated application/programming failures remain exceptions; no broad catch or source-based
send gate follows from this recovery model. An unfinished output artifact is never a successful conversion value.

### Interpretation and recovery boundaries

- Accept safely recoverable formatting differences when their meaning is unambiguous. Recovery changes the inspection view, not the source bytes.
- Retain usable neighboring fields and completed blocks when another field or optional interpretation fails. Use the relevant existing warning or
  result metadata to explain recovery, ambiguity or incomplete extraction. Do not make getters perform another read.
- Preserve original data through the existing source/attachment mechanisms where available. Unrecognized formats need not disappear merely because
  a typed convenience view cannot be constructed.
- Leave genuinely ambiguous typed facts unavailable while retaining their source evidence. Conflicting delivery actions, multiple competing reports or an unknown timezone do not justify choosing
  an arbitrary result. An impossible date must not roll into another day just to produce a timestamp.
- Keep typed-value invariants: construct a typed view only after establishing its meaning. Tolerant source interpretation does not require invalid
  enums, out-of-range numeric components or misleading convenience objects.

Use Jakarta Mail and existing specialist importers for their parsing responsibilities. Add narrow recovery at the interpretation boundary, not a
second general MIME parser, global permissive JVM/Session settings, indiscriminate exception handling or an automatic repair-and-retry pipeline.

### Optional field recovery without source text (accepted 7 November 2026)

A recognized optional-field read or interpretation failure can have no captured raw value to recover. In that case, retain the existing
Problem and available source evidence, leave the typed fact unavailable, and continue interpreting readable neighbors. An unavailable
singular address is absent; an unavailable address list is empty. Do not create a recipient or another typed placeholder from missing text.
Actual captured text and successful provider interpretations keep their existing fallback and selection rules, including From/Sender behavior.

A failed disposition getter can likewise leave disposition unavailable when the direct declaration lookup succeeded with no values and
no disposition occurrence was captured. Continue interpreting readable content under the existing rules. A present, conflicting or
unreadable declaration does not establish absence and retains its existing component boundary, especially for attached multipart children.

This completes the unreleased inspection contract under [#961](https://github.com/bbottema/simple-java-mail/issues/961), alongside
[ADR 0041](0041-email-source-inspection-and-problems.md). It adds no broad exception recovery, public setting or source-based send gate.
Cancellation, unrelated application failures, ordinary outgoing validation and selected content/resource read ownership retain their boundaries.

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

### Explicitly empty imported bodies (accepted 31 October 2026)

An encountered plain-text or HTML body remains present when its decoded content is empty. Import it as an empty String; reserve null for an
absent alternative. This retains an interpretable source fact and the empty-versus-absent distinction in
[ADR 0031](0031-public-api-and-internal-construction.md#required-body-augmentation-fragments-accepted-29-october-2026). Ordinary defaults in
[ADR 0002](0002-email-defaults-and-overrides.md) can fill an absent alternative, but must not replace an explicitly empty imported body.

Keep existing whitespace, encounter-order concatenation, body/attachment ownership and ordinary MIME structure simplification. Copying,
serialization and ordinary export preserve the body presence; exact and protected source bytes retain their separate authoritative contracts.
This is an interpretation correction for unreleased 10.0.0 under [#935](https://github.com/bbottema/simple-java-mail/issues/935), with no new
public API or configuration switch. It does not add source MIME-tree preservation or change cryptographic processing.

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

### Imported custom-header order (accepted 31 October 2026)

When reading MIME or EML, match custom-header names without regard to capitalization and collect their values in source order.
Keep the first display spelling even when later occurrences use a different one. For example, `X-Step: first`, `x-step: second` and
`X-Step: third` form one header with the values `first`, `second`, `third`; grouping the spellings separately loses that order.

This applies the existing header identity and distinct-value rules to import under [#934](https://github.com/bbottema/simple-java-mail/issues/934)
for unreleased 10.0.0. Copying, supported serialization and ordinary reconstructed export retain the resulting metadata. Header filtering,
root-versus-child ownership and duplicate-value handling keep their current behavior. Exact and protected source bytes remain authoritative;
this decision does not preserve the global order of unrelated fields or make ordinary export byte-identical to its input.

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

## Automatic-image interruption (accepted 8 November 2026)

Automatic image resolution follows the same interruption boundary during composition and conversion. An interrupted source open,
read or stream close cancels the operation even when an unresolved image would otherwise be optional. Preserve the original failure,
its interrupted causes and suppressed cleanup failures, and the caller's thread state. An ordinary socket timeout alone does not
establish interruption. A required image read failure retains its cause; ordinary optional URL failures and existing classpath read
failure behavior remain unchanged. [#973](https://github.com/bbottema/simple-java-mail/issues/973) corrects published 9.3.9 behavior
in the dynamic-image feature introduced by #260, without a new application API or configuration option. ADR 0036 retains stream
ownership, source selection and the atomic image-resolution pass.

## Failure and security boundaries

An optional interpretation failing is different from failing to obtain the message content the caller requested. For example, optional delivery-report
extraction can preserve completed report blocks and warn after a read failure. A required eager attachment read can still fail conversion with its
original cause; returning a silently truncated attachment would falsely claim that its content was obtained. Structural corruption that prevents a
meaningful Email, cancellation, missing required inputs and programming failures remain errors. Never catch arbitrary runtime failures as malformed mail.

Clarification accepted 6 November 2026 under [#763](https://github.com/bbottema/simple-java-mail/issues/763): optional delivery-report
acquisition, reading and stream cleanup use the same typed interruption rules as other conversion paths. Cancellation does not require
the thread interrupt flag to remain set. Preserve the caller's thread state and original failure context, including interrupted causes
and suppressed failures. Ordinary socket timeouts and other recoverable I/O failures retain completed-block recovery and warnings.

The S/MIME declaration clarification accepted on 7 November 2026 under #704 and #822 applies this distinction before protection is
recognized and during the late module-absent metadata fallback. An initial declaration-read failure leaves root protection facts UNKNOWN
with an ordinary source-read observation; it is not a signature or decryption failure. A typed interrupted read remains cancellation
even after readable content has been obtained. Keep the original failure context and caller thread state, and do not convert unrelated
application RuntimeException or Error into malformed input. [ADR 0008](0008-minimal-mime-structures-and-protection-order.md#smime-declaration-read-failures-accepted-7-november-2026)
owns the protection-fact decision; exact bytes, content acquisition and independent attachment processing retain their existing owners.

Leniency is not proof of authenticity or permission to retry. Continue verifying signatures over the original bytes, reporting inaccessible ciphertext
honestly, and preserving exact/protected representations. Do not normalize signed bytes to manufacture a successful verification result.
The unavailable S/MIME inspection clarification in [ADR 0008](0008-minimal-mime-structures-and-protection-order.md#unavailable-smime-inspection-accepted-2026-10-21)
keeps uninspected protection distinct from confirmed absence and from cryptographic failure while retaining tolerant ordinary import.
The corresponding OpenPGP distinction is implemented for unreleased 10.0.0 under [#704](https://github.com/bbottema/simple-java-mail/issues/704).
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

### First protected MIME header boundary (accepted 1 November 2026)

When the OpenPGP or S/MIME byte-oriented helper separates a message's headers from its body, use the first empty header line made from the supported
CRLF or LF endings. Each of the two line endings may use either spelling. An earlier LF or mixed separator must not be displaced by a later
CRLF separator inside a nested MIME part or encoded ciphertext. The same rule applies when separating a recovered content entity during unwrapping.

This interprets the header boundary consistently with the tolerated input parser; it does not rewrite retained protected bytes, change
cryptographic verification or transfer decoding, or add support for arbitrary malformed or CR-only MIME. Exact EML submission retains its
canonical-CRLF restrictions under [ADR 0013](0013-exact-eml-submission.md). The correction targets unreleased 10.0.0 under
[#704](https://github.com/bbottema/simple-java-mail/issues/704). The earlier OpenPGP implementation did not change the separate S/MIME scanner;
the maintainer accepted applying the same rule to that scanner on 1 November 2026.

### Declared calendar charset on provider streams (accepted 1 November 2026)

A provider returning an InputStream for `text/calendar` must retain the declared charset's meaning, as the ordinary calendar content handler
does. Resolve a declared charset before acquiring the parser-owned stream; unsupported or illegal declarations retain their conversion failure.
Read and close acquired streams under ADR 0036, retaining read causes and suppressed close failures. Keep the established absent-charset defaults
of each path and calendar-method interpretation. [#945](https://github.com/bbottema/simple-java-mail/issues/945) tracks the correction for 10.0.0;
the incorrect declared-charset behavior is also present in released 9.3.9, so migration guidance describes the changed interpretation.

## Alternatives considered

| Alternative | Benefit | Reason not selected |
| --- | --- | --- |
| Reject every nonconforming source | Simple validity rules | Loses useful mail and repeats historical interoperability failures. |
| Silently repair everything | Fewer apparent import failures | Hides uncertainty and can invent dates, recipients, delivery results or verification success. |
| Make every importer strict unless callers enable a lenient mode | Explicit selection | Burdens normal interoperability with a switch and fragments established default behavior. |
| Catch every exception and return an Email | Maximizes apparent availability | Conceals programming failures, unreadable content and incomplete attachments. |
| Retain only raw bytes and leave interpretation to applications | Avoids recovery policy | Gives up the library's useful mail-domain knowledge and convenient Email read model. |

## Optional Outlook dates (accepted 8 November 2026)

An absent Outlook source date, creation time or last-modification time must remain unavailable without rejecting the message's readable
content or other metadata. Keep the specialist parser's existing date interpretation and selection responsibilities. Its optional date
getters return null for absent values and defensive copies for present values; Simple Java Mail does not catch arbitrary
NullPointerExceptions or manufacture timestamps to compensate for missing metadata.

For the Email sent date, prefer Outlook's client-submit time and consult the existing source-date fallback only when that first choice
is absent. Leave the Email date unfixed when neither value is available. Outlook metadata inspection, copying and serialization retain
the same absence and metadata ownership; ordinary reconstructed MIME keeps its existing current-date generation when no date is fixed.
This corrects the sparse-MSG failure present in released 9.3.9 under [#974](https://github.com/bbottema/simple-java-mail/issues/974), with
the null-safe reader correction owned by [outlook-message-parser#103](https://github.com/bbottema/outlook-message-parser/issues/103).
It does not add another date parser or change existing source-date precedence under ADRs 0036 and 0041.

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

For the original #861 repair, typed receipt/Return-Path interpretation, required body and resource decoding, filename/description decoding and outgoing injection validation retained their existing contracts. Ordinary rendering still encodes and folds supported header values safely; recovered source text must not create a new delayed validation failure for otherwise safe input. Successful import remains distinct from successful sending. Exact and protected representations keep their authoritative bytes. That narrow repair introduced no warning API or recovery setting. [#861](https://github.com/bbottema/simple-java-mail/issues/861) tracks its implementation and delivery.

[ADR 0041](0041-email-source-inspection-and-problems.md) subsequently adds root processing observations and extends the accepted header
recovery cases under #961. Successful provider interpretations remain authoritative; source retention does not overwrite them merely because
the library's own decoder lacks the declared charset. Content/resource failures and outgoing validation retain their separate boundaries.

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

## Representation-specific resource identities (accepted 1 November 2026)

Interpret each resource identifier at the boundary that owns its representation. The shared HTML attribute reader decodes character
references once for CID matching and automatic image lookup. A CID URL then decodes its RFC 2392 percent escapes once, preserving literal
plus signs and the complete case-sensitive identity. Stored filenames remain literal metadata: `a&amp;b.png` is not another spelling of
`a&b.png` when it is a filename. Missing filename metadata remains distinct from a supplied fallback-looking name such as `UnknownAttachment`.

Keep filename and Content-ID mapping separate through MIME/EML and MSG conversion, copying and replies. MSG interpreted conversion uses
the shared complete identities through [ADR 0006's internal bridge](0006-optional-modules.md#shared-cid-interpretation-during-outlook-conversion-accepted-1-november-2026),
rather than the specialist parser's raw HTML regex classification. Retain existing missing-name
fallbacks and the supported name-as-CID shorthand when no separate ID was supplied. Sharing HTML interpretation is not a general unescape
operation for MIME metadata or CSS raw text. Source markup, resource occurrences, eager/lazy ownership and authoritative exact/protected bytes
keep their existing contracts. [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#shared-html-attribute-interpretation-accepted-1-november-2026)
owns automatic-image replacement and lookup boundaries. [#952](https://github.com/bbottema/simple-java-mail/issues/952) tracks this accepted
clarification and its implementation for unreleased 10.0.0, extending #885/#893 without weakening #882/#899/#916/#920.

## Comments in delivery-report actions (accepted 29 October 2026)

Interpret a delivery report's Action as one recognized case-insensitive atom, ignoring well-formed external comments and whitespace under RFC 3464 section 2.1.1. For example, `Action: failed (recipient removed)` establishes `FAILED` without a warning. Require the end of the field after comments: extra tokens, quoted values, malformed comments and unknown actions do not establish a recognized action. Missing, repeated and contradictory fields retain their existing availability and warning rules.

Preserve neighboring report facts and original content, including authoritative exact/protected bytes. This corrects interpretation without a new API, parser option, warning model or source read. [#763](https://github.com/bbottema/simple-java-mail/issues/763) tracks this clarification for unreleased 10.0.0.

## Trailing comments in delivery-report dates (accepted 28 October 2026)

Retain a delivery report's unambiguous timestamp when well-formed comments or whitespace follow its explicit timezone. Keep strict
calendar and timezone interpretation: malformed comments, non-comment suffixes, impossible dates and missing or invalid zones do not
establish a timestamp. Existing ISO-date recovery and neighboring report facts remain unchanged. This source-fact correction is
accepted under [#763](https://github.com/bbottema/simple-java-mail/issues/763) for unreleased 10.0.0.

The numeric timezone's hour component must be in 00 through 23; Jakarta Mail's strict date grammar alone does not establish that
range. Values such as `+2400`, `+9900` and `-2400` leave both last-attempt and retry-until timestamps unavailable with the existing
invalid-date warning. Keep valid offsets, calendar checks, comments, ISO recovery, neighboring facts and original report content.
This clarification was accepted on 29 October 2026 under [#763](https://github.com/bbottema/simple-java-mail/issues/763).

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

## External comments on imported Content-IDs (accepted 29 October 2026)

Interpret well-formed comments and folding whitespace surrounding one complete received Content-ID as header formatting, not part of its identity. Use the same complete, case-sensitive identity for resource lookup and interpreted resource metadata, so a header such as `Content-ID: <logo@example.org> (company logo)` still matches `cid:logo@example.org` in HTML. Keep literal filenames and quoted or domain-literal ID content separate from external comments.

Apply this at the MIME/EML import boundary without changing caller-created resource IDs or rewriting HTML, resource content, or authoritative exact/protected bytes. Preserve existing resource classification and occurrence rules, stream ownership and legacy opaque ID spellings. Malformed or competing framing retains the existing source fallback rather than guessing a different identity; this is not a new general message-ID validator or warning API. [#920](https://github.com/bbottema/simple-java-mail/issues/920) tracks the correction for unreleased 10.0.0.

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

Preserve decoded MIME filenames on attachments and embedded images, including literal angle brackets. Filenames and Content-IDs are distinct metadata: bracket removal applies only to actual Content-ID interpretation and the established missing-filename CID fallback. Import must not silently sanitize an otherwise interpretable filename. Retain whether naming metadata was supplied rather than inferring absence from a fallback label: a real filename of `UnknownAttachment`, including one supplied through Content-Type, remains literal. Existing empty-name, genuinely unnamed and forwarded-message fallbacks retain their behavior.

This applies to static/fluent MIME/EML import and exact inspection. Preserve classification, content bytes, eager/lazy ownership and authoritative exact/protected bytes under ADR 0008 and ADR 0036. Issue [#899](https://github.com/bbottema/simple-java-mail/issues/899) tracks the bracket correction and [#916](https://github.com/bbottema/simple-java-mail/issues/916) tracks the fallback-label clarification accepted on 29 October 2026, both for unreleased 10.0.0.

## Unparseable imported message addresses (accepted 29 October 2026)

When existing MIME/EML interpretation fails on the address grammar of From, To/Cc/Bcc or Reply-To, retain the complete offending field
in the existing tolerant Recipient value and continue reading usable neighboring fields, bodies and attachments. Apply the established
Outlook/receipt raw-recipient fallback without guessing a mailbox, selecting only a partially parsed list or hiding the address solely in
unchecked custom headers. Ordinary outgoing validation must still inspect that typed value; warning-only validation keeps its existing meaning.

Keep all successful Jakarta Mail interpretations, including the non-strict To/Cc/Bcc grammar, existing names, ordered duplicates and singular
From selection. Missing, empty and comment-only fields retain their current behavior. From falls back to Sender only when absent;
Reply-To falls back to the From addresses when absent or parsed empty. An explicitly malformed Reply-To retains its own source rather than
silently becoming From. Recover only the grammar failure of its actual source; an unrelated getter, codec, content, protection or I/O failure
remains an error. Do not introduce strict preflight, a second general parser, a global permissive setting or a new public representation.

Copying and supported serialization retain recovered typed input. Exact inspection retains authoritative bytes and separately validates its
explicit envelope. Return-Path keeps #838's distinct absent-typed-value/source-header rule, while receipt fallback and the deferred RFC-group
policy remain unchanged. This applies the existing tolerant-import decision under [#914](https://github.com/bbottema/simple-java-mail/issues/914).

### Observations for a selected Sender fallback (accepted 7 November 2026)

When an address getter's grammar failure is recovered from `Sender` because `From` is absent, retain that actual recovery as one
source-linked Problem under [ADR 0041](0041-email-source-inspection-and-problems.md#problems-and-immutable-input-identity). Both From and
Reply-To can use the same malformed Sender; this does not create two separate source issues. Keep the complete tolerant address text,
readable neighboring content and existing From/Sender/Reply-To selection.

Record the recovery where the selected source is known. A successful provider interpretation stays authoritative, and an unused Sender
does not acquire a new decoding or validation pass. Existing cancellation, codec, content and unrelated application-failure rules remain
unchanged. This completes unreleased [#961](https://github.com/bbottema/simple-java-mail/issues/961) inspection over #914's recovery behavior;
it adds no public API or released-source migration.

## Parameterized S/MIME attachments (accepted 31 October 2026)

The [5 November detached-parent clarification in ADR 0008](0008-minimal-mime-structures-and-protection-order.md#independent-attachments-after-detached-signature-unwrapping-accepted-5-november-2026)
applies the same independent-attachment boundary after a detached-signed parent exposes its clear entity. A parent signature must not skip
requested child decryption. Original resources and source-linked observations remain available; child outcomes do not replace parent
protection facts or add new exception recovery. [#704](https://github.com/bbottema/simple-java-mail/issues/704) owns the unreleased follow-up.

The [7 November OpenPGP clarification](0008-minimal-mime-structures-and-protection-order.md#independent-smime-files-after-opening-openpgp-mail-accepted-7-november-2026)
also applies this independent-file processing to readable content exposed by OpenPGP. It preserves the same interpretation and failure
rules without treating a protected effective root as another attachment to open.

Recognize an attachment's S/MIME media type independently of its Content-Type parameters. Valid parameters such as `smime-type`
and `name` must not prevent requested decryption or signature inspection. Parse metadata at the S/MIME recognition boundary rather
than stripping parameters from imported resources. Preserve the complete metadata, source bytes, content ownership and existing
cryptographic failure boundaries. This applies the existing attachment parameter-preservation decision and ADR 0008's protection
interpretation to ordinary multipart attachments. Independent signed resources do not establish a plain parent's signature facts;
retain the existing declared signed Outlook-envelope and sole-attachment interpretation. Implemented for unreleased 10.0.0;
[#927](https://github.com/bbottema/simple-java-mail/issues/927) tracks delivery.
