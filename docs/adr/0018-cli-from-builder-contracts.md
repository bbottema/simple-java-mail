# ADR 0018: Generate the CLI from builder contracts and cache its metadata

- Status: Accepted, recorded retrospectively
- Decision recorded: 2026-09-16; not the original decision date
- Applies to: Existing CLI architecture and `codex/10.0.0`
- Implementation status: Existing mechanism; Maven-managed generation and read-only startup accepted for unreleased 10.0.0 on 1 November 2026

## Context

A separately maintained command-line API and help text would duplicate the Java builder surface and drift as features change. Generating the CLI from builders solves that duplication but creates another cost: reflecting over the API, resolving runtime Javadocs and rebuilding the command model used to take seconds before a command could execute. Caching that model improves startup while making generated-artifact compatibility part of the architecture.

## Decision

### Internal validation entry point (accepted 7 November 2026)

The CLI may invoke an internal builder-adapter operation to run governed validation without constructing sending resources. Exclude that
operation from generated options: the existing `validate` command and ordered Email/Mailer options remain the application-facing syntax.
Reuse the normal captured configuration, preparation rules and profile calculation rather than a separately maintained validation API.
The main implementation owns preparation; the core internal CLI contract only crosses the existing module boundary. [#488](https://github.com/bbottema/simple-java-mail/issues/488)
and [ADR 0014](0014-send-time-rehearsal.md#cli-preparation-without-sending-resources-accepted-7-november-2026) own the behavior and lifetime.

### Recognize help options without changing ordinary values (accepted 22 October 2026)

Help belongs to the selected generated command model. An ordinary subject, body, header or file value that ends in
`--help` remains literal, and later builder options keep their order and effect. Stop before later arguments only
when an actual registered help option is supplied. Keep standard and option-specific help convenient, including
ignoring malformed input after help, without invoking builder methods, reading content files or acquiring a Mailer.
Existing parser diagnostics before help and the parser's interpretation of a standalone registered option remain
authoritative. Do not introduce a second escaping syntax. [#841](https://github.com/bbottema/simple-java-mail/issues/841)
tracks this correction.

Daemon selection follows the same distinction: help stays local, while literal values retain the requested execution
mode. Ordinary routed commands and lifecycle operations keep the thin bootstrap. Only an option-shaped help candidate
needs generated-model recognition before routing; this trades a rare client-side metadata load for consistent option
meaning, without invoking message builders or reading content.

### Daemon selectors follow help ordering (accepted 28 October 2026)

Apply the registered-help cutoff to later daemon selectors as well as generated options. A malformed or conflicting selector after
genuine standard or option-specific help is ignored. Invalid selectors before help or without help retain their diagnostics. Preserve
literal values and end-of-options meaning, keep help local, and do not construct a Mailer or start a daemon for help. Retain bounded
argument-file expansion and thin ordinary routing. [#488](https://github.com/bbottema/simple-java-mail/issues/488) tracks this accepted
correction for unreleased 10.0.0.

### Removed execution options follow help ordering (accepted 27 October 2026)

Apply the existing registered-help cutoff before checking removed execution-mode options. A removed option after standard or option-specific
help is among the later arguments that help deliberately ignores; the same option before help or by itself retains its migration error.
This follows the selected command model's existing help recognition, including literal values and end-of-options behavior. Keep argument-file
expansion and local/daemon routing under their existing boundaries, and do not execute builders, read message content or acquire a Mailer for help.
No public option, additional parser or metadata format is introduced. [#734](https://github.com/bbottema/simple-java-mail/issues/734) tracks
this correction for unreleased 10.0.0.

### Daemon selector spellings inside values (accepted 28 October 2026)

Tokens consumed as builder-option values remain literal even when they spell a daemon mode or instance selector. Genuine selectors retain
their syntax, conflicts and diagnostics. Preserve parser-owned argument boundaries, repeated calls, help ordering, end-of-options and
bounded one-time argument-file expansion across local and daemon execution. Ambiguous selector-shaped arguments may consult the existing
command model without invoking builders or constructing Mailers; ordinary routing and lifecycle commands retain their lightweight bootstrap
under [ADR 0019](0019-local-cli-daemon.md). No new escaping syntax or public API is introduced.
The correction is implemented for unreleased 10.0.0; [#488](https://github.com/bbottema/simple-java-mail/issues/488) records development-branch delivery.

### Retired execution spellings as literal values (accepted 27 October 2026)

Picocli's selected command model determines which tokens are consumed as values and which remain unmatched options. A subject, body or
header value matching a retired execution spelling such as `--mailer:async=false` remains literal. Apply the migration diagnostic to actual
unmatched retired options; do not reserve their spelling throughout message content or add an escaping convention.

Keep the existing help cutoff: a genuine help request ignores later arguments, while a retired option before help or supplied alone retains
its migration diagnostic. Unrelated parser diagnostics, repeated builder calls and one-shot/daemon execution keep their existing contracts.
Use the installed Picocli parser's unmatched-argument information without adding a second option/value parser or changing its accepted syntax.
This correction is accepted and implemented for unreleased 10.0.0 under [#734](https://github.com/bbottema/simple-java-mail/issues/734); the issue tracks delivery.

### Preserve each supplied builder call (accepted 22 October 2026)

Repeated CLI builder options represent separate invocations in command-line order. Keep each occurrence's arguments together, including omitted
optional arguments, rather than combining occurrences into one call or deduplicating them by option name. Repeated additions retain all values;
replacement and clear/reset operations follow the same ordered semantics as the corresponding Java builder calls. This does not rename options,
change units or alter mailbox interpretation and exact-envelope validation. [#836](https://github.com/bbottema/simple-java-mail/issues/836) tracks
this correction, implemented and reviewed locally for unreleased 10.0.0. The issue remains open for delivery.

Accepted consolidation, 20 October 2026: extend the internal scalar adapters to cover all settings moved into the advanced Mailer and
Email groups. Java grouping does not change existing CLI option names, units or invocation order. Generate adapter documentation from
the same feature contracts and retain numeric-duration bridges; do not expose group-construction objects as command-line arguments.

The accepted 19 October Java API grouping retains existing scalar debug/telemetry options and numeric millisecond timeout arguments through
`CliMailerConfigurationBuilder`, an internal adapter implemented by the main builder. It delegates into `SessionDebugConfig`,
`MailSendTelemetryConfig` and `Duration` choices; it does not restore removed flat methods to the application Java interfaces. Include this
adapter in CLI roots and regenerate both metadata caches. Existing properties keep their names and millisecond units. Runtime handlers,
factories, ID suppliers, streams and telemetry integrations remain Java-only.

Keep the builder API and its Javadocs authoritative for ordinary email/Mailer CLI options. Traverse explicit builder nodes, select CLI-compatible public methods, apply option overrides/exclusions and convert supported parameter values from strings. Use Therapi runtime Javadocs for method and parameter help, formatted for Picocli. Keep CLI-only routing, lifecycle and invocation choices outside the builder model where they do not configure an Email or Mailer.

Keep the CLI as a layer above the Java library in its own module. The library does not load a CLI implementation to send mail. A request invokes the same builders and sending contracts as a Java caller, with CLI converters taking responsibility for basic file/URL input handling.

Clarification under [#867](https://github.com/bbottema/simple-java-mail/issues/867), accepted 26 October 2026: one registered conversion owns each
source/target type pair. Message-file conversion supports EML and Outlook MSG through content-based dispatch, preserving extensionless and renamed
inputs. Registering separate `String` to `MimeMessage` converters must not let the last registration disable the other format. Generated option
witnesses exercise conversion and binding before feature readback, independently of metadata membership.

### Maven-managed metadata (accepted 1 November 2026)

Generate `cli.data` and `therapi.data` as part of the ordinary Maven CLI build, after the API has been compiled and before CLI tests run.
Keep reusable results under `modules/cli-module/.build-cache/metadata/`, outside Maven's normal build output and ignored by Git. Do not keep
generated binaries in source control or require developers to remember a separate generation command. The build uses the same repository
configuration for every developer, without AI tooling, personal scripts or machine-specific setup.

Choose a cache entry by the contents of the generation inputs, including compiled contracts and Javadoc, generation/serialization code and
resolved dependencies. Ignore archive timestamps and generated output when calculating the key, so an unchanged clean build can reuse its
entry. Only reuse a complete validated pair. Stage a new pair separately and mark it complete after both files have been checked. Copy the
selected pair into the normal build output for packaging; source directories and the invocation's working directory are not cache locations.

Release builds force fresh generation even when a reusable entry exists. Check the finished JAR and standalone distributions in new JVMs
before publication, including builds that skip ordinary unit tests. A check must use the packaged resources and must not share the generator's
already loaded model. Existing binary codecs and integrity envelopes remain internal formats.

Normal CLI execution only loads packaged metadata. Validate cache kind, format version, source-API fingerprint, bounded payload length and
checksum before decoding. Missing, damaged, incompatible or incomplete metadata produces a clear installation/build error; startup never
regenerates commands, fills missing Javadoc entries or writes replacement files. Keep loading lazy so thin daemon control/routing paths do not
pay for a command model they do not need. This replaces the earlier runtime-recovery policy and follows the performance goal of #323.

Avoid serializers that need reflective access to JDK collection internals. Serialize method references with their explicit declaring-class/name/parameter identity and keep cache generation/consumption compatible with supported modern CLI JDKs. Neither Kryo nor Java object serialization is a daemon wire protocol.

Reuse immutable declared-option and Javadoc metadata, but build fresh mutable Picocli state for each request. The generator uses its own
documentation lookup and takes a sorted copy for deterministic persistence. Runtime lookup does not mutate or refill the generated metadata.

### CLI option value spelling (clarified 28 October 2026)

Documentation follows the generated command model's existing value separator. Ordinary builder options use separate option and value
tokens, including `--mailer:withMailSendTimeout PT30S`; the corresponding property assignment retains `=`. The documentation correction
under [#726](https://github.com/bbottema/simple-java-mail/issues/726) changes no parser behavior, option name, value units or public Java API.

## Recorded reasoning and evolution

| Evidence | What it establishes |
| --- | --- |
| [#156](https://github.com/bbottema/simple-java-mail/issues/156) | Explicitly names the builder API as the single source of truth and requests generated commands for simple or convertible values. This is direct historical rationale, not reconstruction from the current mapper. |
| [Therapi explanation](https://github.com/bbottema/simple-java-mail/issues/156#issuecomment-416131919) | Runtime Javadoc was selected to remove duplicated method documentation previously copied into CLI annotations. |
| [34c13187](https://github.com/bbottema/simple-java-mail/commit/34c13187fdfb18327d0325226dbb9f1bebd658bd) | Moves the entry point into the CLI module and removes reverse loading because the CLI is a layer on top of the library. |
| [#323](https://github.com/bbottema/simple-java-mail/issues/323), [option-cache result](https://github.com/bbottema/simple-java-mail/issues/323#issuecomment-864412860), [Javadoc-cache result](https://github.com/bbottema/simple-java-mail/issues/323#issuecomment-864457296) | Startup cost motivated two caches: the option model and resolved Therapi method lookups. The reported measurements explain the motivation, not a current latency guarantee. Implemented by [6159983b](https://github.com/bbottema/simple-java-mail/commit/6159983bbe5dec55be8e95387441d1daa37404d4) and [c572ef9d](https://github.com/bbottema/simple-java-mail/commit/c572ef9d590d944fa919d7b5e82982282ffe47d0). |
| [e7af6c71](https://github.com/bbottema/simple-java-mail/commit/e7af6c71b7c21c7539b60d09f54a712644bbb126) | Stops ignoring the two binary files because regenerating them during CircleCI builds exhausted available CPU/memory. This explains why derived metadata is committed. |
| [#583](https://github.com/bbottema/simple-java-mail/issues/583), [a6479f5c](https://github.com/bbottema/simple-java-mail/commit/a6479f5c4ce438a1e31f216475eb8a5704980f27) | Java 25 startup exposed illegal reflective collection access. The fix removes the offending serializers, writes method references directly and disables Kryo unsafe access. |
| [131bdf52](https://github.com/bbottema/simple-java-mail/commit/131bdf52a2103d3e6cd62c4e9f33eec32bd1ce41), [daemon metadata step](https://github.com/bbottema/simple-java-mail/blob/2653e804838e14e4b10291bdb9863b2d9a1ff67c/02_CLI_DAEMON_IMPROVEMENT_PLAN/phase-1-contract-and-build/04-modernize-cli-bootstrap-and-generated-metadata.md) | A broad reflection compatibility scan caused a heap blowup; explicit parameter compatibility replaced it. Later daemon work added cache envelopes, deferred bootstrap loading and cross-JDK generation checks. |

The [generation and verification procedure](../DEVELOPMENT.md#generated-cli-metadata) records the maintained developer and release workflow.

## Alternatives and consequences

Hand-maintained commands and duplicated help were explicitly displaced by the #156 direction. Rebuilding all metadata on every launch was explicitly displaced by #323. The daemon plan retained Kryo only after modern-JDK compatibility checks, with a declarative-descriptor replacement as its documented fallback; it did not promise indefinite compatibility for serialized object graphs.

The chosen approach makes Java API evolution affect CLI option generation. Exclusions, converters and complete parameter Javadocs are part of the public feature workflow. Not every Java object or overload has a sensible CLI representation, so generated parity is intentionally selective. `@Cli.Optional` is the CLI argument contract; Java nullability has its separate meaning.

The runtime API fingerprint detects signature/annotation changes, not Javadoc prose changes. The separate build-cache key includes compiler-produced Javadoc and generation inputs, so the Maven build refreshes documentation changes automatically. A checksum detects corruption and mismatch; it is not authentication or permission to load untrusted serialized metadata. Fresh packaged consumers on the supported CLI JDKs remain part of verification.

## Implementation anchors

- [BuilderApiToPicocliCommandsMapper](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapper.java), [CliSupport](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java), [TherapiJavadocHelper](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/therapijavadoc/TherapiJavadocHelper.java)
- [CliMetadataCache](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/serialization/CliMetadataCache.java), [CliSourceApiFingerprint](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSourceApiFingerprint.java), [SerializationUtil](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/serialization/SerializationUtil.java)
- [API expansion workflow](../API_EXPANSION_WORKFLOW.md), [development workflow](../DEVELOPMENT.md), [ADR 0019](0019-local-cli-daemon.md)

## Help before mail configuration (accepted 31 October 2026)

Render genuine help and version requests using the generated command model before loading conventional mail configuration.
Invalid mail settings must not prevent users from reading the help needed to correct them. Retain the existing parser-owned
option/value distinction, help cutoff and diagnostics for arguments before help; do not add a second argument parser. Executable
commands still load and validate their configuration before execution. Help remains local, without builder execution, content reads,
Mailer acquisition or daemon startup. Cache generation and persistence contracts remain unchanged. Implemented for unreleased 10.0.0
under [#488](https://github.com/bbottema/simple-java-mail/issues/488); the issue tracks delivery.
