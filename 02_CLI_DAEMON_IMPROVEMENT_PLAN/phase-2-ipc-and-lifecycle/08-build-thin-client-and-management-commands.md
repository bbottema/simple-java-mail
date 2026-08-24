# Step 8: Build the thin client and daemon management commands

- Status: Done
- Depends on: Steps 4 and 7
- Primary module: `cli-module`
- Primary areas: discovery, authenticated control client, background launch, readiness waiting, exit-code mapping

## Goal

Connect the thin bootstrap to daemon discovery and management so users can run, start, inspect, stop, and restart a per-user daemon without loading the full CLI model.

## Tests first

1. Exercise status against absent, starting, ready, quiescing, stale, incompatible, unauthenticated, and tampered instances.
2. Start a daemon in an isolated state root, wait for authenticated readiness, and prove the start command exits only after ready or a bounded failure.
3. Start twice and prove the second command reports the existing compatible instance without spawning another process.
4. Stop and restart a ready daemon and verify PID/session identity changes, state cleanup, and stable exit codes.
5. Make the child exit before readiness, hang during startup, emit large output, or fail log initialization and verify bounded diagnostics and no orphan.
6. Prove the client does not inherit pipes that keep the starting process or terminal open.
7. Prove an incompatible or unverifiable process is never killed by restart.
8. Assert all management routes avoid `CliSupport`, CLI metadata, Jakarta Mail, and Mailer initialization.
9. Exercise installation paths and working directories containing spaces and Unicode.
10. Run the management suite over Unix-domain sockets and forced TCP fallback.

## Implementation

1. Add a client discovery component that validates state ownership, parses bounded metadata, opens the advertised local transport, and completes an authenticated handshake.
2. Add local rendering for status that reports instance, PID, uptime, version, protocol, transport, lifecycle state, active/queued request counts, and Mailer counts when later available. It never prints the authentication secret or configuration values.
3. Implement stop through the authenticated protocol. Use `ProcessHandle` only to observe termination and strengthen identity checks, not as the normal stop mechanism.
4. Introduce a `DaemonProcessLauncher` boundary. The portable implementation starts the same distribution with `daemon run`, redirects stdout/stderr to bounded startup/daemon logs, and detaches client-owned streams.
5. On Windows portable distributions, prefer the matching `javaw.exe` for background start when available. On Unix-like systems, use a child process with all standard streams redirected; service-manager integrations replace this route when installed.
6. Wait for ready using bounded polling of the authenticated endpoint and child exit observation. Do not treat discovery-file existence alone as readiness.
7. Implement restart as authenticated stop, verified termination, then ordinary start. Do not delete live state to force a restart.
8. Route synthetic daemon requests through `require` and `acquire` modes so Phase 2 proves discovery and transport before email execution.
9. Apply `--daemon-instance=<name>` to discovery, start, status, stop, and restart, and prove one instance cannot operate on another instance's process or files.

## User-visible behavior

- `daemon start` is for the per-user instance and requires no administrator rights.
- `daemon run` remains the diagnostic and supervisor entry point.
- Platform autostart/service installation is added in Phase 4, not hidden inside start.
- `daemon status` against no instance is concise and scriptable.
- `-d`, bare `--daemon`, and `--daemon=acquire` start or reuse the selected daemon; `--daemon=require` never starts it.
- Omitting `--daemon-instance` selects the per-user `default` instance.
- Startup timeout does not imply the child is killed unless its identity and ownership are proven.

## Acceptance criteria

- [x] Start waits for authenticated readiness and reports bounded startup failures.
- [x] Stop is protocol-driven and restart cannot kill an unrelated process.
- [x] Status is stable, secret-safe, and available without full CLI initialization.
- [x] Management commands pass over both transports.
- [x] Background children do not inherit client pipes or console output accidentally.
- [x] Existing-instance, stale-state, incompatible-version, and timeout behavior match Step 3.
- [x] No SMTP command is sent to the daemon yet.
- [x] Thin-client startup evidence is recorded against Step 4.

## Completion evidence

- `daemon start`, `status`, `stop`, and `restart` use authenticated discovery and bounded readiness/termination waits over both transports.
- The launcher detaches standard streams, uses `javaw` on Windows, passes configuration through the child environment rather than command-line secrets, and resolves concurrent starts safely.
- A class-loading probe proves routed and management clients do not load Email/Mailer/Picocli model classes; the packaged ZIP lifecycle smoke passes end to end.

## Stop condition

If a portable background launch cannot detach reliably on one platform, keep `daemon run` and the platform supervisor route supported there. Do not introduce self-forking Java code or claim reliable autostart without a real lifecycle test.
