# ADR 0009: Keep API nullability separate from CLI optionality

- Status: Accepted (retrospective record of implemented design and retained build configuration)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Public builder contracts, CLI metadata, and the 10.0.0 build
- Implementation status: Explicit CLI optionality implemented; null instrumentation remains configured but disabled by default

## Context

Java API nullability and command-line argument optionality answer different questions. A parameter can accept Java `null` without necessarily being omittable from a command's positional arguments. Earlier CLI generation inspected `@Nullable` through reflection, which forced the project to maintain runtime-retained copies of JetBrains annotations.

[#165](https://github.com/bbottema/simple-java-mail/issues/165) records the historical search for maintained, lightweight annotations compatible with the project's Java baseline and analysis tools. Its [final resolution](https://github.com/bbottema/simple-java-mail/issues/165#issuecomment-552139049) chose a runtime-retention fork specifically because CLI generation needed reflective access. This is the historical reasoning, not a claim that every compatibility concern in the original issue remains valid today.

## Decision

Use official `org.jetbrains:annotations` for Java/API nullability. Use the separate runtime-retained `@Cli.Optional` annotation for CLI argument omission, and consult it consistently in both option generation and command consumption. Add both annotations when both contracts apply; one does not imply the other.

Retain the build's ability to instrument nullability contracts, with explicit exclusions for assertion helpers and `ServerReply`, but record its actual state: the root POM sets `se.eris.notnull.instrument=false`. The configured `instrument` and `tests-instrument` goals therefore do not establish default runtime enforcement in this checkout. This ADR neither enables instrumentation nor replaces explicit validation in production code.

## Rationale and evidence

[#616](https://github.com/bbottema/simple-java-mail/issues/616) is an unusually explicit architectural record. It identifies the coupling as brittle, chooses official annotations plus dedicated CLI metadata, and rejects preserving or simply renaming the fork. The trigger was #610's scanner mapping of the tiny annotation artifact to JetBrains Runtime; the issue explains that these are different products, not an established vulnerability in the annotations.

[212b2c62](https://github.com/bbottema/simple-java-mail/commit/212b2c62d732b46e0d733b9ad6e40777a277dbcc) implements the separation and records coverage of omitted and supplied optional arguments. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/616#issuecomment-4879390154) confirms that both option declaration and command consumption changed and that the fork left the dependency graph.

Runtime instrumentation has a separate history. [6031bffa](https://github.com/bbottema/simple-java-mail/commit/6031bffa9f4c62f6fe0baf1d29c456c3adb2740b) explicitly disabled the then-used not-null plugin because it did not work with JDK 11+. [cd7ded86](https://github.com/bbottema/simple-java-mail/commit/cd7ded8629073f82fa70529b5619917c2353b573) later inlined the parent configuration and retained the disabling property. Current [pom.xml](../../pom.xml) still declares it. The installed 1.1.1 plugin descriptor maps that property to the boolean `instrument` parameter for both goals. The old incompatibility explains the historical disabling; it does not prove the current plugin is incompatible, and no re-enablement decision was found.

## Alternatives

**Continue reflecting on runtime-retained `@Nullable`** was the prior design and is explicitly superseded by #616. **Rename the fork to placate the scanner** was explicitly avoided there because it would preserve the semantic coupling. **Assume source annotations provide runtime checks** does not describe the current build; enforcement depends on actual generated checks or explicit validation. Choosing a different annotation ecosystem or re-enabling instrumentation is outside this record.

## Consequences

API analysis uses a standard dependency, while the CLI owns its distinct omission rules. There is some intentional duplication when a parameter is both nullable and optional, but the contract is visible rather than inferred. A builder change can require updating annotations, Javadocs, CLI metadata, and omission tests independently.

Instrumentation must not be described as an unconditional runtime guarantee. Any future re-enablement needs a separately verified build change, including compatibility with the selected JDK, generated assertions, and other instrumentation. Changing a nullability annotation is still an API contract change even when bytecode enforcement is disabled.

## Implementation anchors

[Cli.Optional](../../modules/core-module/src/main/java/org/simplejavamail/api/internal/clisupport/model/Cli.java), [BuilderApiToPicocliCommandsMapper](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapper.java), and [CLI implementation](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport) define CLI use. The [root POM](../../pom.xml) owns annotation dependencies, instrumentation goals, exclusions, and the disabling property. This documentation pass inspected those contracts and plugin metadata; it did not alter or rerun the instrumenter.
