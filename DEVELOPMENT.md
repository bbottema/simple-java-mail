# Developer Environment Setup

This document records environment requirements and constraints for building Simple Java Mail.
It is intended for both human developers and coding agents starting new sessions.

For a catalogue of cross-cutting project mechanisms, see [PROJECT_MECHANISMS_CATALOGUE.md](PROJECT_MECHANISMS_CATALOGUE.md).
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

The CLI is generated from builder interfaces and their Javadocs; the [API expansion workflow](API_EXPANSION_WORKFLOW.md#2-api-interface-expansion-core-module) defines the rules for exposing a new method.

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
- **`cli.data` and `therapi.data`** are committed, versioned CLI startup caches generated by
  the `publish-cli` profile. That profile deliberately ignores existing caches so API and
  Javadoc changes are always rebuilt. Generate and consume them on the supported CLI JDKs
  (17 and the current release JDK).
