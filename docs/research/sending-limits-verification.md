# Factory-scoped sending limits: verification evidence

Tracking: [#751](https://github.com/bbottema/simple-java-mail/issues/751).
Decision: [ADR 0027](../adr/0027-factory-scoped-sending-limits.md).
Current execution, ownership, race coverage and evidence boundaries:
[sending-limit concurrency](../concurrency/09-sending-limits.md).

This report preserves verification evidence from the completed implementation. GitHub owns delivery status; the ADR and concurrency guide
describe the maintained contracts. These were local non-live runs, not a new remote CI run or proof of account-wide quota compliance.

## Upstream prerequisites

Ordinary sending needed destination selection before connection acquisition. The previously released SMTP Connection Pool 4.2.0 /
Clustered Object Pool 4.1.1 APIs combined those operations. The independently released selection APIs preserve load balancing upstream,
bind acquisition to the original registration and avoid borrowing a connection merely to discover its configuration.

- [Clustered Object Pool #29](https://github.com/bbottema/clustered-object-pool/issues/29), released in
  [4.2.0](https://github.com/bbottema/clustered-object-pool/releases/tag/4.2.0): 51 tests on Java 8 and Java 21, Java 8 Maven verification,
  Javadocs, unchanged-bytecode legacy consumption and extended selection consumers on classpaths and the modern module path.
- [SMTP Connection Pool #35](https://github.com/simple-java-mail/smtp-connection-pool/issues/35), released in
  [4.3.0](https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/4.3.0): Java 8 core/provider tests (91), modern-JDK full
  reactor verification (102), binary compatibility, module names and core API Javadocs. Java 8 `test` and modern `verify` were separate
  lanes because the inherited modern SpotBugs plugin cannot initialize on Java 8, even when skipped.

Both releases completed their release bookkeeping and signed-artifact checks on Maven Central. Simple Java Mail uses the published
SMTP Connection Pool 4.3.0, Clustered Object Pool 4.2.0 and Generic Object Pool 2.5.1. Initial local candidate dependencies were not the
dependencies used in final integration verification.

## Initial integrated verification — 2026-10-01

| Lane | Result |
| --- | --- |
| Java 21, full `clean verify -Ppublish-cli` | 1,826 tests, no failures or errors, one skipped opt-in CLI performance test |
| Java 11, library `clean verify`, excluding CLI and coverage aggregator | 1,651 tests, no failures or errors, one existing `MiscUtilTest` skip |
| Java 17 / Boot 3.0.13 / Spring 6.0.14 / SLF4J 2.0.9 | Clean targeted Spring compatibility run passed |
| Java 21 / Boot 3.5.16 / Spring 6.2.19 / SLF4J 2.0.18 | Clean targeted Spring compatibility run passed |

The broad lanes include baseline Boot 2.7.18 / Spring 5.3.39, classpath/JPMS consumers, generated CLI options and help, one-shot and daemon
regressions, and Javadocs. SpotBugs was disabled and four test forks were used. An existing reflective configuration fixture was corrected
to supply the expanded `OperationalConfigImpl` constructor fields before these clean runs passed.

## Holistic-review corrections — 2026-10-02

Two failures were reproduced before their fixes:

- A second Mailer using a caller-owned Session could overwrite the first Mailer's limiter through the MIME-conversion context.
  Allowance now belongs directly to the Mailer or selected pool registration. Header-only envelope costing uses `EmailSendingAllowance`,
  and a selection retains its original allowance. A replacement during selection fails before borrowing or charging.
- Failed Mailer construction could permanently pin provisional named-group rules. Earlier validation now precedes registration;
  initialization uses success/rollback bookkeeping outside the registry monitor. Failed concurrent builders cannot remove an established group.

The added tests cover overlapping initialization, early validation and later pool-registration failure, shared-Session individual/custom/
retained paths, live exhaustion after concurrent charging, retained-batch cancellation, open-connection deadlines and graceful shutdown
behind a waiting send. The concurrency guide identifies exact test methods and the limits of the evidence.

## Final verification — 2026-10-02

| Lane | Result |
| --- | --- |
| Java 21, full non-live `clean verify -Ppublish-cli` | 1,837 tests, no failures or errors, one skipped opt-in CLI performance test |
| Java 21, final focused `verify` after selection-ownership safeguards and retained-scope deadline coverage | 115 tests, no failures, errors or skips; library consumers passed |
| Java 11, final-source library `clean verify` | 1,665 tests, no failures or errors, one existing `MiscUtilTest` skip; baseline Spring/starter and public consumers passed |
| Java 17 / Boot 3.0.13 / Spring 6.0.14 / SLF4J 2.0.9 | 53 tests, no failures, errors or skips |
| Java 21 / Boot 3.5.16 / Spring 6.2.19 / SLF4J 2.0.18 | 53 tests, no failures, errors or skips |

Integration coverage includes pooled and module-free direct transports, timeout/cancellation while waiting, authenticated-proxy non-start,
selected cluster ownership, unchanged attachment-read counts, failed acquisition/conversion release, charged provider failure, reentrant
observers, lazy batches, retained scopes and exact recipient occurrences from BCC, custom headers, groups and envelope overrides.
Deterministic diagnostic-clock tests separate deliberate rate waiting from connection acquisition. These results do not prove every
possible provider/application interleaving or every server's retained-connection idle-timeout behavior.

Website `npm run check` and a clean isolated production build passed, including the subsequent version-wording cleanup. The final delivery
scan passed all 3,262 local links across 62 built pages. An earlier scoped feature scan passed 881 links; unrelated Journal work subsequently
resolved the two unpublished-article links found by the earlier whole-site scan.

The coding-guide pass covered the revised ownership and registration paths. Final root documentation checks resolved 199 relative paths
after retiring the implementation plan. `mvn license:remove -Ppublish-cli` completed after builds; all 47 changed Java files started with their
package declaration and diff whitespace checks passed.

No live-email demos, benchmarks or SpotBugs analysis were run for this feature.
