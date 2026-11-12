# Explicit authentication on an SMTP pool registration

Accepted on 5 November 2026. The maintainer accepted review finding 2's final interface and plan, then explicitly requested a subagent to carry the workflow to completion while finding 3 is discussed. This authorizes implementation, verification, the supporting pool release, SJM integration, documentation, selective publication and issue/project/milestone completion after lead acceptance. No SJM release, history rewrite, unrelated dependency work or finding 3 repair is authorized. Do not reopen the settled Session-manager terminology or introduce the superseded `registerSession`/`TransportAuthentication` proposal.

Baselines: SMTP Connection Pool master/tag 4.4.1 at `54702446d0888c59121c0124d770fc5d23ff79f6`; SJM `codex/10.0.0` at `a0dd339b62eb8a9824798c2f8063940ff49ef1ed`; website `codex/10.0.0-content` at `86ce310498495ee9cadc408bcb20f2c4351d1867`. Recheck current local/remote heads and issues before mutation. The prior username repair (#966/pool #38) is delivered. Its retained evidence lives under `C:/workstation/sjm-oauth-protocol-20261105/evidence`; never remove or reuse that directory. Root owns remaining cleanup bookkeeping for that completed task.

## Approved public contract

Keep the current constructors, implicit/lazy Session pools, registration and claim methods. A public `SmtpConnectionPool`/`SmtpConnectionPoolClustered` object manages connection pools keyed by Session, or by `(clusterKey, Session)`; constructing it supplies settings, not a first Session.

Add these overloads to `SmtpConnectionPoolClustered<K>` (and equivalent `ResourceKey<Session, Session>` overloads to `SmtpConnectionPool`):

```java
public void registerResourcePool(ResourceKey<K, Session> key,
    @Nullable Supplier<String> oauth2AccessTokenProvider);

public void registerResourcePool(ResourceKey<K, Session> key,
    ExpirationPolicy<SessionTransport> expirationPolicy, int coreSize, int maxSize,
    @Nullable Supplier<String> oauth2AccessTokenProvider);
```

The new overload is an explicit authentication binding: a supplier selects OAuth; a null supplier selects ordinary Jakarta Mail Session authentication and does not consult legacy pool OAuth alias properties. This nullable component is deliberate, not a missing required provider. Existing overloads and implicit/lazy legacy registration retain their property-based compatibility behavior. Ordinary callers need no authentication argument or new registration step.

```java
SmtpConnectionPoolClustered<String> manager =
    new SmtpConnectionPoolClustered<>(new SmtpClusterConfig<String>());
ResourceKey<String, Session> key =
    new ResourceClusterAndPoolKey<>("outbound", session);
manager.registerResourcePool(key, tokenService::currentAccessToken);
```

Bind authentication before the first claim, through one registration, not a later mutation or second Session. Preserve existing duplicate-registration and sizing/expiration contracts. Store the choice with the exact pool registration and its allocator lifetime, including cluster/Session identity and retirement generations, rather than in Session properties or an unbounded global credential map. Do not silently change credentials on active transports or let an old allocator read a replacement registration's callback.

Resolve a supplied token only for physical connection establishment/reconnection (including configured eager allocation), not ordinary connected reuse or passive configuration. A supplier must be thread-safe and return a nonblank token; null/blank/throwing results fail with preserved causes and never fall back to other credentials. Keep the normal provider's username/TLS selection, no token/property logging, cooperative cancellation and close/disposal ownership, callback failures separate from actual SMTP connection rejection, and existing observer timing. Retire borrowed callback references with registration resources; do not close application-owned callback objects.

## SJM integration and compatibility

Remove `BatchTransportEngine.bridgeOAuth2Properties` from the new SJM path. Main selects its current optional token source using existing fixed-token/provider validation and String/defaults semantics; carry that lazy choice through the core-owned optional-module boundary. Only the batch adapter depends on pool types. It registers with the explicit-binding overload even for ordinary authentication, so stale legacy alias entries cannot become fallback authority. Keep application-owned properties/defaults and opaque values untouched. Preserve fixed-token/callback conflict detection, provider laziness, error causes, locks, selected Mailer/Session authority and successful-shutdown-before-reuse.

Fixed tokens can be supplied by a constant supplier. No new application-facing SJM method, configuration key, CLI option, public authentication-strategy object or duplicated credential selection policy is approved. Standalone `BatchTransportExecutor` must retain its documented credential behavior through the same controlled registration integration; characterize its canonical and legacy inputs before changing selection. Bring a genuinely different public contract back to root instead of silently broadening this scope.

Governing ADRs: 0015 (registration/Session/lease ownership), 0038 (effective connection settings), 0039 (source selection and optional integration), 0006 (module independence), 0031 (public/internal boundaries). Clarify existing owners and index cross-references; use a new ADR only if a distinct enduring decision needs one. Preserve the delivered #966 protocol/username rule and separate S/MIME finding 3.

## Ownership and tracking

### Combined final integration and resource queue (root assignment, 5 November 2026)

Root has assigned `oauth_registration_delivery` pure-source combined integration in new isolated
`C:/workstation/sjm-review-combined-20261105/{sjm,website}` checkouts from accepted main `e458b76c7f99c3a6a27a909098c803b54869cf50`
and website `86ce310498495ee9cadc408bcb20f2c4351d1867`. This later operational assignment coordinates all separately approved
auth (#967), detached-signed attachment (#704), daemon-path (#488) and forwarded-original-clear work. Each feature owner still
owns its own production repair and isolated checkout. The combined owner imports reviewed patches/candidates with per-issue
hash/provenance records, preserves every owner's tests and merges only shared documentation additively. Production conflicts
or a changed public contract return to root. The original auth-only implementation restriction above remains the historical
scope of that worker; this assignment adds integration rather than unauthorized feature design.

No Java, focused or broad verification starts from the combined checkout until all selected production/tests/CLI metadata
are frozen and root gives an explicit run-start after its current resource/PID audit. The independent SOCKS checkout and
unattributed processes are outside this task's ownership and must not be stopped. Root allows the S/MIME owner one bounded
corrected 35-case focus at fork count 1, then the new forward owner its focused checks, while combined source integration proceeds.

The final coherent pair is a modern Java 17 full reactor with `-Ppublish-cli`, including CLI/Spring/aggregate gates and packages,
then a real Java 11 library reactor excluding CLI/aggregator. Both are sequential, use explicit `-Dtest.forkCount=1`, preserve
`reuseForks=false`, all cases, original timeouts and sources, and record the scheduling cap rather than claiming default eight-fork
behavior. Existing original/prototype/failing cohorts remain separate. Daemon packaged probes use these actual combined artifacts
afterward. Root inspects the actual final combined diff/evidence before any SJM/website publication; delivery retains semantic
per-issue commits, website first, no force pushes. Exact new combined paths/evidence join the task retirement inventory.

Worker `oauth_registration_delivery` owns source, tests, applicable ADRs/docs and external issue/release workflow in isolated worktrees under `C:/workstation/sjm-oauth-registration-20261105/{pool,sjm,website}`. It may create bounded tools/cache/evidence directories there; keep original failed and final results separate. You are not alone: preserve concurrent canonical root/nested/staged/untracked work, other branches/worktrees, and the prior worker's artifacts. Do not build in dirty canonical checkouts or install unreleased sibling replacements into shared Maven storage. Use an isolated local repository for supporting-artifact integration experiments if necessary.

Root owns this active plan, canonical cleanup script and final combined acceptance/publication clearance. The worker owns pool registration/allocator/authentication APIs, integrations/tests/README/module docs/release notes; SJM core internal handoff, main credential selection, batch integration and tests; relevant ADRs/index/release history/OpenRewrite inventories and isolated website content. Coordinate overlapping shared files with root. No other implementation worker currently owns these features; finding 3 remains a design discussion.

Before GitHub changes establish published impact and introduction history. Track the additive pool API as an appropriate enhancement with a compatible minor release, provisionally 4.5.0 after verifying live versions. Separately track the actual SJM stale-callback defect with affected released-version evidence, or reuse its owning unreleased feature issue if confined to unreleased behavior. Use live canonical labels, numeric milestones and existing SJM 10.0.0 board; no generic audit issue or invented bug counts. Preserve existing issue discussion, add before usage and expected behavior, keep work open/In Progress until accepted delivery.

No verification evidence prose in any issue/commit/release body: no commands, test counts/pass claims, hashes, build/gate results or execution diaries. Publish behavioral examples, compatibility decisions and governing references only. Return execution evidence directly to root/maintainer and retained bounded artifacts.

## Preflight and verification

Trace both pool facades, legacy/default explicit registration, implicit first claim, custom sizing/expiration, allocator creation/reuse/background work, exact cluster/Session keys, unregistration and delayed disposal. Resolve auth metadata before any eager allocation can consume it; preserve atomic failure/rollback and duplicate-registration behavior without leaking callbacks. Do not execute application token callbacks under registration/bookkeeping locks.

Characterize unchanged behavior and run meaningful baseline failures before implementing: real reused-Session provider-to-fixed-token SJM send, a stale alias that throws if consulted, and direct pool explicit-binding controls. Include a real SMTP/TLS peer asserting actual username/token, not helper/mocking evidence alone. Keep the earlier 16-case mixed-protocol username regression.

Cover explicit supplier/fixed/none with stale legacy callback/token entries; legacy calls unchanged; ordinary Session Authenticator identity; connected reuse vs reconnect; missing/blank/throwing supplier; cancellation/timeout before and during token resolution with late completion; duplicate/failed registration rollback; same Session in distinct clusters, multiple Sessions and registrations; shutdown/re-registration generations and released callback references; custom sizing/expiry and eager allocation. Select representative cross-feature interactions from real branches rather than a Cartesian test explosion.

Pool: focused regressions, full JDK 21 reactor, actual JDK 8 core/provider, real-server demos, Java/JPMS/API compatibility against published 4.4.1, current publisher's effective POM and actual skipUpload bundle rehearsal. Keep demo unpublished. The previous task repaired Maven 3.10 metadata packaging with publisher 0.12.0; retain that lane and the signed bundle evidence. Use exact-head CircleCI gates/normal minor approval only after root acceptance; automation owns POM version rewriting and tag creation.

After supporting release availability, adopt actual Central artifacts in SJM and run focused real-entry regressions plus appropriate complete library reactors on real Java 11 and modern Java. Inspect current-run XML and configuration gates; never treat Maven success as zero failures. Prior broad Oracle Java 21 Mockito native-attachment issues are a known harness limit, not permission to skip cases or alter unrelated dependencies. Use supported modern Java 17 control where appropriate, retain original failures and bounded corrected results separately, and avoid automatic repeat loops without new evidence.

Reconcile all API/Javadoc/integration/config/lock/copy/serialization/CLI surfaces, ADR/index, README/release history, website, Journal/case studies/downloadable bundles and both OpenRewrite inventories. Record exact no-change reasons. Build/generate/package affected metadata when inputs change; compile supported examples and inspect rendered pages/anchors/links where available. Do not claim browser QA when settings disable it. Unreleased target-only signatures do not create fictitious 9.x rewrite rules; optional new pool APIs do not require existing callers to migrate.

## Acceptance, delivery and retirement

Send the actual upstream diff and precise evidence to root before pool publication; continue independent integration/docs work while awaiting lead review. After accepted supporting minor publication, verify exact Central parent/three-module artifact set, signatures, tag/GitHub release/milestone/owning issue state, and absence of demo/local metadata. Then send final combined SJM+website diff and current-run evidence for root acceptance.

After acceptance selectively commit/publish to pool master and existing SJM/website development branches only; website before parent gitlink. Issue-associated subjects end in owning issue numbers, bodies describe behavior/architecture with governing ADRs and no execution evidence. Complete AFTER-first comments, closure/Done and remote readbacks. A local patch or passing suite is not completion. Do not release SJM itself.

Report exact delivered commits, issue/release/project states, evidence/provenance files, remaining limitations, active helper processes and disposable paths. Keep all original user work intact. Root coordinates safe canonical alignment and registers exact bounded retirement paths in the guarded cleanup script, excluding retained evidence and avoiding unrelated or previously rejected cleanup.
