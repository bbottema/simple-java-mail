# Step 15: Integrate systemd, Homebrew, and other Unix supervisors

- Status: Portable and systemd routes done; Homebrew deferred to issue #708
- Depends on: Steps 7, 8, and 13
- Primary platforms: Linux, macOS, BSD and other supported Unix-like systems
- Primary areas: Homebrew, systemd user units, signals, generic foreground operation

## Goal

Run the same foreground daemon correctly under a tested Linux systemd user unit and Homebrew's cross-platform service support, while retaining a simple foreground contract for every other Unix-like system.

## Tests first

1. Install, enable, start, stop, restart, disable, and remove the systemd user unit under an unprivileged test user.
2. Test login/logout behavior, optional lingering, failure restart, stop timeout, environment/config paths, journal output, and private runtime-directory permissions.
3. Deliver SIGTERM, SIGHUP, terminal close, supervisor timeout, and abrupt SIGKILL. Freeze supported behavior for each signal; do not use SIGHUP for live config reload.
4. Run the portable archive's `daemon run`, authenticated stop, and forced TCP fallback on at least one non-systemd Linux or container environment.
5. Validate documented recipes only for specifically claimed additional supervisors.
6. Exercise short and long runtime paths, permissions, symlinks, read-only home directories, temporary-directory cleanup, and multiple Unix users.
7. Render and parse the project-owned Homebrew formula in CI.
8. Install, test, upgrade, and uninstall the formula on macOS and Homebrew on Linux. Exercise `brew services start`, list, restart, and stop against its `service do` definition.

## Homebrew package and service

Publish one project-owned formula for macOS and Homebrew on Linux. It installs the tested portable CLI, supplies the tested Java 21 LTS runtime for the Java 17-compatible CLI, includes a functional formula test, and exposes `sjm` on `PATH`.

The formula's `service do` block runs `sjm daemon run` in the foreground. `brew install` does not start the daemon. Users either rely on `sjm send -d` for on-demand acquisition or explicitly opt into login-time supervision with `brew services start sjm`. Homebrew owns the generated launchd or systemd integration, so the project does not also ship a hand-maintained LaunchAgent.

Start with a project tap so releases remain under project control. Submission to Homebrew/core is a separate later decision.

## Linux implementation

1. Ship one systemd user unit that runs `sjm daemon run` in the foreground with `Restart=on-failure`, a bounded stop timeout, and an owner-only umask.
2. Use the user's runtime directory when available and a validated private fallback otherwise.
3. Let systemd deliver normal termination to the foreground JVM. Do not add a PID file or `Type=forking` behavior.
4. Document `enable --now` separately from optional lingering, which changes whether the user manager survives logout.
5. Keep journald metadata free of raw arguments and secrets; application daemon logs follow Step 13.

## macOS implementation

The supported 10.0.0 route is the portable CLI with on-demand `-d` acquisition. Homebrew distribution and `brew services` integration are tracked under issue #708. A standalone LaunchAgent plist is not shipped or claimed separately.

## Other Unix-like systems and containers

The supported portable contract is `sjm daemon run`, local Unix-domain socket with TCP fallback, authenticated management, and clean SIGTERM shutdown. Provide concise supervisor examples only for systems actually tested.

Containers run `sjm daemon run` as the foreground process under the orchestrator. The plan does not add double-forking, PID files, or a container-specific background mode.

## Acceptance criteria

- [x] A systemd user unit passes install through removal under an ordinary user.
- [x] At least one non-systemd foreground route passes.
- [x] Generic Unix documentation distinguishes tested supervisors from the portable contract.
- [x] The draft Homebrew formula renders without unresolved values; it is parked under issue #708 rather than included in the portable release branch.
- [ ] The hosted macOS Ruby syntax check passes.
- [ ] The Homebrew formula passes install, functional test, explicit `brew services` lifecycle, upgrade, and uninstall on macOS and Linux.
- [ ] Homebrew and systemd stop reach the shared graceful shutdown path.
- [x] No supervisor expects the Java process to fork or maintain its own PID file.
- [x] No separate untested LaunchAgent is shipped.

## Implementation evidence

- On Ubuntu 24.04 under an ordinary WSL user, the rendered systemd unit passes `systemd-analyze`, link/install, enable/start, authenticated status, supervisor SIGTERM cleanup, success classification, disable, and removal, including a long custom state root.
- The portable route passes in clean Linux containers on Temurin 17 and 21 over both local transports. A locked-down Java 17 run also succeeds, and a foreground PID 1 drains under orchestrator SIGTERM with the expected signal exit status 143.
- The distribution ships the tested systemd user unit. Draft Homebrew source with an opt-in `service do` block is parked under issue #708; the portable installation itself does not start a daemon.
- Actual Homebrew publication and lifecycle runs on macOS and Linux remain open acceptance gates.

## Stop condition

The portable archive may be released independently. Do not claim Homebrew availability until issue #708 passes its lifecycle gates, and do not replace it with another untested macOS service definition.
