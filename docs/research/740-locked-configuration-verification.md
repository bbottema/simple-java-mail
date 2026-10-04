# Factory-scoped locked configuration: verification evidence

Implementation: [#740](https://github.com/bbottema/simple-java-mail/issues/740), for unreleased 10.0.0.
Decision and maintained boundaries: [ADR 0025](../adr/0025-factory-scoped-locked-configuration.md).

This record retains verification evidence, not a second implementation plan. GitHub owns delivery status.

## Initial implementation verification — 2026-10-03

- Focused tests covered typed lock resolution, copies and redaction; equal and conflicting customization; template suppression/replacement;
  envelope receiver overrides; exact-byte preservation; mandatory DSN; selected-cluster ownership; pooled concurrency; and unsupported provider paths.
- Java 11 library verification passed, excluding the CLI and coverage aggregator. An earlier run timed out in the existing asynchronous SMTPUTF8
  batch test; the complete rerun passed without changing that test or increasing its timeout.
- Clean Java 21 non-live reactor verification passed with `-Ppublish-cli`, including classpath/JPMS consumers, CLI/process tests, generated metadata
  and Javadocs. Targeted Java 11 lock, factory, adapter/resolver, Spring and starter tests also passed.
- Spring compatibility checks passed for Java 11 / Boot 2.7.18 / Spring 5.3.39; Java 17 / Boot 3.0.13 / Spring 6.0.14 / SLF4J 2.0.9; and
  Java 21 / Boot 3.5.16 / Spring 6.2.19 / SLF4J 2.0.18. The Boot 3 lanes included lock suites and exhaustive metadata checks.
- Website checks and a clean build passed; internal-link verification checked 3,339 links across 62 pages.
- License removal, the coding-guide audit, whitespace checks and relative documentation links passed.

## Final review regressions — 2026-10-04

Two gaps were reproduced before applying their corrections:

1. A caller-owned Session could display the locked username while authenticating with another account. Username locks now reject opaque
   authentication ownership, just as password locks do. Tests cover an Authenticator, no Authenticator, unchanged Session properties, and the
   factory-owned positive path.
2. An exact EML's parsed first header could match a lock while a duplicate original field conflicted. Tests cover Subject, From and Reply-To
   address/name locks, identical duplicates, validation, both rehearsal variants, and sending. Positive cases retain encoded headers, multiple
   addresses within one Reply-To field, and the exact original bytes. Unlocked exact messages keep their existing behavior.

The two regression suites ran 40 tests: eight failures before the corrections, then zero failures or errors afterward on Java 11.
All four lock suites also passed on Java 21: 51 tests, with no failures or errors.

The corrected implementation passed the complete Java 11 library `clean verify` lane, including classpath/JPMS consumers and Spring/starter tests.
Website checks passed 58 tests plus TypeScript and the template dry run. A clean build in a separate temporary directory included scripts and search;
internal-link verification checked 3,387 local links across 63 pages without replacing the shared development preview.

The complete Java 21 non-live reactor `clean verify -Ppublish-cli` also passed, including CLI metadata, daemon/process tests and Javadocs.
`mvn license:remove` passed afterward; hashes of the 45 affected Java files were unchanged, and the source scan found no empty files or generated
license headers. The final coding-guide, whitespace and relative-documentation-link checks passed. The concurrency catalogue records that the shared
infographic remains accurate: configuration locks add no monitor, wait, executor or mutable coordinator.

### Reproduction commands

Use the appropriate JDK's `JAVA_HOME`; quote Maven properties in PowerShell. The normal build controls test concurrency through `test.forkCount`.

```powershell
mvn.cmd -pl modules/simple-java-mail -am test `
  '-Dtest=LockedMailerConfigurationTest,LockedEmailConfigurationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.forkCount=2' '-Dlicense.skip=true'

# Java 11 library lane
mvn.cmd -pl '!modules/cli-module,!modules/jacoco-aggregator-module' clean verify `
  '-Dtest.forkCount=2' '-Dlicense.skip=true' '-Dspotbugs.skip=true'

# Java 21 complete non-live lane
mvn.cmd clean verify -Ppublish-cli `
  '-Dtest.forkCount=2' '-Dlicense.skip=true' '-Dspotbugs.skip=true'

mvn.cmd license:remove
```

On this Windows workstation, dependency downloads use `-Djavax.net.ssl.trustStoreType=Windows-ROOT` rather than bypassing TLS verification.
No live-email tests, standalone benchmarks or SpotBugs runs belong to this verification. Existing performance-harness unit tests remain part of
the normal reactor.
