# Developer Environment Setup

This document records environment requirements, build procedures, generated metadata, and build constraints for Simple Java Mail.
It is intended for both human developers and coding agents starting new sessions.

For architectural decisions and a topic index, see [docs/adr/README.md](docs/adr/README.md).
For feature propagation and verification, see [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md).
For send-state transitions, resource ownership, and races, see [docs/concurrency/README.md](docs/concurrency/README.md).
For issue, Dependabot, and release handling workflows, see [MAINTAINER_WORKFLOW.md](MAINTAINER_WORKFLOW.md).

---

## Java Version

All published library modules require Java 11 or newer. The separately executable `cli-module` requires Java 17 or newer. A full-reactor build therefore runs on JDK 17+ and compiles non-CLI modules with `--release 11` while compiling only `cli-module` with `--release 17`.

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
mvn clean verify -Ppublish-cli -DexcludeLiveServerTests=true
```

Full non-live library compatibility validation, including Javadocs, on JDK 11:

```powershell
mvn -pl '!modules/cli-module' clean verify -DexcludeLiveServerTests=true
```

When changing builder signatures or CLI help, also follow [Generated CLI Metadata](#generated-cli-metadata).

Record repeatable local one-shot and warm-daemon process timings without adding a CI timing threshold:

```powershell
mvn -pl modules/cli-module "-Dtest=CliDaemonPerformanceTest" "-Dsjm.runDaemonBenchmark=true" test
```

The raw samples and median/p95 summary are written to `modules/cli-module/target/cli-daemon-benchmark.csv`.

After any build that ran `license:format`, clean up auto-generated headers before committing:

```powershell
mvn com.mycila:license-maven-plugin:3.0:remove
```

---

## Generated CLI Metadata

The CLI is generated from builder interfaces and their Javadocs. [ADR 0018](docs/adr/0018-cli-from-builder-contracts.md) records why; the [API expansion workflow](API_EXPANSION_WORKFLOW.md#2-api-interface-expansion-core-module) defines the rules for exposing a new method.

The build/runtime path is:

1. Therapi's annotation processor makes selected API Javadocs available at runtime.
2. [CliSupport](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java) supplies the roots `EmailStartingBuilder`, `MailerRegularBuilder`, and `MailerFromSessionBuilder`.
3. [BuilderApiToPicocliCommandsMapper](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapper.java) walks public methods on `@Cli.BuilderApiNode` types, skips bean accessors and excluded/incompatible methods, and builds Picocli options with registered string converters.
4. [TherapiJavadocHelper](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/therapijavadoc/TherapiJavadocHelper.java) resolves method/parameter documentation; `JavadocForCliFormatter` formats it for terminal help.
5. Kryo serializes the option model to `modules/cli-module/src/main/resources/cli.data`; resolved Javadoc lookups are cached in `modules/cli-module/src/main/resources/therapi.data`.

Regenerate both committed files on JDK 17 or the current release JDK:

```powershell
mvn -pl modules/cli-module -am -Ppublish-cli -DskipTests clean package
```

The `publish-cli` profile deliberately ignores existing caches. It runs `demo.CliListAllSupportedOptionsDemoApp`, which calls `CliSupport.listUsagesForAllOptions()` and then persists the Therapi cache. The command above generates artifacts; follow it with the applicable tests and normal verification before treating a feature as checked.

Regenerate before asserting changed help text: the source-API fingerprint detects signature/annotation changes, not Javadoc prose alone. Incomplete `@param` documentation can cause an assertion in `TherapiJavadocHelper.getParamDescriptions(...)`; ambiguous overloads, bridge/synthetic methods, or unsupported parameter conversion need inspection at the mapper. Each CLI-exposed parameter needs its own documented contract.

Runtime Javadoc entries use a `ConcurrentHashMap`, including after loading a valid serialized cache, because concurrent daemon requests can repopulate absent entries after invalidation. Persistence takes a sorted `TreeMap` copy for deterministic output. Include the existing cold-cache and daemon-concurrency regressions when changing generation or loading; do not solve a cache issue by editing the generated binary manually.

## Exercising CLI Process Modes

Ordinary commands and help run locally. `-d`, bare `--daemon`, and `--daemon=acquire` find or start a selected per-user daemon; `--daemon=require` requires an existing one; `--daemon=off` and `--no-daemon` force one-shot execution. Lifecycle commands are `daemon run`, `start`, `status`, `stop`, and `restart`. See [ADR 0019](docs/adr/0019-local-cli-daemon.md) for authentication, Mailer reuse, request isolation, and response-loss semantics.

Check changed commands through both one-shot and daemon routes. Each request owns its working directory and output streams; relative-file conversion must use that context rather than process-wide `user.dir`. A daemon request leases its retained Mailer through command execution and reporting; finishing a request does not close that Mailer.

For connection diagnostics, `probe` calls the synchronous view and prints its report to request-owned stdout. `--authenticate` is an invocation choice, not a generated builder setting or part of a retained Mailer profile. Exit codes are 0 for success, 3 for a failed/unsupported report, and 2 for invalid arguments. The dedicated probe connection closes before the report returns. The [probe integration tests](modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpConnectionProbeTest.java), [loopback demo](modules/simple-java-mail/src/test/java/demo/SmtpConnectionProbeDemoApp.java), and [ADR 0020](docs/adr/0020-dedicated-smtp-connection-diagnostics.md) describe that separate path.

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
  [ADR 0009](docs/adr/0009-nullability-and-cli-optionality.md) records the history and the separate CLI optionality contract.
