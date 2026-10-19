# #763: Delivery-status facts through Email

Accepted plan, 2026-10-16. Implementation verified and production review accepted, 2026-10-18.
Tracking: [#763](https://github.com/bbottema/simple-java-mail/issues/763). Companion: [#764](https://github.com/bbottema/simple-java-mail/issues/764).
Regression coverage: [DeliveryStatusReportTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/converter/DeliveryStatusReportTest.java); reading boundaries: [ADR 0035](../adr/0035-tolerant-reading-and-strict-production.md).

This completed plan is retained temporarily because the independently reviewed #764 plan links to it. Retire the shared handoff reference when that
companion is delivered; #763 itself has no remaining implementation work.

## Contract

Add nullable `Email.getDeliveryStatusReport()` and builder read access. The immutable, serializable core read contracts are
`DeliveryStatusReport`, `RecipientDeliveryReport` and `DeliveryAction`; internal main-module Lombok builders assemble them.
Expose ENVID, typed reporting server, ordered recipient facts, actions, enhanced status, typed diagnostics and optional timestamps.
Collections are immutable/non-null; optional scalar facts are nullable. Render counts, not identifying facts, automatically.

Production-review refinement: expose `EnhancedStatusCode`, `ReportedRecipient`, `ReportedMailServer` and `DeliveryDiagnostic` as core read interfaces,
with producer-owned internal Lombok assembly. Status codes provide validated numeric components and success/temporary/permanent classification without
closing the extensible subject/detail registry. Address/server/diagnostic formats stay attached to their values. Recognized single Internet mailboxes
offer an optional SJM `Recipient`; recognizable SMTP diagnostics offer the existing `SmtpServerResponse`. Preserve raw reported values when a view is
unavailable, never infer names or delivery facts, and omit identifying text from automatic rendering. Keep ENVID opaque and warnings human-readable.

Use the existing MIME/EML conversion and protection pipeline, including existing receiving-key inputs. Interpret the effective outer notification,
not attached/forwarded reports. Support RFC 3464 and RFC 6533 with Jakarta Mail MIME/header helpers, without an experimental DSN dependency.
Recognized malformed reports retain usable facts and aggregated warnings; conflicting fields do not become actionable facts.
Apply [ADR 0035](../adr/0035-tolerant-reading-and-strict-production.md): recover zero-padded numeric status components, explicit ISO timestamps and a
uniquely identifiable direct status part in the wrong position. Multiple candidate parts remain ambiguous; attached/nested reports remain isolated.
If optional report reading fails, retain completed header blocks with an incomplete-extraction warning. Do not interpret an interrupted block as complete.
Required eager content reads retain existing exceptions and original causes. Inaccessible ciphertext has no interpreted report; existing protection metadata remains authoritative.

This is source metadata, not outgoing configuration: no setter, EmailProperty, defaults/overrides, lock, property, Spring/CLI option or report-only
converter API. No dispatcher, store, workflow, handler registry, JSON dependency, automatic correlation or retry. Received reports are untrusted claims.
Copies retain source provenance; replies/forwards do not inherit it. Preserve equality, old/new serialization, exact/protected bytes and attachments.
No extra unrelated attachment reads, retained MIME/provider objects or send-lifecycle changes.

## Implementation and verification

1. Add core read contracts and propagate metadata through Email, composed/exact builders and copying.
2. Extend the shared MIME pass and parse delivery-status blocks with InternetHeaders, existing unfolding and date support.
3. Add standard/international/malformed/nested/protection/exact-byte/copy/serialization fixtures and API-boundary consumers.
4. Amend ADR 0008 with the accepted architecture (existing implemented decisions remain implemented), update ADR index, feature documentation,
   README and release history. No migration entry for additive behavior.
5. Run focused tests, actual JDK 11 and modern non-live reactor verification/Javadocs/classpath/JPMS; remove generated license headers.

Apply the coding guide/API expansion workflow. The resource layers/concurrency infographic do not change. Preserve concurrent source, research,
website and Journal work. The original review-only delivery boundary has been satisfied; the maintainer approved selective commits, publication and
closure of #763 on 2026-10-18. The independent #764 implementation remains outside that approval.
