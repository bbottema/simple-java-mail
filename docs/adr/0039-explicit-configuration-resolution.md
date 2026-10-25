# ADR 0039: Explicit configuration resolution

- Status: Accepted, 2026-10-25
- Target: Unreleased 10.0.0
- Tracking: [#867](https://github.com/bbottema/simple-java-mail/issues/867)
- Implementation status: Implemented for unreleased 10.0.0 under #867; publication pending.

## Context

The loader already resolves configured sources into typed immutable snapshots. Later construction still needs to combine those inputs with builder choices, supplied objects and mandatory requirements. Losing the difference between absence, explicit removal, fallback and an imported declaration makes subsequent validation depend on which path interpreted a property first.

Connection settings exposed this problem through interacting raw properties, partial TLS/proxy groups, supplied Sessions and locks. The same declaration and provenance concepts apply to other features, while their resolution rules differ. Email subject selection uses ordinary override/submitted/default precedence; recipients and signing policies have their own value boundaries.

The explicit internal ownership used for connection recovery provides a useful precedent: retain the states needed to enforce invariants at their boundaries. Configuration resolution needs a small deterministic value model and construction boundary, rather than an asynchronous state-machine framework.

## Decision

### Share declaration mechanics, retain feature ownership

Introduce the internal foundation in `org.simplejavamail.internal.configresolution` in the main implementation module. Core continues to own application contracts, `ConfigKey`, schema parsing, immutable input snapshots, provenance and lock metadata. No new public API, resolution report, rule-registration mechanism or dependency is added.

The foundation uses:

- `ConfigurationChoice<T>`: Inherit, Set(value), or Remove. Reset supplies the documented fallback explicitly and retains its origin; restoration returns to Inherit.
- `ConfigurationDeclaration<T>` and `ConfigurationOrigin`: supplied choice and its source, scope and explicitness.
- `ConfigurationResolution<T>`: selected choice and the declarations retained for validation.
- `ConfigurationResolver`: pure selection mechanics driven by the owning feature's rules.

Keep ordinary immutable lists instead of adding a collection wrapper without an invariant. Prefer explicit methods for capture, resolution and validation. Create a separate collaborator only when it owns reused behavior or a distinct responsibility. Internal assembly follows [ADR 0031](0031-public-api-and-internal-construction.md); a validated plan has a controlled creation boundary.

Feature owners decide precedence, eligibility, equivalence, compound-value boundaries and removal semantics. Clearing a local Email value can permit fallback, while removing an imported connection setting can conflict. No universal merge rule or connection conflict policy is imposed on all configuration.

### Preserve facts through validation

Input adapters retain the distinction between explicitly supplied values, built-in fallbacks and absence. They preserve available source labels and redaction without reopening configuration sources. Selection does not discard competing declarations before their constraints have been checked.

Typed input remains typed. Raw property access stays at an explicit integration boundary; unsupported representations do not gain meaning from application `equals`, `hashCode` or `toString` methods. Borrowed runtime resources retain identity. Capturing configuration does not traverse arbitrary content, initialize borrowed factories or execute application callbacks.

### Connection application requires a validated plan

Connection construction follows:

```text
capture inputs -> resolve complete choices -> validate requirements -> immutable plan -> claim Session -> apply
```

`CapturedConnectionInputs` retains final choices, source facts and Session property views. `ResolvedConnection` retains the selected logical configuration and its declarations. Only successful validation creates `ValidatedConnectionPlan`.

The plan supplies the accepted property patch, logical inspection facts, remote proxy and factory decisions, and reusable requirements. Application does not perform another precedence pass or silently overwrite conflicting input with locks. Runtime bridge ports and provider-owned helpers remain derived application facts. Post-initialization and selected-pool checks verify actual requirements using the same interpretation rules.

Prepare each privately constructed named-failover node once per build, preserving cycle checks and ancestor restrictions before allocating owned resources. Do not cache prepared plans across separate builds. Borrowed Mailers retain their existing ownership.

Validation and ownership rejection leave a supplied Session unchanged. Retain construction rollback and borrowed-resource boundaries. This is a connection-specific effect boundary; ordinary subject selection does not need a synthetic application-plan lifecycle.

### Initial consumers and adoption boundary

Connection configuration is the first complete consumer, with its behavior owned by [ADR 0038](0038-connection-configuration-conflicts.md).

Email subject selection is the second production consumer. Preserve eligible override, submitted value, then eligible default; empty values, suppression, template replacement, copying, repeated completion, exact-message boundaries and existing subject locks retain [ADR 0002](0002-email-defaults-and-overrides.md). Other Email fields and execution settings remain with their current implementations.

### Contract coverage

Each adopted setting and raw alias has a declared coverage category. Independent contract models enumerate feasible combinations of source, choice, representation, scope, policy and requirements. Coupled features include their multi-input interactions; bounded operation sequences cover editing and restoration. Expectations do not call production resolution or interpretation helpers.

Pure matrices establish resolution invariants. Public-builder and provider-boundary cases establish capture, application, inspection and cleanup. Finite category coverage does not claim exhaustive behavior for arbitrary application objects or unknown providers.

## Alternatives

- More checks at individual setters or application sites retain competing interpretations and make final-choice behavior depend on the route taken.
- A universal merge algorithm would change deliberate differences between scalars, collections, compound policies and mandatory requirements.
- A general workflow engine, phase-service hierarchy or external rule registry adds flexibility not required by the two consumers.
- A connection-only data model would duplicate the common declaration and provenance concepts when the next configuration owner adopts them.
- Migrating every configuration path immediately would expand the change beyond the two consumers needed to establish the shared boundary.

## Consequences

Construction retains more explicit information until validation, and supported settings need clear interpretation and coverage classifications. The common model remains small while each feature's rules stay discoverable in its owner. Public usage and input-loading precedence remain unchanged; connection compatibility consequences continue to belong to ADR 0038 and its migration guidance.
