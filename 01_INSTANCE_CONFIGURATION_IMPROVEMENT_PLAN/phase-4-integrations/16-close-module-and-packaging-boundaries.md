# Step 16: Close module, packaging, and generated-metadata boundaries

- Status: Done
- Depends on: Steps 14 and 15
- Primary areas: JPMS, classpath, OSGi/Karaf, generated CLI data, Spring Boot metadata, dependency trees, public API diff

> Historical gate: the Java 8 CLI-generation evidence in this completed step predates plan 02. Plan 02 moved `cli.data` and `therapi.data` generation to Java 17+, and issue #707 later moved the 10.0.0 library baseline to Java 11. The Java 8 results below remain historical evidence for this completed step.

## Goal

Make the new API real in every published artifact form. The refactor is incomplete if it only works from the Maven reactor's classpath.

## Tests first

1. Compile and run a classpath consumer using explicit config and no Spring.
2. Compile and run JPMS consumers for the normal `org.simplejavamail` facade and the supported direct `org.simplejavamail.core` configuration surface.
3. Add a module test that catches split packages between `core-module` and `simple-java-mail`.
4. Verify OSGi exports and the Karaf feature resolve the new packages without a new bundle.
5. Inspect the generated Spring metadata for all canonical properties, supported aliases, wildcard cluster shape, descriptions, types, and secret-safe examples.
6. Regenerate and test CLI metadata after the SMTP-only opportunistic-TLS builder change.
7. Produce a public API comparison against 9.3.2 and classify every removal/addition against Step 3, including `EmailBuilder`, `MailerBuilder`, their public entry methods, direct default-selecting implementation constructors, and the Spring builder bean.
8. Inspect clean dependency trees to prove the refactor adds no runtime library.

## Implementation

1. Export the immutable config/source packages from the `org.simplejavamail.core` module descriptor. Reuse `org.simplejavamail.config` where Step 3 chooses it, rather than creating packages only for symmetry.
2. Export the configured factory package from the `org.simplejavamail` facade module descriptor.
3. Avoid adding classes to a package already owned by the other JPMS module.
4. Update OSGi bundle instructions and Karaf feature metadata only where generated defaults do not already cover the new packages.
5. Replace or generate Spring configuration metadata from the central property schema where practical. Keep the metadata-only Boot class out of runtime binding logic.
6. Regenerate `cli.data` and `therapi.data` with JDK 8 using the documented publish profile.
7. Add a repeatable API-diff command or maintainer script. Intentional 10.0 breaks are allowlisted or reviewed, not hidden.
8. Verify source and target bytecode remain Java 8.

## Acceptance criteria

- [x] No split package exists.
- [x] Classpath and JPMS config consumers compile and run.
- [x] OSGi/Karaf artifacts export and resolve the new API.
- [x] Spring metadata covers every property and wildcard namespace once.
- [x] CLI generated data is current and produced on Java 8.
- [x] The API diff contains no unexplained change.
- [x] Published POM dependency trees contain no new runtime dependency.
- [x] Clean packaged jars contain no test configuration or secret fixture.

## Completion evidence

- Provider-neutral classpath and JPMS consumers, multi-release module descriptors, OSGi manifests, and the Karaf module passed the full verification build.
- OSGi exports include `org.simplejavamail.config`; the facade exports `org.simplejavamail.api`; no package is owned by both modules.
- Spring metadata includes opportunistic TLS, cluster namespaces, and corrected embedded URL/classpath keys; Java 8 generated current CLI data.
- Japicmp reviewed core, facade, and Spring changes against OpenPGP baseline `6ee8cc71`. No POM changed, and packaged jars contain no application/bootstrap test config or secret fixture.
