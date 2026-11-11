# ADR 0041: Inspect received content, retained originals and processing problems through Email

- Status: Accepted, 3 November 2026; source-content and conversion-result clarifications accepted 4 November 2026
- Target: Unreleased 10.0.0
- Implementation: Implemented for unreleased 10.0.0; [#961](https://github.com/bbottema/simple-java-mail/issues/961) tracks review and delivery
- Related: ADRs [0006](0006-optional-modules.md), [0008](0008-minimal-mime-structures-and-protection-order.md), [0013](0013-exact-eml-submission.md), [0035](0035-tolerant-reading-and-strict-production.md), [0036](0036-structural-message-snapshots-and-borrowed-content.md), [0037](0037-repeatable-email-completion.md) and [0040](0040-retained-protected-content-merging.md)

This record defines the received-content inspection and artifact-result contract for unreleased 10.0.0. Current review and delivery
state belongs to [#961](https://github.com/bbottema/simple-java-mail/issues/961); broader whole-Email restoration remains separate under
[#965](https://github.com/bbottema/simple-java-mail/issues/965).

## Context

Email is as much an inspection model for received files as a vehicle for sending through a Mailer. Its convenient subject, address, body,
resource and protection getters should accelerate access to received information. A field's suitability for a newly composed outgoing
message must not determine whether an application can inspect the available source evidence.

ADR 0035 records that established philosophy, including recipient-tolerance decisions under #227 (2019) and #321 (2021). The existing
Email.getHeaders() also supplies outgoing composition, so it intentionally excludes some received fields. #609/#614 separately exposed
native Outlook data through OutlookEmailConversionResult and OutlookMessageData. Those choices remain valid, but ordinary conversion
should not require another result wrapper to discover received evidence or processing limitations.

Interpretation can fail for one field or MIME component while its neighbors remain useful. Successful interpretation can also produce
a different representation, such as HTML generated from an RTF body. Historical observations can survive copying and later edits, so
neither the current message identity nor current normal content necessarily describes the input that first caused a problem.

## Decision

### Email is the inspection result

Return Email directly from the existing import/conversion journey. A returned Email is non-null even when no normal fields could be
interpreted and only available source evidence, metadata and problems remain. Do not add EmailConversionResult<Email>, toEmailResult(),
a builder-result layer, a public source-data/problem builder, or an inspection-activation switch.

The 7 November clarification under #961 keeps this result available when a recognized optional-field reader fails and no raw fallback
was captured. Leave that typed field unavailable while retaining the Problem, original input identity and available source references.
Do not manufacture a typed placeholder from absent evidence. [ADR 0035](0035-tolerant-reading-and-strict-production.md#optional-field-recovery-without-source-text-accepted-7-november-2026)
owns the recovery and exception boundaries; successful provider interpretations and actual captured raw values retain their existing meaning.

Keep successfully represented bodies, attachments and embedded images in their ordinary fields and collections. Do not add recovery
states or invalid-content placeholders to AttachmentResource. Expose immutable source evidence through Email.getSourceData() and
historical processing observations through Email.getProblems(). Fresh composition and older snapshots without captured inspection
have empty source/problem values; empty source data does not invent an input origin or establish successful inspection.

### Missing Outlook sender (accepted 8 November 2026)

An Outlook MSG with no reader-established sender address has no From recipient in the converted Email. Keep any available sender name
and native source metadata in the existing source inspection view; a name alone does not establish a mailbox. A last-modifier identity
does not become a sender. Ordinary copying and supported serialization preserve that absence and the original source evidence.

This deliberately supersedes [#201](https://github.com/bbottema/simple-java-mail/issues/201)'s released dummy-address policy for 10.0.0.
Do not manufacture `donotreply@unknown-from-address.net`. Actual Outlook sender interpretation and MIME/EML From/Sender recovery keep
their existing ownership under [ADR 0035](0035-tolerant-reading-and-strict-production.md). An absent optional sender is not an import
failure and does not create a Problem by itself.

Outgoing preparation still requires a sender. Applications can configure From on a copy or deliberately allow existing Email defaults
to supply it; imports retain their existing default-suppression choice. Overrides retain their ordinary authority. No import setting,
new sender getter or automatic inference is added. [#975](https://github.com/bbottema/simple-java-mail/issues/975) owns this compatibility
change and its migration guidance; acceptance does not claim delivery.

### Generic source structure and selective original content

Source data is an enumerable diagnostic view of available headers, native properties, parts and actually materialized derived views.
It is not another set of business-field getters. The diagnostic API omits named header/body/filename lookups; the existing specialized
OutlookEmailConversionResult and OutlookMessageData APIs remain supported.

Capture available received header occurrences before preprocessing, interpretation and outgoing exclusions. Preserve names, raw values,
repetitions and the reader's available order. Do not invent missing native header interleaving or merge received and extracted header
lists into a false single source. Root and part metadata retain their actual owners. Native scalar values describe what the reader
exposed; they do not promise original storage bytes or a fabricated MIME structure for an MSG input.

Enumerating successful source components does not require retaining another copy of all their payloads. Retain available originals when
the root Email no longer exposes the content as received, excluding different representations of the same canonical value:

- Successful Base64 decoding preserves the attachment content in its normal resource and needs no duplicate raw payload.
- Original reader-provided RTF remains available as source content when the normal Email exposes generated HTML.
- A recovered filename retains its original naming metadata without another copy of unchanged attachment bytes.
- An unreadable or undecodable component retains its available original bytes or acquired prefix outside normal content collections.

Source retention and Problems are independent. An expected successful RTF-to-HTML conversion can retain the original RTF with no Problem.
Register Problems for actual interpretation, read, recovery or protection issues; do not create a problem-lineage event for every
conversion difference or require a Problem before retaining source content. Generic source enumeration must work with an empty Problems list.

Source views identify the actual format and whether a view was received, extracted or generated. Generated MIME is not purported original
MSG content, and no getter starts another unwrap, decode, decryption or signature verification. Keep derived views that existing selected
processing actually materialized; do not archive every possible representation or every parse round.

### Captured content and read ownership

The source graph contains immutable core-owned values and captured storage, not a live parser, MimeMessage, external DataSource callback
or ancestral Email owner. Source bytes and reader-supplied bytes are labelled distinctly. Reader-provided text remains text; do not
serialize a live message or re-encode that text merely to invent original bytes. Exact/protected byte guarantees stay with ADRs 0013/0008.

Capture selectively along the chosen read path. Do not fetch an external attachment solely for diagnostics when deferred reading was
selected. Content can be unavailable because it was not acquired, not exposed by the reader, or deliberately not retained after successful
canonical-equivalent interpretation. Null content is not an empty payload and is not automatically a failure.

A fully captured empty payload has a non-null content object, size zero and isFullyCaptured() true. A read failure after a prefix retains
that prefix with isFullyCaptured() false and an affected Problem. getSize() is the retained byte count, not a claimed expected total.

openStream() opens a new repeatable stream over captured bytes; the caller closes it. readAllBytes() returns a defensive copy of all
retained bytes, which may be only a prefix. These methods and source getters perform no external I/O, decoding, retry or crypto.
Acquired stream ownership, original causes and suppressed close failures follow ADR 0036. A later ordinary borrowed-resource read keeps
its own behavior and does not mutate an existing Email's historical Problems.

Clarification accepted 6 November 2026 under [#961](https://github.com/bbottema/simple-java-mail/issues/961): the selected optional
delivery-report read participates in this capture boundary even when ordinary attachment extraction is deferred. Retain the bytes already
acquired after a failed read, mark incomplete capture and link the observation to the affected source part. A close failure preserves
fully acquired content and its own observation. Keep completed report facts and readable neighbors without another source read, eager
unrelated attachment access or replacement of the borrowed resource. Successful represented content needs no duplicate archive.

Share immutable retained storage across copies. Do not keep a complete large backing allocation for one small exceptional region when
that region can be detached. Do not hide body payloads in unbounded base64/hex/text metadata to evade the selective-retention rule.
This avoids a second full raw archive; it does not promise exactly equal heap size for successful and failed inputs. Encodings, retained
original/converted versions, partial reads and temporary parser/crypto buffers can differ. Assess retained memory separately from peak allocation.
No retention-budget, disk-spooling, silent eviction or source-resolver policy is introduced by this decision.

The later [optional import-limit decision in ADR 0042](0042-optional-import-limits.md), accepted 9 November 2026, bounds selected raw-input
acquisition and SJM part/depth traversal. A limit stop uses the same incomplete captured-content and source-linked Problem contract, while
preserving usable completed content. It does not add a separate retained-evidence byte budget or silently evict already captured sources.
When a limit prevents a retained inner view from materializing any body alternative, that partial Email remains available for inspection
and its separate-view presentation preserves completed outer content. ADR 0042 keeps this stop condition distinct from fully visited absent
alternatives and preserves source-based protection facts when an attachment list is partial; ordinary merge field rules remain in ADR 0040.

### Problems and immutable input identity

EmailProblem contains an operation, stable category, qualified location, available offending source value, explanation, immutable input
identity and direct references to affected source elements. More than one source can explain a conflict; an operation-level problem can
have no particular element. Locations are diagnostic labels, not strings applications must parse to find the source.

Clarification accepted 6 November 2026 under [#961](https://github.com/bbottema/simple-java-mail/issues/961): a delivery-report
interpretation warning references its already captured status part. An ambiguous report references the known conflicting parts, while
a report with no readable status part can reference its known container. Keep the report's existing interpretation and warning text;
attaching these immutable references requires neither another content read nor a duplicate payload archive.

Every actual source context has an EmailSourceIdentity, even with no Problems. It contains a stable UUID diagnostic reference and the
observed input Message-ID when available. Capture that identity before selecting, generating or backfilling output identity. Missing
Message-IDs remain absent, reused IDs do not conflate independent inputs, and a library-generated inspection-wrapper ID is not a purported
received identity. The diagnostic reference is not an email ID, content hash or authenticity claim.

Clarification accepted 7 November 2026 under [#961](https://github.com/bbottema/simple-java-mail/issues/961): an actual grammar recovery
from `Sender`, selected because `From` is absent, records one IMPORT/FIELD_NOT_INTERPRETED observation linked to the captured Sender
occurrence(s). From and Reply-To using that same fallback share the observation. Preserve its original input identity and source links
through copying and serialization. [ADR 0035](0035-tolerant-reading-and-strict-production.md#observations-for-a-selected-sender-fallback-accepted-7-november-2026)
keeps the existing address selection and successful provider interpretations; deliberately unused Sender fields remain outside this recovery.

Clarification accepted 8 November 2026 under [#961](https://github.com/bbottema/simple-java-mail/issues/961): address recoveries performed
while materializing an embedded Outlook MSG are included once in the root Email's Problems. Qualify the child location and link the
observation to its captured source occurrence and original input identity, including through copying and serialization. Keep the
tolerant address value, generated EML attachment and neighboring content. This does not request another content read or extend
inspection to ordinary untouched EML attachments; deliberately unused source values do not gain an observation.

An inherited observation from A still identifies A after copies select B and C as their current Message-IDs. A genuinely new recoverable
problem observed while processing B identifies B. Preserve actual source identity and ownership for materialized protected content;
aggregate inner observations once without promoting a child finding into parent protection success. An unknown or unrequested protection
inspection does not become an invented failure or proof of plaintext. Specialized protection/report facts remain available alongside root discovery.

IMPORT, CONVERSION and COPY identify operations that actually made an observation. Ordinary structural copying does not need a new
finding merely to exercise COPY. An empty Problems list is not a validity certificate, complete-inspection claim, or proof of a valid signature.

### Best-effort recovery and outgoing content

Recover known field/component interpretation and read failures without discarding usable neighboring content or guessing characters,
addresses, dates or cryptographic success. Retain complete undecodable header values rather than partially decoding them into a false
value. Source-only/problem-only inspection remains useful when normal fields cannot be interpreted. Deliberately unused values do not
need decoding or an invented Problem merely to exclude them from composition.

Known component failures may be isolated during output conversion too, yielding a valid completed remaining artifact with observations
and available source evidence. Isolate component work before committing it to the target; a catch around a half-written multipart stream
cannot make that buffer a usable artifact. Invalid arguments/options, consumed-source reuse, cancellation, Errors and unrelated application
or programming failures retain their exception behavior. No catch-all RuntimeException recovery or message-text classification is introduced.

The 7 November #704/#822 declaration clarification retains an initial S/MIME Content-Type read failure as an ordinary source-linked
import observation, without asserting protection presence or a cryptographic failure. Optional report classification must not prevent
independently available body or resource interpretation. Temporary reader failure context used between
modules is not retained as a throwable or live reader on Email. Typed interruption during unavailable inspection still escapes as
cancellation, including after ordinary body retrieval; it does not become a recovered successful inspection or a new warning policy.

No SourceContentOmissionPolicy, explicit omission acknowledgement or historical-problem send gate is added. Direct and diagnostic conversion
terminals share the same best-effort behavior. Normal output renders selected normal content; retained sources are not automatically
added as headers, bodies or attachments. Existing exact/protected authoritative bytes, current output validation, locks and stream/resource
ownership remain independent. This decision does not claim that every damaged input or arbitrary provider failure can be recovered.

### Diagnostic results for non-Email artifacts

An immutable input Email must not become a mutable operation log. EML bytes, EML text and MimeMessage also cannot expose Email's source
and problem getters themselves. Add optional Result-suffixed terminals only for compatible existing artifact targets:
toEmlBytesResult(), toEmlResult() and toMimeMessageResult(). Do not add unsupported source/target combinations for symmetry.

The core EmailConversionResult<T> envelope exposes a usable value or recognized artifact-operation failure, newly observed Problems and
available diagnostic sources. A recovered component Problem can accompany a usable value. A no-value result retains the original
recognized failure and available evidence, never an unfinished output buffer. Direct artifact terminals rethrow that recognized failure.
The wrapper is stable; a target such as MimeMessage retains its existing mutability and ownership contract.

Include observations newly made by delegated stages, such as MSG import followed by output preparation, once. Do not replay inherited
input Email Problems as new conversion events, mutate the input, or fabricate diagnostic MIME headers. Original source data can be
retained with no newly observed Problem. Result selection enables neither additional source reads nor a different recovery policy.

### Combined MSG outputs and independent signed attachments (accepted 5 November 2026)

A combined MSG-to-MIME or MSG-to-EML operation retains the selected import's recognized acquisition/format failure through its artifact
boundary, including the existing static conversion methods. It must not discard that failure by exporting the source-only inspection
Email. Ordinary MSG-to-Email remains non-null inspection; an application can explicitly choose to export that Email later. This is an
operation-local distinction, not a historical Problems gate. Static and fluent stream ownership retain their existing differences.

Automatic interpretation of a malformed independent signed-CMS attachment on an otherwise plain input preserves the readable parent,
the original attachment and its available source evidence. Record the recognized signed-content interpretation failure against the
actual child occurrence and continue neighboring components. Stage optional child facts so a failed child does not establish message-level
signature/decryption facts. Source links follow the occurrence captured during import, including duplicate names and native MSG attachments;
no filename matching, extra diagnostic reads or live reader retention is introduced.

This clarification retains the explicit wrong-key/password and low-level helper failure rules in ADR 0008. It does not authorize recovery
from arbitrary runtime/application failures, explicit decryption failures, cancellation or Errors. The correction completes unreleased #961.

### Copies, serialization and completion

Ordinary copies and supported serialization preserve captured sources, Problems, source links and original identities without reinspection
or accumulation. Later setters change normal content while leaving that received history intact. Supplying a proper DataSource does not
resolve or rewrite an old finding; no matching by filename, list position or outgoing coordinates reconciles history. Do not retain a whole
ancestral message/parser graph merely to keep an affected source occurrence.

Replies, newly composed forwards and outgoing defaults/overrides completion retain their existing new-output/history boundary. Already
materialized normal content remains outgoing content, while inherited source/problem history is not blindly carried into the new output.
Any genuinely new completion observation belongs to that operation. Existing represented-content equality is unchanged.

Restore older supported snapshots with empty source/problem values when none were stored. Never reconstruct alleged original headers
from an edited outgoing map or infer successful inspection from absent historical data. The new source/problem graph is core-owned;
whole-Email Java restoration can still require existing optional producer classes elsewhere in its saved graph. Removing that broader
dependency is deferred to child [#965](https://github.com/bbottema/simple-java-mail/issues/965), outside #961 acceptance. No general
cross-version archival compatibility promise follows from this decision.

## Complete original input and selected-value inspection (accepted 10 November 2026)

The [#961](https://github.com/bbottema/simple-java-mail/issues/961) continuation adds the logical readable selection in
[ADR 0040](0040-retained-protected-content-merging.md#one-logical-readable-email-accepted-10-november-2026). It supersedes this record's
earlier selective-retention rule specifically for complete supplied raw input: automatically retain fully acquired EML/MSG input for
`getInspection().getOriginalMessage()` and deliberate original forwarding. Share safely owned immutable storage across ordinary copies;
do not reacquire the source. An independently transformed protected attachment retains its complete original separately, associated with
the selected attachment occurrence. An unchanged owned payload can share storage. The enclosing message envelope is the original message,
not a substitute original for every contained attachment.

Completeness and confidentiality are separate: an extracted child's complete original does not automatically include the enclosing parent's
encryption. Original forwarding preserves those actual bytes/format and retains a helper-derived outgoing requirement when reusing exposed
confidential material, including outer child headers that body-only encryption does not cover. An intact root encrypted original remains
distinct. Automatic carrier metadata can be neutral without redacting the historical original resource's name/description/Content-ID.
These are helper-output decisions under ADR 0008, not a new read gate, public source mode or automatic deletion/rewrite of retained evidence.

Only complete actual capture qualifies. A failed/limited prefix remains diagnostic evidence and cannot be forwarded as a complete
original. MIME/native reader objects without captured file input and freshly composed messages have no invented original. MSG stays MSG;
generated adapter EML is not original MSG. For EML String input, retained bytes are the UTF-8 interpretation actually consumed, with no
claim about unknown external wire encoding. Unrelated deferred resources remain unread. Import limits retain their acquisition/traversal
scope; they are not whole-heap retention limits or permission to evict captured history silently.

Keep captured source history separate from immutable metadata about each current selected value. `Email.getInspection()` reports origin,
derivation, availability, sources and scoped protection for named fields and indexed resource occurrences. A present empty value differs
from established absence, unavailable processing, deliberate deferral and uncaptured history. Family views reuse captured observations;
no getter performs work, returns duplicate field values or infers parent protection from a child. Old snapshots without captured facts
remain unknown rather than acquiring reconstructed proof. Historical source and Problems remain available after current-field edits.

Defaults/overrides completion likewise preserves the submitted draft's source/Problems/originals, historical protection observations and
unchanged selected-field associations. A replaced field has caller-supplied/unknown current facts; templates supply neither incoming
evidence nor disclosure consent. This supersedes the earlier completion/new-history statement above for these snapshots. New helper
messages still describe their own source and carry only deliberately reused content requirements; preserving history does not make a
captured old signature a verification of reconstructed output or turn historical Problems into a send gate.

Incoming best-effort processing preserves readable neighbors and complete original payload when usable configured credentials do not
match one component or corrupt/unsupported protected input cannot be opened. An invalid signature does not discard readable content.
Record the actual located failure/unavailable result once; deliberately unselected work does not fabricate a Problem. Unusable configured
key stores, explicitly requested missing modules, invalid API use, cancellation/interruption and unrelated application/provider defects
retain their operation-failure boundary. This refines the earlier explicit-decryption-failure statement for automatic Email interpretation;
standalone cryptographic helpers and outgoing preparation retain their own failure contracts. Classify at the owning boundary, never by
exception-message text or a blanket runtime catch.

Pure application protection checks return their own outcomes/reasons without adding Email Problems. A clean parse is not partner
approval. Diagnostic output results also distinguish refusal of helper-derived confidential reuse from a usable completed artifact;
they cannot expose unfinished cleartext output as success or mutate the source Email's history.

Retaining raw originals alongside distinct decoded/interpreted content can increase retained memory. Sharing storage avoids redundant
copies; it does not eliminate the cost of genuinely different representations. Copies/snapshots must not retain ancestor Emails, parsers
or live provider objects. The deferred whole-Email optional-module portability work under #965 remains separate.

## Consequences

Applications can use one Email for convenient parsed content, original evidence and explanation of imperfect input. The diagnostic
source surface remains separate from composition, and source access does not depend on every field being valid for sending.

Retained evidence can contain message content and sensitive metadata. Default summaries, logs, telemetry and protocol diagnostics must
not automatically include it. Explicit application inspection remains available. Captured memory and copy lifetimes need their own
characterization; an older outbound soak does not establish the cost of this source graph.

This architecture does not promise complete recovery of every damaged input, reconstruction of unavailable original bytes or general
cross-version Java serialization compatibility. The feature remains part of unreleased 10.0.0; current delivery state is recorded in #961.
