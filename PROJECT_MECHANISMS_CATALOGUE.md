# Project Mechanisms Catalogue

This catalogue records project mechanisms that are easy to miss because they span modules, build steps, generated files, or runtime classpath behavior. It is meant to be read alongside [DEVELOPMENT.md](DEVELOPMENT.md) and [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md).

## Quick Index

| Mechanism | Main reason it exists | Primary anchors |
| --- | --- | --- |
| API expansion workflow | Keep model, builders, CLI, config, conversion, and modules in sync when the public API grows. | [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md) |
| Immutable configuration snapshots | Resolve ordered sources once and propagate one detached configuration through a factory, its builders, conversion, governance, Sessions, Spring, and CLI. | [ConfigLoader.java](modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java), [SimpleJavaMailConfig.java](modules/core-module/src/main/java/org/simplejavamail/config/SimpleJavaMailConfig.java), [SimpleJavaMail.java](modules/simple-java-mail/src/main/java/org/simplejavamail/api/SimpleJavaMail.java) |
| Spring Boot auto-configuration | Reuse the manual Spring bean path while allowing Boot applications to discover it, replace each layer independently, and retain application ownership of replacement Mailers. | [SimpleJavaMailAutoConfiguration.java](modules/spring-module/src/main/java/org/simplejavamail/springsupport/SimpleJavaMailAutoConfiguration.java), [SimpleJavaMailSpringBeanFactory.java](modules/spring-module/src/main/java/org/simplejavamail/springsupport/SimpleJavaMailSpringBeanFactory.java), [SpringEnvironmentConfigSource.java](modules/spring-module/src/main/java/org/simplejavamail/springsupport/SpringEnvironmentConfigSource.java), [spring-boot-compatibility.yml](.github/workflows/spring-boot-compatibility.yml) |
| Dynamic module loading | Keep optional features out of the core runtime until their module jars are present and used. | [ModuleLoader.java](modules/simple-java-mail/src/main/java/org/simplejavamail/internal/moduleloader/ModuleLoader.java), [modules package](modules/core-module/src/main/java/org/simplejavamail/internal/modules) |
| CLI generation from builder Javadocs | Turn builder API methods and Javadocs into picocli options and committed binary metadata. | [Cli.java](modules/core-module/src/main/java/org/simplejavamail/api/internal/clisupport/model/Cli.java), [BuilderApiToPicocliCommandsMapper.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapper.java), [CliSupport.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java), `modules/cli-module/src/main/resources/cli.data`, `modules/cli-module/src/main/resources/therapi.data` |
| Optional local CLI daemon | Keep CLI parsing and bounded Mailer instances alive behind authenticated per-user local IPC while preserving one-shot execution. | [DaemonBootstrap.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonBootstrap.java), [DaemonProtocol.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonProtocol.java), [DaemonServer.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonServer.java), [DaemonMailerRegistry.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonMailerRegistry.java) |
| Async send and batch connection pooling | Reuse SMTP transports when the batch module is present; otherwise fall back to direct session transports. | [MailerImpl.java](modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java), [TransportRunner.java](modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportRunner.java), [BatchSupport.java](modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchSupport.java) |
| Transport-neutral submission outcomes | Preserve accepted, unsent, invalid, rejected, and ambiguous results across providers and send modes without treating SMTP acceptance as final delivery. | [MailTransportResult.java](modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/MailTransportResult.java), [MailSubmissionReceipt.java](modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSubmissionReceipt.java), [TransportRunner.java](modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportRunner.java) |
| Per-Mailer terminal send observation | Report one immutable whole-attempt outcome per reached email without exposing transport, pool, executor, proxy, or message content. | [MailSendObserver.java](modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSendObserver.java), [MailSendOutcome.java](modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSendOutcome.java), [MailSendObserverNotifier.java](modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailSendObserverNotifier.java) |
| Exact EML submission | Keep one finalized RFC 822 representation authoritative while reusing the normal Email, Mailer, envelope, receipt, observer, proxy, and pool paths. | [ExactEmailBuilder.java](modules/core-module/src/main/java/org/simplejavamail/api/email/ExactEmailBuilder.java), [ExactEmlSource.java](modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/ExactEmlSource.java), [ContentRequirement.java](modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/ContentRequirement.java), [AngusMailTransportAdapter.java](modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusMailTransportAdapter.java) |
| Authenticated SOCKS proxy bridge | Work around JavaMail's anonymous-only SOCKS support by running a local anonymous bridge to an authenticated remote proxy. | [MailerImpl.java](modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java), [AnonymousSocks5Server.java](modules/core-module/src/main/java/org/simplejavamail/api/internal/authenticatedsockssupport/socks5server/AnonymousSocks5Server.java), [AuthenticatedSocksHelper.java](modules/authenticated-socks-module/src/main/java/org/simplejavamail/internal/authenticatedsockssupport/AuthenticatedSocksHelper.java) |
| Smart MIME structure selection | Choose the least complex RFC-compatible MIME structure for the actual email contents. | [MimeMessageProducerHelper.java](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageProducerHelper.java), [SpecializedMimeMessageProducer.java](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java), [MIME_RESOURCE_NAMING_REPORT.md](MIME_RESOURCE_NAMING_REPORT.md) |
| Runtime non-null instrumentation | Preserve and enforce JetBrains nullability contracts through build-time bytecode instrumentation. | [pom.xml](pom.xml), `org.jetbrains.annotations.NotNull`, `org.jetbrains.annotations.Nullable` |

## API Expansion Workflow

The API expansion process is already documented in [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md). Treat that file as the checklist for adding fields or fluent builder methods.

Important connections to the other mechanisms in this catalogue:

- New builder methods can automatically become CLI options if they are CLI-compatible and not annotated with `@Cli.ExcludeApi`.
- New mail features usually need a MIME conversion decision in `MimeMessageHelper` or `SpecializedMimeMessageProducer`.
- Module-specific features may require updates to a core module interface, a module implementation, and the runtime loader.
- New fields that represent user-facing configuration may also need config defaults, overrides, Spring mapping, and CLI data regeneration.

## Spring Boot Auto-Configuration

The auto-configuration implementation lives in `spring-module`, not in the starter. Boot discovers it through
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; no `spring.factories` fallback is packaged. This means an existing Boot
application that already uses `spring-module` receives the integration without changing artifacts, while
`simple-java-mail-spring-boot-starter` remains a dependency-only convenience artifact.

Both manual Spring configuration and Boot auto-configuration delegate bean construction to `SimpleJavaMailSpringBeanFactory`. The factory loads the
configuration through `SpringEnvironmentConfigSource`, which owns Spring Environment mapping, legacy aliases and wildcard property discovery. Source
precedence and the development-only `localhost` fallback remain visible in the factory's configuration-loading flow. This separation keeps the property
adapter and bean construction cohesive while preventing the two integration routes from drifting.

Boot applies `@ConditionalOnMissingBean` separately to `SimpleJavaMailConfig`, `SimpleJavaMail`, and `Mailer`. Replacing an earlier layer therefore feeds
the remaining defaults, while replacing only the Mailer preserves the injectable config and factory. The default Mailer declares `close` as its destroy
method. A user bean does not inherit that declaration, though Spring can still infer `close` from the user's own `@Bean` definition.

The production auto-configuration is compiled once for Java 11 against the Boot 2.7 API. The compatibility workflow runs the same sources and behavior
tests against Boot 2.7/Spring 5 on Java 11, early Boot 3/Spring 6 on Java 17, and current Boot 3/Spring 6 on Java 21. Boot, Spring Framework, and SLF4J are
provided dependencies: the consuming Boot application selects the matching generation through its own dependency management. The fixed Boot 2.7 property
is a reproducible Java 11 build baseline, not a runtime version exported by the starter; compatibility belongs in the workflow and documentation rather
than a Maven version range.

## Dynamic Module Loading

The published `simple-java-mail` artifact declares several support modules as optional dependencies, including authenticated SOCKS, DKIM, S/MIME, batch, and Outlook support. The core implementation talks to these modules through small interfaces in [modules/core-module/src/main/java/org/simplejavamail/internal/modules](modules/core-module/src/main/java/org/simplejavamail/internal/modules), then uses reflection in [ModuleLoader.java](modules/simple-java-mail/src/main/java/org/simplejavamail/internal/moduleloader/ModuleLoader.java) to instantiate the implementation class only if the module jar is actually present.

The pattern is:

1. Define a stable interface in `core-module`, for example `BatchModule`, `SMIMEModule`, `DKIMModule`, `OutlookModule`, or `AuthenticatedSocksModule`.
2. Implement that interface in the optional module, for example `BatchSupport`, `SMIMESupport`, `DKIMSigner`, `OutlookEmailConverter`, or `AuthenticatedSocksHelper`.
3. Add a `ModuleLoader.loadXxxModule()` method with the implementation class name as a string.
4. Use `MiscUtil.classAvailable(...)` for classpath detection where callers need an availability check.
5. Keep optional module dependencies optional in `modules/simple-java-mail/pom.xml`; include runtime optional modules in the CLI assembly when the CLI should ship with them.

Current usage:

- Batch is checked with `ModuleLoader.batchModuleAvailable()` in `MailerImpl` and `TransportRunner`; when present, it registers a session pool and acquires pooled transports.
- S/MIME and DKIM are checked or loaded by the MIME producer only when email content requests signing or encryption.
- Outlook conversion calls `ModuleLoader.loadOutlookModule()` from `EmailConverter` when `.msg` conversion APIs are used.
- Authenticated SOCKS support is loaded only when proxy settings include authentication.

Gotchas:

- `ModuleLoader` caches loaded module instances in a static map. Batch, S/MIME, and DKIM availability checks are also cached unless tests force a recheck.
- Missing modules are a runtime failure only when the feature is requested. Do not add compile-time references from `simple-java-mail` implementation code to optional module classes.
- Test helpers can force-disable/recheck modules through reflection because the loader is intentionally hidden from `core-module`.

## Immutable Configuration Snapshots

`ConfigLoader` is an ordered instance resolver, not a process-wide registry. Each loader contains `ConfigSource` instances from lowest to highest priority. Calling `load()` samples every source once, lets a later non-blank value win, parses only the winning value according to `PropertySchema`, and returns a detached `SimpleJavaMailConfig`.

The normal runtime flow is:

1. Build a snapshot with `ConfigLoader.builder()` or use the conventional lazy snapshot from `SimpleJavaMail.fromDefaults()`.
2. Create an application-scoped `SimpleJavaMail` factory with `SimpleJavaMail.withConfig(config)`.
3. Request a fresh email, Mailer, Session-based Mailer, or conversion builder from that factory.
4. Keep the resulting `Mailer` for its intended application lifetime and close it when replaced or shut down.

Important boundaries:

- `fromDefaults()` means classpath `simplejavamail.properties`, then environment variables, then system properties. It does not inspect Spring and it resolves once on first use.
- Explicit Java builder calls override the values copied from the snapshot when that builder was created.
- `SimpleJavaMailConfig` and its wildcard maps are immutable. Loading another snapshot does not alter any prior factory, builder, Mailer, Session, or governance object.
- `ConfigSource` names appear in provenance and validation errors. Error messages name the key and expected type without echoing the configured value.
- Strict caller sources reject unknown keys. The full process environment and JVM system-property sources ignore unrelated keys.
- Spring creates one snapshot and `SimpleJavaMail` factory per application context. Spring's `Environment` owns profile, placeholder, system-property, and environment-variable precedence; Simple Java Mail does not apply a second raw overlay.
- Static inbound `EmailConverter` routes use `fromDefaults()`. Use a configured factory's `converter()` when parsed builders need another snapshot.
- The public builder interfaces own option Javadocs. Implementation methods and integration surfaces link to those contracts instead of duplicating them.

Tests for new configuration must include a conflicting two-factory case. Mutate source collections after `load()`, build objects in both contexts, and verify there is no cross-talk. A property used by wildcard Session or connection-pool configuration also needs an immutability check for its returned map.

## CLI Generation From Builder API Javadocs

The CLI is generated from the builder API rather than maintained as a fully separate option list. This gives the CLI near one-to-one feature parity with the Java builder API, including the same method documentation, but it also makes the CLI sensitive to API shape, Javadoc completeness, reflection behavior, and serialized metadata compatibility.

Main flow:

1. Builder API root types are listed in [CliSupport.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java): `EmailStartingBuilder`, `MailerRegularBuilder`, and `MailerFromSessionBuilder`.
2. `BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(...)` walks public methods on builder API nodes annotated with `@Cli.BuilderApiNode`.
3. A method is accepted only if it passes `methodIsCliCompatible(...)`: it must be on a builder API node, must not have `@Cli.ExcludeApi`, must not be a bean accessor, must not take collection parameters, and must be convertible from string arguments.
4. `@Cli.OptionNameOverride` can resolve name collisions or expose a CLI-specific option name.
5. `@Cli.Optional` marks optional CLI parameters explicitly. Java nullability remains expressed with JetBrains `@Nullable`; it no longer drives CLI optionality.
6. Method and parameter Javadocs are read through Therapi Runtime Javadoc and formatted for terminal output by `TherapiJavadocHelper` and `JavadocForCliFormatter`.
7. Picocli command metadata is serialized with Kryo to `modules/cli-module/src/main/resources/cli.data`.
8. Therapi lookups are cached to `modules/cli-module/src/main/resources/therapi.data`.

The Javadoc part is unusual: the build uses Therapi's annotation processor to bake selected Javadoc into runtime-readable classes, then the CLI module reflects over builder methods, resolves those baked Javadocs, formats links and examples for terminal output, and stores the resulting CLI model in `cli.data` for faster startup. The result is clever and convenient, but brittle: Java version changes, bridge/synthetic methods, incomplete `@param` tags, method overload ambiguity, or stale binary metadata can all produce surprising CLI behavior.

Regeneration:

```powershell
mvn -pl modules/cli-module -am -Ppublish-cli -DskipTests package
```

The `publish-cli` profile runs `demo.CliListAllSupportedOptionsDemoApp`, which calls `CliSupport.listUsagesForAllOptions()` and then persists the Therapi cache.

Constraints:

- Use JDK 17 or the current release JDK for CLI data regeneration. The CLI artifact itself emits Java 17 bytecode; library artifacts emit Java 11 bytecode.
- Every CLI-exposed method needs complete Javadoc, including `@param` text for every parameter. A parameter count mismatch becomes an assertion error in `TherapiJavadocHelper.getParamDescriptions(...)`.
- Optional CLI arguments must be annotated with `@Cli.Optional`. Keep `@Nullable` as the Java/API nullability contract; do not use it as CLI metadata.
- Methods using complex Java-only objects, collection/map parameters, ambiguous overloads, or APIs that are only a subset of a better option should be excluded with `@Cli.ExcludeApi(reason = "...")`.
- New string-convertible types need a value converter registered in `BuilderApiToPicocliCommandsMapper`.

## Optional Local CLI Daemon

`org.simplejavamail.cli.SimpleJavaMail` enters a small bootstrap before generated CLI metadata is touched. Help and ordinary one-shot commands remain local. `-d`, bare `--daemon`, and `--daemon=acquire` find or start a selected per-user daemon; `--daemon=require` refuses to start one; `--daemon=off` and `--no-daemon` force one-shot execution.

The daemon is one foreground Java process managed through `daemon run`, `start`, `status`, `stop`, and `restart`. Its internal protocol is length-bounded, HMAC-authenticated, freshness-checked, and independent from Java/Kryo serialization. It prefers a short Unix-domain socket and falls back only to an explicit IPv4 loopback listener. Discovery state contains process/session/endpoint metadata and a random secret, never an Email, password, Mailer configuration, or queued message.

Both routes call the same request-scoped `CliSupport.execute(...)`. Each request owns a fresh Picocli tree, output buffers, UUID, and caller working directory. File value converters consult that context instead of changing `user.dir`; output capture never replaces `System.out` or `System.err`.

Daemon execution uses a bounded registry keyed by the captured immutable configuration plus converted Mailer options. Canonical bytes exist only while the profile is derived; the registry stores a daemon-keyed HMAC, so secret values affect equality without becoming retained map-key data, status, or `toString()` output. Compatible requests lease the same thread-safe Mailer; different SMTP hosts, credentials, proxy, trust, pool, or other effective settings produce separate entries in the same daemon. Closing a lease does not close its Mailer. Idle eviction and daemon shutdown remove the entry before closing the Mailer exactly once. With `batch-module` present this keeps its SMTP pool alive; without it, Mailer construction is still reused but SMTP transport pooling is unavailable.

The in-memory request ledger attaches duplicate UUIDs to the original result and rejects changed content under the same UUID. Entry count, retained output bytes, and retention time are bounded; output pressure replaces old results with lightweight replay tombstones instead of permitting re-execution. It is deliberately not a durable queue. If the client loses the response after submission, it reports an ambiguous outcome and does not automatically resend or fall back to one-shot mode.

## Async Send And Batch Connection Pooling

`MailerImpl` exposes explicit synchronous and asynchronous methods for ordinary sends and receipt-returning sends. The `Sync` methods return only after submission and throw failures directly; the `Async` methods represent operational failures through `CompletableFuture`. Existing configurable and boolean methods delegate to those same paths. `MailerImpl.testConnection(...)` retains its configurable/boolean API. The actual async wrapper is `AsyncOperationHelper` in `core-module`; when the batch module is available, `BatchSupport` delegates to the same helper but provides a default executor tuned for mail sending.

Key pieces:

- `MailerGenericBuilderImpl` chooses the executor service. With `batch-module` on the classpath, the default is `BatchModule.createDefaultExecutorService(...)`; otherwise it falls back to `Executors.newSingleThreadExecutor()`.
- `NonJvmBlockingThreadPoolExecutor` is a fixed-size `ThreadPoolExecutor` with a `LinkedBlockingQueue`. If keep-alive time is greater than zero, core threads are allowed to time out so they do not keep the JVM alive forever.
- `withThreadPoolSize(...)` and `simplejavamail.defaults.poolsize` limit concurrent async work, not queued backlog; the built-in executor does not expose a queue-capacity setting.
- Bounded queues and custom rejection/backpressure behavior require `withExecutorService(...)`. Caller-provided executors are caller-owned, so Simple Java Mail leaves their lifecycle and shutdown to the application.
- `BatchSupport.registerToCluster(...)` creates/registers SMTP connection pools using `SmtpConnectionPoolClustered`.
- Cluster-specific property defaults are parsed from `simplejavamail.defaults.connectionpool.clusters.*` into the immutable configuration snapshot and overlaid on the global connection-pool defaults when `BatchSupport` registers a matching cluster key.
- `TransportRunner` sends through `BatchModule.acquireTransport(...)` when batch is available; otherwise it opens a normal `Session.getTransport()` connection for the operation.
- `LifecycleDelegatingTransportImpl` wraps the pooled transport so the caller can signal success with `release()` or failure with `invalidate()`.
- `MailerImpl.shutdownConnectionPool()` shuts down the default executor if it is library-owned and delegates pool shutdown to the batch module when present.

There is no direct `Phaser` usage in this repository's source tree. Batch coordination here is expressed through `CompletableFuture`, executor services, `AtomicInteger` proxy request tracking, and the external SMTP/object-pool libraries used by `batch-module`.

## Transport-Neutral Submission Outcomes

`MailTransportAdapter` owns the provider-specific `Transport.sendMessage(...)` call and returns a non-null `MailTransportResult`. The built-in Angus adapter captures its current SMTP response; the generic Jakarta Mail fallback has no provider response but still knows that all recipients were accepted when `sendMessage(...)` returns normally. An opaque successful path uses `UNKNOWN` only when it genuinely cannot report acceptance.

Adapters catch checked `MessagingException` failures and return `MailTransportResult.failed(...)`. That factory extracts the valid-sent, valid-unsent, and invalid arrays from a `SendFailedException` when present, clones every array on input and output, and retains the original exception. `TransportRunner` immediately translates the addresses to immutable strings and builds either a `MailSubmissionReceipt` or a `MailSubmissionException` whose direct cause is that original Jakarta Mail failure.

The public statuses are deliberately about knowledge of SMTP submission:

- `ACCEPTED`: all submitted recipients are known accepted;
- `PARTIALLY_ACCEPTED`: at least one accepted, but the attempt did not complete as a clean success for every recipient;
- `REJECTED`: no accepted recipients and explicit rejection facts are present;
- `UNKNOWN`: no reliable acceptance fact is available.

Normal, pooled, asynchronous, and `withOpenConnection` sends all pass through the same translation. Custom mailers and logging-only mode complete successfully with `UNKNOWN`, because Simple Java Mail did not observe their server interaction. A pooled Angus transport may retain the preceding SMTP reply, so the adapter compares reply state before and after a failed attempt and never attaches an unchanged old response to the new failure.

Gotchas:

- A `250` reply confirms SMTP submission, not mailbox delivery.
- `UNKNOWN` can mean that the connection failed after acceptance. Do not turn it into an automatic retry without duplicate protection.
- `PARTIALLY_ACCEPTED` means retrying the whole original envelope can duplicate mail for recipients already accepted.
- Submission failures invalidate the current pooled transport; successful results release it normally.

## Per-Mailer Terminal Send Observation

`MailerGenericBuilder.withMailSendObserver(...)` stores one Java callback directly on the builder and the built `Mailer`; it deliberately does not enter `OperationalConfig`, property loading, CLI generation, or configuration diagnostics. Repeating the builder call replaces the earlier observer. `MailSendObserverNotifier` is the only application-callback boundary, and `MailSendAttempt` centralizes timestamp capture and immutable `MailSendOutcome` construction.

The outcome and receipt answer different questions. `MailSendOutcome` covers the whole Simple Java Mail attempt: the Message-ID before and after preparation, request, ready, execution-start and completion times, configured logging-only mode, and the exact caller-facing success or failure. Its optional `MailSubmissionReceipt` covers transport-neutral SMTP submission facts. Preparation and scheduling failures therefore have no receipt, while `MailSubmissionException` contributes its exact receipt.

Ordering is part of the mechanism:

- ordinary asynchronous preparation happens before scheduling; a rejected executor therefore produces a ready but unstarted outcome without acquiring proxy or transport accounting;
- ordinary pooled sends notify only after `SendMailClosure` has released or invalidated its lease, which also permits a pool-size-one observer to start a new send;
- simple batches notify after each reached email while their intentionally shared transport remains open, and stop at the existing first-failure boundary;
- `withOpenConnection` notifies before each scoped sender call returns, while the callback's shared transport remains usable;
- observer `RuntimeException`s are logged and ignored, so they cannot change receipts, caller failures, cleanup, future completion, retries, or later sends.

The observer is a terminal completed-email stream, not transport lifecycle instrumentation. Validation, rehearsal, connection tests, shutdown and standalone `BatchTransportExecutor` operations do not create outcomes.

## Exact EML Submission

`Email` remains the public message type for both composition and exact submission. `EmailStartingBuilder.startingFromExactEml(...)` returns a constrained `ExactEmailBuilder`: it accepts only explicit SMTP-envelope recipients, an optional envelope sender, and DSN options before building the ordinary `Email`. The byte-array input is copied; the InputStream overload is consumed immediately and left open. The CLI exposes only the byte-array overload, and its existing reflection converter reads that argument from a file.

`InternalEmail` owns one `EmailSource` strategy. `ComposedEmailSource` applies governance and validation, renders the selected MIME structure, and derives a normal or protected-content requirement. `ExactEmlSource` validates only exact-envelope invariants, bypasses governance and security transformations, renders a `FinalizedMimeMessage` from the authoritative bytes, and declares `PRESERVE_ALL_BYTES`. This keeps source-specific branching at object construction; the conversion, rehearsal, send, custom-mailer, batch, open-connection, observer, and pooling flows continue to call the same preparation and rendering methods. The normal Email getters are populated from a parsed view of the EML. Copying that Email through the ordinary builder creates a composed source intentionally.

Exact input is accepted only when it is non-empty, Jakarta Mail parseable, uses canonical CRLF line endings, and ends in CRLF. There is no required visible From, recipient, Date, Subject, body, or Message-ID. Envelope recipients are mandatory because Bcc and relay routing cannot be inferred safely from visible headers; each configured value is exactly one mailbox, calls append in order, and duplicates remain. A missing Message-ID is never synthesized. Full rehearsal and sending compare the raw byte length to the configured maximum.

The final transport boundary makes preservation capability explicit. `PreparedMail` carries `ContentRequirement.NORMAL`, `PRESERVE_PROTECTED_CONTENT`, or `PRESERVE_ALL_BYTES`. Existing third-party `MailTransportAdapter` implementations remain source-compatible through the default capability method but support only `NORMAL` until they opt in; the deprecated boolean `PreparedMail` API remains as a compatibility shim. The bundled Angus adapter supports all three requirements. For exact mail its `SMTPMessage` facade suppresses `saveChanges`, disables 8-bit MIME rewriting, delegates all content reads to the finalized message, and ignores the provider's normal Bcc and Content-Length exclusion list when writing. Unsupported provider or adapter combinations fail before submission.

A compatibility failure does not imply a broken pooled connection. `TransportRunner` releases that healthy lease instead of invalidating it, so a following compatible message can reuse the transport. Actual submission failures retain the normal invalidation behavior. `CustomMailer` bypasses adapter selection as before and receives the same `Email` plus the byte-preserving `MimeMessage`; preserving it beyond that boundary is the custom implementation's responsibility.

Gotchas:

- Full-byte preservation includes `Bcc`, `Resent-Bcc`, `Content-Length`, original header folding and order, MIME boundaries, encodings, and any signatures. The caller must provide an already safe outbound representation.
- Do not apply defaults, overrides, validators, embedded-resource resolution, signing, or encryption to exact mail. Any such mutation contradicts the chosen source mode.
- Do not silently fall back to generic `Transport.sendMessage(...)` for protected or exact requirements. An adapter must claim the requirement explicitly.
- Keep the explicit envelope separate from parsed header recipients in tests; concurrent pool tests must correlate both raw bytes and recipient arrays per attempt.

## Authenticated SOCKS Proxy Bridge

JavaMail supports SOCKS proxy properties, but not authenticated SOCKS proxy login. Simple Java Mail handles authenticated proxies by inserting a local anonymous SOCKS5 server between JavaMail and the real authenticated proxy.

Flow:

1. `MailerImpl.configureSessionWithProxy(...)` first writes normal SOCKS host/port settings for anonymous proxy usage.
2. If the proxy config requires authentication, it rewrites the session SOCKS host to `localhost` and initially writes the configured bridge port.
3. It then loads `AuthenticatedSocksModule` and creates an `AnonymousSocks5Server`.
4. `AuthenticatedSocksHelper` constructs `AnonymousSocks5ServerImpl` with an `AuthenticatingSocks5Bridge`.
5. `AbstractProxyServerSyncingClosure` starts the local bridge before the transport connects. The default configured port is `0`, so the operating system selects an available loopback port. The selected port is then written to the effective Session.
6. The local server accepts anonymous JavaMail SOCKS connections and the bridge opens authenticated sockets to the real remote proxy.
7. The listener stops after the last in-flight SMTP request finishes. Already accepted bridge sockets remain alive for pooled SMTP transports.

Concurrency and lifecycle:

- `MailerImpl` tracks active SMTP requests with an `AtomicInteger`.
- Bridge start/stop is synchronized around the proxy server instance.
- Separate authenticated-proxy Mailers receive separate automatic ports, so they can run concurrently without coordinating bridge settings.
- A positive `withProxyBridgePort(...)` value keeps the old fixed-port behavior.
- The bridge server uses a fixed thread pool for accepted SOCKS sessions; each session pipes client and remote sockets until the pipe stops.

Gotchas:

- The authenticated SOCKS module must be available when authenticated proxy settings are used.
- `getProxyBridgePort()` exposes the configured value. When that value is `0`, inspect the effective Session's `mail.smtp.socks.port` or `mail.smtps.socks.port` property for the currently selected port.

## Smart MIME Message Structure Selection

Simple Java Mail avoids one oversized MIME structure for every email. Instead, it picks the least complex producer that matches the actual content. The selection is centralized in [MimeMessageProducerHelper.java](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageProducerHelper.java).

The selector computes three content dimensions in [SpecializedMimeMessageProducer.java](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java):

- Mixed content: attachments or forwarded email.
- Related content: embedded images.
- Alternative content: more than one body variant among plain text, HTML, and calendar text.

Resource naming, embedded image `cid:` values, attachment filenames, duplicate attachment names, and parse-side inline/attachment classification have their own focused history report in [MIME_RESOURCE_NAMING_REPORT.md](MIME_RESOURCE_NAMING_REPORT.md).

Those booleans map to eight producers:

| Producer | Mixed | Related | Alternative |
| --- | --- | --- | --- |
| `MimeMessageProducerSimple` | no | no | no |
| `MimeMessageProducerAlternative` | no | no | yes |
| `MimeMessageProducerRelated` | no | yes | no |
| `MimeMessageProducerMixed` | yes | no | no |
| `MimeMessageProducerMixedRelated` | yes | yes | no |
| `MimeMessageProducerMixedAlternative` | yes | no | yes |
| `MimeMessageProducerRelatedAlternative` | no | yes | yes |
| `MimeMessageProducerMixedRelatedAlternative` | yes | yes | yes |

After the selected producer creates the body structure, `SpecializedMimeMessageProducer.populateMimeMessage(...)` applies additional wrappers in a fixed order:

1. S/MIME signing.
2. S/MIME encryption, including the per-recipient certificate path when any recipient has a `smimeCertificate`.
3. OpenPGP signing.
4. OpenPGP encryption.
5. DKIM signing.

For composed email, `Mailer.rehearse(email)` is a no-network rehearsal of the same preparation rules. `MailerImpl` first creates a separate governed email by applying Mailer defaults and overrides, detaches it from the caller's Email, and then runs strict or lenient client checks. The default overload builds the complete protected MIME message through `SessionBasedEmailToMimeMessageConverter.rehearseMimeMessage(...)`, renders it once, and returns a `MailRehearsal` with the effective Email, defensive EML bytes, encoded size, Message-ID, and transport envelope addresses. The size check uses those rendered bytes. `rehearse(email, false)` stops after `populateBaseMimeMessage(...)`, so it does not load the S/MIME, OpenPGP, or DKIM modules and does not enforce the final-size limit; its bytes and size describe that base MIME message. Exact EML instead returns its unchanged authoritative Email and bytes in both modes; the full mode adds only the raw-size check.

`Mailer.validate(...)` delegates to rehearsal and discards the returned snapshot. The API choice is therefore about output, not validation depth: use `validate` only for a success-or-exception gate and `rehearse` whenever the caller needs prepared facts. Calling both prepares the message twice. The actual send path deliberately uses the shared governed-email and client-validation step rather than calling public rehearsal. This keeps sending to one MIME conversion. Rehearsal also skips Session and Email logging, proxy startup, transport acquisition, pooling, and custom-mailer callbacks. It backfills the generated Message-ID only into its detached effective Email, never into the caller's Email.

Gotchas:

- New body-part concepts usually require revisiting the selector dimensions and every affected producer.
- DKIM, S/MIME, and OpenPGP are optional modules, but if the email requests them during full validation or sending, the corresponding module must be on the runtime classpath.
- The producer starts from `MessageIdFixingMimeMessage` so custom message IDs survive later wrapping.

## Runtime Non-Null Instrumentation

The API and implementation use JetBrains `@NotNull` and `@Nullable` annotations heavily.

`org.jetbrains:annotations` is compiled in for source-level and public API nullability annotations. Earlier versions also depended on `com.github.bbottema:jetbrains-runtime-annotations`, a runtime-retention fork of the JetBrains annotations, because the CLI inspected `@Nullable` by reflection to detect optional command arguments. CLI optionality is now represented separately with `@Cli.Optional`, so the runtime-retention fork is no longer needed.

The root Maven build configures `se.eris:notnull-instrumenter-maven-plugin` to instrument main and test classes. The current configuration recognizes `org.jetbrains.annotations.Nullable` and `org.jetbrains.annotations.NotNull`, and excludes assertion helpers plus `ServerReply`.

Implications:

- Do not treat nullability annotations as cosmetic. They affect generated bytecode and runtime validation.
- Adding or changing public builder API nullability can still affect the CLI ergonomics, but only when CLI optionality should follow. In that case add both `@Nullable` and `@Cli.Optional`; `BuilderApiToPicocliCommandsMapper` marks CLI parameters as required unless the parameter has `@Cli.Optional`.
- Generated code or protocol enum-like classes may need explicit instrumenter exclusions if instrumentation changes behavior.

## Related Mechanisms Worth Checking

These are not expanded as separate catalogue entries yet, but they are common places to inspect when changing core behavior:

- Config resolution and defaults/overrides: `ConfigLoader`, `PropertySchema`, `SimpleJavaMailConfig`, `EmailProperty`, and `EmailGovernanceImpl`.
- Spring property mapping: `SimpleJavaMailSpringSupport`, which produces context-local config, factory, and Mailer beans.
- Outlook and EML conversion: `EmailConverter`, `OutlookEmailConverter`, and `MimeMessageParser`.
- Transport strategy properties: `TransportStrategy` and `MailerImpl.createMailSession(...)`.
