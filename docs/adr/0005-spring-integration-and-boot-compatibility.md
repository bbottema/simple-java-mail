# ADR 0005: Share Spring construction and support Boot generations through one integration

- Status: Accepted (retrospective record of implemented design)
- Recorded: 2026-09-16
- Applies to: Unreleased 10.0.0 Spring integration, Java 11 library baseline
- Implementation: Committed in `fffb32c2`

## Context and historical rationale

Plain Spring integration already read the `Environment` and supplied a Mailer, but required explicit `@Import`. Boot metadata was not auto-configuration: [539e3f23](https://github.com/bbottema/simple-java-mail/commit/539e3f236ef92d9bd17e43423e743743e2e8ce8c), 8 October 2023, describes #475 as IDE property hints with a build-time Boot dependency.

[#714](https://github.com/bbottema/simple-java-mail/issues/714) asks Boot to discover the existing construction path, preserve framework precedence, allow application replacements and clean up the Mailer it creates. It explicitly asks whether one artifact can support Boot 2.7 and 3.x without raising the Java baseline for other users; separate generation-specific artifacts were the fallback if that proved impractical.

[fffb32c2](https://github.com/bbottema/simple-java-mail/commit/fffb32c2c0f70dfc66a41223e1c5532e40582b0b), 1 September 2026, implements the common artifact. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/714#issuecomment-5499266494) confirms individual bean replacement and application-managed Boot, Spring and SLF4J versions.

## Decision

Put auto-configuration in `spring-module`; publish `simple-java-mail-spring-boot-starter` as a dependency-only convenience artifact. Register discovery through `AutoConfiguration.imports`, with no `spring.factories` fallback. Retain manual `SimpleJavaMailSpringSupport` for plain Spring applications.

Both entry points delegate to `SimpleJavaMailSpringBeanFactory`. It loads one context-local immutable snapshot, creates the configured `SimpleJavaMail` factory and builds the default Mailer. `SpringEnvironmentConfigSource` owns property mapping, compatibility aliases and wildcard discovery. Resolve Spring values through `Environment`; do not append a second raw system/environment overlay that could defeat application profiles or placeholders.

Apply `@ConditionalOnMissingBean` separately to `SimpleJavaMailConfig`, `SimpleJavaMail` and `Mailer`. Replacing an earlier layer feeds subsequent defaults; replacing only Mailer leaves the other injectable defaults available. Resolve an application `OAuth2AccessTokenProvider` when building the default Mailer. Declare `close` on that default bean's lifecycle. Application replacement beans retain their own lifecycle declarations; Spring may independently infer a close method on a user bean.

Compile production integration against Boot 2.7 APIs with Java 11 bytecode. Keep Boot, Spring and SLF4J dependencies provided, allowing the consuming application's parent/BOM to select its runtime generation. Verify compatibility through a matrix of Boot/Spring/JDK combinations instead of exporting a Maven version range or scattering runtime generation checks.

## Alternatives and consequences

The issue's generation-specific-artifact fallback was not needed for the implemented API surface. One integration avoids duplicate mapping and lifecycle logic, but compatibility remains an explicit tested boundary rather than a promise about every future Boot release.

Putting all logic in the starter would require existing `spring-module` users to change dependencies and split manual and automatic construction. Sharing the factory prevents those paths drifting; that tradeoff is inferred from the implementation structure, not a separately recorded historical debate.

Direct `spring-module` use in a Boot application can now activate auto-configuration. Applications can override the three layers independently or retain explicit plain-Spring setup. The existing lowest-priority Spring `localhost` default remains; automatic discovery does not change SMTP policy or imply that a server exists there.

The metadata-only `SimpleJavaMailProperties` model supplies IDE hints; it is not a parallel runtime configuration binder. New scalar properties flow through the canonical schema and Environment adapter, with packaging tests checking metadata coverage. See [ADR 0003](0003-immutable-configuration-snapshots.md) and the [API expansion workflow](../../API_EXPANSION_WORKFLOW.md).

## Implementation evidence

- [SimpleJavaMailAutoConfiguration](../../modules/spring-module/src/main/java/org/simplejavamail/springsupport/SimpleJavaMailAutoConfiguration.java), [SimpleJavaMailSpringBeanFactory](../../modules/spring-module/src/main/java/org/simplejavamail/springsupport/SimpleJavaMailSpringBeanFactory.java) and [SpringEnvironmentConfigSource](../../modules/spring-module/src/main/java/org/simplejavamail/springsupport/SpringEnvironmentConfigSource.java).
- [Spring module POM](../../modules/spring-module/pom.xml), [starter POM](../../modules/spring-boot-starter/pom.xml) and the [compatibility workflow](../../.github/workflows/spring-boot-compatibility.yml). At recording, its lanes are Boot 2.7.18/Java 11, Boot 3.0.13/Java 17 and Boot 3.5.16/Java 21; these are repository configuration, not a claim that CI was run during ADR extraction.
- Existing [Boot discovery tests](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SimpleJavaMailSpringSupportBootTest.java), [plain-Spring runtime isolation tests](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SimpleJavaMailPlainSpringRuntimeIsolationTest.java) and [metadata packaging tests](../../modules/spring-module/src/test/java/org/simplejavamail/springsupport/SpringModulePackagingTest.java).
