# Step 4: Build the thin bootstrap and modernize generated metadata

- Status: Done
- Depends on: Steps 2 and 3
- Primary files: `SimpleJavaMail.java`, `CliSupport.java`, `CliDataLocator.java`, `SerializationUtil.java`, CLI metadata generator, `cli.data`, `therapi.data`, `DEVELOPMENT.md`

## Goal

Create a small entry path that can discover or manage a daemon without initializing the generated CLI model, while making CLI metadata generation and consumption a supported Java 17+ build instead of a stale JDK 8 exception.

The existing exception is the warning in `DEVELOPMENT.md` to generate `cli.data` only on JDK 8. It records historical Kryo serialization trouble around reflective `Method` data and Therapi scanning trouble on Java 12+. This step verifies the current code on the new CLI baseline before changing that instruction; it is not removed merely because the daemon uses Java 17.

## Tests first

1. Add a forked client test that invokes status, stop against an absent daemon, and a routed synthetic command while asserting `CliSupport`, builder mapping, ConfigLoader, Jakarta Mail, and Mailer classes were not initialized in the client.
2. Add a one-shot test proving existing help and command parsing still initialize and use the complete generated model.
3. Reproduce or retire the two failure modes named by the JDK 8-only warning, then generate `cli.data` and `therapi.data` on JDK 17 and the current release JDK and consume each artifact on both supported runtime lanes.
4. Regenerate metadata twice from identical inputs and characterize byte-for-byte determinism. If bytes differ, prove semantic equivalence and record the nondeterministic fields.
5. Corrupt, truncate, version-mismatch, and omit each metadata file and assert concise local diagnostics.
6. Benchmark thin bootstrap separately from full help and from one-shot command startup.
7. Assert no CLI metadata object or serializer is reused as the daemon wire protocol.

## Implementation

1. Replace the static import/delegation in the main class with a bootstrap that recognizes management and execution-routing syntax before referencing `CliSupport`.
2. Keep bootstrap parsing deliberately small. It may parse only version/help routing, daemon management, execution mode, instance name, and the remaining argument vector.
3. Load the existing full Picocli command tree only for local help or local command execution. The daemon loads it once after its endpoint is secured and ready state can be managed safely.
4. Add an explicit metadata format/version header and source-API fingerprint so stale metadata fails before reflective invocation.
5. Keep the current Kryo cache only if the modern-JDK generation matrix passes without illegal reflective access or cross-JDK incompatibility. Otherwise replace serialized reflective objects with explicit declaring-class, method-name, parameter-type, option, and documentation descriptors.
6. Only after the modern matrix passes, replace the JDK 8-only metadata-generation instructions with the supported generation command and wire it into the modern full-reactor and publication lanes.
7. Preserve `cli.data` as an internal startup cache. It is not a compatibility promise and is never accepted from an untrusted path.

## Performance contract

- Daemon status and management against an existing state directory must not pay full CLI-model initialization cost.
- A routed command client must perform only bootstrap, discovery, authentication, request write, response read, and output rendering.
- Full help may still load the generated model because it needs all builder-derived options.
- Performance gates compare distributions and medians from Step 1; they do not hard-code one developer machine's timing.

## Acceptance criteria

- [x] Management and routed-client paths do not initialize the full CLI model.
- [x] One-shot commands and full help retain their existing option surface.
- [x] Metadata generation and consumption pass on JDK 17 and the current release JDK.
- [x] Stale or corrupt metadata fails with a concise version/fingerprint diagnostic.
- [x] `DEVELOPMENT.md` contains one current, repeatable generation workflow.
- [x] No JDK 8 metadata-generation requirement remains.
- [x] No Kryo or Java object serialization type appears in the local protocol contract.
- [x] Thin-bootstrap measurements are recorded against the Step 1 baseline.

## Completion evidence

- `SimpleJavaMail` is a thin bootstrap; management, routed execution, and version paths do not initialize the generated Picocli command model.
- The metadata envelope validates kind, format version, API fingerprint, payload length, and checksum before use.
- Forced generation on Java 17 and Java 21 produced identical `cli.data` and `therapi.data` SHA-256 hashes, and `DEVELOPMENT.md` now describes the modern workflow.

## Stop condition

If modern-JDK metadata cannot be made deterministic and portable enough for a published CLI archive, replace the serialized graph with declarative descriptors before daemon IPC implementation begins.
