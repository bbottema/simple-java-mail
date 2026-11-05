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

Keep successfully represented bodies, attachments and embedded images in their ordinary fields and collections. Do not add recovery
states or invalid-content placeholders to AttachmentResource. Expose immutable source evidence through Email.getSourceData() and
historical processing observations through Email.getProblems(). Fresh composition and older snapshots without captured inspection
have empty source/problem values; empty source data does not invent an input origin or establish successful inspection.

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

Share immutable retained storage across copies. Do not keep a complete large backing allocation for one small exceptional region when
that region can be detached. Do not hide body payloads in unbounded base64/hex/text metadata to evade the selective-retention rule.
This avoids a second full raw archive; it does not promise exactly equal heap size for successful and failed inputs. Encodings, retained
original/converted versions, partial reads and temporary parser/crypto buffers can differ. Assess retained memory separately from peak allocation.
No retention-budget, disk-spooling, silent eviction or source-resolver policy is introduced by this decision.

### Problems and immutable input identity

EmailProblem contains an operation, stable category, qualified location, available offending source value, explanation, immutable input
identity and direct references to affected source elements. More than one source can explain a conflict; an operation-level problem can
have no particular element. Locations are diagnostic labels, not strings applications must parse to find the source.

Every actual source context has an EmailSourceIdentity, even with no Problems. It contains a stable UUID diagnostic reference and the
observed input Message-ID when available. Capture that identity before selecting, generating or backfilling output identity. Missing
Message-IDs remain absent, reused IDs do not conflate independent inputs, and a library-generated inspection-wrapper ID is not a purported
received identity. The diagnostic reference is not an email ID, content hash or authenticity claim.

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

## Consequences

Applications can use one Email for convenient parsed content, original evidence and explanation of imperfect input. The diagnostic
source surface remains separate from composition, and source access does not depend on every field being valid for sending.

Retained evidence can contain message content and sensitive metadata. Default summaries, logs, telemetry and protocol diagnostics must
not automatically include it. Explicit application inspection remains available. Captured memory and copy lifetimes need their own
characterization; an older outbound soak does not establish the cost of this source graph.

This architecture does not promise complete recovery of every damaged input, reconstruction of unavailable original bytes or general
cross-version Java serialization compatibility. The feature remains part of unreleased 10.0.0; current delivery state is recorded in #961.
