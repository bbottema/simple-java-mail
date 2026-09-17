# ADR 0018: Generate the CLI from builder contracts and cache its metadata

- Status: Accepted, recorded retrospectively
- Decision recorded: 2026-09-16; not the original decision date
- Applies to: Existing CLI architecture and `codex/10.0.0`
- Implementation status: Existing mechanism with unreleased 10.0.0 hardening; concurrent Therapi-cache repair is present in the inspected working tree

## Context

A separately maintained command-line API and help text would duplicate the Java builder surface and drift as features change. Generating the CLI from builders solves that duplication but creates another cost: reflecting over the API, resolving runtime Javadocs and rebuilding the command model used to take seconds before a command could execute. Caching that model improves startup while making generated-artifact compatibility part of the architecture.

## Decision

Keep the builder API and its Javadocs authoritative for ordinary email/Mailer CLI options. Traverse explicit builder nodes, select CLI-compatible public methods, apply option overrides/exclusions and convert supported parameter values from strings. Use Therapi runtime Javadocs for method and parameter help, formatted for Picocli. Keep CLI-only routing, lifecycle and invocation choices outside the builder model where they do not configure an Email or Mailer.

Keep the CLI as a layer above the Java library in its own module. The library does not load a CLI implementation to send mail. A request invokes the same builders and sending contracts as a Java caller, with CLI converters taking responsibility for basic file/URL input handling.

Persist generated option metadata in `cli.data` and runtime-Javadoc lookups in `therapi.data`; include these generated artifacts in the CLI distribution and source control. Treat them as replaceable internal startup caches, not a public serialization format. Validate cache kind, format version, source-API fingerprint, bounded payload length and checksum before decoding. Regenerate missing/stale/invalid caches from the trusted local API, and tolerate a read-only classpath by retaining the regenerated model in memory.

Avoid serializers that need reflective access to JDK collection internals. Serialize method references with their explicit declaring-class/name/parameter identity and keep cache generation/consumption compatible with supported modern CLI JDKs. Neither Kryo nor Java object serialization is a daemon wire protocol.

Reuse immutable declared-option metadata, but build fresh mutable Picocli state for each request. The current working-tree Therapi cache uses `ConcurrentHashMap` even when loaded from a valid cache, because concurrent daemon requests can populate absent entries after invalidation. Persistence takes a sorted copy for deterministic output.

## Recorded reasoning and evolution

| Evidence | What it establishes |
| --- | --- |
| [#156](https://github.com/bbottema/simple-java-mail/issues/156) | Explicitly names the builder API as the single source of truth and requests generated commands for simple or convertible values. This is direct historical rationale, not reconstruction from the current mapper. |
| [Therapi explanation](https://github.com/bbottema/simple-java-mail/issues/156#issuecomment-416131919) | Runtime Javadoc was selected to remove duplicated method documentation previously copied into CLI annotations. |
| [34c13187](https://github.com/bbottema/simple-java-mail/commit/34c13187fdfb18327d0325226dbb9f1bebd658bd) | Moves the entry point into the CLI module and removes reverse loading because the CLI is a layer on top of the library. |
| [#323](https://github.com/bbottema/simple-java-mail/issues/323), [option-cache result](https://github.com/bbottema/simple-java-mail/issues/323#issuecomment-864412860), [Javadoc-cache result](https://github.com/bbottema/simple-java-mail/issues/323#issuecomment-864457296) | Startup cost motivated two caches: the option model and resolved Therapi method lookups. The reported measurements explain the motivation, not a current latency guarantee. Implemented by [6159983b](https://github.com/bbottema/simple-java-mail/commit/6159983bbe5dec55be8e95387441d1daa37404d4) and [c572ef9d](https://github.com/bbottema/simple-java-mail/commit/c572ef9d590d944fa919d7b5e82982282ffe47d0). |
| [e7af6c71](https://github.com/bbottema/simple-java-mail/commit/e7af6c71b7c21c7539b60d09f54a712644bbb126) | Stops ignoring the two binary files because regenerating them during CircleCI builds exhausted available CPU/memory. This explains why derived metadata is committed. |
| [#583](https://github.com/bbottema/simple-java-mail/issues/583), [a6479f5c](https://github.com/bbottema/simple-java-mail/commit/a6479f5c4ce438a1e31f216475eb8a5704980f27) | Java 25 startup exposed illegal reflective collection access. The fix removes the offending serializers, writes method references directly and disables Kryo unsafe access. |
| [131bdf52](https://github.com/bbottema/simple-java-mail/commit/131bdf52a2103d3e6cd62c4e9f33eec32bd1ce41), [daemon metadata step](../../02_CLI_DAEMON_IMPROVEMENT_PLAN/phase-1-contract-and-build/04-modernize-cli-bootstrap-and-generated-metadata.md) | A broad reflection compatibility scan caused a heap blowup; explicit parameter compatibility replaced it. Later daemon work added cache envelopes, deferred bootstrap loading and cross-JDK generation checks. |

The cache concurrency change is an uncommitted implementation fact, explained by its source comment and the [generation and verification procedure](../../DEVELOPMENT.md#generated-cli-metadata). It is not attributed to an earlier commit or GitHub resolution.

## Alternatives and consequences

Hand-maintained commands and duplicated help were explicitly displaced by the #156 direction. Rebuilding all metadata on every launch was explicitly displaced by #323. The daemon plan retained Kryo only after modern-JDK compatibility checks, with a declarative-descriptor replacement as its documented fallback; it did not promise indefinite compatibility for serialized object graphs.

The chosen approach makes Java API evolution affect CLI option generation. Exclusions, converters and complete parameter Javadocs are part of the public feature workflow. Not every Java object or overload has a sensible CLI representation, so generated parity is intentionally selective. `@Cli.Optional` is the CLI argument contract; Java nullability has its separate meaning.

The API fingerprint detects signature/annotation changes, not Javadoc prose changes. Documentation-only edits still require metadata regeneration. A checksum detects corruption and mismatch; it is not authentication or permission to load untrusted serialized metadata. Committed binaries must be regenerated alongside relevant source changes and checked on the supported CLI runtime lanes.

## Implementation anchors

- [BuilderApiToPicocliCommandsMapper](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapper.java), [CliSupport](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java), [TherapiJavadocHelper](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/therapijavadoc/TherapiJavadocHelper.java)
- [CliMetadataCache](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/serialization/CliMetadataCache.java), [CliSourceApiFingerprint](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSourceApiFingerprint.java), [SerializationUtil](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/serialization/SerializationUtil.java)
- [API expansion workflow](../../API_EXPANSION_WORKFLOW.md), [development workflow](../../DEVELOPMENT.md), [ADR 0019](0019-local-cli-daemon.md)
