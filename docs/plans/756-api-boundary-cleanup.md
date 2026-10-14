# API boundary and internal construction cleanup

Implementation companion to ADR 0031 and #756. Production review and the final documentation corrections were accepted on 2026-10-14;
selective commits, publication and issue closure were authorized together with the retry feature.

## Accepted rules

- `core-module` owns application contracts, deliberately constructible values and provider SPIs. `simple-java-mail` owns main implementation machinery; optional modules own their feature implementations.
- Library-produced reports expose read contracts in core. Their final implementations and Lombok assembly builders live in their producer's `.internal` package, without public internal-constructor bridges on the read API.
- Public application configuration builders are explicit. A setter owns the complete contract; getters, reset and clear methods refer to it. Implementation methods refer to their interface contract. Internal and provider-SPI builders use Lombok; SPI authors do not need application-style companion methods or setter-owned documentation.
- Preserve existing reading, immutable-copy, rendering, identity and execution behavior. Configuration defaults remain on the key builders.
- Keep `MailerImpl`'s two production builder constructors and package-private test constructors. Constructor count is not a refactoring rule.
- Cleanly remove affected construction APIs rather than adding deprecated forwarding aliases. Preserve Email/recipient/attachment serialization; document the agreed break for released report types that become interfaces.

## Implementation order

1. Diagnostics: split MailSendDiagnostics, MailSendMeasurement, MailSendOutcome, MailSendConnectionStep and MailSendSubmissionStep into core contracts and main internal implementations. Remove the temporary core diagnostics builder/constructor bridge.
2. Entry points: expose recipient builders through SimpleJavaMail, move their concrete implementations and ExactEmailBuilderImpl into internal packages, and introduce the core ConfiguredEmailConverter contract.
3. Expanded audit: receipts, rehearsal, queue snapshots, Outlook and protection result assembly; Lombok SPI builders for PreparedMail/DeliveryEnvelope; template selector and configuration-lock boundary leaks; inspect remaining constructor families.
4. Boundary/behavior tests, migration and current documentation, full verification and a production-only review handoff.

## Audit dispositions

| Family | Disposition |
| --- | --- |
| Send diagnostics, measurements, outcomes and histories | Core read contracts; main internal implementations/builders. |
| Submission receipts, rehearsal and queue snapshots | Same producer-owned construction rule. |
| Outlook metadata/conversion and protection preprocessing results | Core contracts; producer-module assembly, no new mandatory optional dependency. |
| PreparedMail and DeliveryEnvelope | Deliberately constructible provider values; Lombok SPI builders replace optional-argument constructor chains. |
| Other provider facts | Preserve necessary construction for adapter authors; check exposed generated builders. |
| Email, Recipient, Recipients and AttachmentResource | Keep concrete input models and intentional construction. Record constructor findings; do not quietly introduce another public builder family. |
| MailerImpl, clock injection and distinct input forms | Retain intentional test/DI/conversion entry points. |
| Other internal optional-value constructor chains | Check callers, remove unused overloads, use internal Lombok assembly only where it simplifies production construction. |

### Completed constructor and boundary inventory

- `SmtpConnectionReport` is deliberately constructible by provider adapters. Its Lombok builder retains the existing setter names; the application-facing result getters stay explicitly documented. `PreparedMail` and `DeliveryEnvelope` also use Lombok builders with validation and copying at construction. `ConnectionPoolClusterConfig` serves application configuration, so its builder remains explicit with documented getter/reset/clear companions.
- `SimpleJavaMailConfig` is the public read contract. The loader owns its package-private snapshot implementation; lock access and source copying belong to `ConfigurationSnapshot` in the shared internal configuration package.
- `EmailProperty` is a plain public field selector. Getter strategies are internal. Email serialization keeps the historical enum names, including the distinction between absent and empty suppression sets.
- `ConfigurationLocks` had an unused convenience constructor; it is removed. The proxy closure likewise no longer offers an unused diagnostics-injection overload or invokes an always-disabled recorder.
- `MailerImpl`, `FactoryMailerRuntime`, `ConnectionRecovery`, the send-operation/observer/rate-limit collaborators, and the batch engine retain intentional production/test or clock-injection constructors. They are not optional-field assembly APIs.
- `MailConnectionRoute` retains separate fresh-route and retained-scope construction. Email/Mailer builder implementations retain snapshot/runtime/definition-identity initialization. These constructors express ownership, not combinations of optional report fields.
- `Email`/`InternalEmail` construction distinguishes composed and exact sources. `Recipient`, `AttachmentResource`, `SmtpCapabilities`, socket classes and CLI environment/formatter/registry constructors represent established inputs, normalization or dependency injection; no new public builder family is introduced for them.
- Exception message/cause overloads remain. `MailTransportResult` keeps one canonical construction boundary and the named provider fact factories; it is not a telescoping-constructor family.
- The released `MailSender` default receipt method manufactured an observation after a void send. Its declaration is now abstract; library implementations already supply genuine results. Migration guidance covers external wrappers/test doubles rather than preserving fabricated receipts or exposing internal assembly.
- The static `EmailConverter` retains its implementation and existing application signatures; it is not an empty compatibility facade. `ConfiguredEmailConverterImpl` owns snapshot-dependent conversion, and a package-private `MimeMessageEmailMapper` shares parsed-field mapping with exact-EML inspection. The mirrored `EmailConversionSupport` API was removed during review. Optional Outlook, S/MIME and OpenPGP implementations stay with their producing modules.

The audit distinguishes intentional SPI construction from library-produced reports. Public signature checks cover the complete core `api` package, not only the diagnostics types.

### Follow-up: avoid mirrored implementation facades

- Rechecked the pending boundary refactors after the converter review. The converter was the duplicated static facade; no other such mirror was found in this cleanup.
- Result implementations store and validate their own observations behind core read contracts; they do not forward a duplicated result API to another implementation. Optional-module results remain owned by their producers.
- The same-module configuration split has a different purpose: the loader implementation is package-private, and lock/source-map access is absent from the application contract. Recipient builders were relocated, not wrapped.
- The field-selector helpers separate internal getter strategies and historical serialization names from the public selector. These are behavioral and compatibility responsibilities, not forwarding facades.

## Verification requirements

- Core-only classpath and JPMS compile consumers, including generics/callbacks, with separate runtime/bootstrap and provider-SPI fixtures.
- Negative compilation and surface checks for removed creators, internal-signature leaks, generated members and documented public builders.
- Defensive copies, constructor/build invariants, new report serialization, historical Email/recipient/attachment fixtures, exact receipt/exception identity and unobserved fast-path behavior.
- Recipient governance, conversion, absent optional modules, retry/failover regressions and unchanged content-read counts.
- Focused tests, real Java 11 library verification, modern-JDK non-live reactor/Javadocs, affected Spring/CLI checks and regenerated CLI caches.
- Website checks, clean build and internal links; coding-guide pass and license-header removal. No live demos, benchmarks or SpotBugs.

## Documentation and delivery

ADR 0031 records the architectural decision, not this inventory or progress. Update API/maintainer guidance and current examples. Migration notes describe only changes to released API/behavior, including report serialization; compare against 9.3.5, not intermediate unreleased commits.

Preserve unrelated staged research, artwork and concurrent website/Journal changes. The original uncommitted-review boundary was superseded
by delivery approval on 2026-10-14. Publish this cleanup with #756, keeping architecture and user-facing documentation in separate semantic commits.

## Verification evidence

Recorded on 2026-10-13; local logs are in `%TEMP%/sjm-api-boundary-verification`.

- Java 11: all upstream and main-module unit tests passed, including 1,754 main-module tests (one existing skip). The subsequent packaged-consumer step exposed fixture issues, corrected before a successful follow-up `verify` across all non-CLI modules with the focused boundary tests. Core-only classpath/JPMS compilation and provider-neutral/third-party runtime checks passed. Spring runtime coverage is recorded separately below.
- The packaged fixtures now inspect submission history rather than construct it, and obtain recipient builders through the factory. An explicit empty Ant source path keeps the classpath check from implicitly compiling the JPMS descriptor.
- Generated core Javadocs expose read interfaces without construction sections or internal implementation pages. Explicit application builder companion links resolve to their setters.
- Website checks passed all 84 tests; the clean build and verification of 4,092 internal links across 67 pages passed. The downloadable field-guide Java source also compiled with Java 11.
- Two existing fake-transport fixtures were isolated from unrelated environmental timing: fixed Message-IDs avoid hostname DNS during MIME generation, and the recovery-allocation count disables idle expiration. All 74 affected failover/Session-ownership tests passed in the repeated full Java 11 run.

- Java 21: `clean verify -Ppublish-cli` passed the complete non-live reactor with Javadocs, packaged classpath/JPMS consumers and CLI generation/assembly. Surefire reports contain 2,370 tests across 230 suites, zero failures/errors and one existing skip. Both CLI metadata caches were regenerated.

- All three Spring compatibility combinations passed, including the core/factory boundary tests: Boot 2.7.18 / Spring 5.3.39 / Java 11; Boot 3.0.13 / Spring 6.0.14 / Java 17; Boot 3.5.16 / Spring 6.2.19 / Java 21. The normal matrix test selection covers every Spring/starter test class.
- Changed root-documentation relative links resolve. No new migration entry describes an unreleased intermediate construction API; the compatibility section is limited to released classes, imports, behavior and report snapshots.
- `mvn license:remove` passed across the reactor. No generated project license headers remain in Java sources; root and website working diffs pass `git diff --check`. The coding-guide pass preserves the deliberate Mailer/test constructor seams and keeps named internal assembly separate from the explicit application builder contracts.

At the end of the 2026-10-13 verification pass, implementation was ready for review; changes were still uncommitted and GitHub state was unchanged.

### Provider-builder simplification, 2026-10-14

- Applied the clarified audience rule from ADR 0031: `SmtpConnectionReport`, `PreparedMail` and `DeliveryEnvelope` use Lombok builders. Application-facing report getters retain direct documentation; provider construction no longer carries application-style getter/reset/clear companions.
- Values validate and defensively copy supplied data when built. Generated setters retain replacement semantics, empty collection defaults and the distinction between an unset choice and explicit `false`.
- Java 11 focused `verify` passed 207 tests across the core, Angus and main modules, plus packaged core-only/provider-neutral and third-party classpath/JPMS consumers. Coverage includes probe output, serialization, capability budgets, immutable copies and adapter dispatch.
- Fresh core Javadocs generated successfully and the report getter descriptions were checked in the HTML. Logs: `spi-builders-java11-verified.log` and `spi-builders-javadoc.log` in the verification directory above.
