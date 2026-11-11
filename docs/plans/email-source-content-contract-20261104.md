# Diagnostic source-content contract

Retired after #961 delivery, 4 November 2026. This is a historical specification/implementation packet, not current status. The accepted
contract is preserved in [ADR 0041](../adr/0041-email-source-inspection-and-problems.md); current delivery and deferred work are in the
[durable roadmap](email-inspection-roadmap-20261104.md). This exact retired path is registered for guarded later cleanup.

Coordination/status entry point: [Email inspection roadmap](email-inspection-roadmap-20261104.md).

Status: source-content representation and retention/read design accepted by the maintainer on 4 November 2026 and implemented in the
working tree. Final review/delivery is tracked in the coordination plan. It replaces the unpublished #961 header/source API; no released API is being removed.
ADR 0041 owns the accepted rules. Operation-result diagnostics, whole-Email serialization portability and outgoing recovery remain
separate implementation obligations; acceptance of this source-content contract does not mark them complete.

Subsequent checkpoint: the [operation-outcome contract](email-conversion-outcomes-20261104.md) was accepted on 4 November. The maintainer
then clarified attachment/source ownership: enumerate source structure, but retain extra payloads only for failed/unrepresented or
meaningfully changed content. The [revised omission and retention direction](email-omissions-and-retention-20261104.md) withdraws the
default-refusal/omission-policy proposal and blanket latest-round payload capture. Keep the direct navigation below; a separate lightweight
reference/resolver API was not accepted. These source-content/recovery revisions are now implemented locally, pending final acceptance and delivery.

Final clarification accepted 4 November: retain available originals whenever root Email no longer exposes the received content as-is,
excluding different representations of the same canonical value. Original RTF is retained alongside converted HTML; successful Base64
decoding needs no duplicate attachment payload. This retention rule is independent of Problem production. Expected successful conversions
need no Problem lineage, and source evidence can be present when getProblems() is empty. No discussed design choice remains unresolved.

## Three roles

| API | Meaning |
| --- | --- |
| Existing Email body, attachment and embedded-image getters | Successfully represented current content, under existing content ownership and validation contracts. No new recovery-state flags on AttachmentResource. |
| `Email.getSourceData()` | Generic diagnostic source structure/metadata, with additional original payloads for failed/unrepresented or meaningfully changed content. Successfully represented unchanged content stays in the normal Email. |
| `Email.getProblems()` | Historical observations with original input identity and direct references to the affected source entries. It does not become a live validation or I/O log. |

The source view is not a second set of business-field getters. It can be enumerated without knowing a failed header/property name.
No public named-header lookup or filename/body/CID lookup is proposed in that domain. Existing specialized Outlook conversion-result
APIs remain available for applications deliberately using Outlook-specific functionality.

## Relationship to the remaining gaps

| Earlier gap | Effect of this accepted contract | Remaining work |
| --- | --- | --- |
| Body/attachment recovery and missing raw/partial-content model | Specifies the source tree, selectively retained exceptional evidence, partial captures and problem links needed for honest recovery. | Implement scoped best-effort recoveries and concrete usable-target/failure boundaries without an omission-policy gate. |
| IMPORT-only problem producers; COPY/CONVERSION not generally emitted | Supplies shared input identities, source references and preserved observations. | Implement accepted outcome ownership and concrete recovery/issue producers under the settled best-effort contract. Normal structural copying must not invent a finding merely to exercise COPY. |
| No diagnostic results for EML-byte/MimeMessage output conversion | Supplies the evidence an outcome could reference. | Implement the subsequently accepted operation-result contract for outputs that are not Email; do not mutate the immutable input Email or put failures in fabricated headers. |
| Whole-Email Java serialization requires optional producer classes | New source elements/content/identities are core-owned captured values. | Existing fields such as optional S/MIME result implementations still need a separate snapshot/compatibility design if full graph restoration without those modules is required. |

The middle two items now have an [accepted operation-outcome contract](email-conversion-outcomes-20261104.md): what is newly observed, which input owns
it, how inherited observations are exposed, when a usable value exists, and which failures still throw. This is a layer above the source
tree, not a reason to redesign its representation. Whole-Email graph portability is a separate compatibility track; the source graph
does not retroactively change old serialized class names or other existing fields. See the scoped assessment in the
[competitive progress companion](../research/simple-java-mail-world-class-smtp-progress-report.md#source-inspection-and-recoverable-conversion).

## Intended public interfaces

All read contracts live in core and are Serializable. Collections are immutable, never null and retain occurrences/order as exposed by
the reader. Nullable values are explicitly marked below. There are no application-facing builders or setters for captured evidence.

```java
// Remain on Email:
EmailSourceData getSourceData();
List<EmailProblem> getProblems();

public interface EmailSourceData extends Serializable {
    enum Format { MIME, OUTLOOK_MSG }
    enum Representation { RECEIVED, EXTRACTED, GENERATED }

    @Nullable EmailSourceIdentity getOrigin();
    @Nullable Format getFormat();
    @Nullable Representation getRepresentation();
    List<EmailSourceHeader> getHeaders();
    List<EmailSourceProperty> getProperties();
    List<EmailSourcePart> getParts();
    List<EmailSourceData> getDerivedSources();
}

public interface EmailSourceIdentity extends Serializable {
    UUID getReference();
    @Nullable String getMessageId();
}

public interface EmailSourceElement extends Serializable {
    EmailSourceIdentity getOrigin();
    String getLocation();
}

public interface EmailSourceHeader extends EmailSourceElement {
    String getName();
    @Nullable String getRawValue();
}

public interface EmailSourceProperty extends EmailSourceElement {
    String getName();
    @Nullable String getType();
    @Nullable String getValue();
    @Nullable EmailSourceContent getContent();
}

public interface EmailSourcePart extends EmailSourceElement {
    List<EmailSourceHeader> getHeaders();
    List<EmailSourceProperty> getProperties();
    List<EmailSourcePart> getChildren();
    @Nullable String getText();
    @Nullable EmailSourceContent getContent();
}

public interface EmailSourceContent extends Serializable {
    enum Representation { SOURCE_BYTES, READER_BYTES }

    Representation getRepresentation();
    boolean isFullyCaptured();
    long getSize();
    InputStream openStream();
    byte[] readAllBytes();
}

// Existing code/operation/message/source-value fields remain on EmailProblem.
// The input identity contract is generalized from the unpublished EmailProblemOrigin.
public interface EmailProblem extends Serializable {
    EmailSourceIdentity getOrigin();
    Operation getOperation();
    Code getCode();
    String getLocation();
    @Nullable String getSourceValue();
    String getMessage();
    List<EmailSourceElement> getSources();
}
```

`EmailSourceIdentity` generalizes the locally introduced `EmailProblemOrigin`: the same input has an identity even when it has no
problems. Its UUID and observed Message-ID retain the already agreed semantics. This is an unpublished target-only type adjustment.
Fresh composition and older snapshots without captured source retain the always-available empty source object: its origin, format and
representation are null, and all collections are empty. Actual source elements always have a non-null origin. Empty source data does not
invent an input identity or claim that source inspection succeeded.
Email itself is the non-null inspection result. Its normal fields may contain no interpreted content while its source/problem metadata
still explains the input. There is no EmailConversionResult<Email> or extra hasValue()/getValue() layer on this journey. Optional artifact
envelopes belong only to non-Email conversion targets.
Locations are diagnostic labels, not strings applications must parse to resolve a problem. `getSources()` supplies the actual immutable
source entries, usually one, possibly several for a conflict, and empty when an operation-level issue has no specific source entry.
Elements can be shared between the source tree and problems; no copy of an entire ancestral Email or parser is retained for a reference.

The unpublished `EmailSourceData.getHeaderValues(String)`, `getOutlookMessageData()` and singular `getUnwrappedContent()` are replaced
in this generic interface. Internal lookup remains an implementation detail. Native scalar/binary data is enumerable as properties;
existing `OutlookEmailConversionResult.getOutlookMessageData()` remains supported. Derived source views are enumerable because extracted
and generated representations need not be one simple unwrapping chain.

## Representation rules

- Root message headers belong in source data. A part contains its own content metadata, properties and children. Header entries may be
  shared where a reader exposes the same header occurrence at the root entity; their identity/location must make that relationship clear.
- `getParts()` contains the source view's immediate content components. Containers have children. Leaf parts have an empty children list.
  It includes successful part structure/metadata too, rather than being an unnamed failure bucket. Enumeration does not imply retaining
  every raw payload. No automatic reads occur merely to enumerate the tree.
- Retaining original content does not require a Problem or mean the conversion failed. Expected RTF-to-HTML conversion can retain the
  reader-provided RTF while producing usable normal HTML and no Problem. Problems reference affected evidence for actual issues, rather
  than recording every conversion difference. Source enumeration also exposes retained originals without problem links.
- MIME/EML preserves the available entity/container structure. MSG preserves the native body/resource components the adapter actually
  exposes and its native properties; it must not claim that generated MIME was the original MSG structure.
- Raw header values retain original spelling, occurrences and reader-exposed text. `getValue()` on a native property is the scalar/text
  value the reader exposes, not a promise of original storage bytes. Native identifiers/types are retained when available. Binary data
  belongs in the property's content handle. Missing typed interpretation never licenses fabrication of a value.
- `getText()` is text already provided by the source reader. It does not run another charset decoder or manufacture text from unknown
  bytes. Retain body-bearing text only under the same failed/changed-content rule as byte payloads; a large body is not cheap metadata
  merely because a reader exposed it as a String. Unknown scalar formats remain visible as reader-exposed values and type labels.
- `SOURCE_BYTES` means captured source bytes before SJM's interpretation/transfer decoding in that view. For MIME bodies this includes
  source transfer encoding. `READER_BYTES` means the adapter supplied byte content but cannot establish the original encoded form.
  Already-decoded reader bytes must not be labelled as original Base64/quoted-printable input.
- Derived views retain their own origin and are labelled RECEIVED, EXTRACTED or GENERATED honestly. Metadata/protection outcomes remain
  separate; a generated wrapper ID does not become a purported received ID. No getter starts unwrapping or recursive inspection.
- When framing cannot establish children, retain the containing data and a problem rather than inventing a tree or silently claiming
  there were no children.

## Retention and read rules

1. **Metadata is always captured when available.** Names, values, source structure and identities are immutable after conversion. Editing
   an Email copy does not rewrite them. No source getter consults a live parser or a changed original file.
2. **Retain additional payloads selectively.** If root Email exposes the received content unchanged, allowing canonical-equivalent
   representations such as successful Base64 decoding, do not keep another raw payload merely because parsing acquired it. Otherwise
   preserve the available original/prefix so the difference can be inspected. Reader-provided RTF remains alongside converted HTML,
   without requiring a Problem for that successful transformation. Ordinary successful transfer/charset decoding alone needs no duplicate.
   A recovered filename needs its original header/property, not a duplicate of unchanged attachment bytes. No complete raw EML archive is
   implied. Reuse immutable storage where appropriate, but copy a small exceptional region if sharing would pin an entire large input.
   Do not read external attachments solely for diagnostics in deferred mode. Existing exact/protected authoritative byte owners stay separate.
3. **The source view holds captured evidence, not borrowed live content callbacks.** The existing normal AttachmentResource may still
   borrow a DataSource under ADR 0036; the diagnostic source content does not silently become a parallel route to that live resource.
   If bytes are unavailable, lazy reading has not acquired them, or a successful unchanged payload was deliberately not retained,
   `getContent()` is null. This is not an empty payload and is not automatically a failure. The ordinary successful resource remains
   available through its existing API; no source callback silently reaches back into it.
4. **Complete, empty and partial captures are explicit.** A fully captured empty payload is a non-null content object with size zero and
   `isFullyCaptured() == true`. If an actual read fails after a prefix is obtained, retain that prefix with `isFullyCaptured() == false`
   and a problem linked to the part/property. `getSize()` is the retained byte count, not an invented expected total.
5. **Read methods only read the captured bytes.** `readAllBytes()` returns a defensive copy of all retained bytes; when the capture is
   partial, this is a prefix, not a complete-payload promise. `openStream()` opens a new repeatable stream over that same captured data;
   the caller closes it. These operations perform no external I/O, decoding, retries, serialization or protection work. Getters also do
   none of those operations. Actual acquisition failures are recorded during conversion; later reads of an ordinary borrowed resource
   retain their existing exceptions and do not mutate historical Email problems.
6. **No reconstruction masquerades as source.** Do not serialize a supplied live MimeMessage or re-encode reader text merely to invent
   original source bytes. Exact/protected source-byte guarantees remain owned by ADRs 0013/0008, not implied by this diagnostic tree.
7. **Copies share immutable evidence.** Ordinary copies and supported Java serialization preserve origin references and problem-to-source
   links without duplicate captures. The source graph contains core-owned values and captured buffers, not optional parser objects.
   Serialization of this diagnostic graph performs no new source reads; normal attachment serialization keeps its separate existing rule.
   Neither source elements nor problems may keep a whole ancestral Email, live parser or unrelated successful payload allocation merely
   to retain one exceptional occurrence. Ordinary copies retain the same Problems/raw source snapshot even after later setters supply
   proper normal content. Do not match output resources to old source coordinates, archive every input round, or rewrite old observations.
8. **Existing limits and ownership still apply.** Capture does not authorize unlimited work or change input-stream closing ownership.
   Source-data retention extends the lifetime of the captured data. Any imposed retention limit must leave an explicit partial capture
   and diagnostic rather than silently truncate data or label it fully captured.

Source evidence is returned for explicit inspection. Default string summaries, logs, telemetry and protocol diagnostics must not automatically
include raw headers, native values or message bytes. This does not restrict the application's explicit access to the retained evidence.

This is intentionally diagnostic access. Reopening or retrying an external resource is an ordinary resource operation; it does not
change the evidence in a previously produced Email. A caller needing a new inspection after a repair creates a new conversion result.

## Recovery behavior

Available interpreted content stays in normal Email fields and collections. An uninterpretable body/attachment/image is not represented
by an invalid AttachmentResource or by a special state on that class. Its source component remains in the diagnostic tree with its
available metadata/text/bytes; a root problem points to it. Readable neighboring content remains available. An empty interpreted body
retains its existing meaning and is never substituted for an unreadable one.

Recognized parsing/acquisition problems belong on the inspection Email with whatever evidence is available, including a source-only or
problem-only Email when normal content cannot be interpreted. A known component-level content failure preserves evidence and neighbors;
arbitrary application/runtime failures are not reclassified as malformed source data. A returned inspection Email is never null.
The problem list is a production-time snapshot. It does not grow later when a caller reads a borrowed normal attachment or sends mail.

The revised outgoing direction is best effort without an omission-policy API. A recoverable component failure yields usable remaining
content, observations and available exceptional source evidence, including compatible one-step source-to-output conversions. Result
selection does not change that behavior. Source data is not automatically rendered, and historical problem count is not a send verdict.
For non-Email artifact targets, inability to produce any usable output remains an operation failure; an unfinished output buffer is not
a usable artifact. This does not introduce a nullable Email inspection result.

## Implementation sequence

1. Update the unpublished #961 read contracts to this generic diagnostic shape. Replace named raw-header lookup and the format-specific
   getter with enumeration; retain compatibility of the existing specialized Outlook conversion-result API. Update ADRs and examples.
2. Add immutable source identities/elements, tree assembly and direct problem references. Verify repeated/case-interleaved headers,
   conflicting sources, generated/extracted views, copy origins and serial graph sharing before changing failure recovery.
3. Retain available exceptional raw/reader content and release successful temporary captures. Exercise all input formats and eager/deferred modes, complete/empty/partial
   capture, reader-only text/bytes, one-shot streams and source changes. Assert that getters/copies/serialization of the source graph
   neither fetch external data nor invent original bytes.
4. Implement agreed body/resource recoveries independently: unknown text charset, unsupported transfer encoding, undecodable naming
   metadata, component I/O and malformed containers. Assert valid normal collections, retained evidence, exact problem links and no
   silent truncation or fabrication. Isolate compatible output component failures so remaining output is valid and complete; report true
   whole-operation failures without an omission-policy gate.
5. Review the combined API, implementation, ADRs, Javadocs, website/Journal/case studies/examples, migration inventories and package outputs.
   Complete the standing review workflow with selective delivery, issue/project bookkeeping and scoped cleanup; preserve other work.

## Verification boundaries

Use public static/fluent MIME/EML/MSG and exact inspection paths, existing protection processing and real malformed-source fixtures.
Compare all-source enumeration with the actual reader's available structure, not just successful normalized output. Check direct links
for header/part/property and cross-view problems, A-to-B-to-C origin retention, independent inputs with reused/missing IDs, no normal
resource state flags, optional-module-independent source graph restoration and unchanged exact/protected bytes. Use fresh reactor
outputs on Java 11/21 and focused controls. Preserve unrelated staging, dirty main/website content and registered worktrees.

Assess large-message peak/retained memory and repeated source-sharing/copying separately from the older outbound soak. Verify that default
summaries and operational reporting do not dump source data. These obligations support the scoped progress-guide assessment; they do not
establish competitive superiority, protocol completeness or a cross-library performance result.
