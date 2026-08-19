# Step 5: Build the immutable configuration snapshot

- Status: Done
- Depends on: Step 4
- Primary module: `core-module`
- New public type: `SimpleJavaMailConfig`
- New internal support: immutable resolved values and source-origin metadata

## Goal

Create the thread-safe value that every later builder and runtime object will consume. The snapshot represents configured values, not live sources and not per-Mailer runtime resources.

## Tests first

1. Construct two snapshots with conflicting SMTP, embedded-image, governance, Session-extra, and cluster values and assert independent reads.
2. Mutate every input collection after construction and assert the snapshot is unchanged.
3. Attempt to mutate returned extra-property and cluster maps and assert failure.
4. Verify nested maps and values are defensively copied where needed.
5. Verify `toString()` and diagnostic output redact every secret descriptor and do not include private key bytes or password text.
6. Verify typed and generic inspection methods follow the Step 3 contract.
7. Verify an empty snapshot is reusable and contains no random or runtime-derived defaults.
8. Verify the snapshot does not implement serialization unless Step 3 explicitly requires a secret-safe serialized form. The recommendation is not to serialize it.

## Implementation

1. Store resolved values in a final detached map keyed by property identifier.
2. Store source origin separately from values so diagnostics can report why a value won.
3. Provide typed string, integer, boolean, and generic inspection methods needed to migrate current public calls.
4. Provide immutable access to wildcard Session properties and cluster configurations.
5. Keep `null`/absence distinct from library defaults. Defaults such as a random cluster UUID, executor, validator, and default port remain builder responsibilities.
6. Add a package-level/internal accessor suitable for fast runtime seeding without exposing mutable structures.
7. Give the class a deliberately redacted `toString()` and document thread-safety.

## Acceptance criteria

- [x] All state is final and transitively immutable from the caller's perspective.
- [x] Source mutation after snapshot creation has no effect.
- [x] Two snapshots can be read concurrently without synchronization.
- [x] No library runtime default that must vary per builder is frozen into the snapshot.
- [x] Secrets are absent from `toString()`, logs, assertion descriptions, and conversion errors.
- [x] Public inspection methods have migration examples from the static getters.
- [x] `core-module` tests pass on Java 8 and a modern JDK.

## Completion evidence

- `SimpleJavaMailConfig` defensively copies scalar, wildcard, cluster, and provenance data and exposes immutable views only.
- Mutation, concurrency, detachment, typed inspection, empty-snapshot, and secret-redaction tests pass in both full reactors.
