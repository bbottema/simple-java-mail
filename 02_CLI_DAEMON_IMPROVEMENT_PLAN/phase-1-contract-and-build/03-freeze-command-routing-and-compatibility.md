# Step 3: Freeze daemon commands, routing, and compatibility behavior

- Status: Done
- Depends on: Steps 1 and 2
- Primary areas: CLI grammar, exit codes, execution modes, daemon scope, migration surface
- No daemon transport or runtime integration is allowed before this step is accepted

## Goal

Turn the architectural sketch into an exact CLI contract so implementation does not invent behavior while handling process failures or operating-system differences.

## Command contract to freeze

1. Exact placement and spelling of `daemon run`, `start`, `stop`, `status`, and `restart`.
2. Existing `sjm send`, `sjm connect`, and `sjm validate` remain valid and default to one-shot execution (`off`).
3. `-d` and bare `--daemon` are shorthand for `--daemon=acquire`. At minimum the requested post-action form, such as `sjm send -d`, is supported.
4. The full accepted values are `--daemon=off|acquire|require`; `--no-daemon` is an alias for `off`. There is no `auto` value.
5. `require` never starts a daemon and fails before command execution when no compatible selected daemon is ready.
6. `acquire` uses a compatible selected daemon or starts it and waits for authenticated readiness. It never performs a one-shot fallback after a request could have reached the daemon.
7. `--daemon-instance=<name>` selects the unnamed/default or a named daemon consistently for application and management commands.
8. Freeze any configuration-key and environment aliases without allowing them to make daemon use the 10.0.0 default; an explicit command-line selector wins.
9. Help, version, and management commands always execute locally.
10. Send, connect, and validate support all three execution modes and return the same command-level result in one-shot and daemon routes.
11. `daemon run` remains foreground and service-manager friendly. It does not fork.
12. Instance scope is operating-system user plus SJM major version plus validated instance name, with `default` used when omitted.
13. A protocol or product-version mismatch produces a specific restart/upgrade diagnostic rather than reusing or killing the other process.

The explicit verbs are deliberately symmetric: `acquire` may obtain/start a daemon, while `require` accepts only one already running. Bare `--daemon` remains the familiar opt-in form for users who do not care about that terminology.

## Exit-code matrix

Freeze distinct stable categories for:

- successful command;
- ordinary CLI parse or validation error;
- command execution or SMTP failure;
- daemon absent in `require` mode;
- daemon startup failure in `acquire` mode;
- authentication or private-state failure;
- incompatible protocol/product version;
- daemon overloaded or shutting down;
- request outcome ambiguous after connection loss;
- management command refused because the discovered process cannot be proven to be the matching daemon.

Exact integers become test fixtures and public documentation. Internal exception class names are not part of the contract.

## Compatibility matrix

1. Existing `sjm send`, `sjm connect`, `sjm validate`, help, and argument-file invocations remain valid and default to one-shot execution.
2. Existing `sjm` and `sjm.bat` script names remain available in portable archives.
3. Existing mailer and email builder options keep their precedence over conventional configuration.
4. Output text may gain a concise daemon-specific diagnostic, but successful command output and machine-relevant exit codes remain stable.
5. A daemon-started command uses the daemon's startup environment and configuration snapshot plus request options. It does not inherit arbitrary environment changes from each client.
6. Relative paths retain caller semantics through the request context defined in Step 9.
7. Direct users of the CLI Maven artifact accept the Java 17 runtime break from Step 2.
8. The one-shot path remains the recovery and compatibility route even when platform service integration is not installed.
9. Multiple effective SMTP/Mailer configurations share one selected daemon safely; users do not need to name a Mailer profile.
10. Named daemon instances are an optional process/configuration-isolation feature selected with `--daemon-instance`, not the normal way to select an SMTP account.

## Process ownership decisions

- The default daemon is per user, not system-wide.
- `start`, `stop`, and `restart` operate only on the caller's instance and authenticated endpoint.
- A PID is never killed solely because it appears in a file.
- A machine-wide service is installed and managed through an explicit OS-specific command/package, not implicitly by `-d` or `--daemon=acquire`.
- Machine-service authorization and file access are separate from the per-user default and must be documented by Steps 14 through 17.

## Compile and shell fixtures

Create portable invocation fixtures for:

- current one-shot commands;
- `sjm send -d`, bare `--daemon`, each full execution value, and `--no-daemon` after each application subcommand;
- each selector before the subcommand as well if the bootstrap can support both positions without ambiguity;
- default and named instances for both application and management commands;
- management command help;
- incompatible daemon version;
- missing, starting, ready, stopping, and stale daemon states;
- argument files containing the subcommand and the execution selector;
- scripts with spaces and Unicode in installation and working-directory paths.

## Acceptance criteria

- [x] The complete command grammar is represented by parser tests and copyable help output.
- [x] The execution-mode and fallback rules have no ambiguous branch.
- [x] `sjm send -d` and the documented full-form equivalents have parser and shell fixtures.
- [x] Default and named daemon selection is explicit and consistent.
- [x] Exact exit-code categories are frozen.
- [x] One-shot compatibility and the Java 17 CLI break are listed separately.
- [x] Every daemon remains scoped to and owned by its operating-system user.
- [x] Version mismatch, stale state, overload, shutdown, and ambiguous-send behavior are specified.
- [x] Every intentional CLI behavior change has an entry for Step 17.
- [x] No unresolved command or routing choice remains when Step 4 starts.

## Completion evidence

- Parser and process tests cover one-shot default/off, `-d`, bare `--daemon`, `acquire`, `require`, named instances, management commands, argument files, and local help/version routing.
- `CliExitCode` freezes successful, usage, configuration, connection, protocol, unavailable, overloaded, interrupted, ambiguous, and internal outcomes.
- `DAEMON.md` and `MIGRATING.md` document fallback behavior, selection precedence, version mismatch, shutdown, and ambiguous-send handling.
