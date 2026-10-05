# Per-email recipient rejection handling: verification evidence

Implementation: [#754](https://github.com/bbottema/simple-java-mail/issues/754), for unreleased 10.0.0.
Decision: [ADR 0028](../adr/0028-per-email-recipient-rejection-handling.md).
Accepted implementation plan: retained in [#754](https://github.com/bbottema/simple-java-mail/issues/754).

This record retains local verification evidence, not a second implementation plan. GitHub owns delivery status.
The code and documentation were accepted after production-only and holistic review on 2026-10-05. The feature remains unreleased;
GitHub tracks its delivery. The completed local plan was retired, with decisions retained in the ADR and evidence recorded here.

## Verification — 2026-10-05

- Real Java 11 library `clean verify` passed: 1,785 tests across 154 suites, zero failures or errors, one existing skip in `MiscUtilTest`.
  The final SPI opt-in refinement and six additional regressions were then checked by a Java 11 focused rerun: 176 tests across nine suites,
  with zero failures, errors or skips.
- Complete Java 21 non-live `clean verify -Ppublish-cli` passed: 1,977 tests across 188 suites, zero failures or errors and the same existing skip.
  This included Javadocs, classpath/JPMS consumers, CLI generation, CLI/daemon process checks and packaging.
- Both tracked CLI caches, `cli.data` and `therapi.data`, were regenerated through the publish-cli build. The six new CLI tests passed,
  including composed/exact true/false options, concrete help, absent clear options and concurrent/later daemon-request isolation.
- Spring compatibility passed for Java 11 / Boot 2.7.18 / Spring 5.3.39 in the library and focused lanes; Java 17 / Boot 3.0.13 /
  Spring 6.0.14 / SLF4J 2.0.9; and Java 21 / Boot 3.5.16 / Spring 6.2.19 / SLF4J 2.0.18.
  Each additional Boot 3 lane ran 62 tests across 13 suites with no failures, errors or skips, including the new property and starter cases.
- Website checks and a clean isolated Eleventy/TypeScript/Pagefind build passed. Internal-link verification checked 3,400 links across 63 pages.
  The shared preview output and concurrent Journal files were not replaced.
- The API expansion workflow and coding guide were applied to the touched Java code, including interface-owned contracts, implementation `@see`
  references, nullable state, supplied-data models, helpful errors and a 160-column check on added Java lines. Whitespace and relative-link checks passed.
- `mvn license:remove` passed. SHA-256 hashes of the 33 touched Java files were unchanged; each starts with its package declaration,
  with no generated license header or empty file.

The Java 17 lane initially did not start because its `JAVA_HOME` pointed one directory above the installed JDK. The corrected installed
`jdk-17.0.20+8` path passed; this was a launcher-path error, not a runtime or source failure.

## Behavior exercised

- Unset, true and false; replacement/clearing; inspection/equality; copying; new serialization and real older Email/envelope snapshots.
  Property configuration does not prepopulate the local builder field.
- Defaults, overrides, property-specific suppression, replacement templates and factory isolation. Equal locked choices work; conflicting ones fail.
  Clearing or suppression cannot remove a lock. Exact Emails bypass ordinary templates and retain compatible envelope locks.
- All accepted, mixed temporary/permanent rejections and all rejected. Loopback wire assertions distinguish no DATA in stopping mode from accepted
  content in continuation mode. Partial sending retains failed completion, the exact exception/receipt, retry targets and unsuccessful observer outcome.
- Final-reply loss retains uncertain acceptance and duplicate risk. The new choice does not normalize uncertainty or trigger another attempt.
- Alternating true/false/unset on one pooled connection, simultaneous pooled sends and success after failure. Choices, replies and recipient facts do not leak.
  Local Session conflicts are rejected before MAIL FROM without discarding a healthy lease.
- Both execution modes, lazy batches and first-failure stopping, open connections, logging-only mode and CustomMailer responsibilities.
- SMTP/SMTPS advanced-property conflicts, caller-owned Sessions and round-robin clusters using the actual selected Session.
  Older adapters claiming generic envelope support do not implicitly support the new choice; an explicitly supporting adapter can opt in independently.
  Generic fallback remains available with no choice and rejects explicit unsupported behavior before provider invocation.
- Exact bytes, signed S/MIME and OpenPGP fixtures, envelope overrides and duplicate recipient occurrences.
  Counting-message and real attachment-source tests show no extra serialization/content reads solely for this setting.
- Typed ordinary/locked property provenance, exhaustive Spring metadata, injected factory/configuration ownership, CLI options and provider-neutral consumers.

No live account, standalone benchmark or SpotBugs run was used. Existing performance-harness unit tests remain part of the normal reactor.
The feature adds no monitor, wait, executor or state machine; the shared concurrency infographic's resource layers remain unchanged.

## Holistic-review follow-ups — 2026-10-05

- Strengthened the concurrent pooled test: every true, false and unset attempt now includes an accepted recipient plus temporary and permanent
  rejections. A reusable barrier holds the first RCPT reply until all three attempts overlap, instead of requiring every attempt to reach DATA.
  Across three rejection waves, only true sends content; all nine attempts complete exceptionally with their own receipt, exception, recipient replies
  and retry targets. The first wave reuses three warmed connections; later waves retain the existing failed-lease replacement behavior.
  The shared Session's advanced partial-sending property remains unset. Existing DATA-reply barrier tests keep their original synchronization.
- Replaced the website's ambiguous "local choice" wording with the setting on Email and the value on its builder. The rejected manual demo remains removed.
- The targeted concurrent regression and the fresh Java 11 focused feature/integration lane passed: 176 tests across nine suites, no failures,
  errors or skips. Website checks passed: 58 tests, TypeScript checking and an Eleventy dry run, without replacing the shared preview output.
- Whitespace and 160-column checks passed. `mvn license:remove` passed; all 32 remaining touched/new Java files start with their package declaration.

## Reproduction

Set `JAVA_HOME` to the lane's actual installed JDK. Quote Maven properties in PowerShell.

```powershell
# Java 11 focused feature and integration lane
mvn.cmd -pl modules/spring-boot-starter -am test `
  '-Dtest=RecipientRejectionHandlingTest,SmtpRecipientRepliesTest,AngusMailTransportAdapterTest,MailTransportAdapterResolverTest,EmailSerializationTest,SimpleJavaMailStarterAutoConfigurationTest,SpringRecipientRejectionHandlingTest,SpringModulePackagingTest,SpringEnvironmentConfigSourceTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.forkCount=4' '-Dlicense.skip=true' '-Dspotbugs.skip=true'

# Real Java 11 library lane
mvn.cmd -pl '!modules/cli-module,!modules/jacoco-aggregator-module' clean verify `
  '-Dtest.forkCount=2' '-Dlicense.skip=true' '-Dspotbugs.skip=true'

# Java 21 complete non-live lane, including generated CLI metadata
mvn.cmd clean verify -Ppublish-cli `
  '-Dtest.forkCount=4' '-Dlicense.skip=true' '-Dspotbugs.skip=true'

# Java 17 / Boot 3.0 lane
mvn.cmd -pl modules/spring-boot-starter -am clean test `
  '-Dtest=SimpleJavaMail*Test,SpringEnvironmentConfigSourceTest,SpringModulePackagingTest,SpringSendingLimitsTest,SpringRecipientRejectionHandlingTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dspring.boot.version=3.0.13' '-Dspring.version=6.0.14' `
  '-Dspring.boot.slf4j.version=2.0.9' '-Dtest.forkCount=4' '-Dlicense.skip=true' '-Dspotbugs.skip=true' '-Djacoco.skip=true'

# Java 21 / Boot 3.5 lane
mvn.cmd -pl modules/spring-boot-starter -am clean test `
  '-Dtest=SimpleJavaMail*Test,SpringEnvironmentConfigSourceTest,SpringModulePackagingTest,SpringSendingLimitsTest,SpringRecipientRejectionHandlingTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dspring.boot.version=3.5.16' '-Dspring.version=6.2.19' `
  '-Dspring.boot.slf4j.version=2.0.18' '-Dtest.forkCount=4' '-Dlicense.skip=true' '-Dspotbugs.skip=true' '-Djacoco.skip=true'

mvn.cmd license:remove
```

On this workstation, Maven dependency downloads use the Windows certificate store rather than disabled TLS verification.
