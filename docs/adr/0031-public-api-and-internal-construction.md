# ADR 0031: Keep internal construction out of the application API

- Status: Accepted
- Decision date: 2026-10-13
- Typed foreign-instance adaptation clarification: Accepted, 2026-10-16.
- Target: 10.0.0
- Related issue: [#756](https://github.com/bbottema/simple-java-mail/issues/756)

The accepted 26 October 2026 [ADR 0039 extension](0039-explicit-configuration-resolution.md) requires the ordinary Mailer build path to
construct from a controlled `ValidatedMailerPlan` for the complete owned graph. Captured/resolved settings and internal assembly builders
remain implementation details. The construction plan retains completed values and borrowed identities, never a live builder. Deliberate
internal injection constructors retain their completed-facts semantics; no application-facing construction API is added.

## Required choices and explicit removal (accepted 22 October 2026)

A required, non-null configuration argument rejects null before changing a builder's stored choice. Missing configuration is an argument error, not an implicit request to clear or reset an earlier choice. Provide named clear/reset companions for removal or restoration, consistently with the API expansion workflow. This does not change explicitly nullable component choices that select documented defaults.

Apply this established required-input idiom to signWithSmime(SmimeSigningConfig) and encryptWithSmime(SmimeEncryptionConfig): reject null before assignment and retain the existing clearSmimeSigning(), clearSmimeEncryption() and clearSmime() operations. Email-level clearing leaves recipient certificates and template fallback under their existing contracts. Optional algorithm nulls still select the module defaults; collecting a configuration performs no cryptographic work or module loading. [#833](https://github.com/bbottema/simple-java-mail/issues/833) tracks this alignment.

The same required-input rule applies to individual and group recipient certificates: `withSmimeCertificate`, `withDefaultSmimeCertificate`
and `withFixedSmimeCertificate` reject null before replacing either the certificate or the group policy mode. A missing certificate must not
silently select a clearing policy or remove certificates already supplied by recipients. Explicit clear/reset operations retain their existing
meaning; accepting a certificate still performs no cryptographic interpretation. This application was accepted on 22 October 2026 under
[#835](https://github.com/bbottema/simple-java-mail/issues/835). The correction is implemented and reviewed locally for unreleased 10.0.0;
the issue tracks delivery.

### Paired backoff choices (accepted 23 October 2026)

The required-input rule also applies to retry and failover backoff setters: reject a missing maximum duration before replacing either
the multiplier or its cap. Rejected input preserves omitted choices, explicit removal markers and previously built policies as well as
an existing configured schedule. The named reset operations retain their fixed-delay meaning; accepting non-null choices does not move
duration or cross-field validation out of the existing construction boundary. This aligns the policies in [ADR 0029](0029-smtp-submission-failover.md)
and [ADR 0030](0030-safe-bounded-mail-send-retries.md) with the same required-input contract. [#859](https://github.com/bbottema/simple-java-mail/issues/859)
tracks this correction for unreleased 10.0.0.

### Required material inside S/MIME configuration builders (accepted 22 October 2026)

The same required-input rule applies to `SmimeSigningConfigBuilder.pkcs12Config(Pkcs12Config)` and
`SmimeEncryptionConfigBuilder.x509Certificate(X509Certificate)`. Reject a null argument before replacing the stored key-store
configuration or borrowed certificate, retaining all algorithm preferences and previously completed configurations. Valid material
replacement preserves those preferences too. Completed configuration construction already rejects missing required material;
this clarification moves an explicit missing-argument failure to the setter without changing that construction invariant.

Optional algorithm nulls still defer to the S/MIME module's documented defaults. Collecting these core-owned choices performs no
cryptography and does not load the optional module, following [ADR 0006](0006-optional-modules.md); certificate identity and key-store
snapshot/serialization retain [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md). This adds no material clear/reset
operation or new public API. [#853](https://github.com/bbottema/simple-java-mail/issues/853) tracks this follow-up to #833 and #835
for unreleased 10.0.0.

### OpenPGP recipient-key replacement (accepted 22 October 2026)

Required collection replacements reject a null collection or a null element before changing stored choices. For
`OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder`'s recipient key rings, prepare detached copies of the complete replacement first, then replace
the selection without changing the cipher. An empty list deliberately clears the keys. Both additive and replacement setters
collect non-null byte arrays, including unfinished empty arrays; passive getters inspect detached choices. `build()` still
requires at least one key ring and rejects every empty ring. This does not move cryptographic interpretation into core setters
or inspection. Existing completed values and deserialization retain their construction invariants under [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md).
The correction is tracked by [#843](https://github.com/bbottema/simple-java-mail/issues/843) for unreleased 10.0.0.

## Context and decision drivers

Applications should discover operations they are meant to call. Java sometimes requires a class or constructor to be public so another
Simple Java Mail package or module can use it. That visibility does not turn an implementation detail into supported application API.

The distinction is longstanding. Commit [453152fc](https://github.com/bbottema/simple-java-mail/commit/453152fc) from 3 February 2019 already
places public configuration interfaces in `core-module` and their implementations in the main module. On 6 March 2023,
[349cd24e](https://github.com/bbottema/simple-java-mail/commit/349cd24e) removed `Email` methods marked `@Deprecated` with "Don't use this"
guidance and moved their responsibilities to `InternalEmail`. The intent was to keep library machinery out of the API users work with.

The current repository has no separate `api-module`: `core-module` is the API-bearing artifact, while `simple-java-mail` contains the main
implementation and bootstrap entry points. The checked Maven/Git history already calls that dependency `core-module` in
[cb5ce64f](https://github.com/bbottema/simple-java-mail/commit/cb5ce64f269381298f811bfd52692a1feaec071e), 31 January 2019.
No rename or removal of an `api-module` was found in the available history.

Module ownership and public visibility are separate decisions. Core also contains shared models, supported provider SPIs and necessary internal
cross-module contracts. That does not make it a general home for main-module implementation machinery simply because it has an `.internal` package.
Public configuration entry points such as `org.simplejavamail.config` are supported API too; package spelling alone is not an audit.

The diagnostics builder review exposed two mistakes. A builder used only by the recorder was advertised as an application entry point,
and a growing chain of positional constructors was preserved as if each unreleased overload were a compatibility promise.

Compatibility is a separate concern from accidental visibility. For example, `MailSubmissionReceipt` already has public construction in the
released `9.3.5` tag, whereas the diagnostics overloads were introduced during unreleased work. A boundary correction must distinguish those cases.

## Decision

Accepted API consolidation, 20 October 2026: advanced Email and Mailer settings use the immutable groups recorded in
[ADR 0003](0003-immutable-configuration-snapshots.md#advanced-configuration-groups-accepted-20-october-2026). Their explicit
application builders own detailed setters, inspection and clearing/resetting. Root builders expose group assignment, supplied-group inspection
and restoration. Removed scalar routes may remain only on internal runtime or CLI contracts; they are not deprecated application aliases.
Preserve core-only continuing usage, borrowed provider identity, secret redaction and optional-module ownership across these new boundaries.

### Keep the API module independent of its implementations

The accepted schema-bound configuration read API puts `ConfigKey<T>` and `ConfigKeys` in core's supported
`org.simplejavamail.config` package. Keys are opaque library-owned values, not an extension registry or caller construction
API. Ordinary key-based reads, presence/provenance and fallback selection compile without the main implementation.
Schema parsing/projection stays internal to core's existing loader; typed keys do not expose parser/descriptor assembly.

Use the existing artifacts; this decision does not rename `core-module` or introduce a second API artifact.

| Maven module | Responsibility | Examples |
| --- | --- | --- |
| `core-module` | Application-facing contracts, shared values and narrow cross-module contracts. | `Mailer`, Email/Mailer/recipient builder interfaces, `Email`, `Recipient`, `MailSendMeasurement`, `MailSendDiagnostics`. |
| `simple-java-mail` | Bootstrap and the main implementation, including runtime-only construction and orchestration. | `SimpleJavaMail`, Email/Mailer builder implementations, `MailerImpl`, diagnostic recording and assembly. |
| Optional/provider modules | Implement their feature against core contracts; expose a narrow typed foreign-instance adapter only when needed. | Angus transport adapter, S/MIME, OpenPGP, batch support and OpenTelemetry adaptation. |

After bootstrap, application code should be able to configure an Email/Mailer, send through its public contract and inspect the results using
types from core and its declared API dependencies. Do not make a fluent return type or result accessor require a concrete implementation type
from `simple-java-mail`. This is a compile-time boundary, not a claim that sending works without the implementation at runtime.

The accepted 20 October common failure contract keeps `MailException`, its `MailFailureKind` classification and optional submission-receipt
reading in core. Generic continuing application catches therefore need no main implementation type. Existing main-owned public validation
subclasses retain their released package names and meaningful catches; moving only part of `org.simplejavamail.mailer` into core would create
a JPMS split package. Internal producer exceptions remain with their implementing modules. This introduces no result-assembly entry point.

Keep optional-feature configuration in core and its main-runtime bootstrap/builders in `simple-java-mail`. The main implementation invokes the
optional module through `ModuleLoader` when it needs the requested behavior. Module support classes and runtime bridges remain internal;
applications never have to construct or supply them. Existing core value builders are not runtime module entry points.

[ADR 0006](0006-optional-modules.md#choose-the-input-boundary-for-the-caller-accepted-2026-10-16) distinguishes plain configuration data,
already accepted types and application-owned foreign runtime objects. The third category permits a small typed adapter in the optional module
that returns a core-owned SJM wrapper contract. This one adaptation call requires the module and foreign API at compile time; continuing SJM
calls use core contracts. It does not permit foreign dependencies in core/main, a module-owned configuration builder or a public `Object` escape
hatch. Caller clarity and compile-time checking take precedence over making every setup call originate from the same artifact.

A separately justified feature-specific result may expose an underlying library type when callers need to inspect that representation. That is
a result contract with an explicit dependency, not a precedent for configuration or construction. A producer's concrete result implementation can
also live in its optional module while applications only see the core read interface. Current Outlook conversion uses that arrangement with
`OutlookEmailConversionResult` and `OutlookMessageData`; the parser and concrete implementations remain in `outlook-module`.

Core is not restricted to Java interfaces or to JDK-only dependencies. Lightweight concrete models such as `Email` and `Recipient` have long
belonged there. Keep their public reading/value behavior there, while keeping runtime collection and implementation-only assembly in the owning
implementation module. Necessary shared utilities and supported SPI construction remain separate, justified cases.

Applications construct Email through `EmailPopulatingBuilder.buildEmail()` or its documented completion variants. The constructor taking that
builder is protected and internal so the library's implementation can initialize the core read model. Application subclasses are not an extension
SPI. The obsolete defaults/overrides marker is removed: preparation bookkeeping remains on InternalEmail rather than an application-facing interface.
This deliberately retires the public constructor and marker present in released versions; callers must use the builder and recompile.

The construction boundary does not change Email's serialized identity. Released snapshots remain readable under the supported Email format,
and builder-produced subtypes retain their runtime source. A historical plain Email remains inspectable but is not normalized into a sendable
implementation; runtime operations reject it with useful builder guidance instead of an impossible-state assertion.

`EnhancedStatusCode` is a shared reading contract for SMTP responses and received delivery reports. Its immutable implementation belongs in core's
internal shared-value package because core's `SmtpServerResponse` and the main MIME parser both produce it. It has no application-facing assembly
entry point. The two source parsers keep their own interpretation rules; sharing the numeric value does not require sharing SMTP reply parsing with
tolerant notification parsing, or restricting the complete raw provider response.

The existing `OperationalConfig` / `OperationalConfigImpl` and builder-interface / builder-implementation splits are precedents for a real module
boundary even when Simple Java Mail deliberately supplies the only implementation. A second implementation is not required to justify that boundary.
Conversely, do not turn every small value class into an interface mechanically. Choose the clean contract first, then its implementation placement.

A supported public utility can contain its implementation and private helpers. Do not mirror its entire static API in an internal support class merely
to move implementation text into an `.internal` package: that adds forwarding methods without establishing a module boundary or narrowing the callable
surface. Keep the static conversion work in `EmailConverter`, factory-dependent work in `ConfiguredEmailConverterImpl`, and extract only genuinely
shared internal mapping. Internal packages identify implementation types; they do not require supported classes to become empty facades.

Configured conversion exposes the same supported conversion routes through source-specific core contracts, obtained from the main factory's
`converter()` entry point. Choosing a source and then a target makes those routes discoverable without a generic target-class lookup or unchecked
casts. This is the configured converter's single public surface; the separately supported static `EmailConverter` methods remain available.
The selected stage retains the factory snapshot; concrete conversion stages remain internal to the main module. Delegate direct MIME/EML
paths to their established implementations rather than forcing a roundtrip through the editable Email model. Export does not silently apply Email
templates: callers explicitly supply governance when wanted, and exact/protected-byte contracts remain separate from editable conversion.

Moving concrete recipient builders behind the factory must preserve their supported convenience behavior, not only their low-level setting methods.
The compact named single-recipient methods from [#705](https://github.com/bbottema/simple-java-mail/issues/705) belong on the core builder contract
returned by `recipientBuilder()`. They use the same name/address/type settings and validation as the explicit chain and retain other configured
recipient options. An internalization refactor must keep tests calling those conveniences instead of replacing the calls with longer chains.

Putting a builder in an `.internal` package is not enough if it still lives in the wrong module. In particular, a core result must not depend on
a concrete main-module assembly builder. Use a core-owned contract or a result implementation behind the read API; do not reverse the dependency
or keep main-only machinery in core merely to satisfy a constructor signature.

### Classify the consumer before exposing construction

Putting a class in an internal package does not correct the wrong module owner. Core configuration values may enforce structural invariants
and defensive copying, but must not interpret optional-module behavior. Move feature validation with its implementation and invoke it through
the core module contract when needed. Several call sites needing the same rule is not justification for core owning that rule; see
[ADR 0006](0006-optional-modules.md#feature-semantics-stay-with-the-feature-implementation).

| Role | Supported external use | Placement and construction |
| --- | --- | --- |
| Application configuration | Build Emails, Mailers and explicitly supported policy/configuration values. | Public contracts in core; concrete main-runtime builders in `simple-java-mail` internal packages. |
| Foreign-instance adaptation | Supply an existing application-owned object without losing its type or identity. | A narrow public adapter in its optional module accepts the foreign type and returns a core contract; ordinary configuration and runtime machinery stay separate. |
| Library-produced result | Inspect the returned observations, rendering and defined value semantics. | Public read API in core; runtime recording and assembly in the implementation module. |
| Supported provider SPI | Implement adapters and create the facts the SPI requires them to return. | Public SPI contracts with Lombok-generated builders where construction is needed. |
| Cross-module implementation detail | No application dependency on the type or member. | Package-private where possible; otherwise an `.internal` package with `@ApiStatus.Internal`. |

`MailSendDiagnostics` is a library-produced result. Applications receive it through `MailSendOutcome` and read its measurements and histories.
They do not manufacture diagnostics. Its assembly builder therefore belongs in the main implementation's `.internal` package, with no public
`MailSendDiagnostics.builder()` factory or nested application-facing builder type. Test convenience does not justify adding such an entry point.

Use the established read-contract/internal-implementation split for library-produced results. Do not put a public constructor bridge,
assembly builder parameter, or annotated factory on the read API merely to let another module assemble it. Assembly belongs with the producer.
Where other cross-module implementation access genuinely needs public visibility, keep it in an internal contract; avoid a synthetic
inheritance hierarchy whose only purpose is to hide methods.

Prefer [JetBrains `@ApiStatus.Internal`](https://github.com/JetBrains/java-annotations/blob/26.1.0/src/jvmMain/java/org/jetbrains/annotations/ApiStatus.java)
for this purpose. It states that an element is visible for library-internal access and must not be used externally. It is an IDE/tooling contract,
not Java access control, and does not by itself hide Javadocs. `@Deprecated` remains appropriate for retiring supported API; do not use it merely
to label new internal machinery. Neither annotation replaces the `.internal` package convention.

### Use builders instead of telescoping constructors

Do not add chains of constructor overloads that fill in progressively more optional fields. Use named builder assignments and one validation/copying
boundary. This applies to public configuration construction and internal result construction alike; the builder's owner determines its visibility.
Use Lombok for internal assembly and provider-SPI builders, while keeping invariants and defensive copies explicit. Adapter authors are a separate
audience from applications configuring Email or Mailer. SPI builders do not need the application builder's getter/reset/clear companions or
setter-owned Javadoc convention. Document the SPI's obligations and supplied facts directly; keep validation and copying at the construction boundary.

For example, provider adapters construct `SmtpConnectionReport`, while applications read it. Its builder is generated with Lombok; its application-facing
getters remain explicitly documented. `PreparedMail` and `DeliveryEnvelope` likewise use generated builders for provider integration.

Public application configuration builders are written explicitly: the setter owns the complete contract, and its getter/reset/clear methods refer
to that setter with `@see`. Implementation methods refer to their public interface. Lombok may still provide value semantics; it must not hide the
application builder contract. Public visibility alone does not decide which builder convention applies; the intended consumer does.

Provide named companions for optional choices: clear removes the supplied choice and permits established fallback; reset restores the published
built-in default. Neither clears unrelated settings. Overloads accepting different representations preserve the same unrelated values: a method
described as changing only an address must not also replace its name.

For recipient groups, the initial certificate/NOTIFY behavior preserves original recipient metadata. The named `reset...Policy()` operations
restore that preserving behavior. Existing `clearing...` operations deliberately strip the corresponding metadata and permit Email fallback;
they cannot serve as policy reset without changing their meaning. [ADR 0001](0001-email-configuration-scopes-and-inheritance.md) owns that scope
distinction. Resetting either policy must leave the other policy and previously built snapshots unchanged.

Declare application-facing getters explicitly when source-based Javadocs cannot discover generated accessors. Retain Lombok value semantics,
but verify freshly generated public HTML, setter links and exclusion of internal assembly. Compilation alone is not publication. OpenPGP
protection details use this same read-contract/internal-assembly boundary, even when several modules need to construct them.

Paired settings such as a failover Mailer and its policy remain inspectable before building. Return the supplied object and stored policy;
do not construct a named destination or start its resources just to answer a getter. A named relationship can therefore have a policy while
its live Mailer getter remains absent. The ordinary construction path resolves that destination internally without changing the supplied-choice contract.

[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md) defines message metadata snapshots and borrowed content. Builder cleanup
must preserve that ownership, serialized subtype and content-read boundary.

Small immutable fluent configuration can use the same setter/getter/reset/clear documentation discipline without introducing a separate mutable
builder. This does not change module ownership: its supported contract belongs in core and any main-runtime bootstrap belongs in `simple-java-mail`.
The typed adapter exception wraps an already supplied instance, not a parallel configuration surface. Its public method documents the accepted
foreign type, returned core contract and ownership; it does not need unrelated setting/reset methods. [ADR 0034](0034-optional-opentelemetry-integration.md)
applies this distinction to telemetry, replacing the earlier module-owned `OtelSupport.using(...)` configuration design with adaptation alone.

Multiple constructors are not automatically telescoping configuration. Keep deliberate dependency/clock injection, distinct input representations
and test seams when they clarify ownership. In particular, `MailerImpl` retains its production builder constructors and package-private test
constructors; a separate builder only for those tests would add indirection without improving the application API.

Builders collect supplied values. They do not give diagnostic reports invented durations, collect observations, or resolve configuration.
Keep configuration fallback ownership on the key builders as recorded in [ADR 0003](0003-immutable-configuration-snapshots.md).

Evaluate compatibility against released, supported contracts. Do not retain unreleased internal overloads simply because an earlier feature commit
introduced them. Existing released construction API needs an explicit compatibility decision before removal. Serialization compatibility is separate:
preserve the intended serialized fields, identifiers and absence behavior without treating constructor overloads as a serialization requirement.
For the report classes replaced with read interfaces, 10.0.0 deliberately breaks construction/binary and old Java-report-snapshot compatibility:
recompile integrations and export persisted observations before upgrading. This does not remove the supported Email, Recipient or attachment
snapshot contract. A relocated public field selector retains its historic serialized enum names through an internal compatibility representation.

OpenPGP signing/encryption configurations are immutable application values and participate in the existing Email snapshot
contract, just like S/MIME configuration. Their serializable form retains supplied material and algorithm preferences;
restoration preserves construction invariants and defensive copying without interpreting keys in core. Signing snapshots
contain the secret key ring and passphrase, even though automatic rendering hides them. Earlier snapshots which omitted
these values remain readable with absent OpenPGP configuration. This is separate from public construction changes and
does not introduce a restoration API or change exact EML's authoritative-source boundary; see ADR 0036.

### Consistent body vocabulary

Use the same body names throughout Email composition and inspection: PlainText, HTMLText and CalendarText. Setter, append/prepend,
clear and getter families keep that noun order on both the builder and the Email value. For 10.0.0, replace ambiguous plain-Text
companions and reversed TextHTML/TextCalendar names without deprecated forwarding aliases. Preserve body content, append/prepend order,
null handling, file/path ownership, serialized fields and existing CLI option names. This is a Java naming correction, not a MIME or
validation change. Record exact released method mappings and target validation in the OpenRewrite plan.

### Attachment resource construction

Keep short Email-builder conveniences for file paths, bytes and DataSources, including the required encoding choice for already encoded input.
Advanced attachment and embedded-image metadata belongs on an explicit core-owned `AttachmentResource` builder, with named choices for content,
name, description, Content-ID and transfer encoding. Accept a constructed resource through singular attachment/image additions as well as the
existing collection additions. Keep normal encoding and already encoded content distinct.

For 10.0.0, replace public positional resource constructors/factories and long Email-builder overloads that append optional metadata with this
builder. Do not retain deprecated forwarding aliases or mirror every input/optional-field combination. The short Email conveniences remain
direct and delegate to the same resource construction boundary. Internal CLI adapters may preserve scalar command syntax without restoring
removed application methods. Preserve useful contracts and generated CLI help when moving configuration.

Resource construction preserves borrowed DataSource identity, copies supplied byte content and keeps paths lazy. It performs no content reads
or filesystem probing. Preserve supported serialization, MIME metadata and encoding behavior; the construction cleanup does not redefine public
resource equality or the conservative template matching in [ADR 0037](0037-repeatable-email-completion.md). Migration tooling must inventory
removed signatures against released versions and validate rewritten consumers against a frozen target; see the
[OpenRewrite plan](../research/10.0.0-openrewrite-migration-implementation-plan.md).

### Inspect incomplete configuration without building it

Application builder getters expose stored choices without validating an incomplete configuration or resolving provider-dependent defaults.
For example, a retry attempt count can be inspected before its delay is supplied, and an omitted failover cap remains absent until construction
resolves its relationship with the initial interval. Mutable inspection results are defensive copies. Optional provider-resolved choices use
named clears that restore absence, rather than resets that introduce a new provider default.

Validation-boundary clarification accepted 2026-10-21: setters collect supplied choices, passive getters inspect them, and the declared
construction boundary validates the complete value. Required-argument checks and documented external-representation parsing remain at their
input boundaries. Cross-field and semantic validation must not happen accidentally because a convenience setter or getter constructs a
nested completed value. Inspection of incomplete choices returns detached stored state; it does not resolve defaults, consume borrowed
resources or require temporarily inconsistent choices to form a valid immutable object. A completed value's getters read its validated state.

An explicitly invoked standalone value factory or builder is its own construction boundary and retains its invariants. Effective rules that
depend on inherited configuration are still checked at the owning feature's declared boundary under ADR 0003; passive inspection is not a
way to postpone an already-required configuration failure or weaken production validation.

For grouped SMTP choices, remove the aggregate DSN getter from `SmtpDeliveryOptions.Builder` and retain its passive component getters.
`SmtpDeliveryOptions.build()` privately constructs and validates the complete DSN; the completed value retains `getDeliveryStatusNotification()`.
Equivalent DSN convenience setters collect choices consistently, preserving replacement, clearing and explicit-request semantics. Standalone
`DeliveryStatusNotification.build()` and `of(...)` remain explicit validated-value construction. No invalid immutable DSN, public draft type
or additional construction method is introduced to support inspection. This clarification is implemented under
[#825](https://github.com/bbottema/simple-java-mail/issues/825) for unreleased 10.0.0.

Clarification accepted 21 October 2026: this also applies to the Mailer builder's grouped retry-policy getter. It returns the supplied
`MailSendRetryPolicy` choices, including omissions and explicit clears, without resolving or validating the effective policy. Inspecting
an already-created builder must not fail because its choices are incomplete. Effective retry settings belong on `OperationalConfig` after
Mailer construction; configuration validation remains a separate construction responsibility. This aligns the retry group with the existing
inspection rule rather than introducing an exception to it.

[ADR 0003's fail-fast validation principle](0003-immutable-configuration-snapshots.md#fail-fast-validation-and-passive-inspection)
governs incoherent inherited rules at their declared construction boundary. A constructor rejecting invalid inherited configuration is
different from a getter inspecting an unfinished Java builder; inspection must not trigger or repeat that validation.

Custom SSL-factory class and instance choices follow this same inspection convention. Expose the stored instance and named clears for both
choices; retain the established rule that a supplied instance takes precedence over a class name. Clearing one choice leaves the other available.

This includes DSN, PKCS12 and OpenPGP application configuration builders. Inspection also works while supplied choices are temporarily invalid;
copy helpers used by getters must not accidentally run construction-time validation. Collection inspection detaches nested mutable arrays, and
callback inspection returns the supplied callback without invoking it. Clearing drops the builder's choice without wiping material retained by
previously built values. Required input material does not acquire an invented reset value or additional validity rules merely to complete inspection.

Debug printers and built-in debug destinations are alternate ways to select one output in `SessionDebugConfig`. `useConfiguredOutput()`
restores captured inheritance; `clearPrinter()` removes only a custom registration, and `resetOutput()` selects standard output. These choices
never close borrowed streams or change activation. `MailSendTelemetryConfig` similarly separates integration, activation and endpoint permission.
Both are immutable core-owned application configuration values with explicit builders; their unset fields are resolved by the owning Mailer.

`MailSendHandlers` groups independent observer/executor registrations without owning runtime dispatch. A Mailer-wide factory produces a fresh
group for each generated send UUID. The generic factory and supplier signatures use only core/JDK contracts; runtime routing and bounded
delivery remain in the main implementation. Companion Javadocs link to the setter that owns each contract.

## Alternatives considered

- **Treat every public member as supported API:** exposes machinery that is only public for package/module access and makes it needlessly permanent.
- **Leave a public builder and say users normally do not need it:** still advertises unsupported construction through completion, docs and examples.
- **Only deprecate direct Email construction:** preserves linkage but continues offering plain objects that normal runtime operations cannot use.
  The major-release boundary is the appropriate point to require the existing supported builder route.
- **Normalize every plain Email or application subtype at runtime:** would broaden source, protection and generated-value ownership merely to
  support another construction route. Keep one supported construction path and preserve readable serialized data separately.
- **Use annotations without internal packages:** useful for the unavoidable bridge, insufficient for a whole implementation type.
- **Keep adding positional overloads:** hides the meaning of adjacent values and repeats defaults across construction paths.
- **Convert every result class into an interface immediately:** can introduce needless implementations and serialization changes; classify and correct
  each boundary rather than perform a mechanical repository-wide conversion.
- **Hide all report construction:** breaks supported provider adapters that must supply probe or transport facts. SPI construction is a separate role.
- **Apply application-builder conventions to every public SPI builder:** adds repetitive methods and documentation for adapter authors who already
  work against the provider contract. Generated builders are sufficient when invariants and field meanings remain clear.
- **Forbid every application call into an optional module:** would force an untyped input or another core dependency for existing foreign objects.
  A typed adapter is a clearer, deliberately limited boundary; it does not expose module runtime machinery or relocate ordinary configuration.

## Consequences and verification

Accepted recipient follow-up, 20 October 2026: `IRecipientBuilder.withAddress(String)` must honor its documented lenient
named-mailbox interpretation. Extract the address and embedded display name from one parseable mailbox. A separately supplied
name wins in either setter order; removing that explicit name restores the embedded name. Replacing the address replaces its
inferred name. Retain malformed input, groups and multiple-mailbox strings for normal validation rather than silently selecting
one recipient. Existing explicit first-mailbox helpers keep their contracts, including address-only name preservation.
This supersedes the earlier decision to leave this setter unchanged during the Email convenience migration.

The supported surface becomes easier to discover and internal assembly can evolve independently. Internal builders and shared contracts remain technically
callable where Java visibility permits it; this is library API discipline, not a sandbox.

Check the generated public Javadocs as well as source placement. Application classpath/JPMS consumers must demonstrate inspection of returned results;
internal unit tests may construct them through the internal builder. Provider fixtures separately exercise supported SPI construction.

Add a separate consumer check that compiles the continuing builder/send/result calls against core and its declared dependencies, without the main
implementation on the compile path. Supply the entry contracts as parameters; bootstrap and runtime integration belong in a separate check.
Check public generic arguments and callback types as well as direct return types.

Test foreign-instance adaptation in a separate optional-module consumer. Its public input must be typed, its return type must belong to core,
and the subsequent configuration/send calls must not need a module-specific subtype. Check wrong-type compilation failures, unchanged resource
ownership and absence of feature-library dependencies from core/main. Property-based activation remains a separate module-present/absent check.

Preserve immutable results, validation, collection copying and serialization. Verify that unobserved sends still avoid diagnostic construction.
Add a regression for removed builder factories/overload chains so a future refactor cannot quietly make them application API again.

## Related decisions and workflow

- [ADR 0032](0032-live-mail-send-progress.md): `MailSendProgress` is a core read interface; its immutable implementation and Lombok assembly builder
  remain in the sending implementation's internal package. Provider progress is a separate, narrow callback SPI, not an application snapshot builder.

- [ADR 0006](0006-optional-modules.md): shared contracts and implementation placement across modules.
- [ADR 0024](0024-builder-interfaces-are-not-extension-spis.md): application builders are library-owned contracts, not extension SPIs.
- [ADR 0026](0026-actual-send-diagnostics.md): public observations and internal report construction.
- [ADR 0020](0020-dedicated-smtp-connection-diagnostics.md): provider-created probe facts require a distinct SPI review.
- [API expansion workflow](../API_EXPANSION_WORKFLOW.md#public-api-versus-internal-cross-module-types) and
  [maintainer workflow](../MAINTAINER_WORKFLOW.md#5-implement): apply the boundary during each API change.

## Accepted API consistency follow-up (20 October 2026)

Notification destinations use plural collection getters on Email and its builder, without singular compatibility getters. MailSendRetryPolicy and FailoverPolicy expose explicit toBuilder() methods that copy every stored setting into an independent builder. SMTP empty credential strings normalize to absence through all ordinary setters; partial-update overloads still change only accepted fields. These choices belong to the unreleased 10.0.0 migration and do not introduce default-name overload families or new optional-module boundaries.


## OpenPGP receive decryption drafts (accepted 23 October 2026)

The passive-inspection rule applies equally to verification and decryption inputs in OpenPgpReceiveConfig. Reject required null arrays/passphrases before changing choices and copy the supplied arrays, but permit unfinished empty key material while collecting and inspecting the builder. Completed OpenPgpReceiveConfig construction validates that every retained key is nonempty. Builder-returned key entries are detached immutable snapshots of supplied choices, not another completed receive-configuration boundary.

Keep the existing public read types without introducing a draft factory. Getter calls do not validate, invoke passphrase callbacks or load cryptographic modules. Clearing a builder does not alter earlier entries or completed configurations; key/passphrase arrays remain copied and secret output remains redacted. Cryptographic interpretation stays with the optional module. [#864](https://github.com/bbottema/simple-java-mail/issues/864) tracks this correction for unreleased 10.0.0.

## Connection conflict policy (accepted 24 October 2026)

ADR 0038 keeps connection conflict handling at Mailer construction. Builders continue collecting final choices, getters inspect passively and repeated setters edit those choices normally. A required policy rejects null before mutation; reset selects the strict fallback and useConfigured restores captured inheritance. Construction errors retain safe setting/source information without a new public resolution-report API. See [ADR 0038](0038-connection-configuration-conflicts.md), accepted under [#867](https://github.com/bbottema/simple-java-mail/issues/867). Implementation is tracked separately from design acceptance.

## Shared configuration resolution (accepted 25 October 2026)

[ADR 0039](0039-explicit-configuration-resolution.md) places the shared resolution implementation in the main module's internal configresolution package. Core retains its public input contracts and schema. ConfigurationChoice, ConfigurationDeclaration, ConfigurationOrigin and ConfigurationResolution are internal typed values; they do not add application construction APIs. CapturedConnectionInputs and ResolvedConnection feed a controlled ValidatedConnectionPlan boundary before Session effects. Getters remain passive, ordinary internal assembly uses Lombok when useful, and no generic phase-service hierarchy is required.

Implemented for unreleased 10.0.0 under [#867](https://github.com/bbottema/simple-java-mail/issues/867); publication remains pending.

## Shared layered resolution (accepted 26 October 2026)

The complete layered adoption in ADR 0039 remains main-owned internal machinery. Core contracts/value builders and optional feature contracts retain their current module boundaries. Public API signatures, passive group inspection and established setter/build validation contracts remain intact; no public rule registry or synthetic application-plan type is added for simple selection. See [ADR 0039](0039-explicit-configuration-resolution.md) and [#867](https://github.com/bbottema/simple-java-mail/issues/867).
