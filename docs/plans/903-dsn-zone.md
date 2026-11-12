Delivered on codex/10.0.0 at 1a2a1d74a4f172780a899af364f70121646fad65 and codex/10.0.0-content at a0f5007e10f69cbb10d99c8ac7580aaf23262783. Approved issues #900 through #907 are closed/completed and Done; configuration-source maintenance has no issue. Only guarded scratch/plan cleanup remains pending because the existing cleanup script stops at an older node_modules reparse point.

# Invalid delivery-report timezone hours (#903)

Issue: https://github.com/bbottema/simple-java-mail/issues/903. Governing decision: ADR 0035.
The maintainer approved design, implementation/delegation and complete workflow delivery on 29 October 2026.
The lead agent reviews the combined result before selective delivery on `codex/10.0.0` and, where applicable, `codex/10.0.0-content`.

Before: `Last-Attempt-Date: Fri, 16 Oct 2026 10:20:30 +9900` becomes a timestamp after import.
After: numeric timezone hours outside 00 through 23 leave that timestamp unavailable, with the existing invalid-date warning.
The same rule applies to `Will-Retry-Until`. Keep valid offsets, strict calendars, trailing comments, ISO recovery,
neighboring report facts and original report content.

The implementation path is ordinary EML/MIME conversion and exact-message inspection -> `MimeMessageParser` ->
`DeliveryStatusReportParser.parseTime` -> existing report result getters. Validate the numeric zone only after Jakarta Mail
identifies the consumed date; retain its grammar and the separate ISO fallback. Do not add another general date parser.

Worker owns `DeliveryStatusReportParser.java`, focused `DeliveryStatusReportTest` additions and a short clarification in the
existing DSN-date section of ADR 0035. Root owns release history, ADR index, OpenRewrite documents, cleanup allowlists and shared website pages.
Preserve all concurrent changes and the existing index; no repair of the separately discussed malformed-address finding belongs here.

Use the exact scratch `.local/feature-review-dsn-20261029` for a HEAD export plus owned overlays, Maven logs and all generated outputs.
Do not run Maven against shared targets. Exercise public fluent EML/MIME, compatibility EML and exact routes; cover positive and negative
out-of-range hours, valid zero and 23:59 boundaries, both date fields, preserved raw status content and exact bytes.
Use existing calendar/comment/ISO controls, then run focused current-source reactor checks on JDK 11 and a modern JDK.

Before delivery, account for public result documentation, properties/Spring/CLI, root/website guidance, Journal/case-study/downloadable
examples and OpenRewrite target fixtures. This changes an unreleased received-result feature and introduces no released-9.x source rewrite.
Keep the issue open/In Progress until reviewed delivery and completion bookkeeping; retire this plan and exact scratch through the guarded cleanup workflow.
