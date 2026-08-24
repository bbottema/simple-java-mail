# Instance-based configuration improvement plan

- Status: Done
- Plan order: 01 of 02
- Followed by: [02 - CLI daemon improvement plan](../02_CLI_DAEMON_IMPROVEMENT_PLAN/README.md), implemented after this plan
- GitHub issue: [#693 Replace static ConfigLoader with instance-based, injectable configuration](https://github.com/bbottema/simple-java-mail/issues/693)
- Target release: 10.0.0
- Working branch: `codex/10.0.0`
- Baseline inspected: 18 August 2026

This plan replaces Simple Java Mail's mutable process-wide configuration with immutable snapshots that can be passed around, injected, and used by more than one independent mail setup in the same JVM. It is intentionally split into phases and one file per implementation step. The refactor is large enough that the public contract, source precedence, lifecycle timing, Spring behavior, CLI behavior, and module boundaries need to move together.

The implementation is complete on `codex/10.0.0`. Each step file records the tests and release gates that proved its part of the contract.

Plan 02 subsequently raised only `cli-module` and its metadata-generation path to Java 17. Issue [#707](https://github.com/bbottema/simple-java-mail/issues/707) later raised the 10.0.0 library baseline to Java 11. The Java 8 evidence below remains the historical completion evidence for this plan; it is not the current release gate.

## Why this is a large refactor

`ConfigLoader` is not only a parser. It currently acts as:

- a class-load hook that discovers `simplejavamail.properties`;
- a mutable process-wide registry;
- a source precedence engine for files, system properties, and environment variables;
- a weakly typed conversion layer;
- a merge engine for `addProperties=true`;
- a wildcard parser for Jakarta Mail Session properties and batch cluster definitions;
- an implicit dependency of email builders, mailer builders, email governance, Session construction, Spring support, and the CLI.

Those consumers do not all read the global state at the same time. Most mailer settings are copied when a builder is created, email defaults are copied when a Mailer is built, extra Session properties and opportunistic TLS are read while the Session is created, and governance creates fresh global-configured email builders again when a message is completed for sending. The result is neither live configuration nor a clean snapshot. It is a set of partial snapshots taken at different lifecycle points.

## Current read points that must be removed

| Current location | Values read | Read time | Main risk |
| --- | --- | --- | --- |
| `ConfigLoader` static initializer | Classpath file, environment, system properties | First class use | Class-load order becomes configuration behavior |
| `EmailPopulatingBuilderImpl` | Embedded-image resolution | Email builder creation | Email builders from different contexts cannot be isolated |
| `MailerGenericBuilderImpl` and `MailerRegularBuilderImpl` | SMTP, proxy, validation, debug, pool, cluster, trust, and other defaults | Mailer builder creation | A retained builder contains an undocumented partial snapshot |
| `EmailGovernanceImpl` | Message defaults, DKIM, and S/MIME | Mailer construction | Security material is resolved at a different time from SMTP settings |
| `EmailGovernanceImpl.produceEmailApplyingDefaultsAndOverrides` | Embedded-image defaults through static `EmailBuilder` calls | Send, log, or validation time | An existing Mailer can still observe later global changes |
| `MailerImpl` | Extra Session properties | Session construction | A supposedly configured builder does not own all Session input |
| `TransportStrategy.SMTP` | Opportunistic TLS plus mutable enum state | Session property generation | The enum singleton is another process-wide configuration channel |
| `SimpleJavaMailSpringSupport` | Spring Environment copied into `ConfigLoader` | Spring context startup | One context mutates every other context and every non-Spring caller |
| CLI startup | All defaults through internal constructors | Per command | Email and Mailer builders have no explicit shared snapshot |

## Historical contracts that must not be rediscovered by accident

The issue history shows why a simple map-to-object rewrite is not enough:

- #61 made application-provided `Properties`, including already typed values, part of the public API.
- #62 established that null and blank values are absent and should fall through to lower-priority or library defaults.
- #168 exposed the danger of guessing a value's type without knowing which property it belongs to.
- #105 introduced opportunistic TLS as an SMTP-only escape hatch for unusual servers that must not attempt STARTTLS. It was never intended to alter `SMTP_TLS`, `SMTP_OAUTH2`, or `SMTPS`.
- #451 moved message defaults and overrides into Mailer-level governance, which determines when expensive security configuration is resolved.
- #538 and #550 established that system properties and environment variables work without a classpath property file.
- #279 and #595 added wildcard Jakarta Mail properties and Spring forwarding for that namespace.
- #565 and #618 made cluster-specific pool settings part of both Java and property-backed configuration.
- #685 fixed wildcard precedence to match the documented `system > environment > file` rule.
- #681 demonstrated that Spring resource and source-order mistakes can configure applications from library test data.

The new implementation starts by turning these behaviors into explicit tests. It does not assume that the current code structure is the contract.

## Recommended target architecture

```text
ordered ConfigSource instances
        |
        v
instance ConfigLoader
        |
        v
immutable SimpleJavaMailConfig snapshot
        |
        v
configured SimpleJavaMail factory
        |
        +--> fresh EmailStartingBuilder
        +--> fresh MailerRegularBuilder
        +--> fresh MailerFromSessionBuilder
        +--> configured conversion helpers
                    |
                    v
        Email, Mailer, governance, Session, batch registration
        all derived from the same snapshot
```

### 1. `SimpleJavaMailConfig` is an immutable resolved snapshot

The snapshot contains only values representable by the existing property configuration surface. Per-builder runtime objects such as an `ExecutorService`, `PrintStream`, `EmailValidator`, custom Mailer, OAuth token provider, or random fallback cluster key remain builder-owned.

The snapshot:

- defensively copies all maps, lists, arrays, and `Properties`;
- exposes no mutator;
- can be shared safely between threads;
- remembers source origin for diagnostics without retaining a live source;
- redacts SMTP passwords, proxy passwords, S/MIME passwords, private key material, and similar values from logs and `toString()`;
- keeps the existing `ConfigLoader.Property` catalogue for a lower-friction migration, while parsing every property according to its own declared type;
- never changes an existing builder, Mailer, Email, Session, or configured factory.

### 2. `ConfigLoader` becomes an instance resolver

A static `builder()` convenience is acceptable because it creates an ordinary object and owns no configuration state. The resolver accepts ordered `ConfigSource` instances, merges raw winning values, validates them with a typed schema, and returns a new snapshot.

Later sources win. The conventional source order remains:

```text
simplejavamail.properties < environment variables < system properties < Java builder calls
```

Custom applications may choose another explicit order. A Spring Environment is treated as one already-ordered source so Spring's own precedence, placeholders, profile handling, and secret-decryption wrappers are not second-guessed by another raw system/environment pass.

### 3. `SimpleJavaMail` is the configured builder factory

`SimpleJavaMail` is a working type name, not a committed package choice. It cannot be added to the root `org.simplejavamail` package because `core-module` already owns that package and doing so from `simple-java-mail` would create a JPMS split package. Step 3 must choose a new public package owned and exported only by the facade module, or choose a different final API name that fits an existing facade-owned package.

Working API shape:

```java
SimpleJavaMail mail = SimpleJavaMail.fromDefaults();

Mailer mailer = mail.mailerBuilder()
        .withSMTPServerPort(587)
        .buildMailer();

Email email = mail.emailBuilder()
        .startingBlank()
        .withSubject("Hello")
        .buildEmail();
```

`fromDefaults()` is the short path. It creates the same kind of immutable factory while implying the conventional Simple Java Mail sources and precedence:

```java
SimpleJavaMailConfig config = ConfigLoader.builder()
        .withClasspathResource("simplejavamail.properties")
        .withEnvironmentVariables()
        .withSystemProperties()
        .load();

SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
```

The exact equivalence, including ClassLoader choice, missing-resource behavior, scalar environment-name mapping, wildcard handling, blank handling, and `system > environment > classpath` precedence, is locked by Steps 2, 3, and 7. `fromDefaults()` never consults Spring's `Environment`. Spring creates a factory from its own context-local snapshot.

The factory is immutable and thread-safe. Each builder it returns is fresh and remains mutable and single-owner, just like builders are today.

The static `EmailBuilder` and `MailerBuilder` entry classes are removed in 10.0.0. Their fluent builder interfaces remain, but `SimpleJavaMail` becomes the only supported place to obtain a default-backed builder. Removing the old entries in the major release keeps one obvious API shape. Deprecating them in 10.0 and removing them in 10.1 would make a minor release source-incompatible; retaining them until 11.0 would leave two competing entry styles for another major cycle.

`fromDefaults()` lazily creates one immutable conventional factory. Applications that need deterministic startup timing, replacement configuration, multiple tenants, or more than one Spring context use an explicit configured factory.

### 4. Replacement means replacement

There is no live reload, watcher, or mutable compatibility facade. To change configuration, build a new snapshot, create a new configured factory and Mailer, route new work to it, then close the old Mailer after in-flight work finishes. Existing objects keep their original settings.

## Implemented public API decisions

Approval of the plan approves these decisions:

1. Remove the static mutable `ConfigLoader.loadProperties(...)`, `getProperty(...)`, `hasProperty(...)`, and `valueOrProperty(...)` behavior in 10.0.0. Do not preserve it behind another global singleton.
2. Retain `ConfigLoader.DEFAULT_CONFIG_FILENAME` and the existing property key names.
3. Retain the `ConfigLoader.Property` identifiers for migration and inspection, but back them with a property-specific typed schema.
4. Add immutable `SimpleJavaMailConfig`, ordered `ConfigSource` support, and an instance `ConfigLoader`.
5. Add a configured `SimpleJavaMail` factory that creates all builder variants, supplies configured conversion paths, and offers `fromDefaults()` for the conventional source recipe.
6. Remove the static `EmailBuilder` and `MailerBuilder` entry classes in 10.0.0. Keep their fluent builder interfaces and migrate all examples and internal callers to a `SimpleJavaMail` instance.
7. Remove mutable `TransportStrategy.SMTP.setOpportunisticTLS(...)` state. Add the replacement only to the regular Mailer-builder/config path, make `TransportStrategy` stateless, and specify that the option affects plain `SMTP` only. It does not weaken or alter `SMTP_TLS`, `SMTP_OAUTH2`, or `SMTPS`.
8. Keep all existing property names and the normal file, environment, and system precedence. Do not rename legacy `javaxmail` keys or the misspelled `mustbesuccesful` key as part of this refactor.
9. Keep Java 8, classpath, JPMS, OSGi/Karaf, plain Spring, Spring Boot 2.7 metadata, CLI, and optional-module compatibility.
10. Add no runtime dependency and no new configuration module. The immutable model and loader belong in `core-module`; the configured builder factory belongs in `simple-java-mail`; the Spring adapter stays in `spring-module`.
11. Correct the crossed embedded-image `outside.base.url` and `outside.base.classpath` identifiers. This is a required 10.0 migration note, not an implementation detail that may disappear during cleanup.

## Source-resolution contract

| Concern | 9.x behavior to preserve or replace deliberately |
| --- | --- |
| `fromDefaults()` recipe | Load `simplejavamail.properties` when present, then environment variables, then system properties; never inspect Spring |
| Ordinary precedence | System property beats environment variable, which beats supplied/file value |
| Java builder precedence | Explicit builder calls beat snapshot defaults |
| Blank values | Blank values are absent and allow lower-priority values to win |
| Missing classpath file | Environment and system sources still work |
| `Properties` object values | Correctly typed non-String values remain supported and are validated against the target property |
| Unknown supplied keys | Fail with the source name and property key, without printing secret values |
| Unknown system/environment keys | Ignore unrelated process settings |
| Wildcard Session properties | Preserve file, environment, system precedence and copy the resulting map |
| Wildcard cluster settings | Preserve aliases, direct UUID keys, partial per-cluster overrides, and global fallback behavior |
| Input streams | Preserve the documented consume-and-close behavior unless Step 3 explicitly changes it and records the migration |
| Source mutation after load | Has no effect on the snapshot |
| Multiple snapshots | Can coexist and be used concurrently without shared state |

One existing defect needs an explicit decision rather than accidental preservation: the enum identifiers for embedded-image `outside.base.url` and `outside.base.classpath` are crossed. Direct property-file behavior currently compensates for the crossed names, while Spring's manual mapping can swap the two settings. Step 1 locks the actual behavior and Step 3 records the intended correction and migration note.

## Expected public migration surface

| Current 9.x use | 10.0 direction |
| --- | --- |
| `EmailBuilder.startingBlank()`, `copying(...)`, `replyingTo(...)`, and related entry calls | Use `SimpleJavaMail.fromDefaults().emailBuilder()` or an application-owned configured factory, then call the same builder operation |
| `MailerBuilder.withSMTPServer(...)`, `usingSession(...)`, `buildMailer()`, and related entry calls | Use `SimpleJavaMail.fromDefaults().mailerBuilder()` or an application-owned configured factory, then call the same builder operation |
| `ConfigLoader.loadProperties(source, false)` followed by default entry classes | Build a `SimpleJavaMailConfig` from explicit sources, then use `SimpleJavaMail.withConfig(config)` |
| Repeated `loadProperties(..., true)` calls | Add sources to one ordered loader, or load a new snapshot using the old snapshot as a low-priority source |
| Retaining the map returned by `loadProperties(...)` and observing later global changes through that live view | Retain the detached `SimpleJavaMailConfig`; later loads produce different snapshots and never alter it |
| Static `ConfigLoader.get...` calls | Read the injected `SimpleJavaMailConfig` snapshot |
| Mutating config and keeping an old builder | Build a new snapshot and request a fresh builder from the new configured factory |
| `TransportStrategy.SMTP.setOpportunisticTLS(false)` | Set the replacement option on the regular Mailer builder; it applies only when the effective strategy is plain `SMTP` |
| Injecting the Spring `defaultMailerBuilder` singleton | Inject the context-local `SimpleJavaMail` factory and request a fresh builder from it |
| Relying on Spring to mutate global defaults | Use the context-local config, factory, and Mailer beans |
| `MailerImpl.createMailSession(...)` relying on globals | Use an overload with explicit resolved Session settings, or the configured factory |

The 10.0 migration guide must contain copyable before/after examples for each row. It must cover only real public breaks and non-obvious behavior changes, not internal class movement.

## Test-driven working method

Every production step follows this order:

1. Add or move a focused test that fails for the missing contract.
2. Make the smallest production change that passes it.
3. Remove the replaced static path.
4. Run the focused module gate.
5. Run the cross-module gate named by the step.
6. Record concrete evidence in the step file before marking it done.

Characterization tests assert observable values and lifecycle timing, not private field names. New tests must not clear JVM-wide system properties, reflect into a static registry, or depend on test execution order. Two-config and two-Spring-context tests run concurrently where practical.

Status vocabulary: `Proposed`, `Planned`, `In progress`, `Blocked`, `Done`.

## Phases and steps

### Phase 1: Freeze the contract

- [x] [1. Inventory and characterize every configuration read point](phase-1-contract/01-inventory-and-characterize-read-points.md)
- [x] [2. Lock source precedence, parsing, wildcard, and failure behavior](phase-1-contract/02-lock-source-resolution-contract.md)
- [x] [3. Freeze the public API and migration matrix](phase-1-contract/03-freeze-public-api-and-migration-matrix.md)

### Phase 2: Build the configuration core

- [x] [4. Introduce the property-specific typed schema](phase-2-configuration-core/04-introduce-typed-property-schema.md)
- [x] [5. Build the immutable configuration snapshot](phase-2-configuration-core/05-build-immutable-config-snapshot.md)
- [x] [6. Replace global loading with ordered instance sources](phase-2-configuration-core/06-build-instance-loader-and-sources.md)
- [x] [7. Provide the immutable `fromDefaults()` context](phase-2-configuration-core/07-provide-conventional-default-context.md)

### Phase 3: Propagate one snapshot through the runtime

- [x] [8. Add the configured SimpleJavaMail builder factory](phase-3-runtime-propagation/08-add-configured-simple-java-mail-factory.md)
- [x] [9. Propagate configuration through email builders and conversion](phase-3-runtime-propagation/09-propagate-email-builders-and-conversion.md)
- [x] [10. Propagate configuration through mailer builders](phase-3-runtime-propagation/10-propagate-mailer-builders.md)
- [x] [11. Snapshot governance and security defaults](phase-3-runtime-propagation/11-snapshot-governance-and-security-defaults.md)
- [x] [12. Remove late Session and transport reads](phase-3-runtime-propagation/12-remove-late-session-and-transport-reads.md)
- [x] [13. Preserve batch, cluster, and CLI behavior](phase-3-runtime-propagation/13-preserve-batch-cluster-and-cli-behavior.md)

### Phase 4: Replace public and framework integration

- [x] [14. Rebuild Spring support around context-local beans](phase-4-integrations/14-rebuild-spring-support-around-beans.md)
- [x] [15. Remove static configuration and legacy builder entries](phase-4-integrations/15-remove-static-configloader-api.md)
- [x] [16. Close module, packaging, and generated-metadata boundaries](phase-4-integrations/16-close-module-and-packaging-boundaries.md)

### Phase 5: Migration and release proof

- [x] [17. Write migration notes and durable configuration documentation](phase-5-release/17-write-migration-and-configuration-docs.md)
- [x] [18. Run isolation, compatibility, and release gates](phase-5-release/18-run-isolation-compatibility-and-release-gates.md)

## Phase gates

- Phase 1 must finish before new public types are implemented.
- Phase 2 must pass while the legacy runtime bridge remains temporarily green; no new production path may depend on it.
- Phase 3 is complete only when production code has no runtime read from mutable global configuration.
- Phase 4 is complete only when two Spring contexts and non-Spring configured factories can coexist in one JVM, and the legacy builder entries and Spring builder bean are gone.
- Phase 5 is complete only when the public API diff, migration examples, Java 8 build, modern-JDK build, CLI metadata, JPMS consumers, and website build all agree.

## Out of scope

- Automatic file watching or live reload.
- A built-in secret encryption format. Decryption remains the responsibility of the source or framework, such as Spring Environment integration.
- Renaming all historical property keys.
- Adding property-backed OpenPGP key configuration.
- Redesigning email default/override semantics beyond removing their global dependency.
- Redesigning the batch engine or connection-pool ownership model.
- Raising the Java, Spring, or Spring Boot baseline.
- Creating a new runtime module or adding a third-party configuration library.

## Completion definition

The refactor is complete when:

- no mutable static configuration registry or mutable transport-strategy setting remains;
- two differently configured factories can build and use Emails, Mailers, Sessions, governance, and batch settings concurrently without cross-talk;
- every runtime object is derived from one identifiable snapshot;
- source resolution is deterministic, property-specific, and secret-safe;
- Spring exposes context-local immutable configuration and factory beans without mutating global state;
- `SimpleJavaMail.fromDefaults()` is the only conventional builder entry and supports the classpath/environment/system setup without consulting Spring;
- `EmailBuilder`, `MailerBuilder`, and the Spring `defaultMailerBuilder` bean are absent;
- all intentional public breaks and non-obvious behavior changes have copyable 9.x-to-10.0 migrations;
- Java 8, classpath, JPMS, OSGi/Karaf, CLI, Spring, and the full Maven reactor pass.
