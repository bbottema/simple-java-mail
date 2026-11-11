# Email inspection and conversion: durable coordination record

Last reconciled: 4 November 2026. The accepted #961 implementation and its review workflow are complete for **10.0.0 (unreleased)**.
This file remains the local entry point for decisions, delivery and deferred work; the maintained architectural contract is
[ADR 0041](../adr/0041-email-source-inspection-and-problems.md). Historical planning checkpoints below do not override this current status.

## Current state and delivery

- [#961](https://github.com/bbottema/simple-java-mail/issues/961) is closed as completed, milestone **10.0.0**, canonical label
  **major feature**, native project **Simple Java Mail 10.0.0 / Done**. The [AFTER usage and resolution](https://github.com/bbottema/simple-java-mail/issues/961#issuecomment-6642682274)
  are posted and read back. The original unsupported-charset BEFORE example remains in the issue body.
- The generic source-content/read contract, selective original retention, root Problems, component recovery and artifact-only diagnostics
  are implemented and accepted. Email remains the non-null inspection result. No agreed #961 design or delivery item remains open.
- Website publication preceded the main gitlink, using nonforce pushes to the authorized development branches. No release, deployment,
  history rewrite or unrelated publication was performed.
- [#965](https://github.com/bbottema/simple-java-mail/issues/965) remains open, **Parked**, without a milestone. Whole-Email restoration
  without optional producer classes is outside #961. The core-only source/problem graph is separately usable.
- Calendar merging remains delivered under [#960](https://github.com/bbottema/simple-java-mail/issues/960) and
  [ADR 0040](../adr/0040-retained-protected-content-merging.md). Its previously blocked dependency-junction cleanup is separate and untouched.

| Delivered part | Branch / commit |
| --- | --- |
| Inspection/conversion feature | `codex/10.0.0` / [`734058b7874084e72cfdb04aaf3944a86c4555b7`](https://github.com/bbottema/simple-java-mail/commit/734058b7874084e72cfdb04aaf3944a86c4555b7) |
| Website guidance | `codex/10.0.0-content` / [`ad01c8fa29153553f8d99962820b6a1e6656f2bf`](https://github.com/simple-java-mail/simplejavamail.org/commit/ad01c8fa29153553f8d99962820b6a1e6656f2bf) |
| Separate ticketless version-documentation companion | [`7531595fc251ca127cd2d9dc7867f611d9a3516a`](https://github.com/bbottema/simple-java-mail/commit/7531595fc251ca127cd2d9dc7867f611d9a3516a) |

Root reviewed the substantive combined code, final corrections, selected trees/messages and final verification before giving delivery
clearance. Both final actual Java 11/21 reactor lanes, the current package/Javadoc/CLI artifacts and packaged AFTER usage are accepted.
The rendered website, public examples, bounded memory characterization and source-only core restoration evidence were reconciled with the
final contract. Unrelated tracked working content and unrelated index entries, including the three staged Angus files, were preserved.

## Maintained decisions and navigation

- [Source structure and selective originals](../adr/0041-email-source-inspection-and-problems.md#generic-source-structure-and-selective-original-content)
- [Captured-data read ownership](../adr/0041-email-source-inspection-and-problems.md#captured-content-and-read-ownership)
- [Problems and original input identity](../adr/0041-email-source-inspection-and-problems.md#problems-and-immutable-input-identity)
- [Best-effort recovery and outgoing content](../adr/0041-email-source-inspection-and-problems.md#best-effort-recovery-and-outgoing-content)
- [Non-Email artifact results](../adr/0041-email-source-inspection-and-problems.md#diagnostic-results-for-non-email-artifacts)
- [Copies, serialization and completion](../adr/0041-email-source-inspection-and-problems.md#copies-serialization-and-completion)
- [Scoped summit progress](../research/simple-java-mail-world-class-smtp-progress-report.md#source-inspection-and-recoverable-conversion)

## Execution records and cleanup

Task-owned execution records remain under `.local/email-source-inspection-20261103/implementation-20261104`; they are not publication prose.
The [workflow status packet](../../.local/email-source-inspection-20261103/implementation-20261104/workflow-status-packet-20261104.md)
preserves the actual impact map, review findings and honest chronology. Root's
[eight-stage audit](../../.local/email-source-inspection-20261103/implementation-20261104/root-workflow-audit-20261104.md) remains root-owned.
The initial preflight was late and some early old-source failures were retrospective baselines, not strict test-first evidence; final
acceptance does not rewrite that history. The raw interrupted Java 21 harness and earlier failing Java 11 results remain separate from
`final-current-jdk11/verification-results.json` and `final-current-jdk21/verification-results.json`.

The original header-only packet and the three detailed source-content/outcome/omission specifications are retired after their accepted
rules and navigation were preserved in ADR 0041. Their exact paths and the owned scratch parent are registered in the existing guarded
cleanup script. Working and selected script syntax, plus task-only guarded WhatIf, are complete. No deletion was attempted; execution
records remain available for review. No owned Java/Node helpers remain running. Keep this durable roadmap; the four retired specifications
can be disposed of later using the registered guarded cleanup. The old Calendar junction was not retried.

## Accepted decisions to preserve

1. Email accelerates inspection. Preserve received evidence and readable neighbors when an interpretation fails; do not invent decoded
   characters, timestamps, mailboxes or protection success.
2. Normal bodies, attachments and embedded images represent successfully converted content. Do not add recovery states or invalid
   placeholders to AttachmentResource.
3. Source data is an enumerable diagnostic view of all available source components, successful and problematic. Avoid a parallel set of
   precise business-field getters in the raw domain. Enumeration does not require another copy of every payload: retain original payload
   when the root Email no longer exposes it as received, excluding representations of the same canonical value, such as successful Base64
   decoding. Retain available original RTF alongside converted HTML. Existing specialized Outlook result APIs remain supported.
4. Problems identify the original input and affected evidence. Input Message-ID may be absent or reused; a stable diagnostic reference
   is separate from email identity. A-to-B-to-C copying never rewrites an inherited origin to B or C.
5. Source metadata and captured content are immutable evidence. No getter, source-graph copy or source-graph serialization fetches
   external content, runs a decoder/crypto operation, retries, or reconstructs alleged original bytes. Selected lazy reading is respected.
6. Captured empty, unavailable/uncaptured and partial content are distinguishable. Captured prefixes are not complete-payload promises.
7. Ordinary copies retain input history without replay or accumulation. Replies/new forwards and outgoing completion retain their
   existing new-output/history boundaries. Subsequent setters affect the normal model, never the copied Problems/source snapshot;
   no matching by filename, list position or outgoing coordinates reconciles history. Problems are not a live validation, attachment-read or send log.
8. Default summaries/logs/telemetry do not automatically dump source content. Measure source retention and repeated-copy behavior;
   the old outbound soak does not certify the new retained-content graph.
9. All current 10.0.0-branch documentation uses unreleased 10.0.0 coordinates. Do not derive the documentation target from the incidental
   root POM build version; preserve historical release facts.
10. Operation-result terminals report only observations newly made by that requested operation, including delegated stages. A usable value
    and an operation failure are mutually exclusive; recovered component problems can accompany a usable value. Result selection does not
    change the default best-effort behavior or enable extra reads/crypto.
    Invalid use and unrelated application failures still throw. The input Email is not mutated into an operation log.
11. Do not reject a conversion merely because a body/resource cannot be represented. Produce usable normal content and report the rest,
    retaining available exceptional evidence. No omission-policy API or historical-problem send gate. Never call half-written output a
    usable artifact; genuine inability to produce a usable non-Email target remains an artifact failure. A diagnostic Email can still hold
    available source information/problems even when no normal content can be interpreted.
12. Email itself is the inspection result. A returned Email is non-null even when it contains only retained available source evidence,
    metadata and problems, with no usable interpreted content. No EmailConversionResult<Email>, toEmailResult() or builder-result variant.
    The optional envelope serves non-Email output targets that cannot carry these getters; no-value artifact behavior does not mean a null Email.
13. Source retention and Problem production are independent. A successful expected transformation can retain its original source without
    a Problem. Register Problems for actual processing/interpretation issues; do not create a conversion-difference ledger or require
    every retained source to be reachable from a Problem.

## Decision log

| Date | Decision/checkpoint |
| --- | --- |
| 3 November | Initial #961 source inspection/root problems, immutable Message-ID/reference origins and copy preservation approved and locally implemented; delivery later identified as unfinished. |
| 4 November | Recovery states on normal AttachmentResource rejected; normal structure remains successfully represented content. |
| 4 November | All-source content view chosen for further design; generic diagnostics lead the API rather than precise raw-header lookup. |
| 4 November | Source-content interfaces and captured-evidence retention/read contract accepted; implementation remains pending. |
| 4 November | Summit companion assessed: message-handling/diagnostic contribution, not SMTP breadth or ranking credit; remaining outcome/portability tracks identified. |
| 4 November | Maintainer explicitly requested durable coordination rather than reliance on context/plan mode. This file now coordinates the linked plans; operation outcomes are the active proposal. |
| 4 November | Maintainer accepted the operation-outcome proposal and requested outgoing omission behavior next. Attachment size/memory prompted review of whether prior parse rounds retain payloads through historical problems; omission/retention proposals remain open. |
| 4 November | Maintainer corrected blanket source retention: only failed/unrepresented or meaningfully changed content needs additional original payloads. Best-effort conversions should return what they can with problems/raw exceptions; SourceContentOmissionPolicy/default refusal withdrawn. Earlier latest-round/full-capture and mandatory lightweight-reference proposals superseded. |
| 4 November | Clarified why output diagnostics need an optional envelope: bytes/String/MimeMessage cannot carry Email getters. Email already is the non-null inspection result; remove redundant Email/builder-result terminals from the proposal. |
| 4 November | Maintainer asked what remains before delegation. Closeout now identifies changed-content retention, lifetime of old exceptional evidence after correction, and whole-Email portability scope as the remaining choices; implementation/review obligations are listed separately. No implementation delegation started. |
| 4 November | Maintainer corrected closeout: ordinary copying/setters already preserve Problems/raw sources unchanged; this is not an open lifecycle choice and needs no coordinate matching. Replace vague "automatic repairs/modified" framing with concrete representation/retention cases. Serialization portability explicitly deferred to created child #965, Parked on project 1 without a milestone. |
| 4 November | Final retention rule accepted: original RTF remains alongside converted HTML; retain source content not exposed as received by root Email, excluding canonical-equivalent representations such as Base64. Retained sources do not imply Problems: record actual issues, not every conversion difference. Design closeout complete; revised runtime implementation/review workflow remains pending. |
| 4 November | Maintainer explicitly delegated implementation and complete review-workflow delivery for #961 as a major feature, and requested its GitHub problem/solution direction be refreshed. Canonical label applied and issue body reconciled; dedicated owner `/root/email_inspection_delivery` takes the implementation packet while root returns to the audit. |
| 4 November | Root accepted the final combined implementation, runtime/package/public-smoke evidence and selected delivery. Website-first nonforce publication completed; AFTER resolution posted; #961 closed/completed and Done, while #965 remains deferred/Parked. Scoped cleanup bookkeeping is complete and this roadmap remains durable. |
