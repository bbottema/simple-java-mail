# Delivery-status interpretation: implementation and verification

Date: 2026-10-17. Tracking: [#763](https://github.com/bbottema/simple-java-mail/issues/763).
Implementation on `codex/10.0.0`; production review accepted on 2026-10-18, with selective publication approved. This is not a release-readiness declaration.

## What changed

The ordinary converted Email exposes nullable `getDeliveryStatusReport()`. Core contains only the immutable reading contracts;
the main module assembles them with internal Lombok builders. The shared MIME pass interprets the effective outer notification's status part,
after established protection preprocessing. It does not promote attached notifications, retrieve mail, create outbound notifications or manage business state.

Jakarta Mail supplies MIME/header tokenization, unfolding, dates and transfer decoding. The international status part has a narrow decoding adapter:
Jakarta normally ignores message/* transfer encoding, while RFC 6533 permits it for global-delivery-status. No global Session/JVM switch is introduced.
Recognized malformed reports keep usable facts and aggregated warnings. Optional report extraction retains completed blocks after a read failure,
with an incomplete-extraction warning; required eager content reads still fail conversion with their original cause. Rendering excludes identities and prose.

Source metadata survives copying, governance and serialization, but templates cannot manufacture it and replies/forwards do not inherit it.
Email's serialization identifier is unchanged; released snapshots have absent report metadata. Exact and protected source representations remain authoritative.
See [ADR 0008](../adr/0008-minimal-mime-structures-and-protection-order.md) and the [implementation plan](../plans/763-delivery-status-reports.md).

## Evidence

- Initial Java 21 reactor compilation passed.
- Focused report/model/API-boundary tests passed, including standard/global reports, base64 international content, legal comments, typed foreign fields,
  duplicate recipients, malformed/conflicting fields, warning aggregation, missing parts, real I/O failures, ordinary/attached/replied/forwarded messages,
  copying, immutability, equality, old/new serialization, source-only governance and exact EML bytes.
- Existing conversion and protection coverage ran with the additions. The encrypted OpenPGP fixture verifies no report without a receiving key, an accessible
  report after decryption and retained original bytes. The S/MIME fixture verifies a received signed notification and exact original representation.
- The cached/lazy MIME-pass regression observes one original status-body read and zero unrelated lazy-attachment reads. Existing protection preprocessing
  can have its own reads; this is not a claim that every complete converter invocation reads no other content.
- Real JDK 11 full non-live library verification passed with Javadocs, core-only/classpath/JPMS consumers, provider, telemetry, Spring/starter and Karaf modules.
  The latest-source focused JDK 11 rerun passed too: seven core boundary tests and nineteen main-module report/serialization tests.
- Clean Java 21 non-live reactor verification passed in 18m49s: 2,542 Surefire cases, zero failures/errors and one intentionally disabled manual daemon
  performance test. It includes Javadocs, regenerated CLI metadata, CLI/daemon checks, API consumers and the coverage aggregate.
- The final latest-source focused Java 21 rerun passed too. Generated license headers were then removed with `mvn license:remove`.
- Website checks passed (89 tests), followed by a clean build and 5,009 internal links across 76 pages. The generated source ZIP matches canonical
workbench sources under the independent #764 scope. Desktop rendering and keyboard access were inspected for the hub, grouped navigation and a strategic chapter; a mobile viewport
  was not visually exercised. Responsive rules are compiled, but are not a claim of mobile visual testing.

The first full JDK 11 attempt hit an existing 10-second ScopedMailSenderTest timeout under load. Its focused rerun passed; the complete lane then passed
with four test forks. The first S/MIME fixture assertion compared repeated serialization of a newly built mutable provider message; it now compares actual
received wire bytes and passes, without changing the production signature-verification path. These were not silently excluded tests.

Reproduction on a real JDK 11:

```text
mvn -pl '!modules/cli-module,!modules/jacoco-aggregator-module' -Dtest.forkCount=4 -Dlicense.skip=true verify
mvn -pl modules/simple-java-mail -am -Dtest=DeliveryStatusReportTest,EmailSerializationTest,PublicApiBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false -Dlicense.skip=true test
```

Modern verification uses `mvn -Dtest.forkCount=4 -Dlicense.skip=true clean verify -Ppublish-cli`. Windows Maven uses the Windows root trust store;
certificate checks are not disabled. Raw local build evidence lives in ignored `.local/delivery-reports-verification/`, not in the committed project.
No live accounts, manual benchmarks or SpotBugs were used.

## Boundaries reviewed

### Stronger report types from production review

The review replaces parallel string/type getters with `ReportedRecipient`, `ReportedMailServer` and `DeliveryDiagnostic`, and replaces the status
string with `EnhancedStatusCode`. These are core read contracts with main-module, package-private Lombok assembly. They add no public construction,
configuration option, module dependency or send-path change. ENVID and human-readable warnings remain strings.

Status classes and numeric ranges are checked; unfamiliar subject/detail combinations remain available. Mailbox views use Jakarta Mail parsing and
produce detached SJM Recipients without inventing names, To/Cc/Bcc, certificates or notification preferences. SMTP diagnostic views reuse the existing
`SmtpServerResponse` only when the source contains a recognizable reply. Unusable views never discard the underlying reported address/explanation.
Derived views do not change source-value equality, and automatic rendering still omits identifying fields and diagnostic prose.

Focused Java 21 verification passed 35 tests. The expanded real-JDK-11 verification passed 108 selected tests, including converter/OpenPGP coverage,
Javadocs and core-only/classpath/JPMS consumers. Tests cover numeric bounds, future formats/codes, international and malformed addresses, actual names,
missing outgoing preferences, non-SMTP/unusable diagnostics, privacy, immutable fields, equality and Java serialization of the stronger types.
The transaction-outbox Docker application was rebuilt against these sources: all nine shared application tests and its four end-to-end scenarios
passed, including persistence of the typed status through an explicit text projection. These are focused additions, not another full 2,542-case run.

The website example and its regression now demonstrate typed status, mailbox and SMTP access, with honest absence behavior. Raw evidence is ignored
`.local/delivery-reports-verification/strong-types-*`. The wider earlier verification remains recorded above; the feature was subsequently accepted in review.

Final-source verification passed 36 focused tests on each of JDK 11 and JDK 21, with regenerated Javadocs and provider-neutral consumers. The added
intermediate-SMTP-reply check preserves a reported 3xx response without calling it acceptance. The Java 21 CLI verification also passed its ten daemon
process and two metadata-cache tests, regenerated both CLI caches, and verified telemetry consumers. Generated license headers were removed afterward.
Website verification passed 95 checks, a clean build and 5,373 internal links across 80 pages. The canonical Strategic Patterns download was refreshed
and checked after updating its typed-status projection. The disposable Docker project has no remaining containers, volumes or networks.

Parser state, warnings, dates and assembly are invocation-local. Reports retain immutable scalar/list snapshots, not MIME parts, streams, Sessions,
transports or application callbacks. The temporary status-part reference is cleared when the shared MIME pass completes. No send/progress/retry/failover
state machine or concurrency resource is added; the existing infographic remains accurate.

The report is an untrusted received claim, not authenticated proof, read confirmation, retry advice or a retroactive mutation of the originally sent Email.
Applications still own ingestion, correlation, JSON/YAML projections, idempotency and business actions. The separate
[application workbench verification under #764](https://github.com/bbottema/simple-java-mail/issues/764) does not establish actual downstream delivery or competitor superiority.

### Tolerant-reading refinement from production review

[ADR 0035](../adr/0035-tolerant-reading-and-strict-production.md) records the maintainer's cross-cutting input philosophy. The scoped refinement recovers
zero-padded status components, explicit ISO timestamps and a uniquely identifiable direct status part outside its expected position. Conflicting parts,
impossible dates, missing timezones and unrelated attached/nested reports are not promoted to facts. Original exact/protected representations remain unchanged.

Report-only read failures preserve completed header blocks, but an interrupted block is not presented as complete. Warnings retain no exception text or
source objects. Required eager content failures retain their original cause. Thread interruption and unexpected runtime failures still propagate.
No new public API, recovery switch, Session/JVM property or broad importer rewrite was introduced.

Expanded verification passed 129 selected tests on each of real JDK 11 and JDK 21, including the existing converter, exact-message and OpenPGP suites.
After adding the final interruption/close-failure checks, final-source verification passed 44 selected tests on each JDK, with Javadocs and the
classpath/JPMS provider consumers. The signed-notification fixture now also contains a recovered zero-padded status while retaining valid verification
and byte-identical original protected content. The exact-EML fixture separately proves unchanged bytes after normalized inspection.

The first focused run caught a fixture accounting error: saving a newly constructed test message had already opened/closed its DataSource before
the measured parse. Resetting the counter after construction isolated the parsing attempt, which closes its stream exactly once. No production
cleanup assertion was weakened. Final raw evidence is ignored `.local/delivery-reports-verification/leniency-*`.

Website verification passed 96 checks, a clean build and 5,373 internal links across 80 pages. The architecture/workflow pass checked 217 relative
links with none missing, and the touched production Java is within the 160-character line limit. The full non-live reactor, live-email tests,
benchmarks and Docker application portfolio were not repeated for this scoped parser refinement; earlier evidence retains its own tested source.
No migration entry was added for corrections to an unreleased feature. The maintainer accepted the feature and approved selective delivery on 2026-10-18;
the Strategic Patterns implementation remains outside this delivery.
