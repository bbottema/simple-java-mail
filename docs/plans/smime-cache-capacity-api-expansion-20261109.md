# Configurable S/MIME key-cache capacity: API expansion and review plan

Status: approved by the maintainer on 9 November 2026 for delegated implementation and completion of the review workflow. Prepared against `codex/10.0.0` at `ab3e445e`. Implementation is to build on the delivered import-limit work under #978.

Tracking: [#977](https://github.com/bbottema/simple-java-mail/issues/977), reopened/Todo before this packet's execution. Reuse this issue for the revised capability and final delivery; do not create a second cache ticket or count another independent fix for the same concern.

The maintainer approved this complete packet with "Accepted. Delegate to subagent." The unlimited default, numeric meanings, factory ownership and configuration surface below are accepted, together with delegated implementation, required review and normal delivery/bookkeeping. The earlier hardcoded 256-entry/30-minute policy is the delivered baseline superseded by this decision.

Execution must follow both the [API expansion workflow](../API_EXPANSION_WORKFLOW.md) and [public API review and repair workflow](../API_REVIEW_WORKFLOW.md), together with the [coding style guide](../CODING_STYLE_GUIDE.md), [configuration contract gate](../CONFIGURATION_CONTRACT_COVERAGE.md), [development guide](../DEVELOPMENT.md) and [maintainer workflow](../MAINTAINER_WORKFLOW.md).

## 1. Approved contract

### One setting, with an explicit default

Add one factory configuration property:

```properties
simplejavamail.smime.cache.capacity=10000
```

The number above is an application example for an organization with many keys. It is not a proposed library default.

| Value | Behavior |
| --- | --- |
| Absent | Use the published library fallback, `-1` (unlimited). |
| `-1` | Retain completed entries without a count limit while their factory cache remains reachable. |
| `0` | Retain no completed entries; an operation can still load and use the key. |
| Positive integer | Retain at most that many completed configurations; retire the least recently used entry when space is required. |
| Below `-1`, wrong type, fraction or integer overflow | Reject at the existing type/feature validation boundary with an actionable error. |

Unlimited preserves the released library's ordinary reuse behavior without inventing another universal numeric working-set size. Applications that need bounded retention must configure a positive capacity. This deliberately supplies no default memory bound. A finite default would need an explicit replacement decision before implementation.

Remove time-based expiry, its scheduling worker, idle clock and shutdown/security-permission machinery. Retention depends on capacity and reachability of the owning runtime. An inactive cache can retain its working set for the owner's lifetime. No TTL setting or hidden time-based fallback is included in this revision.

Capacity counts completed configurations, including their retained keystore/password data and parsed key. It is not a byte, heap, employee, message or concurrency limit. In-flight loads and keys already held by operations have their own existing operation lifetimes. Capacity pressure does not reject signing/decryption, cancel work or destroy a key in use. A later lookup can reload an evicted configuration.

Equal configurations share a completed resident key and concurrent first-use loading. Failed loads do not become resident; owner and waiters retain the original failure identity and later calls can retry. Capacity zero guarantees no completed-entry retention; overlapping requests may still share an active load. Key loading stays outside bookkeeping locks, and cryptographic interpretation remains in the optional module.

### Small Java API surface

Use the existing factory configuration API, adding the canonical `ConfigLoader.Property.SMIME_KEY_CACHE_CAPACITY`, invariant `ConfigKeys.SMIME_KEY_CACHE_CAPACITY` and one documented public fallback constant on the core configuration owner (`ConfigLoader.DEFAULT_SMIME_KEY_CACHE_CAPACITY = -1`).

```java
SimpleJavaMail mail = SimpleJavaMail.withConfig(
    ConfigLoader.builder()
        .withMap(Map.of(ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key(), employeeKeyCapacity))
        .load());

Mailer mailer = mail.mailerBuilder().withSMTPServer("relay.example.com", 587).buildMailer();
Email email = mail.emailBuilder().startingBlank()
    .from("sender@example.com").to("reader@example.com")
    .withPlainText("Update").signWithSmime(customerSigningConfig).buildEmail();
mailer.sync().sendMail(email);

Email received = mail.converter().fromEml(incomingMessage)
    .withSmimeDecryption(customerKeys).toEmail();
```

Sending and conversion use the same factory-owned cache. Applications retain their ordinary Mailer closing responsibility. No additional per-send/per-conversion parameter, Mailer capacity setter, mutable global setter, public cache object, factory overload or single-field configuration wrapper is proposed. `SimpleJavaMailConfig.get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)` inspects captured input; absence stays null rather than fabricating a configured default.

Using existing `ConfigLoader.withMap/withProperties/withConfig` methods retains their source ordering, type handling and provenance. This expansion introduces a property and typed read identity, rather than another family of fluent setters. Builder/getter/reset rules apply to existing setup routes; there is no new mutable builder with undocumented companions.

### Factory ownership

Each `SimpleJavaMail` instance owns its lazy S/MIME runtime. Mailers, converters and applicable helper flows obtained from that factory share its cache. Separate factories have independent caches and capacities, including two factories built from the identical `SimpleJavaMailConfig` object. Their budgets add to the application's aggregate retention; no process-wide total is promised.

- Keep captured configuration immutable. The live cache belongs to runtime ownership, never to the serialized snapshot or a global map keyed by snapshots/factories.
- Keep the reflection-loaded S/MIME module entry point stateless with respect to factory caches. It may construct a necessary factory-owned internal runtime through the existing core module boundary. Optional-library key types and assembly stay in the S/MIME module.
- Do not load the optional module, read key material or construct a cache merely to create a factory, inspect its configuration or send ordinary unprotected mail. Type parsing follows normal source loading; feature-owned capacity semantics are checked before first key loading on the active feature path.
- Retaining a factory, converter or Mailer can retain its cache. Closing an individual Mailer does not clear another participant's entries. The factory adds no close obligation because the revised cache owns heap values and no worker/thread/connection.
- A reusable `Email`, `Pkcs12Config` or serialized Email graph must not capture a live cache or factory. The actual selected sending/preparation context chooses the cache; an Email constructed under factory A can be sent using factory B's cache.
- Existing `fromDefaults()` and static convenience routes use the conventional default factory's context. Internal compatibility/test constructors receive or create an explicit isolated context; they must not silently return to the old module-global cache.
- Factory-owned reply/forward helpers, direct export/protection, rehearsal, send preparation, pooled/clustered/failover selection, incoming decryption, nested Outlook and retained-content processing must preserve the appropriate owner's context. Rehearsal after Mailer close keeps its receiver's preparation/cache context even if the sending Session is subsequently reused.
- Named private failover Mailers participate in the creating factory's cache policy. The new property is factory-root configuration, not a per-named-Mailer override. Reject declarations under `simplejavamail.mailers.<name>.smime.cache.capacity` with guidance to configure the root or use a separate independently configured factory. A borrowed failover Mailer from another factory uses that actual destination's cache. Preserve all existing named connection/template isolation.

## 2. Property, lock, Spring and CLI contract

Use the one canonical scalar and `simplejavamail.locked.smime.cache.capacity`. Register the invariant Integer key, authoritative schema parser, existing suitable diagnostic group and non-secret display metadata. Document the `-1`/zero/positive meanings and central fallback in Java and text configuration. Remove production references to the former private 256/30-minute defaults.

- Ordinary and locked sources follow existing `ConfigLoader` ordering and effective-value rules. A later ordinary source cannot weaken the selected locked value; effective cache policy consumes the resolved snapshot. Do not invent a second parser or interpret a generic source map as a new post-load Java override layer.
- The factory offers no downstream cache-policy mutator. Changing capacity requires creating a replacement configured factory and replacing the participating objects, consistent with immutable snapshots. Existing objects keep their captured policy.
- Property loading does not imply S/MIME activation. Keep malformed source types distinct from inactive optional-feature semantics and retain normal missing-module errors when behavior is actually requested.
- Extend Spring's metadata-only `SimpleJavaMailProperties` shape and reused locked root. Use existing `SpringEnvironmentConfigSource` enumeration and precedence. Cover actual signing/decryption with two independently configured contexts, as well as metadata membership.
- CLI uses the same setting through existing captured configuration. No cache-management command or public Java-only scalar adapter is needed. Confirm actual signing/import routes receive the request's owner; a retained daemon Mailer may be reused only with a profile compatible with the capacity setting. Preserve option names and packaged startup/help.
- Regenerate the normal CLI metadata from final sources and check the fixed option inventory. Generated cache binaries and `target` metadata are not committed.

## 3. Impact map and delivery dependency

#978 is already changing import traversal, module bridges, factory configuration and documentation. Use its accepted delivered revision as the cache implementation baseline. If this plan is approved before #978 completes, the issue lead can prepare tracking, trace paths and refine the exact inventory; source changes on overlapping paths wait for that baseline. Refresh the plan with the actual base revision before implementation.

| Responsibility | Concrete homes and work |
| --- | --- |
| Public configuration | Core `config/ConfigLoader.java`, `ConfigKeys.java`, `PropertySchema.java`, relevant Javadocs and core-only fixtures. One scalar property, typed key and fallback; factory-root scope classification and locked counterpart. |
| Factory ownership | Main `api/SimpleJavaMail.java` and a necessary internal factory runtime collaborator. Preserve `FactoryMailerRuntime`'s existing allowance/recovery responsibilities; avoid a broad rename solely for this feature. |
| Sending and preparation | `mailer/internal/MailerGenericBuilderImpl.java`, `MailerImpl.java`, `SessionBasedEmailToMimeMessageConverter.java`, `internal/smime/SmimeMessageProtection.java` and actual MIME source/producer calls. Propagate the selected receiver/destination's context without mutating supplied Sessions to invent ownership. |
| Conversion and helpers | `converter/internal/ConfiguredEmailConverterImpl.java`, `InternalEmailConverterImpl.java`, `FluentEmailConversions.java`, `email/internal/EmailStartingBuilderImpl.java`, applicable populating-builder/factory helpers and static `EmailConverter` delegations. Reuse the explicit context added around #978 rather than adding global lookups or parallel public overloads. |
| Optional S/MIME implementation | Core internal `SMIMEModule` boundary as needed; optional `SMIMESupport.java`, `SmimeKeyCache.java`. Remove static cache ownership/TTL worker, retain loading isolation, finite LRU, zero/unlimited behavior and failure semantics. Data/provider types stay in their existing modules. |
| Other module/integration crossings | Existing `InternalEmailConverter`, Outlook and receive-side bridges only where an actual context handoff is needed. Preserve #978's per-operation import budgets and all ordinary/absent-module behavior. |
| Inventory and tests | Existing configuration API/extra-route/obligation TSVs, source/property classifications and runtime witnesses; core-only/JPMS usage, Spring packaging/runtime, CLI profile/startup/import controls, public signing/decryption/cache concurrency tests. |
| Maintained docs | ADR 0006 and minimal cross-references/index, release history, website security/configuration/Spring/conversion guidance, OpenRewrite inventories, relevant Journal/case-study examples and downloadable sources. |

The most sensitive interaction is sharing across signing and decryption without leaking policy across factories or following the factory that originally constructed an Email. Establish this through an actual public caller scenario before expanding the remaining plumbing. An isolated cache-helper test is not proof of owner propagation.

## 4. Behavioral scenarios and verification

| Scenario | Required result |
| --- | --- |
| Absent and explicit unlimited capacity | Ordinary repeated key reuse with no time-based eviction; document unbounded retention while the owner remains reachable. |
| Capacity zero | Valid signing/decryption with no completed entries retained; repeated non-overlapping use reloads the supplied material. |
| Finite capacity and lookup order | Least recently used completed entry retires; hits refresh recency; full capacity does not reject work. |
| More than 256 active configurations | Behavior follows the selected capacity, with no leftover private 256-entry ceiling. |
| Old key held across eviction | The active operation can finish; a subsequent lookup reloads. |
| Concurrent first use and loader failure/Error | Same active load/result shared, unrelated loads proceed outside bookkeeping locks, original failure identity preserved and later retry possible. |
| Mailer and converter from one factory | Signing and incoming decryption reuse the intended owner under the same capacity. Repeated `converter()` calls do not create fresh caches. |
| Two factories, even with the same snapshot instance | Independent residency and limits; no first-factory-wins, last-writer-wins or static cache registry. |
| Email built under A, sent/prepared by B | Actual selected B context owns key reuse; ordinary Email copying/serialization introduces no runtime reference. |
| Cluster/failover/named destination and late rehearsal | Correct destination/receiver cache, normal locks and Session ownership, and preserved preparation after close. |
| Root/named scalar scopes, source order and locks | Factory-root property works; unsupported named override is refused; locked input remains effective through ordinary source overlays. |
| Module absence and plain mail | Configuration/getters remain passive; unused S/MIME cache policy creates no module runtime or worker. |
| CLI daemon profile change | A changed capacity cannot reuse an incompatible retained Mailer/context; compatible requests retain normal reuse. |

Perform test-first implementation and the required verification in dependency order:

1. Reconcile #978's delivered sources and exact entry-point inventory. Retain genuine ordinary S/MIME controls. Add behavioral red tests for configuration, zero/unlimited capacity and factory isolation; a missing public key compilation error alone is not sufficient red evidence.
2. Implement the single property and minimal internal owner/context propagation. Exercise real signing and incoming decryption early, then alternate selected destinations and nested/helper paths. Do not hide context in a process-global/ThreadLocal registry or an Email snapshot.
3. Extend finite property/field/consumer contracts with independently expected behavior and executing witnesses for each actual setup route. Include typed reads, source overlays, locks, factory replacement, shared snapshots, Spring and CLI wiring. Preserve existing denominator rules rather than reducing them to make a gate pass.
4. Run focused Maven reactor tests with fresh dependencies. Inspect fresh Surefire XML for failures, errors and skips; failure-ignore can make Maven success misleading. Preserve original private-fixture placeholders after any authorized local credential use and never print/stage those values.
5. Run the real Java 11 library gate and full modern-JDK/configuration gate, CLI packaging/metadata, Spring metadata and relevant application classpath/JPMS/Javadoc checks required by the workflows. Inspect generated public documentation for default/value/scope wording. Verify that TTL scheduling code and production constants are gone; avoid flaky wall-clock or GC-timing tests for the new ownership contract.
6. Compile/type-check changed runnable examples against final artifacts and inspect affected rendered website pages and links. Do not substitute a search or a successful build for behavior/rendering checks.
7. Obtain independent read-only code/API review of the final combined diff and evidence, followed by root lead acceptance. Review both scope isolation and the documentation surface matrix. Return material contract changes to the maintainer and keep unfinished work open.

## 5. Project upkeep and handoff

Rewrite #977's title/body around configurable entry capacity and the approved default/ownership, while preserving its original problem and delivered-commit history. Classify the final scope accurately: with an unlimited default this delivers a configuration capability, rather than fixing unbounded default retention. Reconcile its primary label with that enhancement scope, retain milestone `10.0.0` and the existing project, and move Todo to In Progress when execution begins. Treat this as continued cache-policy work, not a duplicate enhancement/bug total. Keep issue text conversational, with before/after usage and observable acceptance criteria.

- Reconcile ADR 0006 into one current cache decision: configurable capacity, accepted owner/default, no TTL, operation-safe eviction and optional-module boundaries. Retain provenance to #320/#906/#977 without accumulating another contradictory patch narrative. Add only necessary configuration/runtime-owner references to ADRs 0003/0031 and the index. Broader ADR streamlining remains separate.
- Update and, if necessary, move the existing #977 release-history entry to the feature category for its final behavior rather than publishing a second entry or claiming bounded defaults. Website feature guides describe current behavior without version labels. Include property meanings, sharing scope, default unlimited retention, factory replacement, and what closing a Mailer does to the shared cache.
- Inspect security, configuration, Spring and conversion pages; Journal/case-study/downloadable examples, especially the many-customer/employee scenario; update only affected usage and record no-change reasons. Preserve root's unrelated dirty article/style files.
- Reconcile both OpenRewrite inventories and final target fixtures. No source migration is implied by a new optional property, and changing an unreleased 256/30-minute experiment does not create a released migration obligation. If a released supported public API unexpectedly changes, return that finding rather than inventing an automatic rewrite.
- Review core API/default/property metadata, copy/serialization, locks, optional-module activation and lifetime, current CLI option/cache inputs, and appropriate operational/concurrency guidance. Runtime cache ownership must not become outgoing Email/template configuration or a new SMTP/receipt/progress model.
- Keep verification evidence in the direct maintainer report or CI artifacts. Never add commands, test counts, pass/fail reports, review verdicts or execution diaries to GitHub issue titles/bodies/comments, commit messages or ADRs.

Assign one dedicated issue lead an isolated main checkout and an isolated website checkout where needed, based on #978's delivered revision. The lead owns the paths above and may coordinate non-overlapping implementation workers plus an independent reviewer. Every worker must be told it is not alone: preserve all other work, especially root's ADR 0037 clarification, approved import-limit work, staged Angus research, private test settings, nested website changes and the already-dirty cleanup script.

The approval packet covers autonomous normal workflow completion: owned implementation commits, required reviews, safe shared-root/nested integration, ordinary publication to `origin/codex/10.0.0` and `origin/codex/10.0.0-content`, and #977 completion/Done after accepted delivery. The root lead reviews the actual combined result before final integration/publication. Website commits precede the parent gitlink; verify remote heads and exact preservation of unrelated staged/unstaged files. No release, deployment, force push or history rewrite is included.

Use semantic issue-suffixed commits and name the governing ADRs in their bodies. Completion comments start with final supported usage and link behavioral changes, ADRs and delivered commits. Verify labels, milestone, board status and closure only after required work is delivered.

Maintain exact task-owned cleanup entries, preserve existing script bytes/guards and validate syntax/WhatIf. Do not traverse or remove broad shared scratch parents; the existing unrelated node_modules reparse point remains protected. Retire the active plan after durable decisions and useful procedures are assimilated, preserve/report evidence awaiting inspection, and retire only verified task-owned worktrees/artifacts.
