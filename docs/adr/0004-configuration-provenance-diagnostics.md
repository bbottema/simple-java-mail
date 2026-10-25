# ADR 0004: Capture safe configuration provenance during resolution

- Status: Accepted (retrospective record of implemented design)
- Recorded: 2026-09-16
- Applies to: Unreleased 10.0.0 configuration diagnostics
- Implementation: Committed in `fae85049`; separate from live Mailer diagnostics

## Context and historical rationale

An unexpected SMTP host or timeout can come from a file, environment variable, JVM property or framework source. Having the resolved value does not explain which source won. [#715](https://github.com/bbottema/simple-java-mail/issues/715) explicitly requests a structured debugging view and access to the snapshot captured by the factory, while excluding later builder overrides and runtime state. It also requires keeping credentials and unrelated process properties out of that report.

[fae85049](https://github.com/bbottema/simple-java-mail/commit/fae8504956bf07e107df04af9675c7e513e96f0c), 2 September 2026, implements the feature; the [completion comment](https://github.com/bbottema/simple-java-mail/issues/715#issuecomment-5508969390) demonstrates access through `SimpleJavaMail.getConfig().getDiagnostics()`. The issue is the explicit rationale; the commit's short subject alone does not explain all its design choices.

## Decision

Capture diagnostic entries while [ADR 0003's resolver](0003-immutable-configuration-snapshots.md) selects and parses winning values. Each entry retains the canonical property name, a display value, the source name, a redaction flag and a functional group. Include individual wildcard entries so extra Session properties and cluster-specific settings do not lose their own provenance.

Return an immutable, deterministically grouped report from `SimpleJavaMailConfig.getDiagnostics()`. `SimpleJavaMail.getConfig()` returns the actual captured snapshot; its raw value accessors remain available for programmatic use and are not the redacted report.

Declare type, diagnostic group and sensitivity in the central `PropertySchema`. Known sensitive properties are redacted; extra-property names are normalized and checked against sensitive markers. The schema rejects missing diagnostic metadata for a declared property. Composite sources may identify the underlying winner per property; Spring skips its synthetic aggregate when locating the diagnostic source without taking over Spring's value resolution.

Accepted 20 October 2026: ordinary snapshot reads use schema-bound `ConfigKey<T>` values, with their parsers/projections
registered by the authoritative `ConfigKeys` declarations. The Java view can expose `Duration` while raw source/lock storage
and diagnostics retain a legacy property's numeric millisecond representation. This does not resample sources, change
winning-source names, render secret values, or turn a raw map into the diagnostic API. Presence and source inspection take
the same typed key. See [ADR 0003](0003-immutable-configuration-snapshots.md#schema-bound-property-keys-accepted-20-october-2026).

Configuration rendering must protect known secrets before and after construction. Builders must not expose private-key bytes or passwords
through automatic rendering merely because the completed value already excludes them. Explicit programmatic accessors remain separate;
redaction is not a substitute for protecting configuration objects or serialized key material.

Apply the same extra-property sensitivity rules when rendering a Mailer, its builder or its operational configuration, including Session properties.
Automatic rendering must not expose a known password or token merely because it entered through an advanced Jakarta Mail property. Explicit
property access remains available for applications that need the actual values; sharing rendering rules does not change those accessors.

## Alternatives and consequences

Re-reading the environment when logging could describe a different state from the snapshot used to build a Mailer. Capturing provenance during resolution keeps those facts together. This causal explanation is derived from the resolver design and the issue's immutability requirement.

Using a raw map or `toString()` as the diagnostics API would make each application reimplement canonical names, grouping and redaction. Reporting the final effective Mailer in this same object would mix two different stages: captured input and subsequently overridden operational settings. The issue explicitly reserves that latter feature for a separately named API.

Diagnostics deliberately omit absent properties, generated runtime defaults, later builder calls, caller-owned Session internals and executor/pool/transport state. Consequently the report answers where factory input came from, not every question about an active Mailer.

Extra-property redaction is a name-based policy in the current implementation, not a general content classifier. An arbitrary innocently named extension property can carry information the schema cannot recognize. Source names are caller-supplied diagnostic identifiers. Do not interpret this record as a stronger sanitization guarantee than the code provides.

## Implementation evidence

[ADR 0025](0025-factory-scoped-locked-configuration.md) extends this same report to winning locked declarations. Entries retain
`simplejavamail.locked.` in the displayed key, their own source and the ordinary property's type/group/redaction policy. The corresponding
raw getter returns the effective configured value. Rejected customization names the lock and source, never the competing values.

- [ConfigLoader](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java) creates entries from resolved winners.
- [ConfigDiagnostics](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigDiagnostics.java), [ConfigPropertyDiagnostic](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigPropertyDiagnostic.java) and [PropertySchema](../../modules/core-module/src/main/java/org/simplejavamail/config/PropertySchema.java) define the report and redaction boundary.
- [SpringEnvironmentConfigSource](../../modules/spring-module/src/main/java/org/simplejavamail/springsupport/SpringEnvironmentConfigSource.java) retains framework-source provenance.
- Existing [ConfigDiagnosticsTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/config/ConfigDiagnosticsTest.java) and [SpringEnvironmentConfigSourceTest](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SpringEnvironmentConfigSourceTest.java) cover these contracts; this ADR does not report a new test run.

## Connection conflict policy (accepted 24 October 2026)

ADR 0038 uses captured source names plus construction-local raw, Session and Java declaration labels for actionable connection-conflict errors. The existing public diagnostics remain captured-factory-input reports; this adds no public effective-resolution report or live Session inspection API. Preserve escaping and secret redaction. See [ADR 0038](0038-connection-configuration-conflicts.md), accepted under [#867](https://github.com/bbottema/simple-java-mail/issues/867). Implementation is tracked separately from design acceptance.

## Shared configuration resolution (accepted 25 October 2026)

[ADR 0039](0039-explicit-configuration-resolution.md) retains origins on internal declarations through selection and validation, reusing the existing escaping and sensitivity rules. Internal resolution facts do not turn ConfigDiagnostics into a live Mailer report or add a public effective-configuration report. Opaque application objects are not rendered to establish meaning or produce conflict diagnostics.

Implemented for unreleased 10.0.0 under [#867](https://github.com/bbottema/simple-java-mail/issues/867); publication remains pending.
