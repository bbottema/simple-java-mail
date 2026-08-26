# Step 17: Write CLI daemon migration and operations documentation

- Status: Done
- Depends on: Steps 3 and 13 through 16
- Primary repositories: root library checkout and the dedicated 10.0.0 website branch in `simplejavamail.org`
- Primary files: `DEVELOPMENT.md`, `MIGRATION-10.0.md`, `README.md`, `RELEASE.txt`, CLI website pages, packaging/service resources, mechanism documentation

## Goal

Explain the Java 17 CLI boundary, daemon execution model, operating-system setup, delivery semantics, configuration lifecycle, and recovery procedures without making users infer them from service files or implementation details.

## Migration notes

Add concise before/after guidance for:

1. Running the 10.0 CLI on Java 17 or newer while Java library users remain on Java 8.
2. Applications that directly depend on or embed `cli-module`.
3. Existing one-shot `send`, `connect`, `validate`, argument-file, `sjm`, and `sjm.bat` use, which remains valid.
4. Keeping default one-shot execution or enabling `off`, `acquire`, or `require` deliberately, including `sjm send -d` and bare `--daemon`.
5. Starting, inspecting, stopping, and restarting the per-user daemon.
6. The difference between the old non-exiting batch bug and the new persistent feature.
7. Configuration captured at startup and restart-required replacement.
8. Existing CLI option precedence and Mailer profile reuse.
9. OAuth2 fixed-token behavior in a long-lived daemon.
10. Caller-relative files and daemon-user file permissions.
11. The no-automatic-resend rule for ambiguous results.
12. Existing connection-pool defaults and explicit tuning for intermittent CLI commands.
13. `batch-module` support: what remains available without it, and what Mailer/SMTP pool reuse becomes available with it.
14. Multiple effective Mailer configurations in one daemon versus deliberately selecting another daemon with `--daemon-instance=<name>`.

Do not list internal protocol classes, parser extraction, registry types, metadata format changes, or service wrapper implementation when they do not affect a supported invocation.

## Durable CLI documentation

1. Explain one-shot and daemon modes independently, including why one-shot remains useful.
2. Provide copyable command examples for every management command and execution mode.
3. Show a repeated-send example that actually retains an SMTP connection through explicit pool settings.
4. Explain per-user instance scope, optional instance names, version mismatch, and restart after upgrade.
5. Show two SMTP configurations reusing separate Mailers in the same daemon, then show when a named daemon instance is appropriate.
6. Document state and log locations by platform without printing authentication secrets in diagnostic commands.
7. Document status fields and stable exit-code categories for scripts.
8. Explain queue/backpressure limits, graceful stop, startup timeout, and ambiguous result recovery.
9. Explain that the daemon is local only and is not a durable queue, scheduler, or remote SMTP API.
10. Document OAuth2 token refresh limitations accurately.
11. Document how relative attachments, bodies, certificates, and argument files resolve.

## Platform operations

Provide separate, tested sections for:

### Windows

- portable background start with `javaw`;
- on-demand `-d` use without a Scheduled Task or Windows Service;
- the fact that Chocolatey distribution is deferred to issue #708;
- Unix-domain socket support and automatic loopback fallback diagnostics.

### Linux

- portable foreground use;
- systemd user unit installation, `enable --now`, status/logs, stop, removal, and optional lingering;
- the fact that Homebrew distribution is deferred to issue #708 and has no service integration.

### macOS

- portable foreground use;
- on-demand `-d` use and foreground operation under a user-selected supervisor;
- log and state locations;
- the fact that Homebrew distribution is deferred to issue #708.

### Other Unix and containers

- the universal foreground contract;
- only the supervisor recipes actually exercised in Step 15;
- containers run the process in the foreground and let the orchestrator restart it.

## Maintainer documentation

1. Update the Java build matrix and remove the stale JDK 8-only CLI metadata rule.
2. Document metadata regeneration on JDK 17/current JDK.
3. Document protocol-version changes, state compatibility, and the rule against Java/Kryo serialization over IPC.
4. Document package prerequisites, checksum and release validation, the one-time installation smoke workflow, and publication credentials.
5. Add daemon lifecycle and Mailer registry ownership to `PROJECT_MECHANISMS_CATALOGUE.md`.
6. Cross-link and reconcile the CLI gates in `01_INSTANCE_CONFIGURATION_IMPROVEMENT_PLAN` before implementation of this plan begins.
7. Record how to run every forked process, transport, security, performance, and platform gate.

## Copy rules

- Speak directly to the CLI user or operator.
- Use plain language and copyable commands.
- Distinguish portable on-demand acquisition from explicitly enabled systemd supervision and the service-free Homebrew package.
- State scope and effect before commands that enable a service or install files outside the CLI directory.
- Do not imply delivery was unsuccessful merely because the client lost the response.
- Do not call the process a queue or claim exactly-once delivery.
- Do not expose real credentials, tokens, email addresses, message bodies, home paths, or service-account passwords in examples.
- Keep durable CLI pages complete without requiring the reader to reconstruct behavior from migration notes.

## Verification

- Run every command example on its claimed operating system and packaging route.
- Compile every Java embedding/migration example against the intended Java/module baseline.
- Verify shell quoting in PowerShell, cmd, POSIX shell, and systemd units; issue #708 owns package-manager source verification.
- Build the actual updated website checkout and inspect rendered navigation and code blocks.
- Check every external JDK, service-manager, and packaging link.
- Scan rendered docs and packaged examples for secret fixtures and machine-specific paths.

## Acceptance criteria

- [x] Java library and CLI runtime requirements are unmistakably separate.
- [x] Every Step 3 compatibility/migration row has a copyable explanation.
- [x] One-shot, daemon, configuration, pooling, OAuth2, file, and ambiguous-result behavior are documented.
- [x] Windows, Linux, macOS, other Unix, and container instructions match tested support.
- [x] Homebrew and Chocolatey are identified as deferred issue #708 work; no unpublished installation route is claimed.
- [x] Service installation always states privilege, identity, authorization, and file-access effects first.
- [x] Maintainer build/generation/package instructions are current and repeatable.
- [x] The sibling improvement plan has no contradictory CLI Java gate.
- [x] Root docs and the built website agree.
- [x] No internal-only churn or secret-bearing example appears in public migration notes.

## Completion evidence

- `DAEMON.md`, `MIGRATING.md`, `DEVELOPMENT.md`, the root README, and the module catalogue document the Java split, modes, instances, multiple Mailers, pooling, files, OAuth2, shutdown, and ambiguity contract.
- The website CLI, modules, and download pages reflect the same feature/support boundaries; its build, content checks, and 2,007-link internal-link verification pass.
- Log4j configuration, the systemd unit, and assembly resources are validated locally or in the matching hosted CI job. Draft package-manager sources are isolated under issue #708, and operational commands are labelled according to the platform evidence actually available.
