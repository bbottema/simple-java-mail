# Project Mechanisms Catalogue

This catalogue records project mechanisms that are easy to miss because they span modules, build steps, generated files, or runtime classpath behavior. It is meant to be read alongside [DEVELOPMENT.md](DEVELOPMENT.md) and [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md).

## Quick Index

| Mechanism | Main reason it exists | Primary anchors |
| --- | --- | --- |
| API expansion workflow | Keep model, builders, CLI, config, conversion, and modules in sync when the public API grows. | [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md) |
| Dynamic module loading | Keep optional features out of the core runtime until their module jars are present and used. | [ModuleLoader.java](modules/simple-java-mail/src/main/java/org/simplejavamail/internal/moduleloader/ModuleLoader.java), [modules package](modules/core-module/src/main/java/org/simplejavamail/internal/modules) |
| CLI generation from builder Javadocs | Turn builder API methods and Javadocs into picocli options and committed binary metadata. | [BuilderApiToPicocliCommandsMapper.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapper.java), [CliSupport.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java), `modules/cli-module/src/main/resources/cli.data`, `modules/cli-module/src/main/resources/therapi.data` |
| Async send and batch connection pooling | Reuse SMTP transports when the batch module is present; otherwise fall back to direct session transports. | [MailerImpl.java](modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java), [TransportRunner.java](modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportRunner.java), [BatchSupport.java](modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchSupport.java) |
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

## CLI Generation From Builder API Javadocs

The CLI is generated from the builder API rather than maintained as a fully separate option list.

Main flow:

1. Builder API root types are listed in [CliSupport.java](modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliSupport.java): `EmailStartingBuilder`, `MailerRegularBuilder`, and `MailerFromSessionBuilder`.
2. `BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(...)` walks public methods on builder API nodes annotated with `@Cli.BuilderApiNode`.
3. A method is accepted only if it passes `methodIsCliCompatible(...)`: it must be on a builder API node, must not have `@Cli.ExcludeApi`, must not be a bean accessor, must not take collection parameters, and must be convertible from string arguments.
4. `@Cli.OptionNameOverride` can resolve name collisions or expose a CLI-specific option name.
5. Method and parameter Javadocs are read through Therapi Runtime Javadoc and formatted for terminal output by `TherapiJavadocHelper` and `JavadocForCliFormatter`.
6. Picocli command metadata is serialized with Kryo to `modules/cli-module/src/main/resources/cli.data`.
7. Therapi lookups are cached to `modules/cli-module/src/main/resources/therapi.data`.

Regeneration:

```powershell
mvn -pl modules/cli-module -am -Ppublish-cli -DskipTests package
```

The `publish-cli` profile runs `demo.CliListAllSupportedOptionsDemoApp`, which calls `CliSupport.listUsagesForAllOptions()` and then persists the Therapi cache.

Constraints:

- Use JDK 11 for CLI data regeneration. [DEVELOPMENT.md](DEVELOPMENT.md) documents why Java 12+ breaks this path.
- Every CLI-exposed method needs complete Javadoc, including `@param` text for every parameter. A parameter count mismatch becomes an assertion error in `TherapiJavadocHelper.getParamDescriptions(...)`.
- Methods using complex Java-only objects, collection/map parameters, ambiguous overloads, or APIs that are only a subset of a better option should be excluded with `@Cli.ExcludeApi(reason = "...")`.
- New string-convertible types need a value converter registered in `BuilderApiToPicocliCommandsMapper`.

## Async Send And Batch Connection Pooling

`MailerImpl.sendMail(...)` and `MailerImpl.testConnection(...)` can run synchronously or return a `CompletableFuture`. The actual async wrapper is `AsyncOperationHelper` in `core-module`; when the batch module is available, `BatchSupport` delegates to the same helper but provides a default executor tuned for mail sending.

Key pieces:

- `MailerGenericBuilderImpl` chooses the executor service. With `batch-module` on the classpath, the default is `BatchModule.createDefaultExecutorService(...)`; otherwise it falls back to `Executors.newSingleThreadExecutor()`.
- `NonJvmBlockingThreadPoolExecutor` is a fixed-size `ThreadPoolExecutor` with a `LinkedBlockingQueue`. If keep-alive time is greater than zero, core threads are allowed to time out so they do not keep the JVM alive forever.
- `BatchSupport.registerToCluster(...)` creates/registers SMTP connection pools using `SmtpConnectionPoolClustered`.
- `TransportRunner` sends through `BatchModule.acquireTransport(...)` when batch is available; otherwise it opens a normal `Session.getTransport()` connection for the operation.
- `LifecycleDelegatingTransportImpl` wraps the pooled transport so the caller can signal success with `release()` or failure with `invalidate()`.
- `MailerImpl.shutdownConnectionPool()` shuts down the default executor if it is library-owned and delegates pool shutdown to the batch module when present.

There is no direct `Phaser` usage in this repository's source tree. Batch coordination here is expressed through `CompletableFuture`, executor services, `AtomicInteger` proxy request tracking, and the external SMTP/object-pool libraries used by `batch-module`.

## Authenticated SOCKS Proxy Bridge

JavaMail supports SOCKS proxy properties, but not authenticated SOCKS proxy login. Simple Java Mail handles authenticated proxies by inserting a local anonymous SOCKS5 server between JavaMail and the real authenticated proxy.

Flow:

1. `MailerImpl.configureSessionWithProxy(...)` first writes normal SOCKS host/port settings for anonymous proxy usage.
2. If the proxy config requires authentication, it rewrites the session SOCKS host to `localhost` and port to `proxyBridgePort`.
3. It then loads `AuthenticatedSocksModule` and creates an `AnonymousSocks5Server`.
4. `AuthenticatedSocksHelper` constructs `AnonymousSocks5ServerImpl` with an `AuthenticatingSocks5Bridge`.
5. The local server accepts anonymous JavaMail SOCKS connections and the bridge opens authenticated sockets to the real remote proxy.
6. `AbstractProxyServerSyncingClosure` starts the local bridge only while SMTP/test-connection work is active and stops it after the last in-flight SMTP request finishes.

Concurrency and lifecycle:

- `MailerImpl` tracks active SMTP requests with an `AtomicInteger`.
- Bridge start/stop is synchronized around the proxy server instance.
- The bridge server uses a fixed thread pool for accepted SOCKS sessions; each session pipes client and remote sockets until the pipe stops.

Gotchas:

- SMTPS plus proxy is rejected in `MailerImpl` because the underlying JavaMail combination is not supported.
- The authenticated SOCKS module must be available when authenticated proxy settings are used.
- `getProxyBridgePort()` exposes the local bridge port, not the remote proxy port.

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
3. DKIM signing.
4. Bounce-to wrapping with `ImmutableDelegatingSMTPMessage`.

Gotchas:

- New body-part concepts usually require revisiting the selector dimensions and every affected producer.
- DKIM and S/MIME are optional modules, but if the email requests them the corresponding module must be on the runtime classpath.
- The producer starts from `MessageIdFixingMimeMessage` so custom message IDs survive later wrapping.

## Runtime Non-Null Instrumentation

The API and implementation use JetBrains `@NotNull` and `@Nullable` annotations heavily. Two dependencies support this:

- `org.jetbrains:annotations` is provided for source-level nullability annotations.
- `com.github.bbottema:jetbrains-runtime-annotations` is compiled in because Simple Java Mail inspects these annotations at runtime.

The root Maven build configures `se.eris:notnull-instrumenter-maven-plugin` to instrument main and test classes. The current configuration recognizes `org.jetbrains.annotations.Nullable` and `org.jetbrains.annotations.NotNull`, and excludes assertion helpers plus `ServerReply`.

Implications:

- Do not treat nullability annotations as cosmetic. They affect generated bytecode and runtime validation.
- Adding or changing public API nullability can affect the CLI too: `BuilderApiToPicocliCommandsMapper` marks CLI parameters as required unless the parameter has `@Nullable`.
- Generated code or protocol enum-like classes may need explicit instrumenter exclusions if instrumentation changes behavior.

## Related Mechanisms Worth Checking

These are not expanded as separate catalogue entries yet, but they are common places to inspect when changing core behavior:

- Config resolution and defaults/overrides: `ConfigLoader`, `EmailProperty`, and `EmailGovernanceImpl`.
- Spring property mapping: `SimpleJavaMailProperties` and `SimpleJavaMailSpringSupport`.
- Outlook and EML conversion: `EmailConverter`, `OutlookEmailConverter`, and `MimeMessageParser`.
- Transport strategy properties: `TransportStrategy` and `MailerImpl.createMailSession(...)`.
