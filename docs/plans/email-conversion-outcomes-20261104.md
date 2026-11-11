# Conversion-operation outcome proposal

Retired after #961 delivery, 4 November 2026. This is a historical specification/implementation packet, not current status. The accepted
contract is preserved in [ADR 0041](../adr/0041-email-source-inspection-and-problems.md); current delivery and deferred work are in the
[durable roadmap](email-inspection-roadmap-20261104.md). This exact retired path is registered for guarded later cleanup.

Status: design accepted by the maintainer, 4 November 2026. Generic conversion/copy reporting and output-conversion diagnostic results
are settled together. Subsequently clarified: Email itself is the non-null inspection result; the wrapper applies only to non-Email
artifact targets, not Email or its builders. Implemented in the working tree; final review/delivery is tracked in the coordination plan. The [revised omission and retention direction](email-omissions-and-retention-20261104.md)
clarifies default best-effort conversion and selective original payload retention, superseding the earlier refusal/omission-policy proposal.
The current state/accepted decisions are in the [coordination plan](email-inspection-roadmap-20261104.md). The
[accepted source-content contract](email-source-content-contract-20261104.md) supplies evidence, not an operation result by itself.

## Problem and intended use

Email already contains the normal content, retained available exceptional source evidence, metadata and problems. toEmail() returns that
non-null inspection result directly, including when none of the source could be interpreted into normal fields. There is no second value
whose presence callers must check before inspecting the Email, and no EmailConversionResult<Email> or redundant builder-result terminal.

EML bytes, text and MimeMessage do not provide those getters. The optional result envelope belongs to those artifact targets only, and
immutable input Email must not become a mutable output-operation log. A normal copy needs no new finding merely because history was retained.

Add diagnostic result terminals alongside existing value-returning artifact terminals, using one contract for those compatible targets:

```java
EmailConversionResult<byte[]> result = mail.converter()
    .fromEmail(email)
    .toEmlBytesResult();

for (EmailProblem problem : result.getProblems()) {
    report(problem.getOrigin(), problem.getOperation(), problem.getMessage());
    for (EmailSourceElement source : problem.getSources()) dump(source);
}

if (result.hasValue()) {
    store(result.getValue());
} else {
    handle(result.getFailure());
}
```

The result makes a failed operation inspectable without pretending that unfinished output is a usable artifact. Recoverable component
problems can accompany a usable value under the default best-effort direction. Direct and Result terminals share those conversion
semantics; Result selection adds reporting, not a recovery/omission-policy switch, extra source fetching or crypto. In particular, direct MIME/EML export does not start receive-side interpretation simply to produce
diagnostics about fields the operation does not need to understand.

## Accepted public contract

Put this core read contract for non-Email artifact targets in `org.simplejavamail.api.converter`:

```java
public interface EmailConversionResult<T> {
    boolean hasValue();
    @Nullable T getValue();
    List<EmailProblem> getProblems();
    List<EmailSourceData> getSources();
    @Nullable MailException getFailure();
}
```

- `hasValue()` means a usable value for the selected target exists. It does not mean zero observations, valid signatures, full
  interpretation of every input field or a guarantee that a later send succeeds.
- Value and operation failure are mutually exclusive. With a usable value, `getValue()` is non-null and `getFailure()` null, even when
  component problems were recovered. If no usable target can be produced, `getValue()` is null and `getFailure()` retains the cause.
- `getProblems()` is an immutable list of observations first made during this requested operation, including delegated stages. It does
  not repeat the input Email's prior findings. Existing input history remains on that input; no extra duplicate "all history" list is added.
- `getSources()` contains immutable diagnostic source views actually available to this operation. It can expose acquired evidence even
  when no target value was produced. Payload retention is selective for failed/unrepresented or meaningfully changed content, not every
  acquired successful payload. It does not force extra reads. Problem source references share the retained evidence values.
  Retained originals do not require a Problem: successful RTF-to-HTML conversion can retain reader-provided RTF with no observation, while
  canonical-equivalent decoding needs no raw duplicate. Inspect sources independently from the newly observed problems.
- The wrapper and diagnostics are stable. Target values retain their existing contracts: a MimeMessage may still be mutable. The generic
  wrapper makes no new Java-serialization/byte-preservation promise about arbitrary T; no public terminal returns a wrapped Email/builder.
- Bad arguments, invalid target/options combinations, reuse of an already consumed source stage, cancellation, Errors and unrelated
  application/programming failures still throw. The result captures only recognized conversion-owned failures; no catch-all RuntimeException.
- A known failed conversion records a specific new problem where its boundary/source is known, plus the original conversion failure.
  Do not classify exception messages with prose matching or invent a successful value after only partial output was generated.

Candidate problem codes for concrete new boundaries are `CONTENT_READ_FAILED`, `CONTENT_NOT_REPRESENTED` and `CONVERSION_FAILED` in
addition to the accepted interpretation/protection categories. Introduce only codes with real producers and defined semantics.

## Terminals and source-stage behavior

Use Result suffixes only on existing compatible artifact targets: `toMimeMessageResult()`, `toEmlResult()` and `toEmlBytesResult()`.
Keep `toEmail()` and `toEmailBuilder()` as the direct inspection/builder journey. The older specialized Outlook metadata-returning method
remains supported; do not add another generic wrapper around it for symmetry.

Do not add a target a stage does not currently support merely for symmetry. Incoming options and export-only options retain their
current eligibility rules. Validate target/options before consuming the source. Once the actual read begins, the single-use stage stays
consumed even on a returned failure; retry requires a new stage and a new/replayable input. This matches the current stream-safety rule.
Direct and result terminals delegate to the same operation implementation; direct terminals rethrow a known failure instead of returning
the envelope. Both use the same best-effort component recovery; no SourceContentOmissionPolicy is added.

## Newly observed versus inherited findings

Maintain an explicit operation collector. Seed resulting Email history according to ownership, but record new events separately; do not
infer a delta by comparing problem messages, list lengths, Message-ID, or value equality. Repeated equal-looking occurrences can be real.

| Operation | New result observations | Resulting Email history, when there is an Email target |
| --- | --- | --- |
| External MIME/EML/MSG -> Email | New IMPORT findings from this input and materialized stages. | Those findings, with source identity and qualified references. |
| Email -> EML bytes/text/MimeMessage | Only new findings from acquiring/rendering the selected current representation; new output-stage findings use CONVERSION. | No mutable input update and no diagnostic metadata fabricated into output headers. |
| MSG -> EML/MimeMessage | New IMPORT findings from its delegated import plus any new CONVERSION findings from output preparation. | The transient Email is not a reason to hide either stage's observations. |
| Ordinary Email -> editable copy | Normally none. No extra I/O, verification, decoding or observation replay. | Existing history remains unchanged; a genuinely specified future recovery would append one COPY finding. |
| MimeMessage -> editable copy | New IMPORT findings from the delegated import, once. | Preserve the imported history; do not relabel those findings COPY. |
| Replies/new forwards/outgoing completion | New findings only if this operation actually performs a specified recovery. | Existing new-output boundaries remain; do not blindly carry received problems from the subject/template. |

The problem origin identifies the input being processed when the observation first arose, captured before selecting output identity.
Inherited A findings remain A after B/C IDs. A newly observed transformation problem on input B identifies B. No automatically generated
output ID or diagnostic UUID becomes a purported received input identity. Multiple source entries keep their own identities.

Normal copy methods keep their existing builder workflow. No separate copy-result API or new producer is required merely to populate the
COPY enum. Inventory concrete recoverable copy cases first. Known legacy unavailable serialized content belongs in the separate portability/
compatibility discussion; do not silently alter legacy-copy behavior under this generic result proposal.

## Value and failure examples

| Situation | Proposed outcome |
| --- | --- |
| Full target produced, no recovery needed | Value present; new-problems empty; failure null. |
| An accepted metadata interpretation recovery yields a usable target | Value present; exact new observations and evidence available. |
| Tolerant import cannot interpret one part | Direct non-null Email with successfully represented normal content, retained exceptional source evidence and IMPORT problems; no wrapper. |
| Recoverable component acquisition/interpretation fails during one-step source -> EML | A valid completed EML value for representable content, with IMPORT/CONVERSION observations and exceptional source evidence in the result; no omission-policy gate. |
| A deferred selected attachment read fails during output but can be isolated | Produce a valid completed remaining target; located CONVERSION problem with the original cause and available exceptional source evidence. Do not mutate input history. |
| Component/output failure cannot be isolated into any usable completed target | No value; original failure and available exceptional evidence. Never return an unfinished EML buffer as a usable artifact. |
| Existing MIME can be preserved without understanding a declared text charset | Usable target can be present; do not invent a decoding failure for an interpretation not attempted. |
| Parsing cannot produce normal interpreted fields | Direct non-null inspection Email containing available source evidence/metadata and problems. No nullable Email value or hasValue() step. Invalid usage/unrelated programming failures still throw. |
| Invalid options or unrelated custom getter throws | Existing exception propagates; no fabricated diagnostic recovery/result. |

`toEmlBytesResult()` must not return a buffer written before a failed conversion as its usable byte[] value. Captured partial output, if
retained for diagnosis, is separately labelled generated/incomplete source evidence. A caller must not mistake it for a complete artifact.

## Still separate: outgoing omissions and payload lifetime

The maintainer subsequently rejected the proposed default refusal and SourceContentOmissionPolicy as unnecessary gating. Use best-effort
conversion consistently: successfully represented content plus problems and exceptional original evidence, without an explicit omission
acknowledgement. The result envelope distinguishes recovered component observations from actual inability to produce any usable target.
Historical problems never become a send gate; diagnostic sources are not automatically rendered as outgoing content.

The [revised omission and retention direction](email-omissions-and-retention-20261104.md) replaces blanket raw-payload capture with selective
failed/changed-content retention. Standard decoding is not itself a meaningful content change. Keep direct problem-to-source navigation
without pinning whole ancestral Emails/parsers/unrelated successful buffers. Ordinary copies and subsequent setters keep captured
Problems/raw sources unchanged, without coordinate/resource matching. The earlier lightweight-reference/resolver proposal is not mandatory.

## Separate portability track

The captured source/problem graph is core-owned. Whole Email Java restoration still depends on existing concrete producer classes,
notably optional S/MIME result implementations. Deferred child [#965](https://github.com/bbottema/simple-java-mail/issues/965) owns that
compatibility work; it is Parked on the board, has no milestone and does not block #961. It does not change this outcome wrapper or imply
Java serialization is a long-term cross-version archive.

## Implementation and review plan for the settled design

1. Map compatible current terminals and exact exception/consumption boundaries. Keep the existing callers/source options/ownership.
2. Add the core result contract and an internal collector distinguishing inherited history from observations newly emitted by actual stages.
3. Wire Result terminals and direct-terminal delegation, including delegated Outlook import/export and no-value evidence.
4. Add concrete producers only for accepted recoveries/failures. Protect no-new-problem structural copies and stable origin/reference links.
5. Verify full/recovered/no-value results, retained sources with no Problems, canonical-equivalent decoding without duplicate capture,
   original RTF beside converted HTML, partial output separation, known I/O causes, custom runtime failures, cancellations, source reuse,
   mutable target values, eager/deferred sources, ID-changing copies and no input/history/header mutation through public APIs.
6. Reconcile ADRs, guide/Javadocs/CLI applicability, migration/progress inventories and complete the standing selective-delivery workflow.
