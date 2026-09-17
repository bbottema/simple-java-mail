# Architecture decision records

These records explain API and architecture decisions that should survive individual features and refactors. Read the relevant accepted decision before changing the public API. Each record links its implementation and evidence; the [topic index below](#find-documentation-by-topic) also directs you to build procedures, feature checklists, and current concurrency details.

An accepted decision is not a claim that its implementation is complete. Each record distinguishes the chosen design from existing behavior and follow-up work. If the project changes direction, add a superseding decision and link the records rather than rewriting the original rationale.

| Record | Decision | Status | Implementation |
| --- | --- | --- | --- |
| [0001](0001-email-configuration-scopes-and-inheritance.md) | Put S/MIME, DKIM, and DSN settings at the message, transaction, or recipient scope where they make sense. | Accepted, 2026-09-16 | Recipient-level DSN and automatic ORCPT accepted for unreleased 10.0.0. |
| [0002](0002-email-defaults-and-overrides.md) | Reuse Email as the defaults/overrides template and resolve that policy through the selected Mailer's governance. | Accepted, 2026-09-16 | DKIM consolidation and corrected template documentation accepted for unreleased 10.0.0. |
| [0003](0003-immutable-configuration-snapshots.md) | Resolve ordered sources once into an immutable factory-owned configuration. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0004](0004-configuration-provenance-diagnostics.md) | Capture redacted value provenance while resolving configuration. | Accepted, retrospective | Implemented for unreleased 10.0.0; not live Mailer diagnostics. |
| [0005](0005-spring-integration-and-boot-compatibility.md) | Share manual/Boot construction and support Boot generations through one integration. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0006](0006-optional-modules.md) | Isolate optional features behind core contracts and lazy module loading. | Accepted, retrospective | Longstanding design with 10.0.0 concurrency fixes. |
| [0007](0007-provider-neutral-mime-boundary.md) | Separate finalized MIME content from provider-specific submission. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0008](0008-minimal-mime-structures-and-protection-order.md) | Select minimal MIME structures and protect content in a deliberate order. | Accepted, retrospective | Longstanding MIME design plus unreleased protection-pipeline work. |
| [0009](0009-nullability-and-cli-optionality.md) | Separate Java nullability, build instrumentation and CLI argument optionality. | Accepted, retrospective | Instrumenter retained but disabled by default; CLI optionality is explicit. |
| [0010](0010-authenticated-socks-bridge.md) | Adapt authenticated SOCKS through a local bridge with distinct listener/socket lifetimes. | Accepted, retrospective | Longstanding bridge; automatic ports added for 10.0.0. |
| [0011](0011-transport-neutral-submission-outcomes.md) | Preserve provider-neutral submission facts and uncertain final acceptance. | Accepted, retrospective | Implemented for unreleased 10.0.0; richer tracking remains distinct from delivery. |
| [0012](0012-terminal-send-observation.md) | Observe one whole-attempt terminal outcome through the Mailer. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0013](0013-exact-eml-submission.md) | Treat finalized EML bytes as authoritative while retaining normal send infrastructure. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0014](0014-send-time-rehearsal.md) | Prepare governed mail without SMTP submission and expose the resulting snapshot. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0015](0015-execution-views-and-transport-pooling.md) | Choose execution mode explicitly while keeping transport pooling independent. | Accepted, retrospective | Longstanding pooling; 10.0.0 execution views supersede earlier entry points. |
| [0016](0016-bounded-async-admission-and-shutdown.md) | Bound owned async admission and drain accepted work before shutdown. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0017](0017-deadlines-and-physical-cancellation.md) | Give deadlines and cancellation resource-aware, protocol-aware meaning. | Accepted, retrospective | Implemented for supported 10.0.0 integrations; limits explicit in record. |
| [0018](0018-cli-from-builder-contracts.md) | Generate CLI options and startup metadata from builder contracts and Javadocs. | Accepted, retrospective | Longstanding generation with compatibility and cache refinements. |
| [0019](0019-local-cli-daemon.md) | Reuse CLI/Mailer state through an opt-in authenticated local daemon. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0020](0020-dedicated-smtp-connection-diagnostics.md) | Inspect capabilities on a dedicated connection with safe partial reports. | Accepted, retrospective | Java, CLI and external-adapter fixture implemented for unreleased 10.0.0. |
| [0021](0021-mandatory-starttls-configuration-consistency.md) | Reject extra properties that contradict a mandatory STARTTLS strategy. | Accepted, retrospective | Implemented for unreleased 10.0.0. |
| [0022](0022-dsn-identifiers-belong-to-send-attempts.md) | Generate ENVID per send attempt and report it outside MIME content. | Accepted, 2026-09-17 | Accepted for unreleased 10.0.0. |
| [0023](0023-per-message-requiretls.md) | Keep onward REQUIRETLS on Email, separate from connection TLS, and reject sends that cannot honor it. | Accepted, 2026-09-17 | Implemented and accepted for unreleased 10.0.0 under #741. |

ADR 0001 determines **where a setting belongs**; ADR 0002 determines **how reusable Email policy is represented and applied**. Their existing follow-up work remains explicit. The retrospective records explain established choices without approving unrelated behavior changes.

## Find documentation by topic

This index replaces the retired mechanisms catalog. Architectural choices and constraints live in the records; build procedures, implementation checklists, and detailed state transitions live in the working guides below. One mechanism can embody several decisions, so shared decisions are linked rather than duplicated.

| Working guide | Use it for |
| --- | --- |
| [Developer Environment Setup](../../DEVELOPMENT.md) | JDK requirements, build/verification commands, generated CLI metadata, process-mode checks, and build constraints. |
| [API Expansion Workflow](../../API_EXPANSION_WORKFLOW.md) | Propagating features through models, builders, MIME, optional modules, governance, configuration, Spring, and tests. |
| [Concurrency and state-machine catalogue](../concurrency/README.md) | Current ownership, state transitions, locks, races, cleanup ordering, and regression evidence. |
| [Maintainer Workflow](../../MAINTAINER_WORKFLOW.md) | Issue handling, dependency maintenance, and releases. |

| Topic | Architectural decisions and supporting documentation |
| --- | --- |
| API expansion | [0001](0001-email-configuration-scopes-and-inheritance.md), [0002](0002-email-defaults-and-overrides.md), [0003](0003-immutable-configuration-snapshots.md), [0005](0005-spring-integration-and-boot-compatibility.md), [0018](0018-cli-from-builder-contracts.md); implementation coverage belongs to the [workflow](../../API_EXPANSION_WORKFLOW.md). |
| Immutable configuration snapshots | [0003](0003-immutable-configuration-snapshots.md). |
| Configuration provenance diagnostics | [0004](0004-configuration-provenance-diagnostics.md). |
| Spring Boot auto-configuration | [0005](0005-spring-integration-and-boot-compatibility.md). |
| Dynamic module loading | [0006](0006-optional-modules.md); provider discovery and MIME separation in [0007](0007-provider-neutral-mime-boundary.md). |
| CLI generation from builder Javadocs | [0018](0018-cli-from-builder-contracts.md), with explicit optionality in [0009](0009-nullability-and-cli-optionality.md) and the [generation procedure](../../DEVELOPMENT.md#generated-cli-metadata). |
| Optional local CLI daemon | [0019](0019-local-cli-daemon.md) and [process-mode checks](../../DEVELOPMENT.md#exercising-cli-process-modes). |
| Mandatory STARTTLS configuration consistency | [0021](0021-mandatory-starttls-configuration-consistency.md). |
| Per-message onward REQUIRETLS | [0023](0023-per-message-requiretls.md), applying Email scope and governance from [0001](0001-email-configuration-scopes-and-inheritance.md) and [0002](0002-email-defaults-and-overrides.md). |
| Dedicated SMTP connection diagnostics | [0020](0020-dedicated-smtp-connection-diagnostics.md). |
| Async send and batch connection pooling | [0015](0015-execution-views-and-transport-pooling.md) and [0016](0016-bounded-async-admission-and-shutdown.md); [executor admission](../concurrency/03-executor-admission.md) and [pool claims/leases](../concurrency/06-pool-claims-and-leases.md) describe current state machines. |
| Total deadlines and physical abort | [0017](0017-deadlines-and-physical-cancellation.md), with outcome and callback boundaries in [0011](0011-transport-neutral-submission-outcomes.md) and [0012](0012-terminal-send-observation.md); see the [concurrency collection](../concurrency/README.md) for transitions and races. |
| Transport-neutral submission outcomes | [0011](0011-transport-neutral-submission-outcomes.md), supported by [0007](0007-provider-neutral-mime-boundary.md). |
| Per-Mailer terminal send observation | [0012](0012-terminal-send-observation.md). |
| Exact EML submission | [0013](0013-exact-eml-submission.md). |
| DSN envelope identifiers | [0022](0022-dsn-identifiers-belong-to-send-attempts.md); broader target scopes remain in [0001](0001-email-configuration-scopes-and-inheritance.md). |
| Authenticated SOCKS proxy bridge | [0010](0010-authenticated-socks-bridge.md). |
| Smart MIME structure selection and protection | [0008](0008-minimal-mime-structures-and-protection-order.md), with the finalized-content boundary in [0007](0007-provider-neutral-mime-boundary.md). |
| Send-time validation and rehearsal | [0014](0014-send-time-rehearsal.md). |
| Runtime non-null instrumentation | [0009](0009-nullability-and-cli-optionality.md), including why current default builds disable it. |
| Related: config defaults/overrides and Spring property mapping | [0001](0001-email-configuration-scopes-and-inheritance.md)–[0005](0005-spring-integration-and-boot-compatibility.md). |
| Related: Outlook/EML conversion, resource naming and parse-side classification | [0006](0006-optional-modules.md), [0008](0008-minimal-mime-structures-and-protection-order.md) and [0013](0013-exact-eml-submission.md); the detailed naming history remains in the existing [MIME resource report](../../MIME_RESOURCE_NAMING_REPORT.md). |
| Related: transport strategy properties | [0007](0007-provider-neutral-mime-boundary.md) and [0021](0021-mandatory-starttls-configuration-consistency.md), including Session ownership and TLS-policy boundaries. |

## Evidence and status conventions

The extraction was recorded on 16 September 2026 against `codex/10.0.0` at `a4eda9e6557b1a2fb61a7ced60163b80075bc00b`, including the visible uncommitted ENVID work. It covers unreleased mechanisms as requested; being implemented on this branch does not mean being released.

Records link historical commits and live GitHub issues/comments, then identify the relevant current source and existing tests. A dated issue requirement or explanatory commit is evidence of recorded motivation. Code establishes behavior and supports architectural interpretation; it does not prove an undocumented original intention. Alternatives are identified as historical choices where supported, or as retrospective tradeoff analysis. The recording date is not an invented original decision date.

Local implementation and live issue text can differ. In particular, an open issue or stale progress paragraph does not erase a verified commit, while an accepted plan does not prove implementation. ENVID, ORCPT and per-recipient NOTIFY were subsequently accepted for unreleased 10.0.0; negotiated PIPELINING/CHUNKING are not promoted from roadmap to completed mechanisms. Deferred alternatives remain in the linked plans/issues instead of becoming newly accepted ADRs.

This pass added documentation and checked its references; it did not rerun the Java suites cited as existing evidence. Future changes in architectural direction should add a superseding record and link the affected decisions.
