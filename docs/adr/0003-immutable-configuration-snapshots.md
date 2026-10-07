# ADR 0003: Resolve configuration into an immutable, factory-owned snapshot

- Status: Accepted (retrospective record of implemented design)
- Recorded: 2026-09-16; historical dates below identify the underlying work
- Applies to: `codex/10.0.0`, including its unreleased API migration
- Implementation: Original decision committed in `fcf8c3b2`; the accepted 2026-10-07 named-failover extension is planned, not implemented

## Context and historical rationale

The old static `ConfigLoader` made independently configured Mailers share mutable process state. Construction order mattered because builders and completed objects consulted that state at different times. [Issue #693](https://github.com/bbottema/simple-java-mail/issues/693) explicitly identifies isolation, dependency injection, deterministic construction and replacement-object semantics as reasons to replace it.

Earlier defects show why source resolution also needed a single contract. [#538](https://github.com/bbottema/simple-java-mail/issues/538) and [#550](https://github.com/bbottema/simple-java-mail/issues/550) reported that system/environment configuration depended on a classpath properties file being present. [#685](https://github.com/bbottema/simple-java-mail/issues/685) documented wildcard extra Session properties following the reverse of ordinary precedence; [96604f52](https://github.com/bbottema/simple-java-mail/commit/96604f52ba0d396a6403b57f9efd163b713a11c9) corrected that in the older design. These are historical problems, not claims that the defects remain in 10.0.0.

[fcf8c3b2](https://github.com/bbottema/simple-java-mail/commit/fcf8c3b257900a02d31df82be1dd569391227b90), 19 August 2026, records the chosen implementation: replace static configuration and builder entry points with instance-scoped factories and immutable snapshots, and propagate that model through conversion, security, Spring, CLI and provider boundaries. The issue posed several possible API shapes; the commit and current code establish which shape was selected.

## Decision

Use an ordered, instance-based `ConfigLoader` to sample configured sources and produce a detached `SimpleJavaMailConfig`. Sources are ordered from lower to higher priority; later non-blank values win, and only winning scalar values are parsed through the central `PropertySchema`. Apply the same ordering to scalar properties, extra Jakarta Mail properties and pool-cluster configuration; the loader resolves wildcard entries and parses cluster fields separately. Caller-supplied sources are strict by default; whole-process environment/system sources ignore unrelated names.

Pass the snapshot to `SimpleJavaMail.withConfig(...)`. That factory supplies fresh builders and a configured converter while retaining one configuration identity. Propagate the captured configuration through Email governance, Mailers, Sessions and optional modules instead of reopening a global loader in each consumer. Explicit builder values override captured defaults.

Keep `SimpleJavaMail.fromDefaults()` as a conventional lazy factory: classpath properties, then environment variables, then JVM properties, resolved once on first use. A change in configuration requires a new snapshot and replacement objects. The factory owns no Mailer resources; applications close the Mailers they create.

[ADR 0027](0027-factory-scoped-sending-limits.md) adds factory-local sending-limit history alongside, not inside, this immutable snapshot.
Named groups can coordinate participating Mailers without sharing their executors or connections. Two factories built from the same
snapshot still have independent histories; the snapshot and `OperationalConfig` contain settings, never mutable usage counters.

Framework adapters supply sources rather than another global registry. Spring's `Environment` resolves its own profiles, placeholders and source ordering before contributing values; Simple Java Mail does not overlay raw environment/system properties a second time. See [ADR 0005](0005-spring-integration-and-boot-compatibility.md).

[ADR 0025](0025-factory-scoped-locked-configuration.md) adds immutable restrictions under `simplejavamail.locked.`. Source precedence still applies
within each namespace; a locked declaration supplies the effective ordinary value and survives snapshot copying. All Mailers from the factory inherit
the restriction, without another template, global registry or mutable policy tracker.

### Accepted extension: named failover configuration (2026-10-07)

**Planned, not implemented under [#755](https://github.com/bbottema/simple-java-mail/issues/755).**
[ADR 0029](0029-smtp-submission-failover.md#accepted-named-mailer-configuration) records the accepted property-backed failover model:
capture named, independently configured Mailer definitions and their failover references through the same loader. Reuse existing property
types, source precedence, diagnostics and secret handling. References resolve from the immutable captured configuration, not through
runtime source reloads, Spring bean lookup or a process-global registry.

The primary's ordinary values do not supply missing credentials or Email templates to a named failover. Each definition resolves its own
normal builder defaults; mandatory message restrictions survive delegation under the accepted addendum to
[ADR 0025](0025-factory-scoped-locked-configuration.md#accepted-addendum-delegation-to-approved-failover-mailers-2026-10-07).
Normal builders do not gain permission to ignore their captured locks. Known missing references, cycles and incompatible locked values
are construction errors. The concrete snapshot representation and final new property names belong in the implementation plan.

Named definitions contain configuration, not live Mailers or mutable counters. Factory/destination runtime history remains separate;
repeated construction must not accidentally reset recovery observations or multiply configured sending allowance. Independent factories
remain independent even with equal names and definitions. Reusing configuration does not itself share connections or executors.

The factory still owns no Mailer resources and gains no close contract. A primary owns private failover Mailers SJM constructs from definitions
and closes them after admitted work drains. Existing Mailers supplied by the application remain borrowed and independently closed.
Spring's normal default-Mailer lifecycle and CLI cleanup follow the same owner. ADR 0029 owns the construction/rollback/shutdown details
and future verification; this extension is not evidence that those paths are already implemented.

## Alternatives and consequences

The issue explicitly considered retaining a static compatibility facade and passing configuration directly to builders. The selected factory makes ownership visible at the common entry point and reduces the chance that a converter or secondary builder silently uses another configuration. This explanation of the tradeoff follows the implementation; the history does not contain a formal rejection memo for each alternative.

Continuing to patch global timing rules would leave tenants, tests and application contexts coupled. Reading mutable sources on every operation would permit an existing Mailer to change behavior without being replaced. Snapshots avoid those effects at the cost of an intentional 10.0.0 migration and no automatic live reload. Static inbound conversion still uses the conventional factory; callers requiring another snapshot use that factory's converter.

The shared property schema and builder contracts are also the architectural basis for the [API expansion workflow](../API_EXPANSION_WORKFLOW.md). Adding a property means defining its type, propagation, diagnostic policy and applicable Spring/CLI surface once and checking their integration. The checklist itself is a maintenance process, not a separate runtime architecture decision. Message-policy scope and merging remain the separate decisions in [ADR 0001](0001-email-configuration-scopes-and-inheritance.md) and [ADR 0002](0002-email-defaults-and-overrides.md).

## Implementation evidence

- [ConfigLoader](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java), [SimpleJavaMailConfig](../../modules/core-module/src/main/java/org/simplejavamail/config/SimpleJavaMailConfig.java) and [PropertySchema](../../modules/core-module/src/main/java/org/simplejavamail/config/PropertySchema.java) implement resolution, copying and typed values.
- [SimpleJavaMail](../../modules/simple-java-mail/src/main/java/org/simplejavamail/api/SimpleJavaMail.java) records factory ownership and lazy defaults.
- [ConfigLoaderTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/config/ConfigLoaderTest.java) and [Spring context isolation tests](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SimpleJavaMailSpringContextIsolationTest.java) provide existing regression evidence. They were inspected as evidence, not rerun for this documentation change.
