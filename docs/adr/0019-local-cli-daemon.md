# ADR 0019: Make persistent CLI execution an optional authenticated local daemon

- Status: Accepted, recorded retrospectively
- Decision recorded: 2026-09-16; not the original decision date
- Applies to: `codex/10.0.0`
- Implementation status: Portable daemon implemented for unreleased 10.0.0; package-manager publication remains separate

## Context

The original CLI pool problem was a process that remained alive after sending. Closing the Mailer fixed that one-shot lifecycle problem, but a fresh JVM per command still rebuilt CLI state and could not retain SMTP pools between invocations. A launch-script or service-wrapper change cannot supply persistent Mailer ownership, request isolation or a client/server protocol.

A persistent process also changes the risk and resource model. Concurrent invocations may use different credentials, trust settings, working directories and output streams. Retaining all configurations indefinitely would leak resources. Retrying after losing a daemon response could duplicate a message that SMTP already accepted.

## Decision

Preserve one-shot execution as the default. Make daemon use explicit through acquire/require/off modes and named per-user instances. Route discovery and lifecycle commands through a thin bootstrap before generated CLI metadata is loaded. The daemon is an ordinary foreground process that can also be started on demand or managed by a supervisor; it does not require machine-wide service installation.

The accepted 22 October 2026 [help-recognition clarification](0018-cli-from-builder-contracts.md#recognize-help-options-without-changing-ordinary-values-accepted-22-october-2026)
keeps literal values ending in `--help` on the selected execution route. Only an option-shaped candidate consults the generated
model before routing so an unknown option-shaped value cannot bypass `require` mode. Ordinary routed commands, standard
help/version selection, discovery and lifecycle operations retain their thin bootstrap; actual help never submits a send.

Use bounded, authenticated local IPC, preferring Unix-domain sockets and allowing an explicit `127.0.0.1` fallback only for approved capability/path failures. Both transports use the same HMAC-authenticated framing, session/request identities, freshness checks and limits. Discovery state contains protected process/endpoint metadata and a random secret, not queued messages or SMTP configuration. Insecure state or authentication failure must not trigger a weaker transport fallback.

Run both one-shot and daemon commands through the same request-scoped `CliSupport.execute(...)`. Each request gets fresh Picocli state, explicit working-directory context, a UUID and bounded output. Do not mutate process-wide `user.dir`, `System.out` or `System.err` to emulate an individual command's environment. Commands use synchronous Mailer operations; daemon-owned workers supply request concurrency.

Lease Mailers from a bounded registry keyed by the captured immutable configuration and converted Mailer options. Secret and security-file values affect compatibility, but retained keys are daemon-keyed HMACs rather than plaintext configurations or reusable unkeyed credential hashes. Unknown mutable inputs receive unique profiles. Compatible commands may share a Mailer; changing SMTP, credential, proxy, trust or pool inputs prevents reuse. Remove retired entries before closing them exactly once. Retain SMTP connections only through the optional batch module.

Bound connections, workers, queued requests, Mailer entries and retained result bytes/time. Register each request identity before executing it. While its ledger entry is retained, the same UUID and content retrieves or attaches to the original result; changed content under that UUID is rejected. Under output pressure, replace old result bodies with lightweight ambiguous-result tombstones rather than executing again. Retain tombstones across the authentication freshness window.

Keep the ledger memory-only. Once an execution response is lost, do not automatically create a new send or fall back to one-shot execution. Report uncertainty so a new CLI invocation is an explicit user decision. A crash can erase both the result and request history; there is no durable-spool or exactly-once-delivery guarantee.

### Selector and message-value ownership (accepted 28 October 2026)

Daemon routing preserves the command model's ownership of message values, including values shaped like mode or instance selectors.
The accepted [ADR 0018 clarification](0018-cli-from-builder-contracts.md#daemon-selector-spellings-inside-values-accepted-28-october-2026)
permits model inspection for ambiguous selector-shaped input without builder execution, while retaining lightweight ordinary routing,
management, discovery and lifecycle operations. Existing request isolation, limits, help ordering and actual selector behavior remain.
[#896](https://github.com/bbottema/simple-java-mail/issues/896) tracks this correction for unreleased 10.0.0.

### Complete startup configuration (accepted 28 October 2026)

Starting an absent daemon preserves all supported SJM JVM settings supplied by the starting process, including named Mailer definitions,
their supported fields and locked counterparts. The normal loader owns property-name recognition; process launch must not maintain a
smaller independently selected catalog. Retain ordinary source precedence, validation and locks, and carry settings through the existing
child environment without putting secrets on its command line or configuration into discovery state.

The daemon captures defaults once at startup under [ADR 0003](0003-immutable-configuration-snapshots.md). Later clients' JVM properties do
not reconfigure a running instance, and acquire reuses that instance. Restart captures the complete desired configuration supplied by the
restarting invocation; it does not recover the old configuration from daemon state. Explicit request Mailer options continue through the
normal conflict policy and lock checks. [ADR 0029](0029-smtp-submission-failover.md) owns named definitions and their resolved route.

This corrects startup transfer under [#892](https://github.com/bbottema/simple-java-mail/issues/892) for unreleased 10.0.0. It does not add
live reload, per-request JVM configuration transfer or a new configuration protocol. The correction is implemented; the issue tracks
development-branch delivery.

### Expand argument files once (accepted 27 October 2026)

Expand `@argument` files once at the caller boundary before routing. Preserve the existing bounded expander's strict UTF-8 reading,
file/argument limits, nesting and cycle checks, quoting, simplified mode and caller-directory resolution, including nested files.
An escaped `@@literal` becomes the literal value `@literal`; a missing or unreadable file remains literal. Neither result becomes another
file lookup in the execution process, even when the daemon's directory contains a readable file with that name.

Use Picocli's `setExpandAtFiles(false)` for already-expanded requests. Distinguish raw-argument entry points from expanded execution internally:
direct raw calls still pass through the same bounded expansion boundary, while one-shot routing and authenticated daemon execution consume
their completed argument list without another expansion. Keep request working directories explicit; do not change process-wide state.

This correction preserves [ADR 0018](0018-cli-from-builder-contracts.md)'s option/value, help-order and repeated-call contracts. It adds no
application API, configuration switch or escaping syntax. The maintainer accepted it under
[#876](https://github.com/bbottema/simple-java-mail/issues/876) for unreleased 10.0.0. It is implemented; the issue tracks delivery.

### Inline argument-file comments (accepted 28 October 2026)

An unquoted comment completes any argument token already started, including a deliberately quoted empty value. The line terminator
must not join that token to the next line's first argument, regardless of LF, CR or CRLF spelling. Preserve quoted hash characters,
existing quoting and escapes, simplified mode, limits and one-time expansion through local and daemon execution. This clarification
is accepted under [#888](https://github.com/bbottema/simple-java-mail/issues/888) for unreleased 10.0.0.

## Recorded reasoning and evolution

| Evidence | What it establishes |
| --- | --- |
| [#488](https://github.com/bbottema/simple-java-mail/issues/488), [original maintainer response](https://github.com/bbottema/simple-java-mail/issues/488#issuecomment-1863920923) | The issue began with a batch-enabled CLI that would not exit. The response identifies the architectural limitation: separate processes cannot reuse their connection pools, suggesting a daemon as a separate enhancement. |
| [d240b3aa](https://github.com/bbottema/simple-java-mail/commit/d240b3aac20fb594b1885cc94916d405974aec6c) | Fixes ordinary CLI resource closure; that fix does not implement persistent execution. |
| [Accepted daemon plan](https://github.com/bbottema/simple-java-mail/issues/488), [c697bd2e](https://github.com/bbottema/simple-java-mail/commit/c697bd2ed07f02349cedf10709fcd23eb5e0c97f) | Implements the separate client/daemon design, explicitly dependent on immutable configuration rather than a parallel global configuration system. |
| [#488 completion comment](https://github.com/bbottema/simple-java-mail/issues/488#issuecomment-5391342420) | Confirms optional daemon use, named lifecycle operations, multiple isolated Mailer profiles, optional pooling and separate package-manager publication. |
| [Transport step](https://github.com/bbottema/simple-java-mail/blob/2653e804838e14e4b10291bdb9863b2d9a1ff67c/02_CLI_DAEMON_IMPROVEMENT_PLAN/phase-2-ipc-and-lifecycle/06-implement-unix-domain-sockets-with-fallback.md) | Explains capability-based socket selection, common authentication and refusing fallback around insecure state. Native pipe dependencies remain outside the chosen portable approach. |
| [Request-safety step](https://github.com/bbottema/simple-java-mail/blob/2653e804838e14e4b10291bdb9863b2d9a1ff67c/02_CLI_DAEMON_IMPROVEMENT_PLAN/phase-3-persistent-execution/12-add-concurrency-backpressure-and-request-safety.md) | Explicitly forbids retries that could duplicate SMTP-accepted mail and distinguishes reconnect observation from a new send. Durable retry needs a separately designed spool and identity contract. |

Java 17 was selected for the CLI because it supplies the local IPC/process APIs and an LTS baseline with the relevant Windows Unix-domain-socket support. The later library baseline is Java 11. Older #488 comments describing Java 8 library compatibility or broader service packaging describe an earlier stage; the [development guide](../DEVELOPMENT.md#java-version) defines the current baseline, while [#708](https://github.com/bbottema/simple-java-mail/issues/708) retains the deferred package-publication scope.

## Alternatives and consequences

Keeping only one-shot commands remains supported but cannot amortize startup or retain pools. Making every invocation use a daemon would change script lifecycle expectations, hence explicit opt-in. Reusing one Mailer for all commands would mix incompatible transport/security policies; one daemon per profile would avoid that mixing but multiply processes. The bounded in-process registry is the accepted compromise. These profile alternatives are architectural comparisons; the preserved sources do not establish that each received a separate formal rejection.

The protocol is intentionally separate from the internal generated CLI cache: local requests do not deserialize arbitrary Java/Kryo graphs. Authentication is necessary even on local transports because portable Java has no uniform peer-credential contract. It protects the per-user service boundary; it does not turn the daemon into a multi-user remote SMTP service.

The request ledger's guarantee is bounded by retention and daemon lifetime. The implementation prunes old terminal identities; do not interpret plan shorthand about one execution per session as an unlimited UUID archive. An old authenticated frame becomes stale, while a fresh invocation has a new UUID. The current client does not implement a transparent resend/reconnect loop after an execution response is lost.

Shutdown stops admission and drains within its daemon policy before retiring retained Mailers. Incomplete results can remain ambiguous, and bounded shutdown must not be described as proof of clean SMTP completion. The portable implementation does not establish that every OS/JDK release gate has been exercised; the historical transport evidence distinguishes local verification from hosted macOS validation; use the current CI results for release acceptance.

## Implementation anchors

- [DaemonBootstrap](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonBootstrap.java), [DaemonClient](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonClient.java), [DaemonProtocol](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonProtocol.java), [DaemonServer](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonServer.java)
- [CliMailerProfile](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliMailerProfile.java), [DaemonMailerRegistry](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonMailerRegistry.java), [RequestLedger](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/RequestLedger.java)
- [ADR 0018: Builder-derived CLI](0018-cli-from-builder-contracts.md), [ADR 0015: Shared Mailer resources](0015-execution-views-and-transport-pooling.md)
