# Step 15: Remove static configuration and legacy builder entries

- Status: Done
- Depends on: Steps 9 through 14
- Primary modules: `core-module`, `simple-java-mail`, `core-test-module`, `spring-module`
- Primary removals: mutable registry, static reads/writes, `EmailBuilder`, `MailerBuilder`, Spring builder bean, reflection helper, global-reset tests

## Goal

Cut the compatibility bridge only after every production path uses immutable snapshots. This is the point where issue #693 becomes structurally true rather than merely offering an alternative API.

## Tests first

1. Run a production-source scan that fails on static `ConfigLoader.get...`, `valueOrProperty...`, or `loadProperties...` calls.
2. Run a reflection/API test that fails if `RESOLVED_PROPERTIES` or another mutable static configuration store exists.
3. Run the Step 3 compile fixtures and confirm every intended 9.x removal is represented in the migration matrix.
4. Run the full configuration and Spring suites in parallel without a global reset helper.
5. Add a test that starts multiple configured factories and contexts after the conventional default has initialized.
6. Run API fixtures that fail if `EmailBuilder`, `MailerBuilder`, or the Spring `defaultMailerBuilder` bean remains available, and pass through `SimpleJavaMail.fromDefaults()` or an injected configured factory.

## Implementation

1. Remove `RESOLVED_PROPERTIES` and the static initializer from `ConfigLoader`.
2. Remove static mutation and lookup methods approved for removal in Step 3.
3. Keep only the instance loader API, immutable public constants/identifiers, and stateless helpers.
4. Delete `ConfigLoaderTestHelper` and every reflective write into ConfigLoader internals.
5. Rewrite tests to construct explicit snapshots and factories.
6. Remove `System.getProperties().clear()` and broad environment mutation from tests.
7. Remove legacy synchronization that existed only to guard the global map.
8. Delete the `EmailBuilder` and `MailerBuilder` entry classes and migrate all production, test, demo, classpath-consumer, and JPMS-consumer calls to a `SimpleJavaMail` factory.
9. Remove the Spring `defaultMailerBuilder` bean.
10. Remove temporary adapters and no-arg implementation constructors that select conventional defaults. Retain a constructor only if Step 3 recorded a cross-module SPI need, and require explicit configuration there.
11. Update imports and Javadocs so no public documentation suggests process-wide mutation or a second builder entry style.

## Required scan

The final production scan must find no:

- mutable static configuration collection;
- `ConfigLoader.loadProperties(...)` call;
- static `ConfigLoader.get...` or `valueOrProperty...` call;
- reflective config reset;
- mutable `TransportStrategy` setting;
- Spring-to-global copy.
- production reference to `EmailBuilder` or `MailerBuilder`;
- supported no-arg implementation constructor that selects defaults outside `SimpleJavaMail.fromDefaults()`;
- Spring `defaultMailerBuilder` bean.

Static immutable constants and the private lazy conventional context are allowed.

## Acceptance criteria

- [x] The mutable static registry and its initializer are gone.
- [x] `EmailBuilder`, `MailerBuilder`, and the Spring builder bean are gone.
- [x] `SimpleJavaMail.fromDefaults()` is the only supported conventional builder entry.
- [x] All production consumers compile only against snapshots/factories.
- [x] Test isolation needs no reflection or process-wide clearing.
- [x] Parallel configuration tests are stable across repeated runs.
- [x] Intentional public removals exactly match Step 3.
- [x] No hidden compatibility singleton can reintroduce mutation.
- [x] Focused core, facade, Spring, CLI, and batch tests pass.

## Completion evidence

- Production/API scans confirm the mutable registry, static loader operations, transport setter, `EmailBuilder`, `MailerBuilder`, no-argument default-selecting constructors, and Spring builder bean are absent.
- Tests no longer clear process properties or reflect into a registry. The broadly shared `ConfigLoaderTestHelper` name was retained, but its old global-reset behavior was replaced completely by pure factories for detached snapshots.
- Parallel isolation tests and the complete core, facade, Spring, CLI, and batch suites passed on both supported test JDKs.
