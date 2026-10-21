# API Expansion Workflow for Simple Java Mail

Project guide: [Developing and maintaining Simple Java Mail](CONTRIBUTING.md).

This document serves as a blueprint for developers and coding agents when adding new fields or features to the Simple Java Mail API. Following these steps ensures that the new functionality is correctly integrated across all modules, including CLI support, message conversion, and module-specific processing.

For architectural decisions and source anchors, see the [architecture topic index](adr/README.md#find-documentation-by-topic). Use [DEVELOPMENT.md](DEVELOPMENT.md) for build and CLI metadata procedures and the [concurrency documentation](concurrency/README.md) for send-state and resource-ownership contracts.

## Ownership and design gate

For a holistic review round, follow the [public API review workflow](API_REVIEW_WORKFLOW.md). Present findings and obtain the maintainer's design and execution approval before starting the implementation and test steps below.

Map each architectural correction to its governing ADR before implementation. Amend an existing record when clarification is sufficient;
create a new ADR only for a distinct architectural choice. Keep implementation checklists and verification evidence in the plan, not the ADR.
Use test-first development: reproduce the intended defect, confirm red, implement and refactor while green. A missing-method compilation
failure is only an initial red; assert the behavior too. Record passing controls for behavior that must remain unchanged.
Architecture-conformance commit bodies name the relevant ADRs and explain the alignment. Keep semantic subjects and omit sign-offs.

Read [ADR 0001: Email configuration scopes and inheritance](adr/0001-email-configuration-scopes-and-inheritance.md) for ownership and [ADR 0002: Email defaults and overrides](adr/0002-email-defaults-and-overrides.md) for reusable policy and resolution. Their accepted architectural direction is separate from implementation status.

- Put message configuration on Email. Mailer-wide message policy uses `withEmailDefaults(...)` / `withEmailOverrides(...)`; do not duplicate an Email feature with dedicated Mailer default/override methods or backing state. The dedicated DKIM route is being removed in the 10.0.0 migration, not retained as a precedent for new APIs.
- Expose only recipient-specific settings on recipient/group builders. S/MIME encryption certificates and DSN NOTIFY preferences qualify; DKIM/S/MIME signing and transaction-wide DSN RET/ENVID do not. ORCPT is derived metadata for ordinary submissions, not another recipient setting.
- Define the scope and meaning of defaults, explicit values, clearing, disabling, and forced overrides before selecting method signatures. Preserve the settled cross-scope rule: explicit recipient values beat the governance-resolved Email fallback, including Email overrides. Group-fixed values apply when constructing that group's recipients; they are not a global enforcement layer.
- Keep settings that own SMTP connections or execution resources on Mailer. Follow section 7 for those rather than routing them through Email governance.

### Public API versus internal cross-module types

Follow [ADR 0031](adr/0031-public-api-and-internal-construction.md). Classify the consumer first: application configuration, inspection of a
library-produced result, typed adaptation of an existing foreign instance, supported provider SPI, or library-internal construction.
A returned result does not automatically need a public builder.
`core-module` is the API-bearing artifact; `simple-java-mail` is the main implementation. There is no separate current `api-module`.

Check module ownership before package visibility. Put application contracts, builder interfaces and public result/value types in core. Put concrete
main-runtime builders, recording and orchestration in `simple-java-mail`; put optional implementations in their feature module. Core may contain
necessary shared utilities and internal cross-module contracts, but is not a home for main-only machinery just because it has internal packages.
The same rule applies to optional-feature validation: keep pool semantics, signing rules and provider compatibility in the module that implements them.
Core may validate data shape and parse property types. Use the existing internal module contract to validate effective configuration when a feature
is used; add a contract only if the existing boundary cannot express the operation. Test absence of the module, unused configuration and values
replaced by overrides as well as rejection on the active feature path. See [ADR 0006](adr/0006-optional-modules.md#feature-semantics-stay-with-the-feature-implementation).
After bootstrap, continuing application calls and their parameter/return/callback types should need only core and its declared API dependencies
to compile. Keep the implementation available at runtime. A contract with one library-owned implementation still earns this module boundary.

For optional features, keep ordinary configuration in core contracts and the main builder API. Main-runtime bootstrap belongs in
`simple-java-mail`; existing core value builders are not module-runtime entry points. Load the optional implementation through `ModuleLoader`
when the requested behavior needs it. Missing requested modules must produce the focused module-loading error at that boundary. Applications
must not construct runtime support classes or supply internal bridges.

Classify inputs using [ADR 0006](adr/0006-optional-modules.md#choose-the-input-boundary-for-the-caller-accepted-2026-10-16):

1. **Plain configuration data:** accept primitives, strings, bytes or neutral values; let the module construct its library-specific objects.
2. **Already accepted types:** use JDK/SJM types or existing foundational dependencies directly. Do not promote an optional library into core/main
   merely to make its foreign type fit this category.
3. **Existing foreign runtime objects:** expose a narrow typed adapter in the optional module that accepts the foreign public type and returns a
   core-owned SJM wrapper contract. Prefer compiler/IDE guidance over a public `Object`, `Supplier<Object>` or interface hiding an untyped input.

The third case is a deliberate exception to module-independent setup. The application needs the adapter module and foreign API for that call;
continuing configuration and sending use core contracts. Keep the adapter limited to wrapping the supplied instance, without initialization,
global installation or ownership transfer. Defaults, setting methods, properties and locks stay with their existing core/main owners.
Do not add foreign API/context/SDK dependencies to core/main, even as optional API-only dependencies. Document the adapter's accepted type,
returned contract and lifecycle boundary; do not turn it into another configuration builder or public runtime support object.

A deliberately exposed feature-specific result is a separate, documented exception. Prefer a core result read contract when sufficient, as
current Outlook conversion does; exposing a result does not justify moving ordinary configuration into its module.

Java `public` visibility does not by itself make a type supported application API. Implementation types that need to cross module boundaries belong in a package containing an `internal` segment, such as `org.simplejavamail.internal.config.ConfigurationLocks`. Keep them package-private when cross-package access is unnecessary.

Use `@ApiStatus.Internal` where appropriate, but do not use that annotation as a substitute for the internal package. Genuine application-facing types and supported provider extension SPIs remain in their public API packages. Do not expose library-only accessors such as configuration-lock machinery on an application read contract.

Public utilities may retain their implementation and private helpers. Do not add an internal class mirroring every public method simply to relocate code;
identify the actual module, construction or visibility boundary, and extract only shared work that earns its own responsibility.

Keep runtime result assembly builders internal to their implementation module. Core reading contracts must not depend on concrete assembly builders.
Use a read interface and a producer-owned implementation for library-only result assembly, not a public construction bridge annotated as internal.
Reserve `@Deprecated` for retiring supported API, not identifying new internal code.
Do not introduce telescoping constructors. Use named builder assignments and one validation/copying boundary. Internal and provider-SPI builders
use Lombok. Public application configuration builders are explicit, with setter-owned Javadocs and getter/reset/clear references to that setter.
SPI authors do not need those companion methods or the application documentation convention; document their obligations and supplied facts directly.
For values also read by applications, retain explicit documentation on the result getters.
Preserve deliberate dependency injection and test constructors rather than introducing builders solely to satisfy a constructor-count rule.
Compatibility concerns apply to released supported API; they do not justify retaining every unreleased internal overload.

Check both module and package placement during review and verify cross-module classpath/JPMS consumers after a move. Compile continuing application
usage with only core and its declared API dependencies; keep bootstrap/runtime and provider construction fixtures separate. Include negative
compilation checks and generic/callback signature checks. The Javadoc build excludes internal packages, so implementation types must not leak through
public signatures or appear as ordinary user-facing API.

---

## 1. Core Model Expansion (`core-module`)

The foundation of any new feature usually starts with updating the core model objects.

- **Update Model Classes**: Add the new field to the relevant model class (e.g., `Email`, `Recipient`).
  - Use Lombok `@Value` for immutability where appropriate.
  - Ensure the class remains `Serializable`.
  - Update `serialVersionUID` if the change breaks binary compatibility.
- **Update Internal Interfaces**: If the model has an internal interface (e.g., `InternalEmail`), ensure it's updated if necessary.

## 2. API Interface Expansion (`core-module`)

New fields must be accessible through the fluent Builder API.

- **Builder Interface Ownership**: Follow [ADR 0024](adr/0024-builder-interfaces-are-not-extension-spis.md). All builder interfaces are library-owned API contracts, not extension SPIs. Update Simple Java Mail's implementations when adding abstract methods; do not add default methods solely to preserve unsupported external implementations.
- **Update Builder Interfaces**: Add new methods to the public builder interfaces (e.g., `EmailPopulatingBuilder`, `IRecipientsBuilder`, `IRecipientBuilder`).
- **Discoverable Java Choices**: Use enums or cohesive typed values for a closed set of choices; do not make Java callers discover protocol tokens such as `"SUCCESS"`, `"NEVER"`, or `"HDRS"`. Explain enum values in user terms and use typed values in Java examples. Parse text at configuration/CLI boundaries. Do not add or retain String builder overloads just for CLI conversion: register a converter for the typed parameter instead. Any retained text-input overload needs an independent compatibility or Java-use justification.
- **Javadoc Source of Truth**: For application configuration, put the complete behavior contract on the public builder interface. Document convenience overloads well enough to stand on their own and link them to the full overload. Builder implementations, factories, Spring support, and other entry points should use `@see` links to the public builder method instead of maintaining a second copy of its semantics. This convention does not apply to provider-SPI builders.
- **Preserve Useful Guidance**: Simplifying prose must retain examples, supported values, defaults, security caveats and ownership details. Compare removed Javadoc with its replacement, not just the new prose in isolation. Label algorithm examples as examples, verify them against the owning implementation, and retain a route to its complete supported set. Do not replace an inaccurate whitelist with no guidance at all.
- **Clear and Reset Companions**: Provide a named companion for removing or resetting a settable choice. Use `clear...` for absence and `reset...` for the documented built-in fallback; link its Javadoc to the setter. Do not require callers to cast `null` to disambiguate an overloaded setter. A clear operation changes only its own choice, preserving unrelated settings and existing immutable results.
- **Public Configuration Getters**: Link each configuration getter to its corresponding public builder method with `@see`. Keep Lombok for value semantics where appropriate, but declare application-facing configuration getters explicitly so the source-based Javadoc build includes them. Describe getter-specific absence or defensive-copy behavior locally; do not repeat the feature contract. Check the generated HTML for both the getter and its builder link rather than treating a successful Javadoc build as proof that generated accessors are documented.
- **Immutable Fluent Configuration**: A small immutable configuration API may omit a separate mutable builder, but its supported public fluent setter still owns the contract and its getter/reset/clear companions link to it. Preserve explicit-choice versus inherited-property semantics and central defaults. Its contract belongs in core and any main-runtime bootstrap belongs in `simple-java-mail`; avoiding a `build()` call does not permit ordinary configuration APIs in an optional module. A typed foreign-instance adapter wraps an existing object only; it is not a configuration-builder exception.
- **CLI Compatibility Rules**:
  - **Parameter Types**: For CLI-exposed methods, use supported scalars, enums, or types with a `ValueInterpreter` in the `cli-module` (e.g., `X509Certificate`, `File`, `URL`, `Date`). CLI conversion must not dictate an untyped Java API; use a converter or an explicitly documented text-input boundary where necessary.
  - **Avoid Collections**: Picocli mapping works best with individual values or arrays. Avoid `Collection` or `Map` in signatures intended for CLI use. Provide overloads if necessary.
  - **Javadoc**: Provide complete Javadoc for all new methods and parameters. The CLI module uses this to generate help text.
  - **Annotations**: Use `@Cli.ExcludeApi` for methods that should not be exposed to the CLI (e.g., those taking complex Java-only objects). Use `@Cli.OptionNameOverride` if the method name isn't ideal for a CLI flag. Use `@Cli.Optional` on parameters that may be omitted from the CLI; keep JetBrains `@Nullable` for Java/API nullability only.
  - **Converters and Exclusions**: Register a value converter in `BuilderApiToPicocliCommandsMapper` for a newly supported string-convertible type. Exclude ambiguous overloads, unsupported object/collection parameters, and redundant subset APIs with an explanatory `@Cli.ExcludeApi(reason = "...")`; use option-name overrides when distinct operations would otherwise collide.
  - **Typed Array Input**: For a value such as `NotifyOption[]`, register a `ValueFunction` that converts one CLI argument into the typed array, add its help label, and explicitly allow that array in the mapper. Reuse the canonical parser for aliases and validation; do not enable unrelated array/collection types. Keep existing CLI option names when moving an option from a String overload to its typed method.
- **Java 11 Convenience Overloads**:
  - Add `Path`, `Instant`, or another modern type only at the entry points where it removes recurring caller-side conversion. Do not mirror every existing overload combination mechanically.
  - Delegate to one existing parser, builder, or value-storage path so the modern overload cannot develop separate behavior.
  - A `Path` overload closes every stream it opens. An `InputStream` overload keeps its documented ownership contract unchanged.
  - Keep `File`, `Date`, and `InputStream` APIs when they remain useful. Exclude a `Path` or `Instant` builder overload from CLI generation when an existing file or date option already represents the same command-line value.
  - Exercise at least one non-default filesystem when the implementation promises genuine `Path` support; converting immediately through `Path#toFile()` is not sufficient.

Builder inspection must also work before construction is complete: return stored choices without calling `build()`, resolving a derived/provider
fallback or validating an incomplete policy. Include reviewed application value builders in companion checks, not only the original motivating
builder. Automatic rendering protects known secrets on unfinished builders as well as completed values; explicit programmatic accessors remain separate.

## 3. Core Implementation (`simple-java-mail`)

Implement the new API methods and ensure data propagation.

- **Update Builder Implementations**: Update `EmailPopulatingBuilderImpl`, `RecipientsBuilderImpl`, `RecipientBuilderImpl`, etc.
- **CRITICAL: Data Propagation**:
  - For recipient-capable fields, retain metadata through fixed-type groups, Email copies/governance, override receivers, exact-EML envelopes and serialization. Preserve duplicate recipient occurrences and the provider's TO/CC/BCC ordering; never map preferences solely by address. Recipient NOTIFY is an example of a complete preference set, while RET/ENVID remain transaction-wide.
  - Ensure that "copy" methods (e.g., `withRecipient(Recipient)`) and delegation methods correctly copy the new field.
  - Failure to do this will result in data being lost when `simpleJavaMail.emailBuilder().copying(email)` is used or when builders delegate to each other.
- **Update Email Constructor**: Ensure the `Email` constructor copies the new field from the builder.
- **Utility Classes**: Update `MiscUtil` if it contains helper methods for object creation or parsing (e.g., `interpretRecipient`).
- **Send Results**: Runtime facts belong to the send result, not the reusable Email. Preserve added fields through result enrichment, receipt construction, and serialization/read-resolution. Keep internal assembly out of the application API and use builders instead of adding positional constructor overloads. Review genuinely released construction contracts separately; retaining serialized compatibility does not require retaining unreleased constructors. Test absence behavior within the supported snapshot format. ADR 0031 explicitly supersedes compatibility with the former concrete report classes; keep that migration boundary distinct from the preserved Email/recipient/attachment format.
- **Interpreted Source Facts**: Facts extracted from a received message belong to its converted Email read model, not outgoing configuration or a live send result. See [ADR 0008](adr/0008-minimal-mime-structures-and-protection-order.md). Use core read contracts and internal assembly; preserve source metadata through copying/governance/serialization without letting templates manufacture it. Do not add setters, EmailProperty entries, defaults, locks, properties or CLI options for read-only facts. Verify the effective outer message after protection processing, exact/protected bytes, attached-message isolation and absence in older snapshots.

## 4. Message Conversion & Processing (`simple-java-mail`)

Route the new field according to its ownership. Message-content fields affect the produced `MimeMessage`; SMTP-envelope fields belong to `DeliveryEnvelope` and the provider adapter, not MIME headers. Do not change exact or protected MIME bytes to carry transport metadata.

- **Read Tolerantly, Produce Strictly**: Follow [ADR 0035](adr/0035-tolerant-reading-and-strict-production.md) for MIME/EML/Outlook imports and interpreted source facts. Recover unambiguous formatting, preserve usable neighboring facts and source data, and explain incomplete interpretation through existing feature metadata. Do not make an optional interpretation reject an otherwise readable Email. Keep conflicting or genuinely ambiguous facts unavailable; never invent dates, timezones, recipients or delivery results. Required content-read failures retain their causes rather than returning complete-looking truncated content. Add malformed/partial-source regressions alongside valid fixtures, and preserve outgoing validation, exact/protected bytes and cryptographic verification.

- **MimeMessageHelper**: Update this class if the new field translates directly to a standard MimeMessage header or property (e.g., a new recipient type or a standard header).
- **SpecializedMimeMessageProducer**: Update the `populateMimeMessage` method if the new field requires logic to decide how the `MimeMessage` is constructed or if it triggers module-specific processing (like S/MIME or DKIM).
- **MIME Structure**: For a new body-part concept, revisit the mixed/related/alternative selector and each affected producer. [ADR 0008](adr/0008-minimal-mime-structures-and-protection-order.md#mime-producer-selection) contains the producer matrix and protection-order contract; retain custom Message-ID behavior across wrappers.

## 5. Module-Specific Integration

If the feature relates to a specific module, update that module.

For a new optional feature module, follow [ADR 0006](adr/0006-optional-modules.md):

1. Put its contract and shared types in `core-module`, with implementation classes in the optional module. Main-module code must depend on that contract rather than import the optional implementation.
2. Add a `ModuleLoader.loadXxxModule()` entry using the implementation class name and, where needed, a classpath-availability check. Cached module instances can be shared concurrently; availability recheck/force-disable hooks are for tests, not hot-plugging.
3. Keep the dependency optional in `modules/simple-java-mail/pom.xml`. Add it to the CLI assembly when the CLI distribution should include that runtime feature, and verify both module-present and module-absent paths.

Keep supplied configuration separate from module execution. [ADR 0034](adr/0034-optional-opentelemetry-integration.md) applies ordinary
ModuleLoader resolution to OpenTelemetry. Applications use core configuration through the main builder API; a narrow optional-module adapter
may wrap their existing OpenTelemetry instance into that core contract. Module support implementations remain internal. Builders read public
configuration methods and must not demand a concrete implementation or additional internal lifecycle interface from that object. The loaded
module creates the runtime bridge. Never cache application SDKs in shared ModuleLoader state.
Runtime SDK instances can remain Java-only dependencies, but activation and other property-friendly behavior still need factory inheritance,
normal scalar/property, Spring, CLI and lock support. A supplied object is not a reason to require per-Mailer Java activation.

- **S/MIME (`smime-module`)**:
  - Update `SMIMEModule` interface in `core-module`.
  - Implement the logic in `SMIMESupport`.
- **Outlook (`outlook-module`)**:
  - Update `OutlookEmailConverter` if the new field has an equivalent in Outlook `.msg` files.
- **Spring (`spring-module`)**:
  - Update the metadata-only `SimpleJavaMailProperties` model so Spring Boot generates an IDE hint for the new property. Mirror the canonical key's nested shape; this class is not used for runtime binding.
  - Do not add scalar properties individually to `SimpleJavaMailSpringSupport` or `SpringEnvironmentConfigSource`. The latter enumerates `ConfigLoader.Property`, resolves each value through Spring's `Environment`, and forwards it to the context-local `SimpleJavaMailConfig` snapshot automatically.
  - Add a compatibility alias to `SpringEnvironmentConfigSource` only when an existing Spring-facing property name must remain supported. Wildcard namespaces still need explicit discovery because Spring cannot enumerate them through ordinary scalar lookup.
  - Run `SpringModulePackagingTest` to prove the generated `spring-configuration-metadata.json` describes every `ConfigLoader.Property`. Never commit the generated `target` output.

## 6. Defaults & Overrides (EmailGovernance)

Public API configuration should have parity across the Java builder API and the Java defaults/overrides mechanism. When a new user-facing field is added to `Email` or a related model and that value can be represented on a source object, integrate it with the governance layer. This allows projects that centralize behavior through default or override `Email` objects to use the same feature without per-message Java code.

Only skip defaults/overrides integration when the value cannot sensibly be represented on the source model, when it depends on runtime state that cannot be copied, or when it is a per-recipient sub-field that should be set while constructing recipients instead. Document the reason in the implementing issue or PR.

- Add the field selector and its internal mapping (core-module)
  - If the field is on `Email` and needs default/override resolution, add a constant to `org.simplejavamail.api.email.EmailProperty`.
  - Add the matching getter strategy to `org.simplejavamail.internal.config.EmailPropertyAccess`. The public selector names the field; it does not expose its implementation.
  - Keep a same-named constant in `org.simplejavamail.internal.config.EmailProperty` for `EmailPropertySerialization`. This compatibility enum preserves serialized suppression sets; do not put getter strategies or collection flags there.
  - Keep `PublicApiBoundaryTest.everyPublicSelectorHasAnInternalAccessorAndSerializationName` passing, and cover the new selector's default/override suppression through copying and serialization.
- Apply default values (simple-java-mail)
  - In `EmailGovernanceImpl.createConfiguredDefaults(...)`, derive a sensible default from the Mailer's `SimpleJavaMailConfig` snapshot if applicable and set it on the builder.
- Apply defaults/overrides to provided Email (simple-java-mail)
  - In `EmailGovernanceImpl.produceEmailApplyingDefaultsAndOverrides()`, explicitly choose `MiscUtil.overrideOrProvideOrDefaultProperty` for replacement or `MiscUtil.overrideAndOrProvideAndOrDefaultCollection` for meaningful additive merging, then apply the result to the builder. Collection types do not automatically imply merging; policy sets may need to be replaced as a whole.
  - Ensure ignoringDefaults / ignoringOverrides and the per-property suppression sets are respected (this comes for free when using the MiscUtil helpers).
- Module-triggering fields
  - If the new field influences downstream processing (e.g., per-recipient S/MIME), make sure SpecializedMimeMessageProducer considers the presence of the field when deciding to trigger the corresponding module.
  - Ensure the corresponding module implementation tolerates null global config if the trigger is a per-item value.
- Per-recipient fields
  - Resolve recipient/group choices through IRecipientsBuilder / RecipientsBuilderImpl, preserving the resulting metadata on each Recipient. Email governance can supply the email-wide fallback for a recipient-capable setting; it must not create a second hierarchy by rewriting recipient sub-fields behind the builder API.
- Follow ADR 0001's scope rules and ADR 0002's resolution conventions. Distinguish an absent recipient preference from explicit disabling, and define how forced overrides interact across scopes. Treat policy sets such as DSN NOTIFY as complete values rather than blindly merging events with defaults.

## 7. Mailer Configuration API Expansion

Advanced Java settings belong to the immutable core-owned groups accepted in [ADR 0003](adr/0003-immutable-configuration-snapshots.md#advanced-configuration-groups-accepted-20-october-2026):
connection pool, asynchronous execution, SOCKS proxy, sending limits, connection, TLS and validation. Email uses the image-resolution,
content-encoding, template-options and SMTP-delivery groups at its existing scopes. Add detailed setters and companions to the owning
group builder; keep ordinary SMTP and composition conveniences direct. Group replacement discards earlier supplied fields, preserves
captured inheritance and explicit removal, and clears stale caller-Session provenance. Root group getters show supplied choices;
effective Mailer/Email read models retain resolved facts. Do not restore removed scalar aliases to supported roots. Keep scalar CLI
syntax through the internal adapters and test continuing core-only usage plus negative compilation of removed routes.

Mailer configuration API changes are separate from Email model/defaults/overrides governance. Use this path for SMTP/session/runtime behavior such as connection settings, proxy behavior, debug output, transport mode, trust settings, and other Mailer-owned state.

- **Public API**: Add methods to `MailerGenericBuilder`, `MailerRegularBuilder`, or `MailerFromSessionBuilder` based on ownership. Provide complete Javadoc and CLI annotations because the CLI help text is generated from these builder APIs.
- **Operational Ownership**: Add state to `OperationalConfig`, `ServerConfig`, `ProxyConfig`, or another existing Mailer config interface according to the behavior being configured.
- **Configuration Is Supplied Data**: Keep Mailer configuration interfaces as accessors and their value objects as holders of constructor-supplied data. Resolve properties and overrides in the builder and inject the resulting values. Do not introduce default-resolving getter bodies on configuration interfaces or default factories on configuration value objects.
- **Keep Built-in Defaults Together**: Follow [ADR 0003](adr/0003-immutable-configuration-snapshots.md#built-in-defaults-stay-together-on-the-key-builders). Declare each library fallback once as a named constant on its key Email/Mailer builder contract, including optional settings of dedicated policy builders. Policy initializers, resets and property/CLI resolvers reference those constants rather than copying literals or constructing a throwaway default policy. Reference the constant in public Javadocs with `@value`, `@link` or `@see` as appropriate, and check the rendered output. Keep defaults/overrides templates separate, preserve derived fallbacks such as a maximum following the configured initial interval, and do not invent defaults for required inputs. Validation bounds are not configuration defaults.
- **Property Defaults**: If the setting is property-friendly, add a `ConfigLoader.Property` entry and resolve it when creating the builder/config object. This keeps property-file driven projects configurable without Java code.
- **Typed Schema**: Register the property's declared value type in `PropertySchema`. Do not infer a type from the text shape or parse values independently at individual read points.
- **Transport Strategy Mapping**: When a Mailer setting maps to Jakarta Mail properties, keep the `mail.smtp.*` / `mail.smtps.*` names behind `TransportStrategy` helper methods and apply them only after the effective strategy is known.
- **Caller-Session Baseline**: Follow [ADR 0015](adr/0015-execution-views-and-transport-pooling.md#caller-supplied-session-baseline). Preserve supplied
  connection settings instead of applying inherited ordinary SJM defaults, regardless of a historical strategy marker. Resolve explicit choices
  and locks without invoking custom Session-property mutation hooks; apply them only after exclusive ownership is claimed. Effective getters must
  describe the resulting values, including absence of a common timeout. Test marked/unmarked Sessions, property defaults, explicit clears,
  unsupported protocols and unchanged managed initialization.
- **Spring Mapping**: If the property belongs to the public configuration surface, represent it in the metadata-only `SimpleJavaMailProperties` model. Runtime scalar discovery follows `ConfigLoader.Property` automatically; only compatibility aliases and new wildcard namespaces require changes in `SpringEnvironmentConfigSource`.
- **Verification**: Test the Java builder path, property/config path, Spring mapping when applicable, and the final `Session` properties. For a built-in default, verify that construction and reset use the published constant and that property loading agrees; check derived fallbacks separately.
- **Governance Boundary**: Do not wire Mailer connection/session settings into Email defaults/overrides. That mechanism applies to Email/message state and related model values.
- **Internal Resource Ownership**: Provider resources such as write-timeout schedulers are not new configuration settings merely because they are stored on a Session. Resolve public settings in the builder, initialize owned provider resources without connecting or starting workers, and retire them at the physical connection's cleanup boundary. Preserve application-supplied executors and factories, caller-owned Sessions and selected clustered-Session ownership. Recheck locks after provider initialization if it supplies or adjusts defaults. See [ADR 0015](adr/0015-execution-views-and-transport-pooling.md) and [the Angus ownership catalogue](concurrency/07-angus-transport-abort.md#write-timeout-resource-ownership).

## 8. Configuration Support (`core-module`)

Public API configuration should also have parity with property-backed configuration. When a Java API option represents configurable behavior and can be expressed as strings, booleans, numbers, enums, files, or other property-friendly values, expose it through configuration properties as well. This keeps property-file driven projects from needing a Java-only escape hatch for the same feature.

Only skip property configuration when the value cannot be expressed safely or clearly in properties, has no sensible global default, or would require complex object construction that belongs in Java code. Document the reason in the implementing issue or PR.

- **Property Identifier**: Add a new entry to `ConfigLoader.Property` using the canonical public key.
- **Typed Resolution and Diagnostics**: Declare the property's invariant `ConfigKey<T>` in `ConfigKeys` through its typed `PropertySchema` parser/projection factory, and register the functional diagnostic group and sensitivity in `PropertySchema`. This declaration is the authoritative type/parser binding; do not add a parallel type table or caller-created key API. Read it with `get(ConfigKey<T>)` from the injected `SimpleJavaMailConfig` snapshot in `EmailGovernanceImpl`, the Mailer builder/config object, or wherever defaults are applied. Use invariant keys in fallback helpers too. Keep parser/raw-source/lock representations and property-file units intact when projecting a Java Duration view. Raw maps and `Property` are explicit integration boundaries, not ordinary caller-inferred typed getters. Do not add a static read or a second parser. Exhaustive checks require one key binding and diagnostic group for every `ConfigLoader.Property`.
- **Factory Propagation**: Prove that builders from `SimpleJavaMail.withConfig(config)` retain the snapshot through copy, reply, conversion, Session creation, governance, and any applicable optional-module route.
- **Snapshot Isolation**: Include two factories with conflicting values. Mutate source collections after `load()`, build objects through both factories, and verify there is no cross-talk. For wildcard Session or connection-pool properties, also verify that returned maps are immutable.
- **Locked Configuration**: Every property also has a `simplejavamail.locked.` counterpart using the same type, diagnostic group and redaction. Implement the restriction at its configuration owner, not just in the loader: allow equal parsed values, reject conflicting explicit customization, preserve additive envelope requirements, and cover resets, replacement templates and alternate send paths. Update `LockedSimpleJavaMailProperties` only for a new top-level metadata group; its nested types reuse the ordinary model. Run the exhaustive Spring metadata test and add behavior tests. See [ADR 0025](adr/0025-factory-scoped-locked-configuration.md) for exact-message and provider boundaries.
- **Spring Mapping**: If the property belongs to the public configuration surface, add its IDE-hint shape to `SimpleJavaMailProperties` and verify `SpringModulePackagingTest`. `SpringEnvironmentConfigSource` already exposes every scalar `ConfigLoader.Property` through the context-local snapshot while retaining Spring's precedence and placeholder resolution.
- **Dynamic Property Collections**: For collection-style namespaces such as `simplejavamail.defaults.connectionpool.clusters.*`, keep name/type parsing and per-child diagnostic provenance centralized in `ConfigLoader`. Leave optional-feature semantics to the owning module after effective values are resolved. Spring support should forward the whole namespace into `ConfigLoader` and Spring Boot metadata should describe the nested shape, rather than duplicating alias/key resolution.

## 9. Verification Surface Areas

Always verify the following areas:

- **Builder Chain**: Verify the field is preserved across multiple builder calls.
- **Public/Internal Boundary**: Check module ownership as well as package visibility. Compile continuing builder/send/result calls against core and its declared dependencies without the main implementation on the compile path; test bootstrap/runtime separately. Check returned-result construction separately from public configuration builders and provider SPIs. Inspect generated Javadocs for leaked builders/bridges, and keep application classpath/JPMS fixtures on supported inspection methods rather than internal construction helpers.
- **Typed Foreign-Instance Adapters**: Use a separate optional-module consumer to verify the typed input, core-owned return contract and rejection of wrong input types at compile time. Check preservation of instance identity, no initialization or ownership side effects, no module-specific subtype needed for continuing calls, and no foreign feature dependency in core/main. Core/property activation must still exercise normal ModuleLoader presence/absence behavior; a direct typed adapter call explicitly requires that module.
- **Opt-in Runtime Behavior**: Exercise both configured and unconfigured behavior through the actual factory/builder. Testing a policy or state object directly does not verify who enables it during construction. For background behavior, demonstrate recovery without a foreground operation hiding the failure.
- **Concurrent Transitions**: Write down who owns each state transition and when pending results become stale. Test both completion orders of overlapping success/failure, cancellation followed by replacement, and work begun after recovery. Pair focused state tests with real send-path regressions; a routing fix must also preserve public exception families, exact observer/completion failures, receipt retention and resource ownership.
- **Email Copying**: Use `simpleJavaMail.emailBuilder().copying(email).buildEmail()` and verify the field is still there.
  Exercise nullable enabled flags separately from optional values, including automatic notification addresses and explicit disabling; an absent
  resolved address must not erase the underlying choice or let defaults re-enable a disabled request.
- **Message Snapshot Ownership**: Follow [ADR 0036](adr/0036-structural-message-snapshots-and-borrowed-content.md). Copy owned collection structure,
  nested headers, dates and supplied arrays; verify builder/getter/serialization aliases cannot change existing messages. Borrow arbitrary
  DataSources and forwarded MimeMessages without eager reads. Keep exact/retry freezing separate and test unchanged content-read counts.
  Convenience readers close streams they open from a borrowed DataSource. Test success, read/close failures and direct caller-owned stream access.
- **CLI Help**: Run the CLI with `--help` for the relevant command to ensure the new option is documented and has the correct parameter labels.
- **CLI Metadata**: Follow [Generated CLI Metadata](DEVELOPMENT.md#generated-cli-metadata) to regenerate both `cli.data` and `therapi.data` before asserting changed help text, then run normal verification with tests enabled. Include the cold-cache concurrency regression and daemon send/probe tests: an API change can invalidate the prepared caches while concurrent requests are using the CLI.
- **Migration Notes**: Maintain migration guidance only on the website, starting from its [migration index](https://www.simplejavamail.org/migration-notes.html). Document only broken backwards compatibility or changed behavior for existing callers. Compare against released versions, not intermediate APIs introduced during the current unreleased work. Put additive features and their usage examples in feature documentation and release notes instead.
- **Current Website Documentation**: Describe the current Simple Java Mail API and behavior without SJM version numbers or "new in", "since", and similar release labels. Version-specific feature history belongs in release notes; version-specific breaking behavior belongs in migration notes. Use the central `site.version` value in dependency examples. Third-party compatibility tables may state the supported dependency versions.
- **End-to-End**: Verify the field at its actual boundary: inspect the final MIME for content fields, or use a dummy SMTP server for envelope fields. For transport metadata, also verify receipt/observer propagation, unsupported-provider behavior, and isolation across reused or concurrent sends.
- **Exact and Protected Content**: Preserve authoritative bytes when adding envelope metadata. Keep exact-message envelope recipients separate from parsed headers in tests, and correlate both byte content and ordered recipient occurrences for concurrent pooled attempts. Provider capability rejection must happen before submission without treating an otherwise healthy lease as broken.
- **Architecture Documentation**: If the addition changes send execution, transport/provider responsibilities, cancellation, or observation, review the [concurrency catalogue and shared infographic](concurrency/inside-a-mail-send.md#phase-completion-check). Update the affected diagrams and distribution copies, or record why the overview remains unchanged.

There are junit tests available to verify the above or provide a blueprint for new tests.

---
*Blueprint version 1.0*
