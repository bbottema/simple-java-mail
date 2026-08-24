# Simple Java Mail CLI daemon

The daemon is optional. Ordinary `sjm send`, `connect`, and `validate` commands still run once in the current process and close their Mailer. The CLI requires Java 17 or newer; the non-CLI libraries require Java 11 or newer.

## Commands

```text
sjm daemon run                         # foreground, for supervisors and diagnosis
sjm daemon start                       # per-user background process
sjm daemon status
sjm daemon stop
sjm daemon restart

sjm send ...                           # one-shot, the default
sjm send -d ...                        # acquire a daemon
sjm send --daemon=acquire ...          # same, explicit
sjm send --daemon=require ...          # fail if it is not already running
sjm send --daemon=off ...              # explicit one-shot
sjm send --no-daemon ...               # alias for off
```

The selector works the same way for `connect` and `validate`. Bare `--daemon` is the same as `-d`. Help and version output are always produced locally. There is no automatic one-shot fallback after a daemon request may have been accepted.

The equivalent startup defaults are the JVM property `simplejavamail.cli.daemon` or environment variable `SIMPLEJAVAMAIL_CLI_DAEMON`, plus `simplejavamail.cli.daemon.instance` or `SIMPLEJAVAMAIL_CLI_DAEMON_INSTANCE`. An explicit command-line selector wins.

The unnamed instance is `default`. A name selects a separate process, state, startup configuration, and lifecycle:

```text
sjm daemon start --daemon-instance=work
sjm send -d --daemon-instance=work ...
sjm daemon status --daemon-instance=work
```

You do not need an instance per SMTP account. One daemon keeps a bounded registry and derives a private identity from the complete captured configuration and request Mailer options. Different hosts, credentials, trust, proxy, pool, or Session settings use separate Mailers. Equivalent repeated options can reuse the matching Mailer.

## Batch and connection reuse

The official standalone archive includes `batch-module`. Keeping a Mailer alive also keeps its batch registration, executor, and SMTP pool alive. A live transport is reused only while the configured pool retains it. The default five-second idle expiry is deliberately short, so intermittent commands normally need an explicit longer expiry or non-zero core size. The daemon never silently changes those settings.

A custom classpath without `batch-module` remains supported. It still avoids repeated daemon, parser, configuration, and Mailer construction. It does not have an SMTP connection pool to retain.

Eviction and shutdown wait for active leases and command futures, remove the Mailer from lookup, and close it. The registry and request queue are bounded. Status reports anonymous counts, never configuration values or profile fingerprints.

## Configuration and files

Classpath `simplejavamail.properties`, environment variables, and JVM system properties are captured when the daemon starts. Use `sjm daemon restart` after changing startup defaults. Explicit CLI Mailer options override that snapshot and participate in reuse identity.

Relative request files resolve from the client command's working directory, not the directory in which the daemon was started. This includes argument files, bodies, attachments, EML/MSG input, and certificates. The daemon can read only files granted to its operating-system user; the protocol does not upload file contents to bypass operating-system permissions.

Argument files are strict UTF-8, bounded, may be nested four levels, and support comments plus single or double quoting. Relative nested argument files also resolve from the client working directory. Prefix a literal value beginning with `@` with another at sign, such as `@@recipient`, to pass `@recipient`. The existing `picocli.useSimplifiedAtFiles=true` mode still treats each non-comment line as one argument.

A fixed OAuth2 access token supplied through existing configuration is fixed for that daemon generation. A request-supplied changed token creates a different Mailer entry. The CLI does not execute a token-provider command.

## Local security and state

The daemon is per operating-system user, SJM major version, and instance name. It prefers a short Unix-domain socket on Windows, Linux, and macOS. If the JDK or filesystem cannot provide one safely, it binds only `127.0.0.1` on an ephemeral port. Both transports use the same bounded, HMAC-authenticated protocol.

Default state roots are:

- Windows: `%LOCALAPPDATA%\SimpleJavaMail\daemon`
- Linux with `XDG_RUNTIME_DIR`: `$XDG_RUNTIME_DIR/simple-java-mail`
- macOS: `~/Library/Application Support/SimpleJavaMail/daemon`
- other Unix-like sessions: `~/.local/state/simple-java-mail/daemon`

The instance directory is owner-only. Discovery state contains the endpoint, PID/start identity, versions, random session ID, and authentication secret. It contains no Email, message body, queued mail, SMTP password, or Mailer configuration. Do not copy, publish, or edit it. Startup fails if private permissions cannot be established.

Daemon logs use bounded rolling files in the instance directory. Raw CLI arguments, converted option values, MIME messages, authentication material, and profile identities are not logged by the daemon configuration.

## Status and exit codes

`daemon status` prints stable `key=value` lines for the instance, lifecycle state, PID, uptime, product/protocol versions, transport, active and queued requests, Mailer count, active leases, close failures, recent request tombstones, and retained result bytes.

| Code | Meaning |
| ---: | --- |
| 0 | Success |
| 2 | CLI syntax or validation error |
| 3 | Command, builder, validation, or SMTP failure |
| 10 | Required daemon absent |
| 11 | Daemon startup failed |
| 12 | Authentication or private-state failure |
| 13 | Incompatible product/protocol version |
| 14 | Daemon overloaded or shutting down |
| 15 | Request outcome ambiguous after response loss |
| 16 | Management refused because process identity or ownership was not proven |

An ambiguous result does not mean SMTP rejected the message. It means the client cannot prove the terminal result. Inspect the recipient/server side before choosing to run a new command. A new CLI invocation has a new request UUID and is a new delivery decision.

## Installation and keeping the daemon running

`sjm daemon run` is the portable contract. It stays in the foreground and handles authenticated stop plus JVM/operating-system shutdown through one drain path.

The daemon is part of the CLI, not a separate application to install. After installing `sjm`, use `sjm send -d ...` to start or reuse it on demand. The same applies to `connect` and `validate`. Use `sjm daemon start` only when you explicitly want to start it before the first command.

The standalone archive includes one optional, tested Linux convenience at `daemon/systemd/sjm-daemon.service`. It is not enabled during installation. Windows users do not need a Scheduled Task or Windows Service: on-demand acquisition launches a hidden per-user process.

Homebrew and Chocolatey distribution is tracked separately in [issue #708](https://github.com/bbottema/simple-java-mail/issues/708). It is not required for the daemon: until those packages are published, use the portable ZIP or tar and rely on `-d` or the normal daemon-management commands.

### Containers

Run `sjm daemon run` as the container's foreground process and let the orchestrator own restart and termination. Do not use `daemon start` or add a second backgrounding layer. A normal orchestrator SIGTERM reaches the shared graceful-drain path; the Java process exits with the conventional signal status 143. Stop the container through the orchestrator rather than relying on a `docker exec ... daemon stop` response, because termination of daemon PID 1 also tears down that exec session.

CLI clients must run in the same container or deliberately share the private state and socket with the same operating-system identity. The daemon never exposes a remote listener merely to cross a container boundary.

### Linux systemd user unit

Copy `daemon/systemd/sjm-daemon.service` to `~/.config/systemd/user/`, replace `@SJM_EXECUTABLE@` with the absolute `sjm` launcher path, then verify and enable it explicitly:

```sh
systemd-analyze --user verify ~/.config/systemd/user/sjm-daemon.service
systemctl --user daemon-reload
systemctl --user enable --now sjm-daemon.service
systemctl --user status sjm-daemon.service
journalctl --user -u sjm-daemon.service
```

To remove it, run `systemctl --user disable --now sjm-daemon.service`, delete the copied unit, and run `systemctl --user daemon-reload`. The user manager normally stops at logout. `loginctl enable-linger "$USER"` changes that behavior and must be an explicit administrator-approved choice. Restart the unit after changing environment-based configuration.

## Shutdown and recovery

Stop first closes intake, then gives accepted commands up to 30 seconds to drain. If work still cannot terminate, its retained result becomes ambiguous, the executor is interrupted, cached Mailers are force-retired, and the CLI process exits rather than claiming successful delivery. Owned discovery/socket state and the instance lock are then removed. A PID is never killed merely because it appears in a file. `restart` performs an authenticated stop, observes termination, and then starts normally.

If state is stale after a crash, a new daemon must acquire the instance lock before replacing it or deleting its own derived socket. If status reports incompatible or unverifiable state, use the matching old CLI to stop that daemon or terminate it through the supervisor after independently verifying the process identity. Do not delete state and kill the recorded PID blindly.
