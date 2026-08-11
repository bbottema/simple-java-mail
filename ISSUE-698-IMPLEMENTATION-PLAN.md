# Implementation plan: public batch-module orchestration API

- Issue: [Simple Java Mail #698](https://github.com/bbottema/simple-java-mail/issues/698)
- Target release: **Simple Java Mail 9.3.0**
- Upstream baseline: [`smtp-connection-pool` 4.0.1](https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/4.0.1), including the stable module-path chain tracked in [smtp-connection-pool #11](https://github.com/simple-java-mail/smtp-connection-pool/issues/11)
- Related but independently releasable work: [Simple Java Mail #699](https://github.com/bbottema/simple-java-mail/issues/699)
- Status: **implemented for Simple Java Mail 9.3.0; release publication remains a separate authorized workflow**

## Product position

`batch-module` does not introduce another connection-pool implementation. It remains an optional integration and orchestration layer over `smtp-connection-pool`:

```text
public batch callback / async / lifecycle facade
    -> shared batch engine
    -> smtp-connection-pool lease and clustering
    -> one physical Jakarta Mail Transport
```

For the main Simple Java Mail `Mailer`, the module continues to section the optional connection-pool dependencies away from the core artifact and is loaded through the existing reflection boundary.

For standalone Jakarta Mail users, the public facade earns its place only by providing a managed-work boundary:

- callback-scoped access to the actually selected `Session` and `Transport`;
- automatic healthy release after success and invalidation after an escaping failure;
- asynchronous submission through `CompletableFuture`;
- explicit ownership of default executors while leaving supplied executors caller-owned;
- tracking of accepted work and active leases;
- deterministic graceful and forced shutdown; and
- a smaller configuration surface that does not expose `OperationalConfig` or generic object-pool types.

Users who need exact control over claim, release, invalidation, or shutdown should use `smtp-connection-pool` directly. The batch facade must not be presented as a more capable pool or a separate pooling engine.

## Locked boundaries

- Keep the physical connection pool in `smtp-connection-pool`; do not duplicate its allocation, expiry, load-balancing, or lease implementation.
- Keep the raw `SmtpTransportLease` internal to the public facade.
- Preserve the existing `BatchModule` reflection interface as the compatibility adapter for the full Simple Java Mail `Mailer` path.
- Back the public facade and reflective adapter with the same reusable internal engine, using separate engine instances where ownership requires it.
- Expose only Jakarta Mail, JDK, and batch-module types from the new public package.
- Do not expose Angus, `PoolableObject`, `SessionTransport`, clustered-object-pool types, or a future #699 transport type.
- Reject a registered `Session` whose effective default transport is `smtppool`; there must be exactly one physical-pool owner.
- A future #699 Jakarta `Transport` may be selected beneath this facade. A #699 `CustomMailer` backend remains outside it and owns its own lifecycle.
- No Angus source change or Angus version change is required for #698. Angus-specific receipt generalization belongs to #699 if and when a non-Angus Mailer receipt path is implemented.

## Public API direction

Add a supported package rooted at `org.simplejavamail.batch` and freeze its names before implementation. The central concepts are:

- one `AutoCloseable` facade, currently described as `BatchTransportPool<K>`;
- a builder with immutable global defaults and per-cluster overrides;
- a batch-specific load-balancing enum;
- a checked functional callback receiving the selected `Session` and `Transport`;
- a public exception that retains useful causes without rendering Session properties, passwords, fixed tokens, or supplier results; and
- optional asynchronous variants returning `CompletableFuture`.

The facade must support explicit, unambiguous operations for:

1. registering one or more `Session` instances under a cluster key;
2. running against whichever registered Session the cluster selects;
3. running against one exact registered Session;
4. submitting both forms asynchronously; and
5. graceful shutdown, forced shutdown, and blocking `close()`.

Cluster keys should remain generic so standalone users are not forced into the Mailer's UUID convention. Handles supplied to a callback are valid only for that callback invocation. The callback must not connect or close the selected `Transport` itself.

The API naming review must ensure that the facade communicates managed execution rather than implying ownership of a second pool. `BatchTransportPool` is the issue's working name; an executor-oriented name may be selected before the API freeze if it makes the ownership model clearer.

## Configuration

Publish dedicated global and per-cluster configuration covering:

- core connection count;
- maximum connection count;
- claim timeout;
- idle expiry;
- round-robin or random selection;
- default executor sizing/keep-alive where the module owns the executor; and
- an optional caller-provided `ExecutorService`.

Do not require or expose `OperationalConfig`. Map both the public configuration and the existing Mailer `OperationalConfig` to one internal immutable pool-settings model.

Validate configuration at build time, including nonnegative sizes and durations, core size not exceeding maximum size, known load-balancing strategy, and non-null cluster keys and Sessions.

## Shared engine and existing Mailer compatibility

Upgrade `modules/batch-module/pom.xml` from `smtp-connection-pool` 3.1.0 to the compatible 4.x release.

Refactor the batch internals around a generic engine responsible for:

- lazy global pool construction;
- cluster-specific configuration and first-registration semantics;
- idempotent Session registration;
- explicit checks that sticky Sessions were registered under the addressed cluster;
- clustered and sticky `SmtpTransportLease` acquisition;
- callback execution and terminal lease handling;
- active-operation and active-lease tracking;
- OAuth2 property bridging for each registered Session;
- safe failure mapping and interruption preservation;
- post-shutdown claim rejection; and
- whole-engine and per-Session shutdown operations.

Adapt `BatchSupport` to this engine while retaining the current `BatchModule` method contract. Replace `LifecycleDelegatingTransportImpl`'s raw `PoolableObject<SessionTransport>` with `SmtpTransportLease`. Preserve per-Mailer Session shutdown without permanently closing the reflection-loaded shared engine, so later Mailers can still register normally.

The current Angus-only `TransportRunner.extractSmtpServerResponse` implementation is not part of this refactor and should remain unchanged for #698.

## Lease outcome and failure behavior

- If the callback returns normally, release the lease.
- If any callback failure escapes, invalidate the lease before propagating or completing the future exceptionally.
- Use a `finally`-based terminal path so `Error` cannot leak a lease.
- If healthy release itself fails, rely on `SmtpTransportLease`'s invalidate fallback and retain the original cause chain.
- Do not expose an advanced caller-controlled lease outcome in the first public API.
- Document that a caller may deliberately catch a recipient-level exception inside the callback only when it knows the physical connection remains synchronized and reusable.

## OAuth2

Move Simple Java Mail OAuth2 property bridging from acquisition time to Session registration time. A non-sticky cluster claim may select a different Session from the one supplied by the caller, so acquisition-time copying can resolve the wrong credentials.

The selected registered Session must own the fixed token or refresh-aware supplier consumed by `smtp-connection-pool`. Supplier resolution remains upstream at the physical connect or reconnect boundary; reusing an already-connected transport must not resolve a new token.

Tests must use multiple Sessions with distinct suppliers to prove that only the actually selected Session supplies credentials.

## Async execution and lifecycle

The public facade owns an executor only when it created that executor. A supplied executor remains caller-owned and must still accept work after facade shutdown.

Use a lifecycle state such as `OPEN -> CLOSING -> CLOSED` because the underlying clustered pool can otherwise be repopulated after its pools drain.

Graceful shutdown must:

1. atomically stop accepting registrations and new submissions;
2. allow already accepted synchronous and asynchronous work to reach a terminal outcome;
3. stop a module-owned executor from accepting unrelated new work;
4. wait for active leases to be released or invalidated;
5. shut down every registered physical pool and wait for allocator deallocation/`Transport.close()` completion; and
6. complete one idempotently shared shutdown handle.

Forced shutdown must invalidate tracked active leases and attempt to cancel queued module-owned work before waiting for physical cleanup. Escalating an existing graceful shutdown must retain the same completion handle.

`close()` performs and waits for graceful shutdown, restores the interrupt flag if interrupted, and reports cleanup failure through the public sanitized exception. Document the behavior of accepted work queued on a caller-owned executor so shutdown cannot appear deterministic while relying on an executor that never progresses.

## Module-path chain

The dependency chain is released bottom-up with stable `Automatic-Module-Name` values:

1. `generic-object-pool` 2.4.2: `org.bbottema.genericobjectpool`;
2. `clustered-object-pool` 4.0.3: `org.bbottema.clusteredobjectpool`;
3. `smtp-connection-pool` 4.0.1: `org.simplejavamail.smtpconnectionpool`; and
4. `batch-module` 9.3.0: `org.simplejavamail.batch`.

Each upstream project inspects its packaged manifest. `smtp-connection-pool` compiles an end-to-end consumer over its five core/provider/adapter module names, and `batch-module` compiles an application class plus module descriptor requiring the complete direct-pool chain. The JPMS verification profile activates only on JDK 9+, while the separate standalone consumer proves Java 8 source/bytecode compatibility without the `simple-java-mail` facade artifact. Angus 2.0.5 already supplies the valid named module `org.eclipse.angus.mail` and is not a blocker.

## Verification

Add focused unit, integration, and consumer tests for:

- a public-only consumer depending on `batch-module` without the `simple-java-mail` facade artifact;
- imports limited to `org.simplejavamail.batch` and Jakarta Mail types;
- Java 8 source and bytecode compatibility;
- a real module-path consumer requiring the released upstream names and `org.simplejavamail.batch`;
- single-Session connection reuse;
- multi-Session round-robin and random cluster selection;
- exact-Session sticky selection;
- automatic release after success;
- automatic invalidation and physical replacement after failure;
- callback result propagation and preservation of checked failure causes;
- interrupted claims and interrupt-flag preservation;
- fixed OAuth2 tokens and distinct refresh-aware suppliers on selected Sessions;
- module-owned versus caller-owned executor shutdown;
- accepted async work during graceful close;
- forced invalidation during shutdown;
- idempotent concurrent shutdown calls;
- rejection of registration/acquisition/submission after shutdown begins;
- rejection of `smtppool` as the physical delegate;
- absence of credentials and token values from errors and logs;
- a deterministic non-Angus Jakarta `Transport` provider; and
- existing full `Mailer` pooled, clustered, async, connection-test, and shutdown behavior.

Use a standalone consumer fixture or Maven Invoker project for dependency and module-path verification so the test cannot accidentally compile against `simple-java-mail` or batch internals merely because it lives inside the batch module's own test source set.

## Documentation deliverables

Documentation must describe the complete orchestration decision, not present standalone `batch-module` as a unique pooling implementation.

### `smtp-connection-pool` README

Keep a compact comparison matrix in `smtp-connection-pool/README.md`. It must remain useful without the website and compare these user-facing options:

| Option | Best when | Orchestration owner | Lease handling | Pooling/clustering |
| --- | --- | --- | --- | --- |
| No pool: `withOpenConnection` / simple batch | One sequential unit of work | Application / Simple Java Mail | Not applicable | No |
| Simple Java Mail `Mailer` + `batch-module` | Using `EmailBuilder` and `Mailer` | Simple Java Mail | Automatic | Yes |
| Standalone `batch-module` facade | Creating `MimeMessage` objects while wanting managed callbacks and futures | Batch facade | Automatic | Yes |
| Direct `smtp-connection-pool` | Needing exact claim, failure, and shutdown control | Application | Explicit lease | Yes |
| Jakarta `smtppool` provider | Plain Jakarta Mail or Spring owns `Transport` calls | Provider | Mapped to `connect` / `close` | Yes |
| Camel `smtppool:` adapter | Camel owns endpoints and component lifecycle | Camel adapter / provider | Automatic | Yes |

Immediately accompany the matrix with the single-owner rule:

> Exactly one component owns the physical connection pool. Never place `batch-module` or a direct pool around an `smtppool` transport.

Retain concise direct-core and provider examples in the README and link to the fuller website guide once published.

### Canonical simplejavamail.org page

Create `simplejavamail.org/src/pages/smtp-connection-pooling.hbs` at:

```text
/smtp-connection-pooling.html
```

Title the page **SMTP connection pooling and batch orchestration** and register it in `manifest/site.json` as a child of `/modules.html`. This replaces the previously proposed, unpublished `/batch-module-jakarta-mail.html` route; no redirect is required unless that old route is published before this work lands.

The page is the canonical decision guide and must contain:

1. the six-option comparison matrix above;
2. a short chooser beginning with whether a pool is needed at all;
3. the single-physical-pool-owner rule;
4. one section per option with dependency coordinates and a minimal runnable example;
5. explicit ownership of executor, accepted work, Session, lease, physical pool, and shutdown;
6. success release and failure invalidation semantics;
7. graceful and forced shutdown behavior;
8. OAuth2 refresh behavior at physical connect/reconnect;
9. invalid double-pooling combinations;
10. an explanation that PIPELINING, CHUNKING, and other #699 transport optimizations are orthogonal to pool orchestration; and
11. links to canonical executable demos.

Link this page from:

- the `batch-module` section of `modules.hbs`;
- the batch overview in `configuration.hbs`;
- the connection reuse/pooling section in `configuration.hbs`; and
- the clustering section in `configuration.hbs`.

Keep the wider website page authoritative for choosing among products while the upstream README remains authoritative for `smtp-connection-pool` configuration and its direct/provider APIs.

Run:

```text
npm run check
npm run verifyLinks:internal
npm run build
```

### Javadocs, examples, and release text

- Remove `modules/batch-module/src/main/javadoc/index.html` so the normal generated-Javadoc profile documents `org.simplejavamail.batch` instead of publishing the static “no public API” page.
- Add complete ownership, callback-scope, failure, and shutdown Javadocs to every public type and method.
- Add the standalone path-2 example to the upstream non-published demo after Simple Java Mail 9.3.0 is available as a released dependency.
- Update the website guide to link to that canonical runnable example.
- Add a Simple Java Mail 9.3.0 release entry and a Supporting Libraries entry for the adopted `smtp-connection-pool` release.
- Replace stale Simple Java Mail 10.0.0 references with 9.3.0 in the upstream README, release description, product vision, implementation plan, demo documentation, and #698 coordination commentary where editable.

## Delivery order

1. Freeze the public facade name, callback signatures, configuration types, and shutdown contract.
2. Publish the three upstream automatic-module manifest fixes in dependency order and consume `smtp-connection-pool 4.0.1`.
3. Upgrade the batch dependency and migrate the existing internal adapter to `SmtpTransportLease`.
4. Extract the shared engine and preserve existing Mailer behavior.
5. Add the public facade and standalone consumer tests.
6. Complete lifecycle, OAuth2, recursion, non-Angus, Java 8, and module-path verification.
7. Replace the static Javadocs and update Simple Java Mail release notes.
8. Add the upstream README matrix.
9. Add the canonical simplejavamail.org decision page and links, run all site checks, and update this repository's website submodule revision.
10. Release Simple Java Mail 9.3.0 through its independently authorized workflow.
11. After publication, add and verify the upstream standalone batch demo and reconcile remaining stale version references.

## Definition of done

#698 is complete only when:

- the public facade is a managed orchestration layer over one upstream physical pool;
- direct pool users retain a clearly documented lower-level alternative;
- existing Mailer behavior still uses the shared direct-integration engine;
- public signatures contain no internal, generic-pool, Angus, or #699-specific types;
- lifecycle, OAuth2, executor ownership, recursion rejection, and failure semantics are verified;
- Java 8 compatibility and the promised level of module-path support are proven honestly;
- the README and website comparison matrices agree;
- `/smtp-connection-pooling.html` is the canonical orchestration decision guide;
- all relevant documentation consistently targets Simple Java Mail 9.3.0; and
- the website, Javadocs, release notes, submodule revision, and canonical examples are complete.
