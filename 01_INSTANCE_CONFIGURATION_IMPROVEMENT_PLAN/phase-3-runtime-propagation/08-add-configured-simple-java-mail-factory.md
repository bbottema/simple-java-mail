# Step 8: Add the configured SimpleJavaMail builder factory

- Status: Done
- Depends on: Steps 5 through 7
- Primary module: `simple-java-mail`
- Public surface: configured factory plus config-aware builder entry points frozen in Step 3

## Goal

Give one immutable config snapshot an owner that can create every builder variant. This becomes the application, conventional-default, and dependency-injection entry point. The old static builder classes remain only as a temporary bridge until Step 15 removes them.

## Tests first

1. Build factories for configuration A and B, request regular Mailer, Session-based Mailer, and email builders from each, and assert each builder starts with its own values.
2. Request two builders from one factory and prove that mutating one builder does not affect the other.
3. Share one factory across several threads and prove that builder creation is safe.
4. Assert repeated `SimpleJavaMail.fromDefaults()` calls resolve through one lazily initialized immutable conventional factory, and that all builders obtained from it share its snapshot. Do not make object identity part of the public contract.
5. Assert the explicit factory never consults the conventional default, system properties, environment variables, or classpath after construction.
6. Assert `fromDefaults()` never consults Spring and remains usable when no classpath property file exists.
7. Add Java 8 compile fixtures for the Step 3 factory API.

## Implementation

1. Add the configured factory in a new package owned and exported by `simple-java-mail`. Do not create a split package with `core-module`. The exact package is fixed in Step 3.
2. Hold one final `SimpleJavaMailConfig`.
3. Return fresh `EmailStartingBuilderImpl`, `MailerRegularBuilderImpl`, and `MailerFromSessionBuilderImpl` instances initialized with that config.
4. Add `SimpleJavaMail.fromDefaults()` and `SimpleJavaMail.withConfig(...)` as the two factory construction routes.
5. Mark config/factory methods with `@Cli.ExcludeApi` where generated CLI discovery can see them.
6. Migrate production internals to the factory. Do not add config-aware methods to `EmailBuilder` or `MailerBuilder`.
7. Keep implementation constructors out of the supported public API. Any constructor retained for a cross-module SPI must require explicit configuration and must not select defaults independently.

## API constraints

- The factory itself is immutable and thread-safe.
- Builders are still mutable and not advertised as thread-safe.
- The factory does not own Mailer lifecycle. Every built Mailer is closed by its user or Spring context.
- The factory does not reload config.
- No factory is registered globally.
- `fromDefaults()` is the only factory-level global convenience and holds immutable state only.

## Acceptance criteria

- [x] One snapshot can create all supported builder variants.
- [x] Two factories coexist without cross-talk.
- [x] Builders from one factory do not share mutable state.
- [x] `fromDefaults()` covers the former zero-setup Email and Mailer entry cases.
- [x] No new secondary builder-entry API is introduced.
- [x] Removal of `EmailBuilder` and `MailerBuilder` is represented by compile fixtures scheduled for Step 15.
- [x] The factory lives in a JPMS-safe package owned by one module.
- [x] Java 8 API fixtures compile.
- [x] No runtime behavior outside builder creation changes yet.

## Completion evidence

- `SimpleJavaMailFactoryTest` covers A/B factories, fresh mutable builders, regular and Session Mailer builders, email builders, conversion, cluster fallback, and the conventional factory.
- Java 8 compiled the exported `org.simplejavamail.api.SimpleJavaMail` facade without a split package.
- Production and API scans confirm there is no secondary default-backed builder entry.
