# ADR 0003: Resolve configuration into an immutable, factory-owned snapshot

- Status: Accepted (retrospective record of implemented design)
- Recorded: 2026-09-16; historical dates below identify the underlying work
- Applies to: `codex/10.0.0`, including its unreleased API migration
- Implementation: Original decision committed in `fcf8c3b2`; the accepted 2026-10-07 named-failover extension is implemented and accepted under #755 for unreleased 10.0.0

## Unique pool-cluster aliases (accepted 22 October 2026)

Within one resolved configuration, distinct pool-cluster aliases must not resolve to the same UUID. Reject that ambiguity during snapshot loading with an error identifying both aliases and their UUID, instead of losing a partial configuration or lock through map replacement. Named aliases with an explicit UUID and direct UUID aliases remain supported independently; repeated leaves for the same alias retain normal ordered-source and lock resolution. Do not add cross-alias merging or optional pool semantics to the loader. [#830](https://github.com/bbottema/simple-java-mail/issues/830) tracks this identity-boundary clarification.

## Context and historical rationale

The old static `ConfigLoader` made independently configured Mailers share mutable process state. Construction order mattered because builders and completed objects consulted that state at different times. [Issue #693](https://github.com/bbottema/simple-java-mail/issues/693) explicitly identifies isolation, dependency injection, deterministic construction and replacement-object semantics as reasons to replace it.

Earlier defects show why source resolution also needed a single contract. [#538](https://github.com/bbottema/simple-java-mail/issues/538) and [#550](https://github.com/bbottema/simple-java-mail/issues/550) reported that system/environment configuration depended on a classpath properties file being present. [#685](https://github.com/bbottema/simple-java-mail/issues/685) documented wildcard extra Session properties following the reverse of ordinary precedence; [96604f52](https://github.com/bbottema/simple-java-mail/commit/96604f52ba0d396a6403b57f9efd163b713a11c9) corrected that in the older design. These are historical problems, not claims that the defects remain in 10.0.0.

[fcf8c3b2](https://github.com/bbottema/simple-java-mail/commit/fcf8c3b257900a02d31df82be1dd569391227b90), 19 August 2026, records the chosen implementation: replace static configuration and builder entry points with instance-scoped factories and immutable snapshots, and propagate that model through conversion, security, Spring, CLI and provider boundaries. The issue posed several possible API shapes; the commit and current code establish which shape was selected.

## Decision

### Schema-bound property keys (accepted 20 October 2026)

Ordinary Java reads use opaque invariant `ConfigKey<T>` values exposed by `ConfigKeys`: `config.get(ConfigKeys.SMTP_PORT)`
returns `Integer`, while `config.get(ConfigKeys.SESSION_TIMEOUT)` returns `Duration`. The key declaration selects the
authoritative schema parser and Java projection together; do not maintain a separate key/type catalogue that can drift.
Use the same invariant key in both `valueOrProperty` overloads, presence and provenance inspection. Remove the unsafe
caller-inferred generic getter and the parallel scalar-name getter families. Applications cannot construct arbitrary keys.

Retain `ConfigLoader.Property` as the textual/raw-integration identity. The raw heterogeneous map and source/lock storage
keep the parser representation and existing property-file units. In particular the six legacy integer-millisecond
properties project to Java `Duration` values without changing accepted text/types, signed integer bounds or whole-millisecond
precision. Existing ISO-duration properties retain their own precision and feature validation. Raw maps remain explicitly
dynamic; canonical Java reads use keys. Missing configured values, including aggregate maps, remain absent/null: snapshots
contain explicit configuration, not fabricated empty/default entries. Present wildcard maps are detached and immutable.

Keep explicit/configured/fallback precedence and supplied-object ownership. A supplied empty String is absent to the
selection helper; its existing treatment of whitespace, zero and false is retained. Schema reads do not reopen sources,
resolve runtime defaults, alter named isolation or weaken mandatory locks. Diagnostics retain their original source,
parser representation and redaction. See [the implementation ledger](https://github.com/bbottema/simple-java-mail/blob/e3fbaf777082955e6ea3d387b245254996d49505/docs/plans/10.0.0-typed-configuration-keys.md).

Use an ordered, instance-based `ConfigLoader` to sample configured sources and produce a detached `SimpleJavaMailConfig`. Sources are ordered from lower to higher priority; later non-blank values win, and only winning scalar values are parsed through the central `PropertySchema`. Apply the same ordering to scalar properties, extra Jakarta Mail properties and pool-cluster configuration; the loader resolves wildcard entries and parses cluster fields separately. Caller-supplied sources are strict by default; whole-process environment/system sources ignore unrelated names.

Pass the snapshot to `SimpleJavaMail.withConfig(...)`. That factory supplies fresh builders and a configured converter while retaining one configuration identity. Propagate the captured configuration through Email governance, Mailers, Sessions and optional modules instead of reopening a global loader in each consumer. Explicit builder values override captured defaults.

A supplied Java `Properties` source includes its inherited string defaults. Sample those defaults at load time, let direct entries win within
that source, and then apply the usual source ordering, type checks and locks. A typed direct entry still takes precedence over an inherited
string value. This does not make a general Map source inherit anything.

A built Mailer's operational configuration also owns its collection structures. Reusing a builder or editing an inspection result cannot change
that captured configuration. Copy property containers and trusted-host lists while retaining the identity of borrowed provider objects stored
as property values; configuration snapshots do not clone socket factories, callbacks or other application resources.

A caller-supplied Session provides its own connection baseline. Inherited ordinary SJM connection defaults do not replace it; explicit builder
customizations, configured additional Jakarta Mail properties and mandatory locks retain their respective roles. Resolve that distinction at
construction rather than using a strategy marker as an ownership flag. Effective inspection is supplied data too: a common socket timeout
can be absent when the Session's connect/read/write values differ or are unspecified. See [ADR 0015](0015-execution-views-and-transport-pooling.md#caller-supplied-session-baseline).

Keep `SimpleJavaMail.fromDefaults()` as a conventional lazy factory: classpath properties, then environment variables, then JVM properties, resolved once on first use. A change in configuration requires a new snapshot and replacement objects. The factory owns no Mailer resources; applications close the Mailers they create.

### Fail-fast validation and passive inspection

Clarified 21 October 2026: inherited rules must be coherent at their declared validation boundary. Builder precedence allows customization
of valid inherited configuration; it is not a mechanism for repairing an invalid inherited rule. Reject that rule when its owning feature
consumes it, rather than assuming a later Java setter or clear will make it usable. This gives configuration failures an early, predictable
location instead of making their visibility depend on the caller's subsequent customization path.

Apply ordered source resolution first. Individual files, environment variables and other source layers may contribute different parts of
a rule; they need not each contain a complete configuration. Keep the existing rule that only winning scalar values are parsed. Completeness
and cross-field coherence follow the feature's declared value boundary. For example, an inherited sending-limit count and period form one
rule: [ADR 0027](0027-factory-scoped-sending-limits.md#inherited-rate-rules-fail-fast) requires that pair to be coherent when creating the Mailer
builder, before Java customization. A property-backed count without its period is rejected there even if a later call would replace the pair.

Distinguish this baseline from unfinished Java choices. Builders may collect partial choices where their contract permits it; validation
belongs at the relevant configuration or construction boundary. Inspecting an already-created builder returns stored choices, including
absence, without resolving or validating them. [ADR 0031](0031-public-api-and-internal-construction.md#inspect-incomplete-configuration-without-building-it)
owns that inspection rule. Fail-fast validation does not justify throwing from a getter.

Validation remains with the feature owner and its established activation boundary. This clarification does not load optional modules merely
to inspect configuration, reject unused pool overrides, or move optional-feature semantics into core. The effective-feature validation rules
in [ADR 0006](0006-optional-modules.md#feature-semantics-stay-with-the-feature-implementation) remain applicable; partial supplied configuration
values do not become complete inherited rules merely because they share a configuration snapshot.

### Explicit inheritance and cohesive settings (accepted 19 October 2026)

Use `useConfigured...` to restore a captured property choice, `reset...` to select the published library fallback, and `clear...` to remove a
named optional registration. `useConfiguredEmailDefaults()` restores the captured template; `withoutEmailDefaults()` selects an empty ordinary
template while preserving mandatory locks. Explicit `withEmailDefaults(...)` remains whole-template replacement.

Keep Jakarta Mail debug and telemetry configuration in immutable core-owned `SessionDebugConfig` and `MailSendTelemetryConfig` values.
Unset fields inherit the owning factory snapshot; the values themselves do not consult configuration sources. Replacing a group replaces its
supplied choices and does not merge with the previous group. Value/builder getters expose those choices, including absence; operational
inspection exposes resolved groups. Track provenance privately so inherited absence does not overwrite a caller Session's baseline.
Selecting a debug output or telemetry integration does not enable that feature. Borrowed streams and SDKs are never closed by these values.

Represent Java socket/worker/queue/pool durations with `Duration`, including public cluster and standalone-pool configuration. These migrated
settings retain whole-millisecond precision and existing limits; reject fractional milliseconds and overflow instead of truncating. Property
keys and numeric millisecond inputs remain unchanged. Convert at the configuration/provider boundary, preserving zero and unset meanings.

### Advanced configuration groups (accepted 20 October 2026)

Keep ordinary SMTP setup and message composition directly discoverable. Move advanced Mailer choices into immutable core-owned
`ConnectionPoolConfig`, `AsyncExecutionConfig`, `SocksProxyConfig`, `SendingLimitsConfig`, `ConnectionConfig`, `TlsConfig`, and
`ValidationConfig` values with explicit application builders. Move advanced Email choices into `ImageResolutionConfig`,
`ContentEncodingConfig`, `EmailTemplateOptions`, and `SmtpDeliveryOptions`. Keep recipient metadata, resource-level encoding,
read receipts, and typed signing/encryption operations at their existing scopes.

A group assignment replaces all previously supplied choices for that group. Unset members inherit the captured factory configuration,
not the previous group. Inspection of supplied values retains absence; built-object inspection describes resolved choices. Removing
a local optional choice and restoring captured configuration retain their existing distinct meanings, as do mandatory locks and the
caller-supplied Session baseline. Group construction and inspection allocate no workers or connections and read no message content.
An explicit empty Java string remains a supplied group choice with the original setter's validation or clearing behavior.
It must not silently restore a captured value by applying the separate blank-property-source inheritance rule.
Per-cluster pool overrides retain their distinct fallback scope. Core validates representation; optional feature modules retain semantic validation.

State the caller-Session construction boundary on both the TLS value and its receiving builder method: custom SSL factory and
opportunistic-upgrade choices belong to Session construction. A caller-supplied Session must already carry those choices; unsupported
group assignments are rejected. Clear documentation of this boundary does not split the group or change Session ownership.

Remove moved scalar methods and their inspection/reset/clear companions from the supported Java root builders in the 10.0.0 migration;
do not retain deprecated aliases. Internal runtime and CLI adapters may retain scalar operations without exposing them on application contracts.
The asynchronous queue builder offers `rejectWhenFull()` or `waitForCapacity(Duration)`, keeping the admission wait and its policy together.
Waiting occurs on the submitting thread; it neither executes SMTP work there nor changes the total send deadline. Existing scalar properties
and CLI options retain their names and representations.

### Client-validation reporting

Use `ValidationConfig.Builder.withClientValidationWarningsOnly(boolean)` for the existing choice between warning about client-validation
findings and rejecting the message. A Boolean expresses these two outcomes without an additional enum. The name describes the actual
behavior: completeness, address and CRLF checks still run; enabling this choice changes how their findings are handled. Do not describe
this as disabling the checks or as changing which inputs the configured address validator accepts.

An unset choice inherits captured properties before the built-in `false` fallback. Explicit resetting selects that fallback; restoring
configured behavior restores absence. Applicable locks remain binding, and final-size enforcement and other preparation failures retain
their independent contracts. Preserve existing property names, CLI syntax and Boolean polarity while aligning Java setting and inspection
names with the warning-only meaning. This is a naming correction with unchanged validation behavior.

### Built-in defaults stay together on the key builders

Accepted clarification, 13 October 2026: publish Simple Java Mail's built-in fallback values as named constants on the relevant
Email or Mailer builder contract. Message-related defaults belong on `EmailStartingBuilder` or `EmailPopulatingBuilder`, according to
the existing API owner; operational defaults belong on `MailerGenericBuilder` or the relevant Mailer builder contract.
Users and maintainers should not have to search policy classes and implementation objects to discover the library's defaults.

This convention predates immutable snapshots. [eaa76777](https://github.com/bbottema/simple-java-mail/commit/eaa767778fa97f4f889de23c884af9abdba2d93c),
24 December 2017, moved Mailer construction and property defaults through builders and placed named timeout, thread-pool, proxy-bridge
and logging defaults on the generic Mailer builder. [68659327](https://github.com/bbottema/simple-java-mail/commit/686593277fa35905723d8c98a2366dee651c42e0),
9 September 2026, explicitly required declaring built-in defaults alongside existing builder defaults in the API expansion workflow.
The older commits establish the convention; this clarification records its discoverability rationale and its application to policy builders.

Dedicated policy builders remain useful for configuring a cohesive feature. Their initial values and reset methods reference the same
key-builder constants, as do property and CLI resolution. They do not become independent catalogues of defaults. Immutable policy/configuration
values validate supplied settings but do not resolve defaults, consult configuration sources, or expose default-producing factories.
Do not construct a throwaway default policy just to recover its fallback values.

A derived fallback still follows the caller's selected values: for example, fixed failover rechecks use the configured initial interval as
their maximum. Resolve that relationship rather than introducing an unrelated constant. Required user choices, such as a submission-retry
count and initial delay, stay required; centralizing defaults does not invent them. Validation bounds are also distinct from fallback values.

These are library fallback constants, not application-owned Email defaults/overrides or configured snapshot entries. Their central placement
does not change precedence, lock handling, optional-policy enablement, serialization, or the runtime owner of the setting.

[ADR 0034](0034-optional-opentelemetry-integration.md) applies this convention to the optional OpenTelemetry integration: its
activation and endpoint-reporting Booleans resolve from the initiating Mailer's captured configuration, with centralized key-builder defaults.
The loaded module binds a supplied or application-global SDK without reopening sources. A shared immutable integration receives separate
per-Mailer bindings. The typed adapter from [ADR 0006](0006-optional-modules.md#choose-the-input-boundary-for-the-caller-accepted-2026-10-16)
only wraps an existing foreign instance; neither it nor configuration getters resolve defaults or own SDK resources.

[ADR 0027](0027-factory-scoped-sending-limits.md) adds factory-local sending-limit history alongside, not inside, this immutable snapshot.
Named groups can coordinate participating Mailers without sharing their executors or connections. Two factories built from the same
snapshot still have independent histories; the snapshot and `OperationalConfig` contain settings, never mutable usage counters.

Framework adapters supply sources rather than another global registry. Spring's `Environment` resolves its own profiles, placeholders and source ordering before contributing values; Simple Java Mail does not overlay raw environment/system properties a second time. See [ADR 0005](0005-spring-integration-and-boot-compatibility.md).

[ADR 0025](0025-factory-scoped-locked-configuration.md) adds immutable restrictions under `simplejavamail.locked.`. Source precedence still applies
within each namespace; a locked declaration supplies the effective ordinary value and survives snapshot copying. All Mailers from the factory inherit
the restriction, without another template, global registry or mutable policy tracker.

### Accepted extension: named failover configuration (2026-10-07)

**Implemented and accepted under [#755](https://github.com/bbottema/simple-java-mail/issues/755). The extension targets unreleased 10.0.0.**
[ADR 0029](0029-smtp-submission-failover.md#accepted-named-mailer-configuration) records the accepted property-backed failover model:
capture named, independently configured Mailer definitions and their failover references through the same loader. Reuse existing property
types, source precedence, diagnostics and secret handling. References resolve from the immutable captured configuration, not through
runtime source reloads, Spring bean lookup or a process-global registry.

The primary's ordinary values do not supply missing credentials or Email templates to a named failover. Each definition resolves its own
normal builder defaults; mandatory message restrictions survive delegation under the accepted addendum to
[ADR 0025](0025-factory-scoped-locked-configuration.md#accepted-addendum-delegation-to-approved-failover-mailers-2026-10-07).
Normal builders do not gain permission to ignore their captured locks. Known missing references, cycles and incompatible locked values
are construction errors. `simplejavamail.mailers.<name>.<existing-property-tail>` holds each independent definition, and
`simplejavamail.failover.mailer` selects it. The same policy keys can occur inside a named definition for another relationship.

Named definitions contain configuration, not live Mailers or mutable counters. Factory/destination runtime history remains separate;
repeated construction must not accidentally reset recovery observations or multiply configured sending allowance. Independent factories
remain independent even with equal names and definitions. Reusing configuration does not itself share connections or executors.

The factory still owns no Mailer resources and gains no close contract. A primary owns private failover Mailers SJM constructs from definitions
and closes them after admitted work drains. Existing Mailers supplied by the application remain borrowed and independently closed.
Spring's normal default-Mailer lifecycle and CLI cleanup follow the same owner. ADR 0029 owns the construction/rollback/shutdown details
and future verification; this extension is not evidence that those paths are already implemented.

Application builder inspection reads stored choices, including absence, without constructing a throwaway configuration to resolve defaults.
Clearing an optional provider-resolved choice restores absence; it does not introduce a new hard-coded algorithm. Configuration construction
checks representation invariants. The owning runtime validates feature semantics and effective inherited combinations when that feature is used,
before creating its resources. Setting order remains independent of final validation. An optional module's rules must not run just because its
configuration is captured; [ADR 0006](0006-optional-modules.md#feature-semantics-stay-with-the-feature-implementation) defines that boundary.

## Alternatives and consequences

The issue explicitly considered retaining a static compatibility facade and passing configuration directly to builders. The selected factory makes ownership visible at the common entry point and reduces the chance that a converter or secondary builder silently uses another configuration. This explanation of the tradeoff follows the implementation; the history does not contain a formal rejection memo for each alternative.

Continuing to patch global timing rules would leave tenants, tests and application contexts coupled. Reading mutable sources on every operation would permit an existing Mailer to change behavior without being replaced. Snapshots avoid those effects at the cost of an intentional 10.0.0 migration and no automatic live reload. Static inbound conversion still uses the conventional factory; callers requiring another snapshot use that factory's converter.

The shared property schema and builder contracts are also the architectural basis for the [API expansion workflow](../API_EXPANSION_WORKFLOW.md). Adding a property means defining its type, propagation, diagnostic policy and applicable Spring/CLI surface once and checking their integration. The checklist itself is a maintenance process, not a separate runtime architecture decision. Message-policy scope and merging remain the separate decisions in [ADR 0001](0001-email-configuration-scopes-and-inheritance.md) and [ADR 0002](0002-email-defaults-and-overrides.md).

## Implementation evidence

- [ConfigLoader](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java), [SimpleJavaMailConfig](../../modules/core-module/src/main/java/org/simplejavamail/config/SimpleJavaMailConfig.java) and [PropertySchema](../../modules/core-module/src/main/java/org/simplejavamail/config/PropertySchema.java) implement resolution, copying and typed values.
- [SimpleJavaMail](../../modules/simple-java-mail/src/main/java/org/simplejavamail/api/SimpleJavaMail.java) records factory ownership and lazy defaults.
- [ConfigLoaderTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/config/ConfigLoaderTest.java) and [Spring context isolation tests](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SimpleJavaMailSpringContextIsolationTest.java) provide existing regression evidence. They were inspected as evidence, not rerun for this documentation change.
