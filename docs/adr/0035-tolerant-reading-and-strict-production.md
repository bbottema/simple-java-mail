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

Recipient interpretation must also handle a successful parser call that produces no mailbox, such as a comment-only string. Retain the original
nonempty source under the existing lenient builder contract, just as for a parsing exception, without inventing an address or changing its chosen
name. Missing-input checks still apply, and ordinary outgoing validation rejects an unusable retained address before submission.

Email builder address conveniences apply this leniency within a single-mailbox contract. A successfully parsed list or RFC address group is an
argument error directing the caller to `recipientsBuilder()`, even when the group has one member or is empty. Quoted punctuation in a display
name is not a delimiter. Parsing separates name/address without performing deliverability or DNS checks; malformed nonempty text retains its
original spelling for configured outgoing validation. The independent Recipient builders and inbound parsers keep their existing contracts.

## Failure and security boundaries

An optional interpretation failing is different from failing to obtain the message content the caller requested. For example, optional delivery-report
extraction can preserve completed report blocks and warn after a read failure. A required eager attachment read can still fail conversion with its
original cause; returning a silently truncated attachment would falsely claim that its content was obtained. Structural corruption that prevents a
meaningful Email, cancellation, missing required inputs and programming failures remain errors. Never catch arbitrary runtime failures as malformed mail.

Leniency is not proof of authenticity or permission to retry. Continue verifying signatures over the original bytes, reporting inaccessible ciphertext
honestly, and preserving exact/protected representations. Do not normalize signed bytes to manufacture a successful verification result.
The unavailable S/MIME inspection clarification in [ADR 0008](0008-minimal-mime-structures-and-protection-order.md#unavailable-smime-inspection-accepted-2026-10-21)
keeps uninspected protection distinct from confirmed absence and from cryptographic failure while retaining tolerant ordinary import.

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
