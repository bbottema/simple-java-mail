# Developer Environment Setup

Project guide: [Developing and maintaining Simple Java Mail](CONTRIBUTING.md).

This document records environment requirements, build procedures, generated metadata, and build constraints for Simple Java Mail.
It is intended for both human developers and coding agents starting new sessions.

For architectural decisions and a topic index, see the [architecture decisions](adr/README.md).
For feature propagation and verification, see [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md).
For send-state transitions, resource ownership, and races, see [docs/concurrency/README.md](concurrency/README.md).
For connection-failover architecture, integration contracts and verification references, see [docs/failover/README.md](failover/README.md).
For issue, Dependabot, and release handling workflows, see [MAINTAINER_WORKFLOW.md](MAINTAINER_WORKFLOW.md).

---

## Java Version

All published library modules require Java 11 or newer. The separately executable `cli-module` requires Java 17 or newer. A full-reactor build therefore runs on JDK 17+ and compiles non-CLI modules with `--release 11` while compiling only `cli-module` with `--release 17`.

The main module emits its multi-release module descriptor under `META-INF/versions/11`: its jsoup HTML parser dependency exposes a Java 11
descriptor. Custom module paths include `org.jsoup`, and the main module's narrow consumer fixtures use that descriptor directory. Other
modules retain their own descriptor configuration.

Use a real JDK 11 lane when validating library compatibility. That lane excludes `cli-module`; JDK 11 cannot load its Java 17 classes. CLI metadata generation runs on JDK 17 or the current release JDK. The Kryo cache has explicit serializers for reflective methods and Therapi's read-only collection shapes, and does not reflect into JDK internals.

Use a local, gitignored `.maintainer-env.ps1` file for machine-specific paths:

```powershell
# .maintainer-env.ps1, not committed
$env:JAVA_HOME = "<absolute path to a JDK 17 or newer>"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
$env:SJM_GH = "<optional absolute path to gh when it is not on PATH>"
```

Then load it in the shell used for builds:

```powershell
. .\.maintainer-env.ps1
java -version   # should report 17 or newer for a full build
```

---

## Angus development dependency for 10.0.0

The unreleased 10.0.0 branch uses `org.eclipse.angus:angus-mail:2.1.0-SNAPSHOT` by default until a final Angus release contains the required fixes. This includes the custom hostname-verifier rejection correction tracked in [Angus #235](https://github.com/eclipse-ee4j/angus-mail/issues/235) and merged in [PR #236](https://github.com/eclipse-ee4j/angus-mail/pull/236).

The project POM declares the [Central Portal snapshot repository](https://central.sonatype.com/repository/maven-snapshots/) with snapshot refresh enabled and checksum verification required. No personal Maven settings or opt-in profile is needed. `mvn -U ...` can explicitly refresh cached dependency metadata; offline builds use the snapshot already in their local repository.

Keep the dependency stack coordinated: Angus Mail `2.1.0-SNAPSHOT`, Jakarta Mail API `2.2.0-M1`, Jakarta Activation API `2.2.0-M1` and Angus Activation `2.1.0-M1`. Their versions are the `angus.mail.version`, `jakarta.mail.version`, `jakarta.activation.version` and `angus.activation.version` properties in the root POM. Replacing only the provider while retaining the older APIs can cause runtime linkage failures.

The root and CLI release-dependency checks allow the selected Angus Mail snapshot specifically; other snapshot dependencies remain subject to the normal rule. CLI distributions retain the base `-SNAPSHOT` filename so their existing dependency-content checks apply to the packaged snapshot. When a compatible final Angus release is available, replace the development dependency and remove the temporary exceptions. The compatibility follow-up remains tracked in [#758](https://github.com/bbottema/simple-java-mail/issues/758).

## Build

Standard full build (skipping tests and slow checks):

```powershell
mvn verify -DskipTests -Dmaven.javadoc.skip=true
```

For full non-live verification, including generated Javadoc JARs, run on JDK 17+:

```powershell
mvn clean verify -Ppublish-cli
```

Full non-live library compatibility validation, including Javadocs, on JDK 11:

```powershell
mvn -pl '!modules/cli-module,!modules/jacoco-aggregator-module' clean verify
```

The Java 11 lane also excludes the coverage aggregator because it depends on the Java 17 CLI artifact. The full modern-JDK build checks both.

When changing builder signatures or CLI help, also follow [Generated CLI Metadata](#generated-cli-metadata).

### Coverage

Configuration-route completeness is enforced separately from JaCoCo. See [Configuration contract coverage](CONFIGURATION_CONTRACT_COVERAGE.md)
for the Java 11 library gate, full-reactor gate, fixed inventories and current-run evidence. Narrow `-Dtest` verification must explicitly skip
that gate or use the `test` phase; a partial run cannot claim complete coverage.

On JDK 17+, `mvn verify` runs the tests and generates the combined JaCoCo report at
`modules/jacoco-aggregator-module/target/site/jacoco-aggregate/index.html`. Use `mvn clean verify`
for a fresh report. Keep tests and JaCoCo enabled: `-DskipTests` or `-Djacoco.skip=true` does not
produce fresh coverage. No separate coverage script or machine-specific JDK path is required.

### Windows file-mapping failures

On affected Windows checkouts, run local maintenance builds with `-Dlicense.skip=true`. A license formatter write failure
has previously truncated Java sources while reporting that a file had a user-mapped section open. Stop the owned build
before inspecting damage, retain a successful source JAR that matches the current working tree, and restore only confirmed
damaged files from that archive. Restoring from HEAD can discard uncommitted work. Release-source license verification
remains part of the clean release workflow.

The CLI metadata build step stages a complete cache pair and replaces resource outputs atomically where the filesystem supports it.
An open Windows file can still block replacement. Keep the valid persistent cache, close the owned reader and rerun the Maven build;
do not hand-edit metadata binaries or terminate unrelated Java processes.

When Javadoc preservation matters, inspect the affected generated HTML and compare it with the packaged Javadoc entries.
An incremental build can reuse stale HTML even after runtime Javadoc metadata or classes have changed; a successful build
alone does not prove that the published documentation contains the latest contract.

### SMTP integration and conformance

The Wiser SMTP and authenticated-SOCKS integration tests are local and run in ordinary verification. They use allocated ports and do not need an external mail account.
The old `excludeLiveServerTests` profile has been removed; manually runnable demo apps are still not JUnit tests.

The [SMTP conformance runner](../tools/smtp-conformance/README.md) adds pinned Postfix/Exim fixtures and independent protected-content checks:

```powershell
python tools/smtp-conformance/run.py --mode all
```

Use `--mode embedded` without Docker or `--mode real` for the explicit Failsafe server matrix. The runner records source/runtime provenance and sanitized evidence under `target/smtp-conformance`.

### Optional performance checks

Record repeatable local one-shot and warm-daemon process timings without adding a CI timing threshold:

```powershell
mvn -pl modules/cli-module "-Dtest=CliDaemonPerformanceTest" "-Dsjm.runDaemonBenchmark=true" test
```

The raw samples and median/p95 summary are written to `modules/cli-module/target/cli-daemon-benchmark.csv`.

For the separate mail-send/content audit, see [the manual performance harness](../tools/performance/README.md).
It uses a loopback SMTP sink, repeated JVM runs and separate JFR recordings. Its experimental
classpath overlays are only for known-safe synthetic inputs, never production mail or CI timing gates.

For sustained send-memory investigation, see [the memory pressure runner](../tools/memory/README.md).
It uses isolated sender/server JVMs, bounded workloads and retained-heap checkpoints across observer, retry, failover and Mailer-lifetime profiles.
These manual runs remain separate from the ordinary test suite and throughput audit.

### Generated license headers

After any build that ran `license:format`, clean up auto-generated headers before committing:

```powershell
mvn com.mycila:license-maven-plugin:3.0:remove
```

---

## Generated CLI Metadata

The CLI is generated from builder interfaces and their Javadocs. [ADR 0018](adr/0018-cli-from-builder-contracts.md) owns the build/runtime
separation; [the API workflow](API_EXPANSION_WORKFLOW.md#2-api-interface-expansion-core-module) covers exposing methods through the CLI.
`CliBuilderApi.roots()` remains the authoritative root inventory, including scalar adapters for grouped configuration.

Every normal CLI build reaching `process-classes` runs `CliMetadataBuild`, after compilation and before CLI tests. For example:

```powershell
mvn -pl modules/cli-module -am verify
```

Use JDK17 or newer, and add `-Dlicense.skip=true` on affected Windows checkouts as described above. The metadata step requires no separate
script, AI tool, editor configuration or personal setup. `mvn clean` removes normal outputs; it preserves the module's ignored
`.build-cache/metadata/<input-key>/` entries. The build copies a selected pair into `target/classes` for ordinary JAR packaging.

The local content key covers the resolved generation classpath's class files, compiler-produced API Javadoc and normalized Maven dependency
versions, plus the CLI/parent POMs. It includes generator and serializer code. It ignores ZIP timestamps, generated metadata and transient
manifests. An API or Javadoc edit therefore refreshes the data automatically; an unchanged clean build can reuse it. The key is conservative:
a changed classpath input may regenerate the pair even when the resulting help happens to be identical. Cache manifests record input identity
and both file digests. A missing, damaged or incomplete local entry is regenerated by the build, with a lock and a staged complete pair.

`performRelease=true` activates fresh generation regardless of the local entry. The existing CircleCI deployment command already selects that
mode. `-Ppublish-cli` assembles the standalone ZIP and TAR; it does not force every local packaging build to discard reusable data. Developers
can explicitly force a rebuild for diagnosis with `-Dsjm.cli.metadata.force=true`, a Maven build option that ordinary CLI execution never reads.

Generation uses an explicit build-thread Javadoc scope. It walks the selected builders, formats option/parameter text, constructs the command
tree once to include help references, and serializes `cli.data` and `therapi.data` using the existing codecs and integrity envelopes. Neither
binary is maintained in `src/main/resources` or committed to Git. The old demo-app generation entry point and runtime regeneration property
are retired.

Normal startup loads the packaged files lazily and validates them. It never discovers replacement commands, reads source Javadoc for missing
entries, writes metadata, or guesses development mode from files in the working directory. Missing, damaged, incompatible or incomplete
packaged metadata produces an installation/build error. Runtime option and Javadoc data are immutable; each request still gets fresh Picocli
state, and thin daemon control/routing paths retain their lazy behavior.

The `verify` phase checks the finished CLI JAR in fresh JVMs. With `-Ppublish-cli`, Maven also unpacks and checks the actual ZIP and TAR,
including their library/configuration classpaths. These package checks still run with ordinary unit tests skipped. They compare shipped
metadata/dependencies with the current build and run real CLI help from a separate working directory. Standalone configuration keeps logging
resources but excludes metadata copies that could shadow the JAR. Do not skip these checks before publication.

Use the CLI metadata/cache, packaged-startup, help, concurrency and daemon regressions when changing this path. A partial `-Dtest` selection
needs `-Dconfiguration.contract.skip=true` at `verify`, and does not establish complete configuration coverage. API/Javadoc changes no longer
need a separate manual regeneration or generated-binary commit.

## Exercising CLI Process Modes

Ordinary commands and help run locally. `-d`, bare `--daemon`, and `--daemon=acquire` find or start a selected per-user daemon; `--daemon=require` requires an existing one; `--daemon=off` and `--no-daemon` force one-shot execution. Lifecycle commands are `daemon run`, `start`, `status`, `stop`, and `restart`. See [ADR 0019](adr/0019-local-cli-daemon.md) for authentication, Mailer reuse, request isolation, and response-loss semantics.

Check changed commands through both one-shot and daemon routes. Each request owns its working directory and output streams; relative-file conversion must use that context rather than process-wide `user.dir`. A daemon request leases its retained Mailer through command execution and reporting; finishing a request does not close that Mailer.

For connection diagnostics, `probe` calls the synchronous view and prints its report to request-owned stdout. `--authenticate` is an invocation choice, not a generated builder setting or part of a retained Mailer profile. Exit codes are 0 for success, 3 for a failed/unsupported report, and 2 for invalid arguments. The dedicated probe connection closes before the report returns. The [probe integration tests](../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpConnectionProbeTest.java), [loopback demo](../modules/simple-java-mail/src/test/java/demo/SmtpConnectionProbeDemoApp.java), and [ADR 0020](adr/0020-dedicated-smtp-connection-diagnostics.md) describe that separate path.

## TLS interception during local tests

A local security product can replace certificates even for loopback SMTP fixtures. If dependency downloads or test JVMs need the trusted
Windows roots, configure `-Djavax.net.ssl.trustStoreType=Windows-ROOT` for those JVMs; do not disable certificate or hostname verification.
Positive TLS fixtures can combine their test certificate with the configured roots in memory without installing a certificate into Windows.

The Windows store cannot authorize an interception root that is not in that store. If a security product still replaces a fixture certificate,
use an unintercepted test environment or the [conformance runner's container JVM](../tools/smtp-conformance/README.md#run-it).
Keep the rejection assertions intact and report any excluded case explicitly; an environment-specific exclusion is not the normal verification recipe.
The October 2026 Norton investigation is historical evidence, not a permanent instruction to skip the positive implicit-TLS fixture.

## Known Build Constraints

- **Javadoc generation** uses classpath mode (`legacyMode`) because the Java sources live in
  `src/main/java`, while JPMS descriptors are compiled separately from `src/main/java9` into
  multi-release JARs. Modular Javadoc source discovery cannot find the API packages in that
  layout. This setting only affects documentation; the published JPMS descriptors stay in place.
  Javadoc errors fail the build. Use `-Dmaven.javadoc.skip=true` only for a deliberately partial check.
- **ossindex** (Sonatype vulnerability scan) has been removed from the build lifecycle.
  It is configured with `<phase/>` (empty phase) in the root `pom.xml` to unbind it.
- **cli-module** uses `log4j-slf4j2-impl` (not `log4j-slf4j-impl`) because `slf4j-api`
  is at version 2.x which requires the SLF4J 2 bridge adapter.
- **Null instrumentation** remains configured for main and test classes, but the root POM sets
  `se.eris.notnull.instrument=false`. The default build therefore does not establish runtime
  enforcement merely from JetBrains annotations. The retained exclusions include assertion helpers
  and `ServerReply`; generated/protocol classes need compatibility checks before any re-enablement.
  [ADR 0009](adr/0009-nullability-and-cli-optionality.md) records the history and the separate CLI optionality contract.
