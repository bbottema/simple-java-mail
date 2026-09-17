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

## Alternatives and consequences

Re-reading the environment when logging could describe a different state from the snapshot used to build a Mailer. Capturing provenance during resolution keeps those facts together. This causal explanation is derived from the resolver design and the issue's immutability requirement.

Using a raw map or `toString()` as the diagnostics API would make each application reimplement canonical names, grouping and redaction. Reporting the final effective Mailer in this same object would mix two different stages: captured input and subsequently overridden operational settings. The issue explicitly reserves that latter feature for a separately named API.

Diagnostics deliberately omit absent properties, generated runtime defaults, later builder calls, caller-owned Session internals and executor/pool/transport state. Consequently the report answers where factory input came from, not every question about an active Mailer.

Extra-property redaction is a name-based policy in the current implementation, not a general content classifier. An arbitrary innocently named extension property can carry information the schema cannot recognize. Source names are caller-supplied diagnostic identifiers. Do not interpret this record as a stronger sanitization guarantee than the code provides.

## Implementation evidence

- [ConfigLoader](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java) creates entries from resolved winners.
- [ConfigDiagnostics](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigDiagnostics.java), [ConfigPropertyDiagnostic](../../modules/core-module/src/main/java/org/simplejavamail/config/ConfigPropertyDiagnostic.java) and [PropertySchema](../../modules/core-module/src/main/java/org/simplejavamail/config/PropertySchema.java) define the report and redaction boundary.
- [SpringEnvironmentConfigSource](../../modules/spring-module/src/main/java/org/simplejavamail/springsupport/SpringEnvironmentConfigSource.java) retains framework-source provenance.
- Existing [ConfigDiagnosticsTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/config/ConfigDiagnosticsTest.java) and [SpringEnvironmentConfigSourceTest](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SpringEnvironmentConfigSourceTest.java) cover these contracts; this ADR does not report a new test run.
