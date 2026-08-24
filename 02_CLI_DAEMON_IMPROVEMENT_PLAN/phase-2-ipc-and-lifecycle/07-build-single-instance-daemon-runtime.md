# Step 7: Build the single-instance foreground daemon

- Status: Done
- Depends on: Steps 5 and 6
- Primary module: `cli-module`
- Primary areas: daemon state machine, instance lock, readiness, control requests, graceful process shutdown

## Goal

Build `sjm daemon run` as a foreground, single-instance local server with deterministic startup, readiness, stale-state recovery, and shutdown. This step uses a synthetic request handler and does not yet execute email commands.

## Tests first

1. Spawn the daemon in an isolated state directory and observe `STARTING`, `READY`, `QUIESCING`, and terminated states through authenticated protocol calls.
2. Start two processes for the same instance concurrently and prove exactly one acquires the lock and publishes readiness.
3. Kill the process after lock acquisition, after bind, during discovery publication, and after readiness. Start a replacement and verify safe recovery at each point.
4. Replace stale state with a live unrelated PID, a reused PID with a different start identity, and an inaccessible process. Prove no process is killed.
5. Send authenticated and unauthenticated status and stop requests.
6. Deliver JVM shutdown, console interrupt where supported, and supervisor-style termination while synthetic requests are active.
7. Hold a partial client frame across shutdown and verify the daemon stops accepting new work and terminates within the documented bound.
8. Fill logs or make the log directory unavailable and verify startup either chooses the documented safe fallback or fails before readiness.
9. Run the lifecycle repeatedly to expose leaked locks, threads, selectors, channels, files, and socket endpoints.
10. Assert `daemon run` remains attached to the invoking process and does not fork or report ready before the endpoint accepts authenticated health requests.

## Implementation

1. Add an internal daemon state machine with one-way transitions and a single owner of cleanup.
2. Acquire the private instance lock before opening an endpoint or interpreting stale state.
3. Generate a new daemon session identifier and authentication secret for every successful start.
4. Bind the selected transport, start bounded accept/worker infrastructure, then atomically publish discovery and ready state.
5. Handle health, status, and stop messages before application command execution exists.
6. Use `ProcessHandle.current()` PID and start information where available to make discovery identity stronger than PID alone.
7. Register one JVM shutdown hook that requests quiescence and delegates to the same idempotent cleanup path.
8. Close the listener before draining accepted synthetic work. Remove discovery and socket state only while still owning the instance lock.
9. Record startup failure to stderr and, when safe, a bounded non-secret last-start diagnostic.
10. Keep the main run method foreground and compatible with service managers.

## Lifecycle contract

```text
NEW -> LOCKED -> BOUND -> READY -> QUIESCING -> STOPPED
                  |         |
                  +-------> FAILED
```

Only `READY` accepts new application requests. Status can describe `STARTING` or `QUIESCING` when an authenticated endpoint exists. Stop is idempotent. Repeated cleanup is harmless.

## Acceptance criteria

- [x] Concurrent starts produce one ready daemon and one clear already-running result.
- [x] Ready is published only after an authenticated health request can succeed.
- [x] Crash recovery is safe at every startup phase.
- [x] PID reuse or tampered state cannot stop an unrelated process.
- [x] Stop and process termination use the same idempotent quiesce/cleanup path.
- [x] No listener, worker, lock, discovery file, or socket path survives a clean stop.
- [x] `daemon run` never backgrounds or forks itself.
- [x] No Email, Mailer, ConfigLoader, or full CLI command execution occurs yet.

## Completion evidence

- Forked-process tests cover authenticated readiness, concurrent starts, stale discovery, PID/start-time identity, graceful stop, restart-after-exit, and cleanup.
- The foreground server owns the instance lock, endpoint, discovery publication, worker pools, registry, ledger, and shutdown state machine.
- A clean stop removes discovery and socket state while holding ownership; `daemon run` never forks or backgrounds itself.

## Stop condition

If lifecycle cleanup requires force-killing the daemon under normal operation, stop and fix the ownership/state machine before command execution is added.
