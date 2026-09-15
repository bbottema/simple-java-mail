# Step 4a: Explicit Mailer sync/async execution views

- Status: Complete and accepted; implementation and verification finished on 12 September 2026, production review accepted on 13 September 2026. Delivered independently of the probe, which was subsequently completed and accepted under #733 on 15 September 2026.
- Parent: [#722](https://github.com/bbottema/simple-java-mail/issues/722); cross-linked with the [SMTP probe, #733](https://github.com/bbottema/simple-java-mail/issues/733).
- Child issue: [#734](https://github.com/bbottema/simple-java-mail/issues/734), milestone 10.0.0.
- Classification: separate 10.0.0 `enhancement`; do not reopen completed Phase 2 issues.
- Branches: `codex/10.0.0` and website `codex/10.0.0-content`.

## Review bookmark

The first probe batch presented the Mailer entry points, `SmtpConnectionPhase`, and `SmtpConnectionReport`. Transport strategy has since been removed from the report, construction, and website output; focused verification passed. The entry-point portion is superseded by this redesign.

At this historical bookmark, no later production batch had been presented; the next batch was `SmtpCapabilities`, `SmtpTlsDetails`, and `SmtpDiagnosticText`. The CLI probe command and third-party adapter fixture were still separate, unfinished work under #733. That follow-up is now accepted; see the [completed probe plan](04-add-structured-smtp-capability-probe.md). Phase 3's separate authentication-policy step remains unfinished.

The accepted migration was committed independently: it includes the existing send, simple-batch and connection-test families. The probe declarations, implementations, types, tests and documentation were left for #733. That follow-up adds the agreed probe methods to these same views; no temporary aliases or unsupported-operation stubs were introduced to make the migration commit stand alone.

## Public API

Make execution mode explicit per operation. Add nested public interfaces `Mailer.Sync` and `Mailer.Async`, returned by `Mailer.sync()` and `Mailer.async()`.

| Operation | Sync return | Async return |
| --- | --- | --- |
| `sendMail(Email)` | `MailSubmissionReceipt` | `MailSend<MailSubmissionReceipt>` |
| `sendMailsInSimpleBatch(Iterable<Email>)` | `void` | `MailSend<Void>` |
| `testConnection()` | `void` | `CompletableFuture<Void>` |
| `probeConnection()` | `SmtpConnectionReport` | `CompletableFuture<SmtpConnectionReport>` |
| `probeConnection(boolean authenticate)` | `SmtpConnectionReport` | `CompletableFuture<SmtpConnectionReport>` |

- Remove all corresponding operations from the outer Mailer: default-driven methods, execution-mode booleans, flat Sync/Async variants, and receipt-specific variants. No deprecated forwarding aliases.
- Both single-email families become `sendMail`; callers may ignore the returned receipt. Neither view has `sendMailAndGetReceipt`.
- Remove builder `.async()`, builder/config `isAsync()`, and the backing configuration field. Keep the probe authentication boolean.
- Keep configuration accessors, queue inspection, validation, rehearsal, `withOpenConnection`, and lifecycle methods on Mailer. The scoped `MailSender` API is unchanged.
- Neither view extends Mailer, MailSender, or AutoCloseable. No shared generic operations interface or default sync/future bridges.
- Complete contracts belong on the view interfaces; implementations link to them with `@see`.

```java
mailer.sync().sendMail(email); // receipt is optional to inspect
MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email);
```

Simple batches retain lazy iteration, sequential sending over a shared connection, and first-failure stopping. Do not accumulate receipts or introduce a batch-result collection. Configured `MailSendObserver` callbacks expose individual outcomes.

## Implementation and integrations

1. Cache one immutable private adapter per mode on each MailerImpl. View access opens no connections, starts no workers, and registers no additional pool. Both views share their owning Mailer's resources, synchronization, and lifecycle.
2. Delegate directly to the established receipt-producing paths. Remove receipt-discarding forwarding and obsolete completed-handle scaffolding.
3. Preserve existing sync/async paths, caller-thread preparation, admission, cancellation, deadlines, batch laziness, observer ordering, and cleanup. Do not implement all async operations by wrapping sync calls in `supplyAsync`.
4. Return the exact successful receipt; retain direct synchronous failures, asynchronous completion failures, immediate argument-validation exceptions, and failure receipts.
5. Preserve executor, queue, pool, timeout, property, and Spring configuration. These configure resources, not execution mode.
6. Remove logging-only mode flags from SessionLogger and TestConnectionClosure. Shared Session/conversion logging is mode-neutral; no new global mode or execution-context propagation just for logging.
7. Migrate production callers, demos, tests, method references, Javadocs, and provider-neutral consumers. Single-email async handles now carry receipts rather than Void.
8. CLI `send` and `connect` use the synchronous view, ignoring send receipts and preserving output/exit semantics. Daemon request concurrency remains daemon-owned. Remove generated `--mailer:async` and its option help; reject attempted use with a helpful CLI argument error before executing the command. Regenerate metadata through the normal build. The CLI probe command stays out of scope.

## Verification

- Exact interface signatures/types; positive compiled examples that ignore/retain receipts; negative fixtures for removed builder, default, hybrid, flat, and receipt-specific methods.
- Stable view identity, side-effect-free access, mixed modes sharing resources, retained views after shutdown.
- Both modes across send, batch, test, and probe: success and applicable preparation, admission, transport, and cleanup failure paths. Distinguish direct exceptions from exceptional completion.
- Receipt identity between return and observer; unchanged failure receipts; cancellation/completion ordering without the discard wrapper.
- Queue/backpressure, pool concurrency, cancellation/deadline, observer, batch laziness/first failure, and open-connection regressions; no batch receipt-history accumulation.
- CLI help, removed-option errors, unchanged output, one-shot completion/exit codes, and concurrent daemon requests.
- Java 11 libraries, classpath/JPMS consumers, full non-live modern-JDK reactor including CLI, applicable Spring/starter checks.
- Apply API_EXPANSION_WORKFLOW.md and CODING_STYLE_GUIDE.md; run `mvn license:remove` afterward and keep generated headers out of commits.
- Website check, clean build, internal-link verification. No SpotBugs, benchmarks, or live-email demos.

## Documentation and delivery

- Add a 10.0.0 migration section covering every operation mapping, removed builder mode, receipt-bearing sends, streaming batches, failure contracts, application-owned runtime mode selection, and integration recompilation.
- Migrate current 10.0.0 README/website sending, diagnostics, configuration, and CLI examples, release notes, and mechanisms catalogue. Explain that receipts describe SMTP submission, not final delivery, and are optional to inspect.
- Preserve historical documentation, unrelated staged research, and all unrelated Journal work.
- Update concurrency catalogue entry-point references. The approved infographic's implementation layers and resource ownership remain accurate; record the review, without regenerating it.
- Production review was accepted on 13 September 2026. Commit the migration separately, push both repositories, then close #734 and mark its project item Done. Keep #733 open and do not mark Phase 3 complete.

## Implementation and verification record

Implementation and production review are complete. The two cached adapters delegate to the existing operation paths; the old mode field, flat/default/boolean execution API and receipt-discarding wrapper are removed. CLI metadata was regenerated by the normal build. Current 10.0.0 callers and documentation use the views; historical version pages and unrelated Journal/research edits are untouched.

The coding-guide audit covered the changed public contracts, builder/config removal, Mailer adapters and execution helpers, Session logging/conversion, and CLI delegation/rejection. Contracts stay on the view interfaces with implementation `@see` links. The adapters add no resource ownership, defaults, locks or generic coordination layer. The audit also clarified the distinction between execution-thread and queued-completion-worker observer dispatch.

| Verification lane | Result |
| --- | --- |
| Focused queue/backpressure, cancellation/deadline, observer, pooling, preparation/admission and probe regressions | Passed |
| Public API signatures, positive examples, negative compilation fixtures, cached views and shared ownership | Passed on Java 11 and 21 |
| Full non-live Java 11 library reactor, classpath/JPMS and managed-Angus consumers | Passed |
| Full non-live Java 21 reactor including CLI and packaging | Passed |
| Added CLI failure/cleanup and real daemon-process concurrency checks | Passed: 19 focused tests, including the existing daemon regressions |
| Boot 2.7.18 / Spring 5.3.39 on Java 11, plus refreshed API/view/pool tests | Passed |
| Boot 3.0.13 / Spring 6.0.14 on Java 17 | Passed |
| Boot 3.5.16 / Spring 6.2.19 on Java 21 | Passed |
| Website checks and clean build | Passed |
| Website internal links | Checked; five pre-existing broken links remain in the unrelated Journal work, none in the migrated pages |
| License-header cleanup | `mvn license:remove` passed after the final build; no generated production headers or newly introduced headers remain |

The pool-size-one observer regression now runs with either a sync or async outer send and re-enters through the sync view, proving reuse of the same released transport and exact receipt identity. The daemon concurrency fixture gates recipient submission, not connection allocation: existing pool allocation callbacks are intentionally serialized.

Local logs are under `tmp/execution-views-*.log`. No SpotBugs, benchmarks or live-email demos were run. Both copies of the approved infographic retain SHA-256 `6678f77dd10760e193af9c88ebc17c31ea3483c7d9e8c8da58eed9ab0621f7c9`; the catalogue records why the existing layers remain accurate.

Four untouched test files already contain license headers in HEAD (`DkimWireSignerTest`, `OpenPgpDetailsTest`, `OpenSslSmimeInteroperabilityTest`, `SmimeGlobalStateIsolationTest`). They are outside the license plugin's production-source include and were not changed by this migration.

Review was accepted under #734 on 13 September 2026. Resume the probe at `SmtpCapabilities`, `SmtpTlsDetails` and `SmtpDiagnosticText` after the separate delivery. The CLI probe command, third-party adapter fixture and the rest of Phase 3 remain outside this change.

## Standalone delivery verification (13 September 2026)

The migration was separated into an isolated candidate based on the previous committed branch tip, without the uncommitted probe classes, entry points, tests or website sections. Both full non-live Maven verification lanes passed against that candidate: Java 11 libraries with classpath/JPMS consumers, and Java 21 including CLI/daemon tests, Javadocs and standalone packaging. The three Spring combinations also passed independently: Boot 2.7.18 / Spring 5.3.39 on Java 11, Boot 3.0.13 / Spring 6.0.14 on Java 17, and Boot 3.5.16 / Spring 6.2.19 on Java 21.

The isolated website check and clean build passed. Internal-link verification found only the same five existing Journal links, outside the migration. CLI metadata regenerated from the standalone candidate matches the reviewed files. License cleanup ran after the builds; unrelated research and Journal staging was preserved. Local delivery logs are under `tmp/execution-views-delivery-58f023f0/`.
