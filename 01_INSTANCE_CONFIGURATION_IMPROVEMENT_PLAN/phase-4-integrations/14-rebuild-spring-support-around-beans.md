# Step 14: Rebuild Spring support around context-local beans

- Status: Done
- Depends on: Steps 8 through 13
- Primary module: `spring-module`
- Primary files: `SimpleJavaMailSpringSupport.java`, `SimpleJavaMailProperties.java`, Spring tests and test resources
- Compatibility baseline: plain Spring 5.3.x and Spring Boot 2.7.x on Java 8

## Goal

Replace the giant `@Value` method and global ConfigLoader mutation with one context-local immutable config bean, one configured factory bean, and the default Mailer bean. The factory is the only builder route.

## Tests first

1. Start two Spring application contexts in the same JVM with conflicting SMTP, embedded-image, governance, security, extra Session, and cluster values. Assert independent config, factory, builder, Mailer, and Session objects.
2. Close or refresh one context and assert the other context and an existing non-Spring factory are unchanged.
3. Cover plain Spring `@TestPropertySource` and Spring Boot application properties.
4. Cover Spring profiles, placeholders, custom `PropertySource` precedence, system properties, environment properties, and a wrapper that returns decrypted values.
5. Cover wildcard `extraproperties.*` and `connectionpool.clusters.*` across multiple enumerable sources.
6. Cover underscore/dash compatibility aliases and the embedded URL/classpath correction.
7. Assert OAuth2 provider injection still supports zero, one, multiple, and `@Primary` provider beans.
8. Assert the default Mailer closes with the context and two builders requested from the injected factory are fresh and independent.
9. Retain the production-jar guard that rejects packaged root `application.*` and `bootstrap.*` files.

## Implementation

1. Adapt `ConfigurableEnvironment` into a single named `ConfigSource`. Ask `environment.getProperty(...)` for final values so Spring owns source order, placeholder resolution, profiles, and decryption.
2. Enumerate candidate keys only to discover wildcard names. Resolve each candidate through the Environment before adding it. Preserve the current, explicit limitation that wildcard discovery can only inspect `EnumerablePropertySource` instances, and document it instead of pretending every Spring `PropertySource` can list keys.
3. Register a singleton `SimpleJavaMailConfig` bean.
4. Register a singleton configured factory bean using that snapshot.
5. Remove the `defaultMailerBuilder` bean. Callers inject the configured `SimpleJavaMail` factory and request a fresh builder from it.
6. Build the singleton default Mailer from a fresh factory-owned builder and the optional OAuth2 provider. Give the bean a close destroy method.
7. Delete the enormous `@Value` parameter list and `ConfigLoader.loadProperties(..., true)` call.
8. Keep Spring Boot dependencies optional/build-time as they are today. Do not make Boot a runtime requirement for plain Spring.

## Precedence behavior

Spring Environment is authoritative inside Spring. The core loader must not re-read raw JVM sources afterward. If this changes behavior for custom Spring property-source ordering, document it as a non-obvious 10.0 change.

## Acceptance criteria

- [x] No Spring production code mutates or registers global Simple Java Mail configuration.
- [x] Two contexts with conflicting values pass concurrently.
- [x] Plain Spring and Spring Boot retain supported behavior.
- [x] Wildcard namespaces and aliases pass.
- [x] No Spring builder bean bypasses the configured factory.
- [x] Default Mailer lifecycle and OAuth2 provider selection pass.
- [x] No Spring Boot runtime dependency is introduced.
- [x] The packaging regression guard passes.

## Completion evidence

- Spring now exposes `simpleJavaMailConfig`, `simpleJavaMail`, and lifecycle-managed `defaultMailer` beans and no `defaultMailerBuilder` bean.
- `SimpleJavaMailSpringContextIsolationTest` proves conflicting contexts, a non-Spring factory, wildcard properties, aliases, and embedded URL/classpath values remain isolated.
- Plain Spring support, OAuth2 provider selection, default Mailer disposal, metadata, and packaged-resource guards passed without adding a Boot runtime dependency.
