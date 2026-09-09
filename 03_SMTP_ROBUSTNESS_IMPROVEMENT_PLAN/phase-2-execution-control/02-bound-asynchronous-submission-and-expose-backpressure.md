# Step 2: Bound asynchronous submission and expose backpressure

- Status: Complete: implemented, verified and accepted on 9 September 2026; unreleased. Step 3 remains deferred.
- Depends on: Existing observer and scheduling-failure semantics; coordinate with Step 1
- Child issue: [#725](https://github.com/bbottema/simple-java-mail/issues/725), under [#722](https://github.com/bbottema/simple-java-mail/issues/722), milestone 10.0.0
- Classification: `major feature`, never also `enhancement`
- Release sensitivity: Changing the built-in executor default is compatibility-sensitive and should be decided before the 10.0 API freeze
- Primary modules: `core-module`, `batch-module`, `simple-java-mail`, `spring-module`, `cli-module`

## Goal

Prevent the built-in async path from accepting unlimited pending mail sends and make overload behavior visible to callers.

The current thread-pool size limits active workers, while its unbounded `LinkedBlockingQueue` can continue accumulating work. Supplying a custom executor remains a valid advanced escape hatch, but ordinary users should not need to construct concurrency infrastructure merely to set a queue bound.

## Contract to design

### Implemented decisions

- Preserve the unbounded default; opt in with `withAsyncQueueCapacity(int)`. Capacity -1 is unbounded, zero is direct handoff only, and positive values bound waiting tasks, excluding active workers.
- One queue belongs to one Mailer. Worker count and SMTP connection-pool capacity are independent. With no batch module the existing one-worker default remains.
- `withAsyncQueueOverflowPolicy(REJECT)` rejects immediately; `WAIT_FOR_CAPACITY` waits on the caller for `withAsyncQueueWaitTimeoutMillis(int)` (positive, default 1000ms). SMTP never runs through a caller-runs overflow policy.
- `MailSendRejectedException.getReason()` distinguishes queue full, wait timeout, interrupted admission, and executor shutdown. Ordinary rejected emails preserve the existing caller-thread observer ordering and exact failure identity. Lazy rejected batches produce no per-email outcomes or iterator access.
- `Mailer.getAsyncQueueSnapshot()` returns optional, immutable, content-free counts and settings for built-in executors. Existing outcome timestamps supply per-email queue delay. Runtime estimates are separate from configuration provenance.
- All settings have `simplejavamail.defaults.async.queue.*` properties, typed schema/group/sensitivity declarations, Spring metadata, and CLI options. They are Mailer settings, not Email fields: no Email copying, conversion, defaults/overrides, or serialization changes are needed.
- Caller-owned executors retain their behavior and lifecycle; combining them with non-default built-in queue settings fails explicitly. `resetAsyncQueue()` discards those settings. Standalone `BatchTransportExecutor` keeps its own separate queue.
- Graceful close rejects new owned-executor work, drains accepted work, then closes its pools. Caller-owned and synchronous work must be finished by the application. Blocking close from the Mailer's own worker is rejected rather than deadlocking. This does not implement SMTP cancellation or a total deadline.
- SMTP Connection Pool 4.1.0 is adopted, retaining the eight 9.3.4 waiter-recovery regression cases. Upstream claim-cancellation controls remain opt-in and are not connected to Mailer sends by this dependency upgrade.

The questions below are retained as the design checklist that led to these decisions.

Define independently:

- maximum queued submissions;
- maximum concurrently executing submissions;
- whether limits are global to one Mailer or scoped per endpoint/cluster;
- the overflow policy;
- the maximum time a caller may wait for capacity;
- how saturation is represented in futures, observers, diagnostics, and metrics.

At minimum, consider explicit policies equivalent to:

- reject immediately;
- block until capacity or deadline;
- delegate to a caller-supplied rejection policy.

Do not silently run SMTP work on the submitting thread as an incidental `CallerRunsPolicy` side effect. If that behavior is supported, give it an explicit user-facing name and document the thread and latency consequences.

The design must decide whether a finite default is introduced in 10.0 or only a first-class configurable bound. A default change can surface rejections in applications that previously accumulated unlimited work, but retaining an unbounded default weakens the robustness benefit.

## Tests first

1. Characterize current queue growth with blocked worker threads and more submitted futures than the pool can execute.
2. Fill the configured queue exactly, submit one more send, and prove each overflow policy.
3. Verify rejected scheduling never acquires a pooled transport, proxy lease, or SMTP connection.
4. Verify the mail-send observer receives exactly one scheduling-failure outcome on the caller thread.
5. Verify accepted work retains FIFO behavior unless a documented executor policy says otherwise.
6. Verify closing a Mailer stops new submissions and drains or rejects already queued work according to the chosen shutdown contract.
7. Cover pool sizes smaller than, equal to, and larger than the executor size.
8. Cover simple batches and lazy iterables without opening an iterable after batch scheduling rejection.
9. Prove a caller-owned executor remains caller-owned and its existing rejection behavior is not wrapped misleadingly.
10. Cover Java API, property configuration, Spring metadata, and CLI exposure where representable.

## Diagnostics and operations

Expose enough immutable state to answer:

- current queued and active counts;
- configured limits and overflow policy;
- queue wait time for a completed attempt;
- number or reason of rejected submissions without retaining Email content.

Do not add mandatory Micrometer or OpenTelemetry dependencies. Stable counters or observation hooks may support optional adapters later.

## Acceptance criteria

- [x] The built-in async path can be configured with a hard queue bound.
- [x] Every overflow policy has deterministic future and observer behavior.
- [x] Rejected work acquires no transport or proxy resources.
- [x] Queue saturation is diagnosable without exposing message content.
- [x] Caller-owned executors preserve their ownership and documented semantics.
- [x] Shutdown behavior is explicit for active, queued, and newly submitted work.
- [x] Configuration, Spring, CLI, classpath, and JPMS surfaces remain aligned.

## Stop condition

If one setting cannot describe both ordinary async sends and batch-module execution without changing established batch semantics, keep their executors separate and document the boundary. Do not hide two queues behind one misleading limit.

## Verification and review handoff, 8 September 2026

- `MailSendQueueTest`: default/unbounded behavior, exact saturation, caller-thread observation and failure identity, lazy batch rejection, typed configuration, bounded admission/interruption, no-batch execution, graceful drain, self-close protection, and caller-owned executors, including an executor borrowed from another Mailer.
- `MailSendExecutorTest`: zero/positive capacities, concurrent producers, shutdown waking an admission waiter, idle-worker restart, and non-daemon workers even when submitted from a daemon thread.
- `QueueRejectionResourceTest`: rejected sends and connection tests never construct the closures that register proxy accounting.
- `MailSubmissionPoolingTest`: executor/queue behavior with connection-pool sizes below, equal to, and above worker count; accepted queues drain before pool shutdown. Existing observer, pooled receipt isolation, invalidation/reentrant-send, and batch tests remain green.
- `BatchTransportExecutorTest`: all eight failed-lease waiter-recovery cases ported from 9.3.4 pass against SMTP Connection Pool 4.0.2.
- Full Java 21 `clean verify -Ppublish-cli -DexcludeLiveServerTests=true` completed successfully: 786 tests, no failures/errors, one skipped. Java 11 non-CLI `clean verify -DexcludeLiveServerTests=true -Dmaven.javadoc.skip=true` completed successfully: 696 tests, no failures/errors, one skipped. A final Java 21 focused verify covered the last ownership/resource-boundary test changes and reran the classpath/JPMS consumers.
- All three Spring lanes pass: Boot 2.7.18 / Spring 5.3.39 / Java 11, Boot 3.0.13 / Spring 6.0.14 / Java 17, and Boot 3.5.16 / Spring 6.2.19 / Java 21. Typed queue provenance and generated IDE metadata are included.
- CLI metadata regenerated; all three queue options' generated help was run from the packaged CLI. Mapping and typed argument tests pass. Settings and runtime diagnostics compile on the classpath and JPMS without Angus on the consumer runtime path.
- Website check and clean build pass. The internal-link scan reports only five existing Journal links; unrelated Journal changes are preserved. Updated API/configuration/diagnostic/migration links are valid.
- That run did not generate Javadocs cleanly: Java 21 reported source-discovery errors for `org.simplejavamail.api.email` and `org.simplejavamail.api`, then treated them as non-fatal. The follow-up below resolves this separately from the original passing compile/test result.
- `mvn license:remove` completed. No license headers were introduced by this diff. Four unchanged older test files still have tracked headers and were not swept into this change. Both staged and unstaged whitespace checks pass.
- Coding-guide audit: kept queue admission and diagnostics cohesive in `MailSendExecutor`, lifecycle sequencing in `MailerImpl`, and immutable settings in the existing typed config path. Public contracts live on the API; implementation methods link back to them. No speculative strategy hierarchy or general-purpose utility layer was added. The audit corrected daemon-thread inheritance, avoided blocking application common-pool threads during shutdown, and kept a borrowed Mailer executor caller-owned.

At the 8 September handoff, changes remained on `codex/10.0.0` and website `codex/10.0.0-content`, uncommitted for review. #725 remained In Progress; #726 was Blocked at the separate provider gate. No issues were closed and no commits were pushed.

## Pool 4.1.0 integration and Javadoc verification, 8 September 2026

- Adopted the published SMTP Connection Pool 4.1.0 / Clustered Object Pool 4.1.0 / Generic Object Pool 2.5.0 chain. Existing send APIs remain unchanged; this does not implement #726.
- The focused Java 21 reactor verification passed all 107 tests for pooled sends, observer ordering, queue admission and shutdown, receipt isolation, recipient replies, and SMTP fault boundaries. All eight failed-lease waiter-recovery cases still pass. Their synchronization helper now recognizes `ClaimAttempt.awaitAvailability`, the released dependency's availability-wait method.
- The full run exposed a timing flaw in `QueueRejectionResourceTest`: constructor-mock startup could outlast the worker's five-second hold, freeing capacity before the rejection assertions. Constructor mocks are now installed first, and the worker remains held until the test's `finally` block releases it. The existing overall test timeout and resource/rejection assertions remain in place.
- The standard Javadoc profile now uses classpath documentation mode (`legacyMode`) for sources in `src/main/java`; separately compiled multi-release JPMS descriptors remain unchanged. `failOnError=true` prevents source-discovery errors from being hidden behind a successful build. This makes the earlier phase-one command-line workaround the normal build configuration.
- Full Java 21 `clean verify -Ppublish-cli -DexcludeLiveServerTests=true` passed: 788 tests, no failures/errors, one opt-in benchmark skipped. Generated core, main and batch Javadoc JARs contain the expected public API pages. Classpath and JPMS consumers pass; the core/main JARs retain their versioned module descriptors. Regenerated CLI metadata is byte-for-byte unchanged.
- Full Java 11 `-pl '!modules/cli-module' clean verify -DexcludeLiveServerTests=true` passed with Javadocs enabled: 697 tests, no failures/errors. One existing symlink-escape test was skipped because this JDK could not create a Windows symbolic link without the required privilege; that test passed on Java 21. Core, main and batch Javadoc pages and the classpath/JPMS consumers pass on this lane too.
- `mvn license:remove` passed; no generated main-source headers remain. The four unchanged older test headers were preserved. Staged and unstaged whitespace checks pass. The two adjusted test helpers were checked against the coding guide: synchronization and cleanup remain explicit, and no production API or implementation abstraction was added.
- Build guidance, dependency documentation, release notes and plan status were updated. This follow-up was left uncommitted on `codex/10.0.0` for review. The website and Journal were not touched by that follow-up, and no GitHub state was changed.

## Review acceptance and direct-handoff recovery, 9 September 2026

- The queue API, implementation and documentation are accepted. This completes step 2, not the separate mail-send cancellation and total-deadline contract in step 3.
- Holistic review reproduced a zero-capacity admission race: the last worker could expire after initial executor rejection, leaving the timed `SynchronousQueue` offer without a consumer. `MailSendExecutor` now prestarts an available worker before every admission poll and retains the post-offer restart needed for buffered queues.
- A controlled regression waits for the busy worker to finish and expire before entering the original admission handler. It fails before the fix and passes afterwards for direct handoff and buffered admission, with exactly one worker-side execution and no rejection count. Shutdown-waiter coverage now tests both capacities.
- Focused Maven verification passed all 66 queue, executor, resource-boundary, pooling, observer and batch tests, including timeout, interruption and shutdown behavior. The controlled expiry case also passed 200 repetitions outside the normal suite. Classpath/JPMS consumers and Javadocs passed.
- Before committing, the full Java 21 `clean verify -Ppublish-cli -Dlicense.skip=true -DexcludeLiveServerTests=true` run passed: 806 tests, no failures/errors, one opt-in benchmark skipped. CLI metadata was regenerated, and Javadoc packaging plus classpath/JPMS checks passed. Website checks and the clean build passed; the internal-link scan still reports only the five unrelated Journal links.
- Only the executor and its test changed for the race fix. Existing staging and unrelated work were preserved. Commit authorization does not include pushing, issue closure, or Journal changes.
