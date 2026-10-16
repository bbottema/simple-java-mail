# ADR 0006: Load optional features behind core contracts

- Status: Accepted (retrospective record of implemented design)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Existing module architecture, including unreleased 10.0.0 OpenPGP support
- Implementation status: Implemented; module-cache concurrency fix inherited from 9.3.3
- Input-boundary clarification: Accepted, 2026-10-16; the typed OpenTelemetry adapter is implemented and reviewed under #762 for unreleased 10.0.0.

## Context

The library supports uses with very different dependencies: ordinary SMTP sending, Outlook conversion, authenticated SOCKS, pooling, DKIM, S/MIME, and OpenPGP. Making every implementation mandatory would impose their dependencies on applications that never use those features. Merely marking Maven dependencies optional is insufficient if ordinary code still references their implementation classes.

The original [#183](https://github.com/bbottema/simple-java-mail/issues/183) explicitly sought clearer optional dependencies, better organization, and removal of cycles between the main project and support code. The [maintainer's conclusion](https://github.com/bbottema/simple-java-mail/issues/183#issuecomment-460091836) kept Maven modules together in one repository after finding separate Git submodules harder to understand and awkward for tooling.

## Decision

Keep feature contracts and shared data types in `core-module`, implement each optional feature in its own Maven module, and let the main implementation load that module by implementation-class name when needed. Use the core interface after loading; do not import an optional implementation into ordinary main-module code.

The same direction applies to the main API: `core-module` is the API-bearing artifact, while `simple-java-mail` provides bootstrap and the main
implementation. Core can contain necessary shared internals, but not arbitrary main-only machinery. [ADR 0031](0031-public-api-and-internal-construction.md)
distinguishes module ownership from public/internal package visibility.

### Application configuration stays in core and the main builder API

Applications request and configure a feature through the core API and its builder contracts. Main-runtime builder and bootstrap entry points
belong in `simple-java-mail`; continuing contracts belong in core. Existing core-owned value construction, such as `Pkcs12Config.builder()`,
does not require a runtime module. The optional module delivers the behavior promised by the API, rather than providing a second configuration surface.

At the point where the requested behavior needs the module, the main implementation calls `ModuleLoader` and invokes the returned core
module contract. If that requested module is missing, let the focused module-loading error reach the caller. Do not make applications
load the runtime module themselves or supply its internal bridge.
Collecting configuration does not by itself require constructing a module runtime.

### Choose the input boundary for the caller (accepted 2026-10-16)

Prefer an API that makes the required input clear and catches mistakes at compile time. A single-looking builder surface is not worth hiding
the type of an application-owned object. Distinguish three categories:

| Input category | Public API shape | Where adaptation happens |
| --- | --- | --- |
| Plain configuration data | Primitives, strings, bytes and neutral configuration values. | Core/main collect the data; the optional module constructs its library-specific objects when needed. |
| Already accepted types | JDK types, SJM types and types from approved foundational dependencies, such as Jakarta Mail and JMail. | Use these types directly in the core contract; no additional feature dependency is needed. |
| Application-owned foreign runtime objects | A small, typed adapter in the optional module accepts the foreign public type and returns an SJM core wrapper contract. | The adapter preserves the supplied instance; the loaded runtime module later uses it behind the core boundary. |

For example, an X.509 certificate is a JDK type. An application's configured OpenTelemetry instance is a foreign runtime object whose identity,
providers and lifecycle cannot be reconstructed from a few scalar settings. Its adapter may expose a typed `wrap(OpenTelemetry)` entry point.
It must not become another telemetry configuration builder, an SDK factory or the runtime support object itself.

This is a narrow exception to module-independent application setup, not to dependency isolation. Calling the typed adapter explicitly requires
that optional module and its foreign API on the application's compile/runtime path. After adaptation, continuing SJM configuration and sending
use core contracts. Core/main must not acquire a direct feature-library dependency, even an optional API-only dependency, just to expose the input.
An existing foundational dependency is not precedent for promoting every optional library into core.

Do not replace the foreign type with a public `Object`, `Supplier<Object>` or nominally neutral interface that only hides an untyped value.
Those shapes move a useful compiler check into runtime failure and obscure what the caller must supply. Internal cross-module storage or
unwrapping is a separate implementation concern; it must not leak recording/binding machinery into the application contract.

The adapter only wraps the supplied object. It does not initialize the foreign library, install a global instance, start instrumentation,
configure exporters or take ownership of resources. Ordinary settings, defaults, properties and locks still belong to the existing core/main
configuration owners. Do not add a concrete-class or internal-bridge requirement to their public setters; use the supported contract.

### Feature-specific results are a separate exception

There can be a deliberate exception for inspecting a feature-specific result. A result implementation can live in its producing module,
and exposing a type from an underlying library may be justified when callers explicitly need that library's result. Record that dependency
as part of the supported result contract; it does not authorize a module-owned configuration API. Prefer a core read contract
when it conveys the needed facts. Current Outlook conversion follows that preference: `OutlookEmailConversionResult` and `OutlookMessageData`
are core contracts, while their implementations and parser adaptation live in `outlook-module`.

### Runtime loading and shared instances

`ModuleLoader` lazily caches one instance per module interface using `ConcurrentHashMap.computeIfAbsent`. Optional feature implementations must therefore tolerate shared concurrent use. Class-availability checks let selected paths degrade deliberately: absent batch support means direct transports, while requesting an unavailable signing module raises a focused error. Module absence is not permission to silently omit requested protection.

OpenPGP follows the same boundary. Its PGPainless implementation belongs to `openpgp-module`; adding it does not impose OpenPGP dependencies on ordinary senders. The separate provider adapter SPI is covered by [ADR 0007](0007-provider-neutral-mime-boundary.md): replaceable transport providers and the fixed set of bundled optional feature modules use different discovery contracts.

### OpenTelemetry configuration and runtime loading (updated 2026-10-16)

[ADR 0034](0034-optional-opentelemetry-integration.md) follows the ordinary loading contract: enabled Java or property configuration
requests `ModuleLoader.loadOpenTelemetryModule()`. Its cached entry point is stateless and creates a separate Mailer-owned runtime.
Applications use core telemetry configuration and the main builder API, or enable the feature through factory properties. The configuration
object does not implement lifecycle machinery. Supplying an existing OpenTelemetry instance uses the typed-adapter exception above; only that
adaptation entry point is public in the optional module. Runtime support and lifecycle bridges remain internal.
Never cache application SDKs in ModuleLoader or activate from classpath presence alone. Property-only activation does not require calling the
adapter; the loaded module uses the application's global instance. See ADR 0034 for the concrete application of this boundary.

## Rationale and evidence

- [cb5ce64f](https://github.com/bbottema/simple-java-mail/commit/cb5ce64f269381298f811bfd52692a1feaec071e) moved modularized code into Maven modules and fixed their dependency relationships for #183. The issue records the organizational motivation; reflection is the implemented means of avoiding mandatory implementation linkage.
- [#465](https://github.com/bbottema/simple-java-mail/issues/465) documents a regression where a Message-ID fix introduced hard DKIM/S/MIME references. [3a86aed5](https://github.com/bbottema/simple-java-mail/commit/3a86aed5555178fdd80c240d783b743c2652c0b9) removed that dependency. This is concrete evidence that dependency declarations alone do not preserve optionality.
- [#720](https://github.com/bbottema/simple-java-mail/issues/720) reported the unsafe `HashMap` cache. [62f9b472](https://github.com/bbottema/simple-java-mail/commit/62f9b472defaa43f535b671d0e4f76505cabf13f) made construction atomic; the [release comment](https://github.com/bbottema/simple-java-mail/issues/720#issuecomment-5551459169) explicitly states that concurrent first use must reuse one module instance.
- [#704's completion record](https://github.com/bbottema/simple-java-mail/issues/704#issuecomment-5320304898) confirms optional OpenPGP integration through the normal Mailer path in the 10.0.0 work.

## Alternatives

**Separate Git repositories/submodules** were tried and abandoned in #183. **Direct optional-implementation references** were shown incompatible with the intended runtime boundary by #465. **Eagerly constructing every optional module** would defeat lazy availability and is inconsistent with the chosen design; no separate historical vote on eager construction was found. This record does not claim that `ServiceLoader` was historically evaluated and rejected for feature modules.

The 2026-10-16 input decision also considered a uniform main-builder API accepting `Object` or an untyped supplier, and adding the foreign API
dependency to core/main. The former sacrifices caller guidance and compile-time safety; the latter makes an optional feature part of the ordinary
dependency surface. A typed module adapter introduces one explicit import for applications already using that library, while preserving both.
Moving all feature configuration into that adapter was rejected: adaptation of an existing instance does not justify parallel configuration owners.

## Consequences

The ordinary runtime stays small and feature-specific failures occur at use. The cost is reflective class-name coupling, runtime rather than compile-time detection of missing modules, and shared implementation instances that require thread-safe behavior. Cached availability is not a hot-plugging API; the force-disable/recheck hooks exist for tests.

The typed adapter is the explicit exception to runtime-only module availability: callers using it need its artifact at compile time as well.
Verify it separately from core-only continuation, including rejection of wrong input types at compile time and preservation of the supplied
instance and its ownership. Verify that ordinary property/core activation still reports a missing module through ModuleLoader, and that merely
adapting an instance does not start runtime work. The [#762 research and verification record](../research/762-opentelemetry-integration-verification.md) retains its evidence.

When adding a feature module, cover both presence and absence on the real runtime path. An all-modules reactor test alone cannot detect another #465. Keep constructor side effects and singleton state small enough that concurrent first use remains safe.

## Implementation anchors

[ModuleLoader](../../modules/simple-java-mail/src/main/java/org/simplejavamail/internal/moduleloader/ModuleLoader.java), [core module contracts](../../modules/core-module/src/main/java/org/simplejavamail/internal/modules), [optional dependency declarations](../../modules/simple-java-mail/pom.xml), [OpenPGP module dependencies](../../modules/openpgp-module/pom.xml), and [ModuleLoaderTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/internal/moduleloader/ModuleLoaderTest.java) establish the current boundary. These tests were inspected as implementation evidence; this documentation pass does not claim a new runtime test run.
