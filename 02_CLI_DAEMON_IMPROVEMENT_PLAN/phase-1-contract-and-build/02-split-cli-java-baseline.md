# Step 2: Raise only cli-module to Java 17

- Status: Done
- Depends on: Step 1
- Primary files: root `pom.xml`, `modules/cli-module/pom.xml`, `DEVELOPMENT.md`, `.circleci/config.yml`
- Compatibility boundary: Java 8 libraries, Java 17 CLI

## Goal

Make Java 17 a deliberate, enforced requirement of the published CLI artifact while preserving Java 8 source, API, and bytecode compatibility for every non-CLI artifact.

## Tests first

1. Add class-file audits that expect major version 52 for every non-CLI production class and major version 61 for CLI production classes.
2. Compile a Java 8 consumer against the normal `simple-java-mail` facade and every supported direct library module without placing `cli-module` on its classpath.
3. Run the complete CLI test suite on JDK 17 and the current release JDK.
4. Prove a full modern-JDK reactor compiles library modules against the Java 8 API surface rather than only emitting Java 8-shaped bytecode.
5. Prove a JDK 8 library-only reactor excludes `cli-module` and any aggregate step that requires its Java 17 classes.
6. Inspect the effective POM for the root, `core-module`, `simple-java-mail`, and `cli-module` so compiler and enforcer inheritance is explicit.
7. Inspect published source, Javadoc, and binary jars to ensure Java 17-only imports occur only in `cli-module`.
8. Add a failure fixture that deliberately introduces a Java 9+ API into a library module and prove the release gate catches it.

## Implementation

1. Keep a library release property fixed at 8 and introduce a narrowly named CLI release property fixed at 17.
2. Build the full reactor on JDK 17 or newer. In modern-JDK lanes, compile library production and test sources with `--release 8` and compile `cli-module` with `--release 17`.
3. Override the inherited compiler and enforcer execution only in `cli-module`. Do not relax the Java 8 rule globally to a regex that also permits accidental library drift.
4. Add a CLI-specific `requireJavaVersion` rule for JDK 17 or newer.
5. Define and document a repeatable JDK 8 library-only module selection. It must include all library modules that claim Java 8 support and exclude only the CLI and incompatible aggregate/reporting modules. This lane uses source/target 1.8 because JDK 8 `javac` has no `--release` option.
6. Keep protocol, daemon, service, and Java 17 process types inside `cli-module`. Java 8 modules may expose only their existing CLI annotations/models and ordinary Mailer APIs.
7. Update the developer setup so it no longer says the entire reactor must run on JDK 8 or that CLI metadata must be generated on JDK 8.
8. State the CLI Maven artifact's Java 17 minimum in its POM description, README, release notes, and standalone archive.

## Release lanes

| Lane | JDK | Scope |
| --- | --- | --- |
| Library compatibility | 8 | All supported non-CLI production modules and tests that can run on JDK 8 |
| Full reactor | 17 | Libraries compiled for Java 8 plus CLI compiled for Java 17 |
| Current JDK | Current release JDK | Full tests, packaging inputs, illegal-reflection and modern-runtime coverage |

## Migration impact

Applications that directly depend on or embed `org.simplejavamail:cli-module` must run on Java 17. Applications that depend on `simple-java-mail`, `core-module`, or any other non-CLI module remain supported on Java 8.

The portable `sjm` scripts must fail with a concise Java 17 requirement before printing a long class-version stack trace where practical. Package-manager installations supply a tested runtime that satisfies that minimum; they need not pin the oldest compatible JDK.

## Acceptance criteria

- [x] Every non-CLI production class remains class-file version 52.
- [x] Every CLI production class is compiled with `--release 17` and class-file version 61.
- [x] A Java 17 API cannot compile in a library module.
- [x] The full reactor passes on JDK 17 and the current release JDK.
- [x] The documented JDK 8 library-only gate passes.
- [x] Root enforcer rules still reject library baseline drift.
- [x] No Java 17 type leaks into a Java 8 module's signatures, descriptors, generated metadata, or optional dependencies.
- [x] The CLI-only compatibility break is recorded for Step 17.

## Completion evidence

- The root build keeps `library.java.release=8`; `cli-module` overrides source, target, and release to 17 and enforces JDK 17 or newer.
- `CliJavaBaselineTest` audits all emitted CLI classes as class-file version 61 and representative non-CLI classes as version 52.
- The Java 8 library-only gate, full Java 17 reactor gate, and full current-JDK reactor gate pass on the completed tree.

## Stop condition

If Maven inheritance cannot express the split without weakening library enforcement, stop and introduce an explicit CLI build profile or separate aggregate build rather than changing the root Java baseline to 17.
