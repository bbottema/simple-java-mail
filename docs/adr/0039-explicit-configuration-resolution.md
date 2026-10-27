# ADR 0039: Explicit configuration resolution

- Status: Accepted, 2026-10-25
- Target: Unreleased 10.0.0
- Tracking: [#867](https://github.com/bbottema/simple-java-mail/issues/867)
- Implementation status: Complete layered adoption and the accepted 2026-10-26 logical-identity/whole-Mailer construction extension are implemented for unreleased 10.0.0 under #867.

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

The same preflight checks feature-owned execution compatibility for every node, including rejection of a caller-owned executor combined
with non-default built-in queue settings. No privately constructed child may allocate resources before a parent has passed this check.
This remains a Mailer-construction rule; group builders and inspection stay passive, and caller-owned executors are not closed on rejection.

Validation and ownership rejection leave a supplied Session unchanged. Retain construction rollback and borrowed-resource boundaries. This is a connection-specific effect boundary; ordinary subject selection does not need a synthetic application-plan lifecycle.

### Initial consumers and adoption boundary

Connection configuration is the first complete consumer, with its behavior owned by [ADR 0038](0038-connection-configuration-conflicts.md).

Email subject selection is the second production consumer. Preserve eligible override, submitted value, then eligible default; empty values, suppression, template replacement, copying, repeated completion, exact-message boundaries and existing subject locks retain [ADR 0002](0002-email-defaults-and-overrides.md). Other Email fields and execution settings remain with their current implementations.

### Contract coverage

Each adopted setting and raw alias has a declared coverage category. Independent contract models enumerate feasible combinations of source, choice, representation, scope, policy and requirements. Coupled features include their multi-input interactions; bounded operation sequences cover editing and restoration. Expectations do not call production resolution or interpretation helpers.

Pure matrices establish resolution invariants. Public-builder and provider-boundary cases establish capture, application, inspection and cleanup. Finite category coverage does not claim exhaustive behavior for arbitrary application objects or unknown providers.

### Complete finite adapter contract (accepted 26 October 2026)

The maintainer requires full coverage of the supported finite adapter inventory. Classification alone is insufficient: each exact public setup
overload is invoked and checked, each canonical property and supported raw alias has a behavioral witness, and every adopted logical field and
consumer adapter is assigned an executable contract. Shared category proofs continue to avoid repeating a Cartesian product for every field.
Native provider access and grammar are separate axes. Angus parity witnesses execute the actual property access, parsing, factory lookup and
trust policy, intercepting only network/resource handoff. Expectations for deliberate SJM restrictions remain separate from provider parity.
State-carrying `toBuilder()` and copy-factory overloads remain setup routes even when their immutable holder has no setters. They preserve
supplied/removal states and borrowed identities while returning independent drafts; neutral zero-argument value-builder factories add no competing input.
For trusted hosts, preserve the provider's whitespace-token grammar without trimming nonseparator characters; compare interpreted membership
without changing the selected raw property or inspection order.

Normal verification enforces a fixed independent inventory against successful evidence from the current Maven invocation. A library gate remains
Java 11 compatible; the full reactor additionally requires CLI, Spring and Boot starter witnesses. Failed/skipped invocations, missing adapters,
stale runs and changed inventory fingerprints cannot contribute accepted evidence. Runtime membership discovery includes newly reachable setup
interfaces and inherited routes. No instrumentation or evidence API is added to the runtime library; support lives in the test artifact and tests.
The [configuration coverage guide](../CONFIGURATION_CONTRACT_COVERAGE.md) owns maintenance and gate usage. A complete result is a finite contract
claim, not a universal guarantee for unknown providers, arbitrary application code or all possible values.

## Alternatives

- More checks at individual setters or application sites retain competing interpretations and make final-choice behavior depend on the route taken.
- A universal merge algorithm would change deliberate differences between scalars, collections, compound policies and mandatory requirements.
- A general workflow engine, phase-service hierarchy or external rule registry adds flexibility not required by the two consumers.
- A connection-only data model would duplicate the common declaration and provenance concepts when the next configuration owner adopts them.
- Migrating every configuration path immediately would expand the change beyond the two consumers needed to establish the shared boundary.

## Consequences

Construction retains more explicit information until validation, and supported settings need clear interpretation and coverage classifications. The common model remains small while each feature's rules stay discoverable in its owner. Public usage and input-loading precedence remain unchanged; connection compatibility consequences continue to belong to ADR 0038 and its migration guidance.

## Complete layered adoption (accepted 26 October 2026)

The complete inventory includes standalone batch cluster/default settings and provider recipient/Email/Session fallbacks. These are genuine optional-module consumers, so the small foundation moves to core under the same internal package/type names, with an internal JPMS export. This supersedes its initial main-only placement. Core is the appropriate owner once the machinery is actually shared across modules; no optional module gains a dependency on main.

Converter option selection retains lazy fallback Session/governance creation. Provider-owned selection retains runtime negotiation and generated identifier timing. Per-recipient certificates remain authoritative over the governed Email fallback. Public APIs and the feature-owned interpretation/activation boundaries remain unchanged.

The maintainer extends the initial two-consumer boundary to every application configuration value assembled from multiple layers. This supersedes the initial adoption limit above; the deliberately small foundation and feature-owned rules remain the architecture. The issue tracks delivery of the expanded implementation.

Email adoption includes all scalar and whole-policy template contributions, the calendar pair, recipient/Reply-To/envelope additions, case-insensitive header selection, attachment/image contributions, encoding, delivery and receipt choices, fixed message metadata, local construction defaults and recipient/group default/fixed policies. Preserve existing source eligibility, null versus empty, suppression, supplied duplicates and ordering, conservative resource identity, copying/serialization and exact-message boundaries.

Mailer adoption includes pool/cluster, executor/queue, sending allowance, retry/failover, deadline, debug, telemetry, validation, logging, template selection and legacy-content choices as well as the existing connection consumer. Explicit Java, captured configuration and documented built-in fallback become typed declarations. Group replacement, explicit removal, reset and restoration retain their established feature meanings. Named node preparation does not transfer failover or retry execution ownership to the foundation.

Core remains the input-loading owner. Its public value builders collect data; source precedence and schema parsing produce immutable snapshots before consumer resolution. Main-owned adapters consume those typed snapshots. Single-source callback registration and send/close operations retain their existing ownership rather than acquiring artificial configuration layers. The adoption inventory classifies every property and public configuration family, including these justified boundaries.

The foundation shares deterministic scalar selection and ordered eligible declarations. Collection matching, header-name identity, compound completion, constraints, activation and resource effects remain with the owning feature. A completed value may be projected from a retained result when its feature has no further comparison requirement. ConnectionConflictPolicy continues to govern its existing connection scope only.

Public signatures and observable precedence remain unchanged. Ordinary Email setters store submitted choices; buildEmail creates a submitted Email without applying templates. Explicit completion or selected-Mailer preparation applies defaults/overrides. Mailer settings keep their documented construction and validation boundaries. A separate application plan is required where it protects effects, not for every scalar or collection.

A final source-level Javadoc audit compares every migrated declaration against the pre-change baseline, accounting for moved or corrected documentation and preserving useful examples, defaults, constraints, ownership, security caveats and links. Generated public documentation and CLI metadata must reflect the final sources. Finite behavioral models cover the adopted domains; provider/application behavior outside those categories remains outside a universal guarantee.

### Rule/category proof and representative wiring (accepted 26 October 2026)

The expanded implementation proves the shared rules over explicit source/choice/merge categories and all supported input routes, then checks representative API wiring and field/category membership. Exhaustive Cartesian products of all fields and contextual settings are not required. Keep deterministic category cases, meaningful transition examples and permanent regression reproductions; avoid repeating the same rule proof for every API field. Category classification and behavioral assertions establish different facts and neither substitutes for the other.

### Logical setting identity and complete Mailer preparation (accepted 26 October 2026)

The maintainer approved strengthening the two boundaries around selection. Each declaration and resolution now names a `ConfigurationSetting<T>`:
the logical target of supported input representations. A source `ConfigKey<T>` still identifies a loaded property. The resolver rejects candidates
for a different logical setting before eligibility or inheritance filtering, even when their Java value types match. Ordinary immutable lists
continue to carry declarations; no public registration mechanism is introduced.

Feature owners establish identity and applicability before flattening information. Header capture keeps original spellings and sources while
grouping case-insensitive names. S/MIME inner/outer declarations are resolved before constructing the header snapshot. Template resolution
retains suppression, first eligible display spelling and the previous last-matching-entry interpretation of legacy flat maps. Connection
adapters keep original property/setter labels and protocol applicability while assigning projected members a common logical target. Unknown
provider properties retain their literal identity without acquiring interpretation or guarantees.

Property interpretation also governs locks and live-state verification. TEXT members use provider-visible Strings, including inherited defaults
behind opaque direct entries. Factory object slots retain direct-entry access; factory class names use the TEXT view. Numeric and Boolean members
retain their provider direct-value rules. Non-String keys are preserved as opaque entries without invoking their rendering methods to invent names.
Explicit additional-property maps keep their literal-entry contract, including the documented omission of their inherited defaults.

The earlier connection-specific effect boundary is extended for Mailer construction:

```text
CapturedMailerInputs -> ResolvedMailerConfiguration -> ValidatedMailerPlan -> construct
```

Capture detaches mutable configuration structures, borrowed identities and the named-route topology once per build. It retains no live builder
for later resolution or construction. Resolve completes connection choices, debug fallback, operational choices and Email governance. Configured
signing-material reads retain their existing selection and stream-ownership rules; a replacement template still skips unused ordinary defaults.
Application callbacks and telemetry binding do not run during capture or resolution.

Only the controlled `ValidatedMailerPlan.validate(...)` path can produce construction input. It checks every owned node and route relationship,
including connection declarations and locks, execution compatibility, applicable optional-pool shape, governance requirements and shared allowance
consistency within the captured graph. Compare connection declarations before constructing the server representation so a representation failure
cannot hide the actionable source conflict. The existing BatchModule internal SPI owns pure pool validation and uses the same selected-cluster
interpretation as runtime registration; unused overrides remain unused.

Construction consumes frozen plan values without another builder read or precedence pass. It creates executors, binds observers and constructs
owned named children only after whole-graph validation succeeds. Borrowed executors, Sessions, callbacks and supplied Mailers retain identity
and ownership. Existing internal injection constructors continue to accept completed supplied facts rather than inventing declaration history.
Acquisition rollback covers newly owned executors and children even when failure precedes the Session-configuration body.

The plan certifies checks possible from captured input. Session ownership can change, optional providers can initialize differently, and established
pool/allowance state remains live. Keep atomic registration, post-initialization/selected-pool verification, cleanup and rollback. Type boundaries
enforce phase order and construction inputs; behavioral contracts establish interpretation and constraint completeness. Simple scalar consumers
such as subject selection retain direct resolution without a synthetic construction lifecycle.
