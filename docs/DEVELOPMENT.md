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

### Merged Angus development lane

Normal builds use released Angus Mail 2.0.5, Jakarta Mail API 2.1.5, Jakarta Activation API 2.1.4 and Angus Activation 2.0.3.
The explicit `angus-merged-development` profile uses a private source build of Angus merge
[`74a8e1be20011123d5930ef1d112ad6d10490614`](https://github.com/eclipse-ee4j/angus-mail/commit/74a8e1be20011123d5930ef1d112ad6d10490614),
which adds `mail.<protocol>.socketFactory.transport` and `.transport.class`. This is an experimental integration lane until a compatible
final Angus release is selected. It does not add a public snapshot repository or change the ordinary dependency defaults.

[Use-MergedAngus.ps1](../scripts/Use-MergedAngus.ps1) fetches the exact commit into a private Git object database, exports unchanged sources,
and installs the fresh relevant reactor into a private Maven cache. `-AngusGitRepository` can reuse an existing local object database;
its checkout is never switched or edited. `-SourceDirectory`, `-WorkDirectory`, `-RepositoryPath`, `-JavaHome` and `-MavenCommand`
are configurable. The default work directory is `.local/socks-direct-922-20261103/merged-angus` with its own `m2` subdirectory.
Use a short task-owned work directory on Windows to avoid source and Javadoc path limits:

```powershell
$angusLane = @{
    WorkDirectory = 'C:\temp\sjm-angus-922'
    RepositoryPath = 'C:\temp\sjm-angus-922\m2'
    JavaHome = $env:JAVA_HOME
}
.\scripts\Use-MergedAngus.ps1 @angusLane
```

The unchanged upstream coordinates remain `org.eclipse.angus:angus-mail:2.1.0-SNAPSHOT`. Upstream's POM defaults are Mail API 2.2.0-M1,
Activation API 2.2.0-M1 and Angus Activation 2.1.0-M1. The private build explicitly overrides `mail-api.version=2.1.5`,
`activation-api.version=2.1.4`, `angus.activation.version=2.0.3` and `mail.spec.version=2.1`, retaining SJM's released API/Activation stack.
These are local build overrides, not upstream release compatibility promises. The merged source also contains other Angus changes, including clearing stale server extensions before EHLO and after STARTTLS. Source-specific characterization keeps those semantics separate from the released baseline. The runner performs the equivalent of:

```powershell
mvn "-Dmaven.repo.local=<private cache>" -pl providers/angus-mail -am clean install `
    "-Dmail-api.version=2.1.5" "-Dactivation-api.version=2.1.4" `
    "-Dangus.activation.version=2.0.3" "-Dmail.spec.version=2.1" `
    "-DskipTests=true" "-Dmaven.javadoc.skip=true"
```

This upstream packaging step compiles test sources but skips executing upstream tests and generating upstream Javadocs. It does not establish
SJM behavior. The aggregate Angus provider extracts dependency source JARs, so always rebuild with `-am clean install`; a provider-only build
can silently incorporate stale core sources. The runner rejects edits or added source files in a reused export, records exact provenance and
artifact hashes under the private cache's `.sjm-merged-angus` directory, and checks those hashes on `-ReuseBuild`.

Run focused SJM tests against that cache without rebuilding Angus each time:

```powershell
.\scripts\Use-MergedAngus.ps1 @angusLane -ReuseBuild -MavenArguments @(
    '-pl', 'modules/simple-java-mail', '-am', 'test',
    '-Dtest=AuthenticatedSocksDirectRoutingTest,AuthenticatedSocksEstablishedAbortTest,AuthenticatedSocksCapacityTest',
    '-Dsurefire.failIfNoSpecifiedTests=false'
)
```

The runner enables the development profile and `license.skip=true`, and retains command output in the work directory. Its default invocation
only prepares Angus. It rejects the normal user Maven cache and publishing commands. The profile requires the pinned build's private-cache
provenance marker; prefer the runner so artifact hashes are checked too. Because upstream POM metadata retains its prerelease defaults,
the profile explicitly exempts only the three intentionally pinned API/Activation dependencies from upper-bound enforcement and only the
Angus Mail dependency from the release-dependency rule. Other convergence and dependency checks remain enabled.

Surefire receives `sjm.test.authenticatedSocksRoute=direct` in this profile and `bridge` in the ordinary lane, allowing route regressions to
fail explicitly. Run the ordinary released-Angus controls with the same private cache and without this profile. Keep narrow checks at the
`test` phase, or explicitly skip configuration-contract aggregation for a deliberately partial `verify` run as described below.
For the required JDK 11 library and JDK 17 full-reactor gates, pass the corresponding commands from [Build](#build) through `-MavenArguments`
and select that JDK with `-JavaHome`. Do not run concurrent writers against a shared cache or reactor output directory.

The independent transport factory must return an open, unconnected, non-SSL socket. Keep TLS in an `SSLSocketFactory` slot so Angus can
retain its TLS selection and trust callbacks. Legacy factory class settings may require an additional lazy `getDefault()` lookup to select
physical creation separately from native TLS. A class getter that changes its factory or configuration on each call is therefore better
expressed through an explicit transport factory. On the module path, application factory packages resolved by this adapter must export their
public factory methods to `org.simplejavamail.mailprovider.angus`; native Angus reflection can additionally require `org.eclipse.angus.mail`.
Custom factories and supplied Sessions retain their existing total-deadline and physical-abort support boundaries. Authenticated destinations also retain foreground-only connection setup, including when a core pool size is configured.

When a compatible final Angus version is available, select it through `angus-mail.version`, update the ordinary lane's expected route to
`direct`, and run the applicable acceptance gates with the ordinary dependency checks. The transport integration remains the same;
this source-build profile is a temporary development lane.

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

The CLI cache writer can encounter the same Windows restriction independently of the license formatter. Run the real
generator with an isolated first resource directory and current reactor classes, verify the generated bytes, then replace
changed source caches atomically. Do not edit cache binaries or terminate unrelated Java processes.

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

The CLI is generated from builder interfaces and their Javadocs. [ADR 0018](adr/0018-cli-from-builder-contracts.md) records why; the [API expansion workflow](API_EXPANSION_WORKFLOW.md#2-api-interface-expansion-core-module) defines the rules for exposing a new method.
`CliBuilderApi.roots()` is the authoritative root inventory, including internal scalar adapters for the immutable Email and Mailer groups.
Those adapters preserve established option names and millisecond arguments without exposing removed scalar routes on the application Java builders.

The build/runtime path is:

1. Therapi's annotation processor makes selected API Javadocs available at runtime.
2. [CliSupport](../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java) uses `CliBuilderApi.roots()` for the public bootstrap contracts and internal scalar adapters, including the regular-Mailer and exact-Email scopes.
3. [BuilderApiToPicocliCommandsMapper](../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapper.java) walks public methods on `@Cli.BuilderApiNode` types, skips bean accessors and excluded/incompatible methods, and builds Picocli options with registered string converters.
4. [TherapiJavadocHelper](../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/therapijavadoc/TherapiJavadocHelper.java) resolves method/parameter documentation; `JavadocForCliFormatter` formats it for terminal help.
5. Kryo serializes the option model to `modules/cli-module/src/main/resources/cli.data`; resolved Javadoc lookups use the separate versioned `JavadocCacheSerialization` codec in `modules/cli-module/src/main/resources/therapi.data`. Both retain the source-API fingerprint and integrity envelope. Legacy, corrupt or unreadable optional Javadoc caches fall back to embedded Javadocs.

Regenerate both committed files on JDK 17 or the current release JDK:

```powershell
mvn -pl modules/cli-module -am -Ppublish-cli -DskipTests clean package
```

The `publish-cli` profile deliberately ignores existing caches. It runs `demo.CliListAllSupportedOptionsDemoApp`, which calls `CliSupport.listUsagesForAllOptions()` and then persists the Therapi cache. The command above generates artifacts; follow it with the applicable tests and normal verification before treating a feature as checked.

Regenerate before asserting changed help text: the source-API fingerprint detects signature/annotation changes, not Javadoc prose alone. Incomplete `@param` documentation can cause an assertion in `TherapiJavadocHelper.getParamDescriptions(...)`; ambiguous overloads, bridge/synthetic methods, or unsupported parameter conversion need inspection at the mapper. Each CLI-exposed parameter needs its own documented contract.

Runtime Javadoc entries use a `ConcurrentHashMap`, including after loading a valid serialized cache, because concurrent daemon requests can repopulate absent entries after invalidation. Persistence takes a sorted `TreeMap` copy for deterministic output. Include the existing cold-cache and daemon-concurrency regressions when changing generation or loading; do not solve a cache issue by editing the generated binary manually.

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
