# CLI daemon improvement plan

> **Sequence requirement:** This is improvement plan 02. Implementation must not begin until [01 - Instance-based configuration](../01_INSTANCE_CONFIGURATION_IMPROVEMENT_PLAN/README.md) is complete. The daemon relies on its immutable configuration snapshot and configured factory; it must not build a second configuration model in parallel.

- Status: Portable daemon implementation complete; package-manager publication deferred to issue #708
- Plan order: 02 of 02
- Hard dependency: [01 - Instance-based configuration improvement plan](../01_INSTANCE_CONFIGURATION_IMPROVEMENT_PLAN/README.md) must be completed first
- GitHub issue: [#488 Implement SJM as daemon process so CLI is faster and can use the batch module](https://github.com/bbottema/simple-java-mail/issues/488)
- Package-manager follow-up: [#708 Publish the SJM CLI through Homebrew and Chocolatey](https://github.com/bbottema/simple-java-mail/issues/708)
- Target release: 10.0.0
- Working branch: `codex/10.0.0`
- Baseline inspected: 18 August 2026
- Implemented and locally verified: 19 August 2026

This plan turns the one-command-per-JVM CLI into an optional per-user client and daemon. The daemon keeps the expensive CLI model and selected `Mailer` instances alive, so repeated commands avoid most startup work and can reuse SMTP connection pools. It also retains the existing one-shot route for scripts and recovery.

The Java compatibility decision is deliberately different from the rest of Simple Java Mail. `cli-module` moves to Java 17. Every library artifact remains Java 8-compatible. Java 17 is recommended instead of exactly Java 16 because it is the next LTS release, contains the Unix-domain socket API introduced in Java 16, and contains the Windows Server 2019 fix that was missing from the first JDK 16 implementation.

The production implementation now follows the approved portable direction below. The daemon core, Java baseline split, documentation, and cross-platform portable-archive CI jobs are present. Homebrew and Chocolatey publication tooling and lifecycle validation are parked under issue #708 and do not block the portable 10.0.0 release.

## Implementation result

- Java 8 remains the library baseline; `cli-module` is Java 17 bytecode and is tested on Java 17 and 21.
- One-shot remains the default. `-d`/`--daemon=acquire`, `require`, `off`, named instances, and all management commands are implemented.
- The daemon uses private authenticated local state, Unix-domain sockets with a strict `127.0.0.1` fallback, bounded request/result storage, and a bounded reusable Mailer registry.
- The official archive retains `batch-module`; controlled SMTP tests prove two compatible daemon sends share one SMTP session, while a no-batch classpath remains functional.
- Java 17 and Java 21 generate byte-identical `cli.data` and `therapi.data` caches.
- The Windows portable ZIP and Linux portable tar pass help, start, status, routed validation, and stop; the tar launcher is executable and both hosts select the intended local transports.
- Ubuntu 24.04/JDK 21 passes the daemon process suite and a rendered systemd user unit's install, enable, start, SIGTERM stop, disable, and removal lifecycle.
- Clean Linux containers on Temurin 17 and 21 pass the exact portable tar over native Unix-domain sockets and forced loopback TCP. A second Java 17 run passes as UID/GID 65532 with a read-only root filesystem, all capabilities dropped, `no-new-privileges`, and a private `0700` state directory. The foreground command also runs as PID 1 and drains under orchestrator SIGTERM, producing the expected signal exit status 143.
- The local benchmark measured a 387 ms warm-daemon median versus 1,804 ms one-shot median on Windows/JDK 21, a 4.66x improvement. Raw samples are produced by the opt-in benchmark test.
- The website, durable daemon guide, migration guide, maintainer guide, tested systemd unit, and hosted OS/JDK matrix are implemented. Homebrew and Chocolatey sources are isolated on the issue #708 branch until their real lifecycle can be validated.
- Machine-wide services, Scheduled Tasks, standalone LaunchAgents, and self-contained MSI/pkg/deb/rpm installers are outside this improvement. On-demand `-d`, the optional systemd unit, and foreground `daemon run` cover the portable 10.0.0 background-daemon use cases; Homebrew service integration belongs to issue #708.

## What issue #488 means now

The issue started as a CLI process that remained alive when `batch-module` initialized its connection pool. That shutdown problem has already been corrected: current send and connect commands wait for their futures and close the `Mailer` in `finally`.

The remaining feature is different:

- `org.simplejavamail.cli.SimpleJavaMail` starts one JVM, initializes the CLI model, performs one command, and exits;
- every send, connect, and validate path creates and closes a new `Mailer`;
- a connection pool cannot survive between CLI invocations;
- Appassembler's daemon goal can wrap a long-running process, but it cannot provide a request protocol or persistent `Mailer` ownership;
- the current standalone archive contains ordinary Windows and Unix launch scripts, not a service.

The solution is therefore a local client/server feature, not a POM switch.

## Is `batch-module` supported?

Yes. The official standalone CLI already includes `batch-module` as an optional runtime dependency, and both one-shot and daemon execution continue to support it.

- With `batch-module` present, a daemon-owned `Mailer` can keep its batch executor and SMTP connection pool alive between compatible commands. This is the route that enables actual SMTP connection reuse.
- Without `batch-module`, the daemon still works and still avoids repeated CLI-model and `Mailer` setup, but it has no pooled SMTP transport to retain.
- The daemon does not turn `batch-module` into a durable queue, and it does not add a new `batch` CLI command.
- Evicting a cached `Mailer` or stopping the daemon closes that `Mailer`, its batch registration, executor, and connection-pool resources.

Custom distributions may therefore continue to omit `batch-module`; the supported behavior and performance difference must be tested and documented for both classpaths.

## Why raising only the CLI baseline helps

Java 17 gives `cli-module` useful process and local-IPC primitives without forcing Java applications that use Simple Java Mail to upgrade:

- `UnixDomainSocketAddress`, `StandardProtocolFamily.UNIX`, `SocketChannel`, and `ServerSocketChannel` provide one local transport API on supported Linux, macOS, and Windows systems. The API was delivered in Java 16 by [JEP 380](https://openjdk.org/jeps/380).
- `ProcessHandle` provides PID, start-time, liveness, and exit observation for stale-state checks and daemon management.
- modern file APIs make atomic discovery-file replacement and explicit permission handling easier.
- the CLI metadata generator can be made a modern-JDK build rather than retaining a documented JDK 8-only exception.

Java 16 itself is not selected as the published minimum. It was a short-lived feature release and its first Windows Unix-domain socket implementation was disabled on Windows Server 2019; that gap was resolved in JDK 17. The portable CLI therefore targets Java 17 class files and is tested on Java 17 and the current release JDK.

Raising the CLI baseline does not remove every fallback. Unix-domain sockets can still be unavailable on an older Windows build, an unusual Unix port, or a restricted filesystem. The daemon prefers Unix-domain sockets and falls back to authenticated loopback TCP after an explicit capability check.

## Recommended target architecture

```text
sjm process, Java 17+
        |
        | lightweight bootstrap
        +--> help/version/one-shot command
        |
        +--> authenticated local request
                     |
                     v
          per-user sjm daemon, Java 17+
                     |
          +----------+-----------+
          |                      |
          v                      v
  request-scoped parser    bounded Mailer registry
  and path context         keyed by effective config
          |                      |
          +----------+-----------+
                     |
                     v
              send/connect/validate
              with synchronous result
```

### 1. The daemon is an ordinary foreground process

`sjm daemon run` owns the server loop and blocks until an authenticated stop request, operating-system shutdown, or fatal startup failure. It does not fork itself or pretend to be a Unix service. Systemd, Homebrew services, containers, and user-selected supervisors all manage the same foreground command.

### 2. The normal CLI is a thin client

Daemon routing happens before `CliSupport` initializes the generated command tree. Management commands and daemon discovery must not deserialize `cli.data`, scan builder APIs, build an Email, or initialize a `Mailer`.

Help and version output remain local. Send, connect, and validate retain their existing one-shot implementation and can instead be routed through the daemon according to an explicit execution mode.

### 3. Unix-domain sockets are preferred, not assumed

The daemon uses a filesystem-addressed Unix-domain socket on supported systems, including modern Windows. The same bounded protocol runs over loopback TCP when `StandardProtocolFamily.UNIX` is unavailable or the socket path cannot be made safe and short enough.

The fallback never binds a wildcard address. Both transports require the daemon's random authentication token because portable Java does not expose a uniform peer-credential contract.

### 4. The daemon owns reusable Mailers

A request does not directly create and close a `Mailer`. It acquires a lease from a bounded registry keyed by a canonical fingerprint of the complete effective Mailer configuration. Secret values participate in the fingerprint but never appear in keys, diagnostics, metrics, or logs.

One daemon can own many such entries at once. SMTP host A and SMTP host B, or two different credentials for the same host, become different effective Mailer profiles inside the same daemon. The user does not normally select a stored profile by name: the existing mailer options on `sjm send -d ...` determine which entry is reused or created.

The registry:

- constructs at most one live `Mailer` for one effective profile at a time;
- supports concurrent leases because `Mailer` is thread-safe;
- bounds entries and queued work;
- evicts idle entries only after their leases and futures complete;
- closes every `Mailer` during eviction, replacement, and shutdown;
- does not silently change core connection-pool defaults.

The default five-second connection expiry remains the library default. Daemon documentation explains how to configure a longer expiry or a core pool size when commands are expected to reuse live SMTP connections.

### 5. One-shot and daemon execution share one command executor

Parsing, builder invocation, waiting for futures, output production, and exit-code mapping move behind a request-scoped executor. One-shot mode supplies a close-after-command `Mailer` provider. Daemon mode supplies the leased registry provider. This prevents the two routes from drifting.

No request mutates `user.dir`, redirects global `System.out` or `System.err`, or reuses a mutable Picocli parse result. Relative files resolve against the invoking client's working directory through an explicit request context.

### 6. The first release is not a durable mail queue

The client receives success only after the existing send future completes. Every request has a UUID and the daemon retains recent in-progress and terminal results for duplicate detection. If the daemon dies after SMTP accepts a message but before the response reaches the client, the client reports an ambiguous result and does not resend automatically.

Exactly-once delivery across process or machine failure would require a durable spool and a different product contract. It is out of scope.

## Proposed Java and build contract

| Artifact or build path | Required contract |
| --- | --- |
| `core-module` and all non-CLI published modules | Java 8 source/API/bytecode compatibility |
| `cli-module` Maven artifact and portable archive | Java 17 minimum, class-file version 61 |
| Full reactor | Run on JDK 17 or newer; compile libraries with `--release 8` and CLI with `--release 17` |
| Java 8 compatibility lane | Build and test the library reactor without `cli-module` and without aggregators that require it |
| CLI compatibility lanes | Run tests on JDK 17 and the current release JDK |
| CLI metadata | Generated and consumed on supported modern JDKs; no JDK 8-only generation rule |

The root enforcer must continue to fail if a library module drifts above Java 8. `cli-module` receives a narrowly scoped compiler and enforcer override; changing a shared `${java.version}` property to 17 for the entire reactor is forbidden.

The modern full-reactor lanes use `--release 8` for library modules so compiling on JDK 17/21 cannot leak newer JDK APIs. The separate JDK 8 compatibility lane cannot use `--release`; it retains the equivalent source/target 1.8 compiler path. Both routes produce and audit the same Java 8 library bytecode.

### What the existing JDK 8-only generation rule means

`DEVELOPMENT.md` currently tells maintainers to generate the committed CLI metadata on JDK 8. That warning came from two older build failures: `cli.data` serialized reflective `Method` data through Kryo, and Therapi Javadoc scanning failed on synthetic/bridge methods as exposed by newer JDKs. It is a CLI build rule, not a runtime requirement for the library and not a daemon rule.

Step 4 does not delete that safeguard on assumption. It first proves metadata generation and cross-consumption on JDK 17 and the current JDK. If the current serializer and scanner cannot pass that matrix, the reflective cache is replaced with explicit descriptors before the warning is removed.

Because plan 01 is completed first, this plan then updates only its CLI-specific Java 8 statements and gates. It does not supersede the Java 8 requirements for any library module.

## Proposed command and routing contract

The exact parser fixtures and exit-code numbers are frozen in Step 3. The user-facing shape is:

```text
sjm daemon run
sjm daemon start
sjm daemon stop
sjm daemon status
sjm daemon restart

sjm send ...                         # one-shot; unchanged default
sjm send -d ...                      # acquire a daemon
sjm send --daemon ...                # same as -d
sjm send --daemon=acquire ...        # explicit full form
sjm send --daemon=require ...        # daemon must already be running
sjm send --daemon=off ...            # explicit one-shot
sjm send --no-daemon ...             # familiar alias for off
```

The same execution selector applies to connect and validate.

| Mode | Behavior |
| --- | --- |
| `off` | Execute once in the current process and close the Mailer; this is the initial default |
| `require` | Use a compatible running daemon or fail before executing the command |
| `acquire` | Use a compatible running daemon, or start it and wait for readiness before sending exactly one request |

Bare `--daemon` and `-d` mean `acquire`, matching the common expectation that opting into a daemon makes one available. The explicit value is named `acquire` rather than `auto`: it states that starting the daemon is allowed, while `require` states the symmetric stricter behavior. Routing options are accepted after the action, as in `sjm send -d`; Step 3 also freezes whether the equivalent pre-action placement is retained.

Daemon management, help, and version are always local. There is no fallback from daemon execution to a one-shot send after a request might have reached the daemon. A user can explicitly rerun with `--daemon=off` after assessing an ambiguous result.

One daemon instance is scoped by operating-system user, SJM major version, and an optional instance name. The unnamed `default` instance is used when no selector is supplied. `--daemon-instance=<name>` selects another process consistently for management and application commands:

```text
sjm daemon start --daemon-instance=work
sjm send -d --daemon-instance=work ...
sjm daemon status --daemon-instance=work
```

Named instances are for operational separation, such as different startup configuration, lifecycle, logs, or security boundaries. They are not required merely to use multiple SMTP accounts. A version mismatch is reported with a restart instruction; a new client never sends an application request to an incompatible daemon.

## How the CLI finds and talks to its daemon

This is internal plumbing, not a new user configuration format. The short-lived CLI needs to discover the correct local daemon, prove that it is talking to that daemon, send the original command, and receive its exit code/output. To do that it uses:

- a small authenticated local message format (the **protocol**) over a Unix-domain socket or loopback TCP; and
- a private per-user runtime record (the **state**) containing the endpoint, process identity, version, lock, and a random authentication secret.

The runtime record contains no email, message body, queued mail, SMTP password, or Mailer configuration. Users normally never edit it. Step 5 specifies it because stale or writable discovery data could otherwise make stop/restart contact the wrong process or let another local user submit mail.

The protocol is local-only, bounded, and independent from Java object serialization and Kryo. Step 5 freezes an exact length-prefixed format with:

- protocol magic and major/minor version;
- message type;
- request UUID;
- authentication token proof;
- caller working directory and expanded argument vector;
- response exit code, stdout, stderr, and structured failure category;
- strict limits for frame, field, argument, and output sizes.

The private discovery record contains transport type, socket path or loopback port, daemon PID, process start identity, product version, protocol version, and a 256-bit random token. It is replaced atomically only after the daemon is ready.

The state directory is per user and permission-restricted. The socket filename uses a short stable hash so platform path-length limits are not tied to a long home-directory path. The daemon acquires its instance lock before inspecting or deleting a stale socket. It never deletes a path copied blindly from a mutable discovery file.

## Configuration and prerequisite plan

The daemon depends on one immutable effective configuration per Mailer identity. The integration point is the configured factory delivered by [01 - Instance-based configuration](../01_INSTANCE_CONFIGURATION_IMPROVEMENT_PLAN/README.md):

```text
daemon startup sources
        |
        v
immutable SimpleJavaMailConfig
        |
        +--> request Mailer overrides
                    |
                    v
        canonical effective Mailer identity
                    |
                    v
             bounded Mailer registry
```

This entire plan starts only after plan 01 is complete. Step 10 consumes its configured factory and complete Mailer snapshot; it does not reopen those design choices or complete the two plans concurrently.

The initial daemon has no live file watcher. Environment variables and conventional configuration are captured at daemon start. Changing them requires `sjm daemon restart`. Existing CLI options still override the captured defaults for a request and participate in the Mailer identity.

Named profile files, remote secret stores, and executable OAuth token-provider commands are not introduced here. A caller can supply updated existing CLI values, which creates a different Mailer identity. A fixed OAuth2 token loaded only at daemon startup requires restart when it changes.

### Multiple configurations in one daemon

The normal route is one daemon with a bounded set of Mailers:

```text
sjm send -d --smtp-host smtp.work.example --username alice ...
        -> default daemon -> work Mailer entry

sjm send -d --smtp-host smtp.personal.example --username alice ...
        -> default daemon -> personal Mailer entry
```

The daemon combines its immutable startup defaults with each request's existing mailer options, computes a secret-safe identity from the complete effective result, and reuses only the matching `Mailer`. No public profile ID or new profile-file format is required. `--daemon-instance=<name>` selects a different daemon only when the user deliberately wants separate startup defaults or lifecycle/isolation.

## Distribution and installation terms

The release starts with one delivery route, while a second is tracked separately:

1. **Portable archives:** the existing zip/tar style with `sjm` and `sjm.bat`; users provide Java 17 or newer. This is the supported 10.0.0 release route.
2. **Package-manager packages:** issue #708 tracks a Homebrew formula for macOS/Linux and a Chocolatey package for Windows. These may be published for the same 10.0.0 artifacts after their lifecycle tests pass.

Package-manager source files are not bundled into the portable archive. The first release does not add a separate daemon package or an OS-specific installer.

## Platform strategy

| Platform | Install route | Per-user background route | Transport |
| --- | --- | --- | --- |
| Windows 10/11 and supported Server releases | Portable archive | On-demand hidden process through `-d` or `daemon start` | Unix-domain socket where supported, otherwise loopback TCP |
| Linux with systemd | Portable archive | On-demand `-d` or optional systemd user unit | Unix-domain socket |
| macOS | Portable archive | On-demand `-d` or foreground process under a user-selected supervisor | Unix-domain socket |
| BSD and other Unix-like systems | Portable archive | On-demand `-d` or foreground process under a tested local supervisor | Unix-domain socket when the JDK supports it, otherwise loopback TCP |
| Containers | Portable archive or derived container image | Foreground `sjm daemon run` under the orchestrator | Unix-domain socket or loopback inside the container |

[Issue #708](https://github.com/bbottema/simple-java-mail/issues/708) retains the approved package-manager direction: Homebrew can use formula-defined launchd and systemd services through `service do` and `brew services`, while Chocolatey can wrap the tested portable ZIP. Neither route is claimed as available until its lifecycle gates pass.

## Security and operational decisions

Approval of the plan approves these constraints:

1. Bind only a Unix-domain socket or loopback address; never expose the daemon remotely.
2. Authenticate every application and control request, including status where it would expose configuration or activity.
3. Prefer a per-user daemon and fail closed when private state permissions cannot be established.
4. Never log request argument values wholesale. SMTP passwords, proxy passwords, OAuth tokens, message bodies, addresses where inappropriate, and authentication tokens are redacted.
5. Use a dedicated daemon log configuration with bounded rolling files. Do not reuse the current trace-heavy interactive configuration.
6. Bound connections, workers, queued requests, frame sizes, output capture, registry entries, and recent-result retention.
7. Stop accepting requests before draining active work. Wait for send futures before closing Mailers and connection pools.
8. Do not kill a PID based only on a stale text file. Verify the lock, PID, start identity, endpoint, token, and protocol handshake.
9. Do not silently retry a send or fall back to one-shot execution after an ambiguous response.
10. Keep daemon state, protocol types, Java 17 APIs, and service integration inside `cli-module`; no Java 17 reference may leak into Java 8 artifacts.

## Test-driven working method

Every production step follows this order:

1. Add or move a focused test that fails for the missing contract.
2. Make the smallest production change that passes it.
3. Run the one-shot and daemon variants of the affected command.
4. Run the Java/class-file gate named by the step.
5. Run the relevant operating-system process test where platform code changed.
6. Record concrete evidence in the step file before marking it done.

Forked-process tests use isolated temporary state directories, fixed timeouts, captured logs, and guaranteed cleanup. They never discover or stop a developer's real daemon.

Status vocabulary: `Proposed`, `Planned`, `In progress`, `Blocked`, `Done`.

## Phases and steps

### Phase 1: Freeze the contract and split the build baseline

- [x] [1. Characterize the current CLI lifecycle and performance](phase-1-contract-and-build/01-characterize-current-cli-and-daemon-contract.md)
- [x] [2. Raise only cli-module to Java 17](phase-1-contract-and-build/02-split-cli-java-baseline.md)
- [x] [3. Freeze daemon commands, routing, and compatibility behavior](phase-1-contract-and-build/03-freeze-command-routing-and-compatibility.md)
- [x] [4. Build the thin bootstrap and modernize generated metadata](phase-1-contract-and-build/04-modernize-cli-bootstrap-and-generated-metadata.md)

### Phase 2: Build local IPC and process lifecycle

- [x] [5. Define how the CLI finds and talks to its daemon](phase-2-ipc-and-lifecycle/05-freeze-local-protocol-and-state-layout.md)
- [ ] [6. Implement Unix-domain sockets with a loopback fallback](phase-2-ipc-and-lifecycle/06-implement-unix-domain-sockets-with-fallback.md)
- [x] [7. Build the single-instance foreground daemon](phase-2-ipc-and-lifecycle/07-build-single-instance-daemon-runtime.md)
- [x] [8. Build the thin client and daemon management commands](phase-2-ipc-and-lifecycle/08-build-thin-client-and-management-commands.md)

### Phase 3: Make command execution persistent and safe

- [x] [9. Make parsing, files, and output request-scoped](phase-3-persistent-execution/09-make-command-execution-request-scoped.md)
- [x] [10. Integrate immutable configuration and Mailer identity](phase-3-persistent-execution/10-integrate-config-snapshots-and-profile-identity.md)
- [x] [11. Build the bounded reusable Mailer registry](phase-3-persistent-execution/11-build-bounded-mailer-registry.md)
- [x] [12. Add concurrency, backpressure, and duplicate-request safety](phase-3-persistent-execution/12-add-concurrency-backpressure-and-request-safety.md)
- [x] [13. Harden secrets, logging, replacement, and shutdown](phase-3-persistent-execution/13-harden-logging-secrets-and-shutdown.md)

### Phase 4: Integrate operating systems and packaging

- [ ] [14. Integrate Windows background and Chocolatey lifecycle](phase-4-platform-packaging/14-integrate-windows-background-and-service-lifecycle.md)
- [ ] [15. Integrate systemd, Homebrew, and other Unix supervisors](phase-4-platform-packaging/15-integrate-linux-macos-and-other-unix-supervisors.md)
- [ ] [16. Build portable archives and cross-platform CI](phase-4-platform-packaging/16-build-cross-platform-artifacts-and-ci.md)

### Phase 5: Documentation and release proof

- [x] [17. Write CLI daemon migration and operations documentation](phase-5-release/17-write-cli-daemon-migration-and-operations-docs.md)
- [ ] [18. Run security, performance, compatibility, and release gates](phase-5-release/18-run-security-performance-and-release-gates.md)

## Phase gates

- Phase 1 must finish before protocol or daemon runtime implementation begins.
- Phase 2 is complete only when the same protocol passes over Unix-domain sockets and forced loopback fallback, with isolated per-user state and no SMTP behavior.
- Phase 3 is complete only when one-shot and daemon execution share one executor, repeated sends reuse exactly one eligible Mailer, and ambiguous results cannot trigger automatic duplicate delivery.
- Phase 4 is complete only when the portable routes pass on Windows, Linux, and macOS, the systemd example is proven, and published package-manager routes pass their real lifecycle tests.
- Phase 5 is complete only when library bytecode remains Java 8, CLI bytecode is Java 17, portable and package-manager artifacts are inspected, and security and performance evidence is recorded.

## Out of scope

- A remotely reachable SMTP submission service or network API.
- A durable spool, offline queue, scheduled delivery system, or exactly-once guarantee across daemon crashes.
- Arbitrary multi-user access to one privileged machine daemon.
- Uploading attachment/body files through IPC to bypass operating-system file permissions.
- Live configuration-file watching or mutation of an existing Mailer.
- A new executable OAuth token-provider command or plugin mechanism.
- A platform-specific non-Java thin client, GraalVM image, or removal of the normal Java launcher.
- Replacing Picocli or redesigning all generated builder options.
- Enabling Appassembler's legacy JSW daemon wrapper.
- Shipping a Scheduled Task, standalone LaunchAgent, machine-wide service, or self-contained MSI/pkg/deb/rpm installer.
- Raising the Java baseline of any non-CLI artifact.
- Providing one universal installer for every Unix service manager.

## Completion definition

The improvement is complete when:

- the CLI artifact clearly requires Java 17 while every non-CLI artifact remains Java 8-compatible;
- help, version, one-shot send/connect/validate, and legacy scripts retain their documented behavior;
- daemon routing occurs before full CLI initialization;
- a per-user daemon communicates through an authenticated Unix-domain socket with a tested loopback fallback;
- repeated compatible requests reuse one bounded, safely retired `Mailer` and can reuse its SMTP pool;
- caller-relative files, argument files, output, exit codes, and failure semantics match one-shot execution;
- concurrent requests are bounded and graceful shutdown waits for accepted work before closing Mailers;
- Windows, Linux, macOS, and generic Unix operational routes are documented and tested in proportion to their support claim;
- no secret or message body leaks into discovery files, logs, diagnostics, test reports, or packaged examples;
- current portable archives and published package-manager packages pass clean installation, upgrade, explicit daemon use, and uninstall checks;
- issue #488 can be closed with measured startup and repeated-send evidence rather than only a running background process.
