# Step 3: Freeze the public API and migration matrix

- Status: Done
- Depends on: Steps 1 and 2
- Primary areas: public API design, compile fixtures, migration scope
- No runtime integration is allowed before this step is accepted

## Goal

Turn the architectural sketch into exact Java 8 signatures and decide every intentional public break before implementation spreads those signatures across modules.

## API contract to freeze

1. Package and final names for `SimpleJavaMailConfig`, `ConfigSource`, the instance `ConfigLoader` builder, and the configured `SimpleJavaMail` factory.
2. Ordered source methods for classpath resources, caller streams, `Properties`, maps, environment variables, system properties, an existing snapshot, and custom sources.
3. Snapshot inspection methods that replace static `hasProperty`, generic/typed `getProperty`, and `valueOrProperty` use.
4. Factory methods for regular Mailer builders, Session-based Mailer builders, email starters, and configured conversion.
5. `SimpleJavaMail.fromDefaults()` as the single conventional-default entry point, plus its exact equivalence to the classpath, environment, and system source recipe.
6. The regular Mailer-builder method for opportunistic TLS, its property-backed default, and its strict plain-`SMTP` scope. Do not add a general-purpose public runtime getter; snapshot inspection already covers configured values.
7. The explicit replacement for `MailerImpl.createMailSession(...)` callers that need configured Session properties.
8. Spring bean names, scopes, and types.

## Compile fixtures

Create small Java 8 consumer sources for:

- explicit non-Spring configuration;
- two simultaneous configured factories;
- `SimpleJavaMail.fromDefaults()` email, regular Mailer, and Session-based Mailer builders;
- replacement configuration and Mailer handoff;
- custom `ConfigSource`;
- direct snapshot inspection;
- a caller-provided Session;
- Spring injection of config, factory, fresh builder, and default Mailer;
- JPMS use through `org.simplejavamail` and direct `org.simplejavamail.core` use where supported.

Keep a 9.3.2 source fixture for each removed API. The migration table pairs each failing 9.x fixture with a compiling 10.0 fixture.

## Decisions to record

- Confirm that the mutable static compatibility facade is removed, not deprecated in place.
- Confirm that the conventional default is one immutable lazy snapshot and document first-use timing.
- Remove the `EmailBuilder` and `MailerBuilder` entry classes in 10.0.0. Do not plan a 10.1 removal after deprecating them in 10.0; that would move a source break into a minor release.
- Make the configured `SimpleJavaMail` factory the only supported route to a default-backed builder. Audit public no-arg implementation constructors and retain one only where a cross-module SPI genuinely requires it.
- Confirm later-source-wins ordering for custom loaders.
- Confirm whether supplied streams retain the documented consume-and-close contract.
- Correct the embedded URL/classpath descriptor keys and name the Spring behavior change. Keep this as a mandatory migration item through release completion.
- Keep the misspelled `mustbesuccesful` key and other historical key spellings.
- Make `TransportStrategy` stateless and remove its global setter. Replace it with a regular Mailer-builder option whose documented and tested effect is limited to `TransportStrategy.SMTP`.
- Remove the Spring `defaultMailerBuilder` bean. Spring exposes its context-local config, configured factory, and default Mailer; callers obtain fresh builders from the factory.

## Migration matrix

For each break, record:

- removed or changed 9.x signature;
- exact 10.0 replacement;
- source, binary, or behavioral impact;
- copyable before/after code;
- whether it belongs in `MIGRATION-10.0.md`, website migration notes, Javadoc, or only release engineering notes.

Internal constructor changes, internal package movement, and test-helper removal are excluded from user migration notes.

## Acceptance criteria

- [x] Exact public signatures compile with Java 8 and do not expose implementation classes unnecessarily.
- [x] All Java-only factory/config methods are excluded from generated CLI options.
- [x] The config snapshot can be injected without importing `simple-java-mail` implementation packages.
- [x] The API supports multiple configurations without any global registration call.
- [x] `fromDefaults()` is the sole supported conventional builder entry and its implied sources are executable contract tests.
- [x] The removed builder entry classes and Spring builder bean each have a direct migration example.
- [x] Opportunistic TLS is explicitly limited to plain `SMTP` in signatures, Javadocs, tests, and migration notes.
- [x] The embedded URL/classpath correction remains present in the migration matrix.
- [x] Every intentional 9.x source break has one replacement example.
- [x] The migration matrix distinguishes public behavior from implementation cleanup.
- [x] No unresolved API choice remains when Step 4 starts.

## Completion evidence

- Java 8 compiled the public factory, config, email-builder, regular Mailer-builder, and Session-builder surface.
- Generated CLI metadata excludes the Java-only config and factory methods and includes the regular-builder TLS option.
- Japicmp against OpenPGP baseline commit `6ee8cc71` contains only the approved additions, removals, and internal constructor changes; the migration guide covers every public break.
