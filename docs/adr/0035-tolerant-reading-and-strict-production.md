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
