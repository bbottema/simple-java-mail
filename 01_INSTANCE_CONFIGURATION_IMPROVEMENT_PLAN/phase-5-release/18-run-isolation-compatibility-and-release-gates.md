# Step 18: Run isolation, compatibility, and release gates

- Status: Done
- Depends on: Steps 1 through 17
- Target release: 10.0.0
- Primary areas: full verification, public API audit, cleanup, completion evidence

> Historical gate: this records the plan 01 release matrix as executed. Plan 02 later split it into a Java 8 library lane and Java 17/21 CLI lanes; issue #707 then moved the 10.0.0 library baseline to Java 11. The results below remain historical evidence for this completed step.

## Goal

Prove that the refactor works as one architecture across Java versions, module systems, frameworks, runtime routes, and documentation. Then remove every temporary bridge and record the evidence.

## Isolation matrix

Run configurations A and B concurrently with conflicting values for:

- SMTP server and credentials;
- transport and opportunistic TLS;
- proxy and trust settings;
- debug and Session properties;
- email defaults, overrides, and embedded-image behavior;
- DKIM and S/MIME defaults;
- global and per-cluster batch settings;
- regular and caller-provided Sessions;
- non-Spring factory, plain Spring context, and Spring Boot context.

For each, prove that replacing B with C does not change A or any object already built from B.

## Compatibility matrix

1. Java 8 full reactor with tests, Javadocs, nullability instrumentation, and CLI generation.
2. Supported modern JDK full reactor.
3. Clean classpath consumer.
4. Clean JPMS facade and direct-core consumers.
5. OSGi bundle inspection and Karaf feature resolution.
6. `SimpleJavaMail.fromDefaults()` builders with classpath, environment, and system sources.
7. Explicit config, custom source, multi-config, and replacement-object consumers.
8. Plain Spring and Spring Boot with one and two contexts.
9. CLI send, validate, connect, and help output.
10. Direct send, async send, custom Session, proxy, and batch/pooled routes.
11. Configured DKIM, S/MIME, OpenPGP coexistence elsewhere in 10.0, and the provider-neutral/Angus paths already added on this branch.
12. Website production build and link check.

## Static and security audits

1. Scan production source and bytecode for mutable static config fields and forbidden ConfigLoader calls.
2. Scan logs, exception messages, `toString()` output, test reports, and generated docs for configured secret fixtures.
3. Mutate all source collections after load and all returned collections where accessible.
4. Repeat concurrency tests enough times to catch order dependence.
5. Verify no temporary compatibility adapter, reflection reset, or plan-only TODO remains.
6. Verify `EmailBuilder`, `MailerBuilder`, the Spring builder bean, and public default-selecting implementation constructors are absent from the supported 10.0 surface.

## Public and artifact audits

1. Compare the 10.0 public API with 9.3.2 and reconcile it with the migration matrix.
2. Inspect published POM shapes and dependency trees for every affected module.
3. Inspect module descriptors, automatic module names, OSGi manifests, Spring metadata, CLI data, and packaged resources.
4. Compile every migration snippet.
5. Run `git diff --check` in both root and website checkouts.

## Completion evidence

- Java 8 gate: Oracle JDK 1.8.0_152 with Maven 3.9.10; `mvn "-Dlicense.skip=true" verify` passed all 15 modules in 2 minutes 53 seconds.
- Modern gate: Oracle JDK 21 LTS build 21+35 with Maven 3.9.10; `mvn "-Dlicense.skip=true" verify` passed all 15 modules in 3 minutes 15 seconds.
- The facade ran 454 tests and CLI ran 28 tests in each full reactor. The final focused SMTP-only TLS test also passed on both JDKs.
- Japicmp 0.25.7 compared core, facade, and Spring jars with OpenPGP baseline commit `6ee8cc71`; all additions and removals reconcile with the migration matrix, and two accidental Lombok getters were removed before completion.
- Provider-neutral classpath/JPMS consumers, OSGi/Karaf packaging, Spring two-context isolation, Session, batch, OpenPGP, DKIM, S/MIME, Angus, and CLI routes passed in the reactors.
- Java 8's publish/profile build regenerated `cli.data` and `therapi.data`; CLI help presents the authoritative public builder Javadoc for `withOpportunisticTLS`.
- No POM changed, so the refactor adds no runtime dependency or module. Jar/resource and secret scans were clean.
- Website `npm run build`, `npm run check`, and `npm run verifyLinks:internal` passed with 24 pages, 23 indexed pages, and 1,998 internal links.
- The shared `ConfigLoaderTestHelper` class name remains for test-source compatibility, but it has no global behavior: it only creates fresh detached snapshots. This is not a production or mutable compatibility bridge.

## Acceptance criteria

- [x] The full isolation matrix passes without cross-talk.
- [x] Java 8 and modern-JDK reactors pass.
- [x] Classpath, JPMS, OSGi/Karaf, Spring, CLI, Session, and batch routes pass.
- [x] No mutable static config channel remains.
- [x] `fromDefaults()` is the only conventional builder entry and does not consult Spring.
- [x] No secret value appears in diagnostics or artifacts.
- [x] The API diff and migration guide match exactly.
- [x] No new runtime dependency or module was introduced.
- [x] All temporary bridges and global test helpers are gone.
- [x] Root and website builds pass from clean, reviewed changes.
- [x] Every prior step contains concrete completion evidence and is marked `Done`.
