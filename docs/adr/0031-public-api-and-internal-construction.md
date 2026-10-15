# ADR 0031: Keep internal construction out of the application API

- Status: Accepted
- Decision date: 2026-10-13
- Target: 10.0.0
- Related issue: [#756](https://github.com/bbottema/simple-java-mail/issues/756)

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

### Keep the API module independent of its implementations

Use the existing artifacts; this decision does not rename `core-module` or introduce a second API artifact.

| Maven module | Responsibility | Examples |
| --- | --- | --- |
| `core-module` | Application-facing contracts, shared values and narrow cross-module contracts. | `Mailer`, Email/Mailer/recipient builder interfaces, `Email`, `Recipient`, `MailSendMeasurement`, `MailSendDiagnostics`. |
| `simple-java-mail` | Bootstrap and the main implementation, including runtime-only construction and orchestration. | `SimpleJavaMail`, Email/Mailer builder implementations, `MailerImpl`, diagnostic recording and assembly. |
| Optional/provider modules | Implement their feature against core contracts without making that implementation mandatory for other features. | Angus transport adapter, S/MIME, OpenPGP and batch support. |

After bootstrap, application code should be able to configure an Email/Mailer, send through its public contract and inspect the results using
types from core and its declared API dependencies. Do not make a fluent return type or result accessor require a concrete implementation type
from `simple-java-mail`. This is a compile-time boundary, not a claim that sending works without the implementation at runtime.

Core is not restricted to Java interfaces or to JDK-only dependencies. Lightweight concrete models such as `Email` and `Recipient` have long
belonged there. Keep their public reading/value behavior there, while keeping runtime collection and implementation-only assembly in the owning
implementation module. Necessary shared utilities and supported SPI construction remain separate, justified cases.

The existing `OperationalConfig` / `OperationalConfigImpl` and builder-interface / builder-implementation splits are precedents for a real module
boundary even when Simple Java Mail deliberately supplies the only implementation. A second implementation is not required to justify that boundary.
Conversely, do not turn every small value class into an interface mechanically. Choose the clean contract first, then its implementation placement.

A supported public utility can contain its implementation and private helpers. Do not mirror its entire static API in an internal support class merely
to move implementation text into an `.internal` package: that adds forwarding methods without establishing a module boundary or narrowing the callable
surface. Keep the static conversion work in `EmailConverter`, factory-dependent work in `ConfiguredEmailConverterImpl`, and extract only genuinely
shared internal mapping. Internal packages identify implementation types; they do not require supported classes to become empty facades.

Putting a builder in an `.internal` package is not enough if it still lives in the wrong module. In particular, a core result must not depend on
a concrete main-module assembly builder. Use a core-owned contract or a result implementation behind the read API; do not reverse the dependency
or keep main-only machinery in core merely to satisfy a constructor signature.

### Classify the consumer before exposing construction

| Role | Supported external use | Placement and construction |
| --- | --- | --- |
| Application configuration | Build Emails, Mailers and explicitly supported policy/configuration values. | Public contracts in core; concrete main-runtime builders in `simple-java-mail` internal packages. |
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

## Alternatives considered

- **Treat every public member as supported API:** exposes machinery that is only public for package/module access and makes it needlessly permanent.
- **Leave a public builder and say users normally do not need it:** still advertises unsupported construction through completion, docs and examples.
- **Use annotations without internal packages:** useful for the unavoidable bridge, insufficient for a whole implementation type.
- **Keep adding positional overloads:** hides the meaning of adjacent values and repeats defaults across construction paths.
- **Convert every result class into an interface immediately:** can introduce needless implementations and serialization changes; classify and correct
  each boundary rather than perform a mechanical repository-wide conversion.
- **Hide all report construction:** breaks supported provider adapters that must supply probe or transport facts. SPI construction is a separate role.
- **Apply application-builder conventions to every public SPI builder:** adds repetitive methods and documentation for adapter authors who already
  work against the provider contract. Generated builders are sufficient when invariants and field meanings remain clear.

## Consequences and verification

The supported surface becomes easier to discover and internal assembly can evolve independently. Internal builders and shared contracts remain technically
callable where Java visibility permits it; this is library API discipline, not a sandbox.

Check the generated public Javadocs as well as source placement. Application classpath/JPMS consumers must demonstrate inspection of returned results;
internal unit tests may construct them through the internal builder. Provider fixtures separately exercise supported SPI construction.

Add a separate consumer check that compiles the continuing builder/send/result calls against core and its declared dependencies, without the main
implementation on the compile path. Supply the entry contracts as parameters; bootstrap and runtime integration belong in a separate check.
Check public generic arguments and callback types as well as direct return types.

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
