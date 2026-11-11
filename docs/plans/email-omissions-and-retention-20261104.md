# Outgoing content and selective source retention

Retired after #961 delivery, 4 November 2026. This is a historical specification/implementation packet, not current status. The accepted
contract is preserved in [ADR 0041](../adr/0041-email-source-inspection-and-problems.md); current delivery and deferred work are in the
[durable roadmap](email-inspection-roadmap-20261104.md). This exact retired path is registered for guarded later cleanup.

Status: design settled, 4 November 2026. Retain available original source content that is not exposed as received by root Email, excluding
different representations of the same canonical value. The original RTF remains alongside converted HTML. Source retention does not
require a Problem; successful expected transformations need no problem lineage. The prior default-refusal/SourceContentOmissionPolicy
proposal is withdrawn. Prefer useful conversion results with actual problems and retained source evidence. Implemented in the working tree;
final review/delivery remains tracked in the coordination plan.
The [coordination plan](email-inspection-roadmap-20261104.md) owns status; [ADR 0041](../adr/0041-email-source-inspection-and-problems.md)
records this revision. Email itself is the non-null inspection result. The [operation-outcome contract](email-conversion-outcomes-20261104.md)
supplies an optional envelope only for non-Email artifact outputs such as bytes/String/MimeMessage.

## Revised principle

Keep successfully represented content in normal Email fields and resources. Keep available original content separately when conversion
could not represent it, or changed it in a way that would otherwise hide evidence. Diagnostic source structure/metadata can still enumerate
all reader-exposed parts. Enumerating a part does not require retaining a second payload for it. There is no general raw-input archive.

Retaining original evidence is independent of performing a repair/conversion: a conversion can succeed without retaining its input.
The maintainer settled what the inspection model should expose afterward: if a caller sees a value and says it differs from what was
received, the retained source should provide the available original evidence. Reader-provided RTF converted to HTML is the accepted example:
normal Email has usable HTML and source data keeps the original RTF. Successful Base64 decoding retains the same attachment content in
the normal model, so it needs no second raw payload. This is not a request to invent original bytes or add new conversion behavior.

Retained source content and Problems are not one-to-one. A successful expected format conversion can have retained original content
without any Problem. Record a Problem for an actual interpretation/read/recovery issue. Do not label every change of representation a
problem, require a Problem to retain source data, or add per-transformation Problem lineage. Generic source enumeration remains necessary
even when getProblems() is empty.

Conversion should return what it can represent, with problems and retained exceptional evidence explaining the rest. Do not reject a
conversion merely because one part could not be materialized. No source-content omission policy, acknowledgement flag or send gate is added.
One-step MSG-to-output conversion and import-then-export use the same best-effort content selection. Selecting a Result terminal adds
reporting, not a different recovery policy.

## Content ownership examples

| Input outcome | Normal Email | Additional diagnostic retention |
| --- | --- | --- |
| Attachment content parsed without alteration | Ordinary attachment with its existing ownership contract. | Source metadata/structure; no second raw payload solely for diagnostics. |
| Attachment content cannot be decoded/read completely | No invalid attachment in the normal list; usable neighbors remain. | Available original/partial source bytes, source metadata and a linked problem. Never invent missing bytes. |
| Filename cannot be interpreted, but attachment bytes remain usable | Ordinary attachment under the agreed metadata-recovery behavior. | Original filename header/property and problem; no extra copy of unchanged attachment bytes. |
| Reader-provided RTF is successfully converted to HTML | Usable HTML in its normal field. | Available original RTF, without a Problem merely because this expected conversion occurred. |
| Conversion replaces/rewrites received body or resource content | Usable converted content in its normal field/resource. | Available original content not exposed as received by the root Email. Register a Problem only if an actual processing/interpretation issue occurred. |
| Standard successful MIME transfer decoding or charset interpretation | Ordinary decoded content. | No raw copy merely because encoded input bytes and interpreted output differ in representation. |
| Container framing cannot establish child boundaries | Any neighbors the reader can establish reliably. | The smallest available enclosing source content needed for the failure; do not claim an invented child structure or exact memory equivalence. |

A modified metadata field does not by itself justify retaining its unchanged binary payload. Body-bearing native properties and large
base64/hex strings count as content, not cheap metadata. Record which boundary actually failed/changed; do not infer payload retention
from a problem count or the mere fact that every parse transforms wire syntax into objects.

## Caller usage

The result API and broader component recovery below are design work, not current runtime behavior.

```java
Email email = mail.converter().fromOutlookMsg(msgPath).toEmail();

// Successfully recovered attachments remain ordinary attachments.
for (AttachmentResource attachment : email.getAttachments()) use(attachment);

// Problems lead to exceptional retained source evidence.
for (EmailProblem problem : email.getProblems()) {
    for (EmailSourceElement source : problem.getSources()) inspect(source);
}

// Expected transformations can retain originals without producing Problems.
inspect(email.getSourceData().getParts());
```

```java
EmailConversionResult<byte[]> result = mail.converter()
    .fromOutlookMsg(msgPath)
    .toEmlBytesResult();

// A recoverable attachment failure does not by itself remove the output value.
if (result.hasValue()) save(result.getValue());
report(result.getProblems(), result.getSources());
```

An output artifact contains the content successfully rendered for that target. Exceptional source evidence stays alongside it in the
result; do not automatically send raw undecodable parts, fabricate diagnostic headers or conceal raw bytes as ordinary attachments.
Direct value terminals remain convenient but cannot carry an out-of-band report for byte/String/MimeMessage targets. Recommend Result
terminals to callers who need the observations and exceptional raw evidence. No input Email mutation or automatic raw logging is introduced.

## Actual inability to produce a value

For Email targets, the result is always the returned non-null Email itself, even with no interpreted fields and only available source
evidence/metadata/problems. Do not introduce getValue()/hasValue() around this inspection model. The no-value behavior below concerns
non-Email output artifacts, not the existence of an inspection Email.

Recoverable source-component failures should not reject the operation. If a selected deferred attachment fails during conversion, recover
at its component boundary where a valid remaining target can be produced, report the new CONVERSION problem and retain available evidence.
This revises the prior blanket rule that every selected-attachment read failure necessarily removes the output value. It does not promise
new I/O during lazy parsing or source getters; observation occurs when the selected operation actually attempts the read.

A usable output is still a valid completed artifact for its represented content. A half-written EML buffer is not the value. If the entire
input cannot be acquired/framed into a usable output artifact, or generation cannot produce that artifact at all, return the non-Email
no-value failure envelope with available evidence; direct artifact terminals rethrow that recognized failure. Invalid usage, cancellation, Errors and
unrelated application/programming failures retain their existing exception behavior. Never invent unavailable source bytes to satisfy
"best effort" or bypass the existing exact/protected output guarantees and validation rules.

Implementation must stage or otherwise isolate recoverable component work before committing it to the target; a catch around a partially
written multipart stream is insufficient. Define concrete supported recovery boundaries and report broader limitations honestly.

## Memory meaning and history

The maintainer's N-KB example is the intended ownership model: retain the usable normal content plus the failed content in source form,
rather than the usable normal content plus a complete raw duplicate. A healthy input should add diagnostic metadata, not another full set
of attachment payloads. Selective retention replaces the earlier proposal to retain every acquired payload in the latest round.

It is not an exact N-KB guarantee. Wire encoding can be larger than decoded content, failed reads can yield only a prefix, changed content
may require original and converted versions, and parser/crypto buffers add transient allocations. Existing exact/protected authoritative
bytes can have their own required owners. Measure retained memory separately from peak parsing memory; do not call one equal to the other.

Keep only the storage required for exceptional evidence. A tiny retained slice must not accidentally pin a complete large EML buffer;
copying that small exceptional region can be preferable to sharing the entire backing allocation. Release successful temporary captures
and parser owners after the operation where existing contracts permit. Normal AttachmentResource ownership remains unchanged.

Current local #961 source inspection contains metadata, not the future source-part payload graph. Ordinary copies reuse the immutable
source snapshot; copying is not another parse. Problems preserve origins/history under existing copy/serialization rules. EML export does
not embed SJM history, so export/reparse does not automatically accumulate all earlier source/problem graphs.

Ordinary copies retain the same Problems and captured raw sources. Later normal setters, including supplying a proper DataSource, do
not rewrite that input ledger or source snapshot. No matching by filename, list position or source/outgoing coordinates is involved.
Copies must share retained exceptional payloads, not recapture them. Problems/source elements must not retain a whole ancestral Email,
parser or successful input buffer to preserve one affected occurrence. The previously suggested replacement EmailSourceReference API was
not accepted and is not required merely to solve blanket raw-payload retention. Keep the accepted direct problem-to-source navigation while
reviewing actual reachable storage. Honor the existing copy/history boundaries; do not silently discard evidence, archive every round,
mutate an older snapshot or relabel old origins. The maintainer explicitly confirmed this copy/setter behavior; it is not an open choice.

## Implementation and remaining work

- Apply selective retention consistently to bytes, reader text, native body properties and enclosing/derived source views. Preserve the
  all-source diagnostic structure without keeping successful unchanged payloads under another name.
- Verify original RTF retained beside converted HTML with no invented Problem, canonical-equivalent Base64 decoding without an extra
  raw payload, and source enumeration independent of the Problems list. Original retention is not gated on recovery/problem production.
- Add concrete component recovery on import and compatible output paths, with no default refusal or omission-policy API. Preserve valid
  normal resource collections, actual source availability and fully completed output artifacts.
- Reconcile the unpublished #961 API, ADRs, examples, migration/progress inventories and the accepted operation-outcome failure cases.
- Check large healthy inputs, one failed component, metadata-only changes, changed payloads, malformed containers, eager/deferred modes,
  encrypted content, repeated copying and history transformations. Inspect retained backing allocations as well as object/list counts.
- Measure first. No metadata-only option, retention budget, temp-file lifecycle or new reference/resolver API is approved by this clarification.
- Complete the standing selective review/delivery workflow after implementation; these local design edits do not constitute delivery.
