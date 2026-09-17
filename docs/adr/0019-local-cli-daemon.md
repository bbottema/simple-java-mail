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

Use bounded, authenticated local IPC, preferring Unix-domain sockets and allowing an explicit `127.0.0.1` fallback only for approved capability/path failures. Both transports use the same HMAC-authenticated framing, session/request identities, freshness checks and limits. Discovery state contains protected process/endpoint metadata and a random secret, not queued messages or SMTP configuration. Insecure state or authentication failure must not trigger a weaker transport fallback.

Run both one-shot and daemon commands through the same request-scoped `CliSupport.execute(...)`. Each request gets fresh Picocli state, explicit working-directory context, a UUID and bounded output. Do not mutate process-wide `user.dir`, `System.out` or `System.err` to emulate an individual command's environment. Commands use synchronous Mailer operations; daemon-owned workers supply request concurrency.

Lease Mailers from a bounded registry keyed by the captured immutable configuration and converted Mailer options. Secret and security-file values affect compatibility, but retained keys are daemon-keyed HMACs rather than plaintext configurations or reusable unkeyed credential hashes. Unknown mutable inputs receive unique profiles. Compatible commands may share a Mailer; changing SMTP, credential, proxy, trust or pool inputs prevents reuse. Remove retired entries before closing them exactly once. Retain SMTP connections only through the optional batch module.

Bound connections, workers, queued requests, Mailer entries and retained result bytes/time. Register each request identity before executing it. While its ledger entry is retained, the same UUID and content retrieves or attaches to the original result; changed content under that UUID is rejected. Under output pressure, replace old result bodies with lightweight ambiguous-result tombstones rather than executing again. Retain tombstones across the authentication freshness window.

Keep the ledger memory-only. Once an execution response is lost, do not automatically create a new send or fall back to one-shot execution. Report uncertainty so a new CLI invocation is an explicit user decision. A crash can erase both the result and request history; there is no durable-spool or exactly-once-delivery guarantee.

## Recorded reasoning and evolution

| Evidence | What it establishes |
| --- | --- |
| [#488](https://github.com/bbottema/simple-java-mail/issues/488), [original maintainer response](https://github.com/bbottema/simple-java-mail/issues/488#issuecomment-1863920923) | The issue began with a batch-enabled CLI that would not exit. The response identifies the architectural limitation: separate processes cannot reuse their connection pools, suggesting a daemon as a separate enhancement. |
| [d240b3aa](https://github.com/bbottema/simple-java-mail/commit/d240b3aac20fb594b1885cc94916d405974aec6c) | Fixes ordinary CLI resource closure; that fix does not implement persistent execution. |
| [Accepted daemon plan](../../02_CLI_DAEMON_IMPROVEMENT_PLAN/README.md), [c697bd2e](https://github.com/bbottema/simple-java-mail/commit/c697bd2ed07f02349cedf10709fcd23eb5e0c97f) | Implements the separate client/daemon design, explicitly dependent on immutable configuration rather than a parallel global configuration system. |
| [#488 completion comment](https://github.com/bbottema/simple-java-mail/issues/488#issuecomment-5391342420) | Confirms optional daemon use, named lifecycle operations, multiple isolated Mailer profiles, optional pooling and separate package-manager publication. |
| [Transport step](../../02_CLI_DAEMON_IMPROVEMENT_PLAN/phase-2-ipc-and-lifecycle/06-implement-unix-domain-sockets-with-fallback.md) | Explains capability-based socket selection, common authentication and refusing fallback around insecure state. Native pipe dependencies remain outside the chosen portable approach. |
| [Request-safety step](../../02_CLI_DAEMON_IMPROVEMENT_PLAN/phase-3-persistent-execution/12-add-concurrency-backpressure-and-request-safety.md) | Explicitly forbids retries that could duplicate SMTP-accepted mail and distinguishes reconnect observation from a new send. Durable retry needs a separately designed spool and identity contract. |

Java 17 was selected for the CLI because it supplies the local IPC/process APIs and an LTS baseline with the relevant Windows Unix-domain-socket support. The later library baseline is Java 11. Older #488 comments describing Java 8 library compatibility or broader service packaging describe an earlier stage; the current plan's baseline update and portable release scope supersede them.

## Alternatives and consequences

Keeping only one-shot commands remains supported but cannot amortize startup or retain pools. Making every invocation use a daemon would change script lifecycle expectations, hence explicit opt-in. Reusing one Mailer for all commands would mix incompatible transport/security policies; one daemon per profile would avoid that mixing but multiply processes. The bounded in-process registry is the accepted compromise. These profile alternatives are architectural comparisons; the preserved sources do not establish that each received a separate formal rejection.

The protocol is intentionally separate from the internal generated CLI cache: local requests do not deserialize arbitrary Java/Kryo graphs. Authentication is necessary even on local transports because portable Java has no uniform peer-credential contract. It protects the per-user service boundary; it does not turn the daemon into a multi-user remote SMTP service.

The request ledger's guarantee is bounded by retention and daemon lifetime. The implementation prunes old terminal identities; do not interpret plan shorthand about one execution per session as an unlimited UUID archive. An old authenticated frame becomes stale, while a fresh invocation has a new UUID. The current client does not implement a transparent resend/reconnect loop after an execution response is lost.

Shutdown stops admission and drains within its daemon policy before retiring retained Mailers. Incomplete results can remain ambiguous, and bounded shutdown must not be described as proof of clean SMTP completion. The portable implementation does not establish that every OS/JDK release gate has been exercised; the transport plan still distinguishes local verification from hosted macOS validation.

## Implementation anchors

- [DaemonBootstrap](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonBootstrap.java), [DaemonClient](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonClient.java), [DaemonProtocol](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonProtocol.java), [DaemonServer](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonServer.java)
- [CliMailerProfile](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliMailerProfile.java), [DaemonMailerRegistry](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/DaemonMailerRegistry.java), [RequestLedger](../../modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/daemon/RequestLedger.java)
- [ADR 0018: Builder-derived CLI](0018-cli-from-builder-contracts.md), [ADR 0015: Shared Mailer resources](0015-execution-views-and-transport-pooling.md)
