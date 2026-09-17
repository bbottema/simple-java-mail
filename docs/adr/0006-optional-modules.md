# ADR 0006: Load optional features behind core contracts

- Status: Accepted (retrospective record of implemented design)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Existing module architecture, including unreleased 10.0.0 OpenPGP support
- Implementation status: Implemented; module-cache concurrency fix inherited from 9.3.3

## Context

The library supports uses with very different dependencies: ordinary SMTP sending, Outlook conversion, authenticated SOCKS, pooling, DKIM, S/MIME, and OpenPGP. Making every implementation mandatory would impose their dependencies on applications that never use those features. Merely marking Maven dependencies optional is insufficient if ordinary code still references their implementation classes.

The original [#183](https://github.com/bbottema/simple-java-mail/issues/183) explicitly sought clearer optional dependencies, better organization, and removal of cycles between the main project and support code. The [maintainer's conclusion](https://github.com/bbottema/simple-java-mail/issues/183#issuecomment-460091836) kept Maven modules together in one repository after finding separate Git submodules harder to understand and awkward for tooling.

## Decision

Keep feature contracts and shared data types in `core-module`, implement each optional feature in its own Maven module, and let the main implementation load that module by implementation-class name when needed. Use the core interface after loading; do not import an optional implementation into ordinary main-module code.

`ModuleLoader` lazily caches one instance per module interface using `ConcurrentHashMap.computeIfAbsent`. Optional feature implementations must therefore tolerate shared concurrent use. Class-availability checks let selected paths degrade deliberately: absent batch support means direct transports, while requesting an unavailable signing module raises a focused error. Module absence is not permission to silently omit requested protection.

OpenPGP follows the same boundary. Its PGPainless implementation belongs to `openpgp-module`; adding it does not impose OpenPGP dependencies on ordinary senders. The separate provider adapter SPI is covered by [ADR 0007](0007-provider-neutral-mime-boundary.md): replaceable transport providers and the fixed set of bundled optional feature modules use different discovery contracts.

## Rationale and evidence

- [cb5ce64f](https://github.com/bbottema/simple-java-mail/commit/cb5ce64f269381298f811bfd52692a1feaec071e) moved modularized code into Maven modules and fixed their dependency relationships for #183. The issue records the organizational motivation; reflection is the implemented means of avoiding mandatory implementation linkage.
- [#465](https://github.com/bbottema/simple-java-mail/issues/465) documents a regression where a Message-ID fix introduced hard DKIM/S/MIME references. [3a86aed5](https://github.com/bbottema/simple-java-mail/commit/3a86aed5555178fdd80c240d783b743c2652c0b9) removed that dependency. This is concrete evidence that dependency declarations alone do not preserve optionality.
- [#720](https://github.com/bbottema/simple-java-mail/issues/720) reported the unsafe `HashMap` cache. [62f9b472](https://github.com/bbottema/simple-java-mail/commit/62f9b472defaa43f535b671d0e4f76505cabf13f) made construction atomic; the [release comment](https://github.com/bbottema/simple-java-mail/issues/720#issuecomment-5551459169) explicitly states that concurrent first use must reuse one module instance.
- [#704's completion record](https://github.com/bbottema/simple-java-mail/issues/704#issuecomment-5320304898) confirms optional OpenPGP integration through the normal Mailer path in the 10.0.0 work.

## Alternatives

**Separate Git repositories/submodules** were tried and abandoned in #183. **Direct optional-implementation references** were shown incompatible with the intended runtime boundary by #465. **Eagerly constructing every optional module** would defeat lazy availability and is inconsistent with the chosen design; no separate historical vote on eager construction was found. This record does not claim that `ServiceLoader` was historically evaluated and rejected for feature modules.

## Consequences

The ordinary runtime stays small and feature-specific failures occur at use. The cost is reflective class-name coupling, runtime rather than compile-time detection of missing modules, and shared implementation instances that require thread-safe behavior. Cached availability is not a hot-plugging API; the force-disable/recheck hooks exist for tests.

When adding a feature module, cover both presence and absence on the real runtime path. An all-modules reactor test alone cannot detect another #465. Keep constructor side effects and singleton state small enough that concurrent first use remains safe.

## Implementation anchors

[ModuleLoader](../../modules/simple-java-mail/src/main/java/org/simplejavamail/internal/moduleloader/ModuleLoader.java), [core module contracts](../../modules/core-module/src/main/java/org/simplejavamail/internal/modules), [optional dependency declarations](../../modules/simple-java-mail/pom.xml), [OpenPGP module dependencies](../../modules/openpgp-module/pom.xml), and [ModuleLoaderTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/internal/moduleloader/ModuleLoaderTest.java) establish the current boundary. These tests were inspected as implementation evidence; this documentation pass does not claim a new runtime test run.
