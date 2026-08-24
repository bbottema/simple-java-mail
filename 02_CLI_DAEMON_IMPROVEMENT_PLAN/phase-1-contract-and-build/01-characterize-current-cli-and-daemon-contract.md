# Step 1: Characterize the current CLI lifecycle and performance

- Status: Done
- Depends on: completion of [01 - Instance-based configuration](../../01_INSTANCE_CONFIGURATION_IMPROVEMENT_PLAN/README.md)
- Primary module: `cli-module`
- Primary files: `SimpleJavaMail.java`, `CliSupport.java`, `CliCommandLineConsumerResultHandler.java`, `CliProcessSmokeTest.java`, `cli.data`, `log4j2.xml`

## Goal

Turn the current one-shot behavior, startup cost, output, path resolution, and shutdown behavior into an executable baseline before introducing another process. This step distinguishes the already-fixed batch shutdown problem from the still-missing persistent daemon.

## Tests first

1. Run `--help`, `--version`, send help, validate, connect against a controlled fake server, and send against a controlled fake SMTP server in forked processes.
2. Record exit code, stdout, stderr, log routing, wall-clock startup time, and whether the process exits without external interruption.
3. Run the command process both with and without `batch-module` on the classpath. With it present, prove the current `Mailer.close()` path terminates cleanly; without it, prove the CLI remains supported without batch pooling.
4. Instrument or otherwise observe when `CliSupport`, `cli.data`, `therapi.data`, Picocli, ConfigLoader, and Mailer classes initialize.
5. Measure a bare Java process separately so daemon goals do not confuse JVM launch time with CLI-model initialization time.
6. Run repeated one-shot sends far enough apart to show that no SMTP connection or Mailer survives between processes.
7. Characterize current argument-file expansion, relative attachment/body/certificate paths, working-directory behavior, Unicode paths, paths with spaces, and missing-file messages.
8. Characterize synchronous completion for send and connect, including a failed future and process interruption.
9. Characterize all observable output and log content for SMTP credentials, proxy credentials, OAuth tokens, message bodies, and recipient data.
10. Repeat the supported baseline on Windows and Linux, and run the same tests on JDK 17 and the current release JDK.

Benchmarks use warmed disk caches, multiple iterations, raw samples, median, and p95. They are diagnostic gates, not microbenchmarks. CI timing is recorded but does not use a brittle fixed millisecond threshold.

## Deliverables

- A checked-in behavior matrix covering local commands, exit codes, output streams, lifecycle, file resolution, and configuration timing.
- A repeatable forked-process benchmark or test harness that can compare one-shot, first-daemon-request, and warm-daemon-request execution later.
- A named regression test for commit `d240b3aa`'s wait-and-close behavior.
- A list of current secret/body logging that Step 13 must remove.
- An explicit list of static initialization that the thin bootstrap must avoid.
- A support matrix distinguishing daemon startup savings from the SMTP connection reuse available only when `batch-module` is present and configured to retain a connection.

## Preserve

- Existing command names and builder-derived options unless Step 3 records an intentional change.
- Existing one-shot send, connect, and validate semantics.
- Synchronous success only after the current command's work completes.
- Existing argument-file and relative-path meaning from the caller's perspective.
- The ability to remove or omit `batch-module` from a custom distribution.

## Correct deliberately

- The current daemon feature does not exist; do not preserve one-process-per-command as the only route.
- Persistent logging may not retain trace-level message content or raw option values.
- Caller-relative files may not be reinterpreted relative to the daemon's startup directory.

## Acceptance criteria

- [x] Every existing root and subcommand has a named forked-process baseline.
- [x] The batch-enabled one-shot process exits cleanly without `Ctrl+C`.
- [x] Both the batch-enabled official distribution and a custom distribution without `batch-module` have named supported baselines.
- [x] Startup cost is split into JVM launch, CLI bootstrap/model load, configuration, and command work where measurable.
- [x] Current static initialization before command execution is identified.
- [x] Path, output, exit-code, and future-completion behavior is captured.
- [x] Logging exposure is recorded using synthetic secrets only.
- [x] No daemon implementation or public command is introduced in this step.

## Completion evidence

- `CliDaemonPerformanceTest` records bare JVM, one-shot CLI, daemon startup, first request, and warm-request timings as an opt-in forked-process benchmark.
- `CliDaemonProcessTest` preserves one-shot send, connect, and validate behavior; exercises the official batch-enabled distribution; and proves a custom runtime without `batch-module` remains supported.
- The benchmark on Windows/JDK 21 measured a 1,804 ms one-shot median and a 387 ms warm-daemon median for validation, a 4.66x improvement on that host.

## Stop condition

If the existing CLI cannot be exercised deterministically without a live SMTP service, stop and build the fake-server test boundary before setting any performance or compatibility target.
