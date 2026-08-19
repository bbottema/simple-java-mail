# Step 7: Provide the immutable `fromDefaults()` context

- Status: Done
- Depends on: Step 6
- Primary modules: `core-module` and `simple-java-mail`
- Primary concern: one minimal default entry without mutable global configuration

## Goal

Define `SimpleJavaMail.fromDefaults()` as the one conventional entry. It must preserve the easy classpath/environment/system route while making its lifecycle explicit and immutable. It must not inspect or reinterpret a Spring `Environment`.

## Tests first

1. Put `simplejavamail.properties` on an isolated test ClassLoader and assert the conventional snapshot combines it with injected or guarded environment/system values in the expected order.
2. Assert a missing resource still permits environment and system values.
3. Assert `fromDefaults()` initializes its conventional snapshot and factory lazily and exactly once per loaded Simple Java Mail runtime.
4. Change a system property after first initialization and assert the existing conventional snapshot and builders do not change.
5. Build a separate explicit snapshot after that change and assert it can observe the new value.
6. Assert there is no public reset or mutation hook.
7. Request email, regular Mailer, and Session-based Mailer builders from `fromDefaults()` and assert they use the same snapshot, so their defaults cannot be taken at different times.
8. Place a Spring `Environment` on the classpath or in a running context and assert `fromDefaults()` still uses only the core conventional source recipe.

## Implementation

1. Add a core-side conventional resolver preset for classpath, environment, and system sources. Creating the preset must not itself cache resolved values.
2. In the facade module, add a private lazy holder for the immutable conventional `SimpleJavaMailConfig` produced by that preset.
3. In Step 8, expose that same facade-side holder through `SimpleJavaMail.fromDefaults()`. The core module must never refer back to a facade type or acquire a dependency cycle.
4. Load `ConfigLoader.DEFAULT_CONFIG_FILENAME` with the historical ConfigLoader ClassLoader choice unless Step 3 approves another explicit rule.
5. Do not expose a setter, reload, or test reset. Tests that need another default use an isolated ClassLoader or explicit config.
6. Keep the old static registry and builder entry classes only as temporary implementation bridges while Steps 8 through 14 migrate production callers. Remove both in Step 15.

## Public behavior

`SimpleJavaMail.fromDefaults()` is a convenience factory, not a reload mechanism. It implies `simplejavamail.properties`, environment variables, and system properties in the locked precedence order. Applications that need startup control should build an explicit config during application boot and retain the resulting configured factory.

This first-use timing change is non-obvious and belongs in the 10.0 migration notes for users who previously called `ConfigLoader.loadProperties(...)` or changed system properties during runtime.

## Acceptance criteria

- [x] The conventional config is immutable and built once.
- [x] `fromDefaults()` supplies email and both Mailer builder variants from the same snapshot.
- [x] No other supported public entry creates a default-backed builder.
- [x] Spring configuration cannot leak into or override this conventional context.
- [x] Explicit configured factories remain independent from it.
- [x] The core module exposes only the resolver/config side and never depends on the facade factory.
- [x] No test-only reset API is added.
- [x] First-use timing and the explicit alternative are documented.
- [x] Focused classloader tests pass on Java 8 and JPMS tests are scheduled in Step 16.

## Completion evidence

- Isolated-ClassLoader factory tests prove lazy one-time initialization, the missing-resource path, classpath/environment/system ordering, and the absence of a reset hook.
- Factory and Spring isolation tests prove explicit snapshots and Spring contexts cannot leak into `fromDefaults()`.
- Core contains only config resolution; the lazy configured factory lives in the facade's exported `org.simplejavamail.api` package.
