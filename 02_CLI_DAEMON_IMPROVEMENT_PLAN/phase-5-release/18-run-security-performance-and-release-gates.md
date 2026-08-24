# Step 18: Run security, performance, compatibility, and release gates

- Status: In progress
- Depends on: Steps 1 through 17
- Target release: 10.0.0
- Primary areas: full verification, OS lifecycle, security, performance, artifacts, completion evidence

## Goal

Prove the daemon is faster for repeated CLI work, safe against local misuse, compatible with one-shot behavior, correctly packaged for every claimed platform, and isolated from the Java 8 library baseline. Then remove temporary bridges and record the evidence needed to close issue #488.

## Java and compatibility matrix

1. JDK 8 library-only reactor with tests, public API fixtures, classpath, JPMS multi-release output where applicable, OSGi/Karaf, Spring, and non-CLI optional modules.
2. JDK 17 full reactor with library `--release 8`, CLI `--release 17`, CLI metadata generation, one-shot tests, daemon tests, and portable packaging.
3. Current release JDK full reactor with illegal-reflection checks and metadata cross-consumption.
4. Class-file audit: version 52 outside `cli-module`, version 61 inside CLI production output.
5. Current one-shot help, version, send, connect, validate, argument-file, `sjm`, and `sjm.bat` fixtures.
6. Direct CLI Maven consumer on Java 17 and normal Simple Java Mail consumers on Java 8.
7. Sibling instance-configuration plan integration with one immutable daemon startup snapshot and independent non-CLI factories.

## Transport and lifecycle matrix

Run each applicable route with absent, start, ready, busy, quiescing, stopped, crashed, stale, tampered, incompatible, and upgraded daemon state:

- Windows Unix-domain socket;
- Windows forced loopback TCP;
- Linux Unix-domain socket;
- Linux forced loopback TCP;
- macOS Unix-domain socket;
- macOS forced loopback TCP;
- portable foreground process;
- portable per-user background process;
- systemd user route;
- Homebrew-managed service route when published;
- one tested non-systemd/container foreground route.

## Command and Mailer matrix

1. Send, connect, and validate in `off`, `require`, and `acquire` modes, including `sjm send -d`, bare `--daemon`, and `--no-daemon` aliases.
2. Help, version, status, start, stop, and restart without full CLI initialization.
3. One and multiple compatible profiles with Mailer reuse.
4. Incompatible SMTP, credential, proxy, trust, security, Session, cluster, and pool profiles without reuse.
5. Sync/async builder settings while preserving synchronous CLI completion.
6. Batch connection reuse, cluster registration, idle eviction, and full close.
7. Relative/absolute attachments, body files, EML/MSG, certificates, argument files, spaces, Unicode, and denied service-account access.
8. Fixed and changed OAuth2 tokens with the documented replacement route.
9. Concurrent requests, overload, disconnected clients, replayed IDs, shutdown drain, and daemon crash ambiguity.
10. Default and named daemon instances, plus multiple compatible and incompatible Mailer configurations inside one daemon.
11. Both supported classpaths: official distribution with `batch-module` and a custom distribution without it.

## Security audit

1. Inspect every listener and packaged default to prove no non-loopback network exposure.
2. Test state owner/mode/ACL, symlink/reparse-point, socket-path, port, PID-reuse, discovery-tamper, token, HMAC, replay, oversized-frame, slow-client, and output-amplification cases.
3. Run two ordinary OS users against each per-user implementation.
4. Scan source, bytecode constants, stdout, stderr, daemon logs, discovery files, service-manager logs, test reports, crash diagnostics, status, and packages for synthetic secrets and message bodies.
5. Audit dependencies and platform/package inputs, including licenses, checksums, provenance, and known vulnerabilities under the release policy.
6. Confirm protocol parsing never invokes Java serialization, Kryo, reflection from peer-supplied class names, a shell, or an executable token provider.
7. Confirm every resource limit is finite and tested at/over its boundary.

## Performance and resource proof

Use the Step 1 harness on the same hosts and distributions to record raw samples, median, p95, and environment for:

- bare Java/bootstrap process;
- one-shot help and validate;
- one-shot controlled connect/send;
- first daemon start and first request;
- warm daemon validate;
- warm daemon send with no retained SMTP connection;
- warm daemon send with a retained connection;
- concurrent warm requests at the supported bound.

Before implementation is declared done, record and approve relative targets based on the Step 1 baseline. At minimum, evidence must show:

- routed warm commands avoid full CLI-model initialization in the client;
- repeated compatible requests construct one Mailer rather than one per command;
- configured connection retention eliminates repeated SMTP connections/handshakes in the controlled test;
- the warm route provides a material, repeatable wall-clock improvement rather than only moving work to another process;
- idle daemon threads, memory, file descriptors/handles, logs, registry entries, and result ledger remain bounded over a long-running soak test.

Do not hide daemon startup in the warm benchmark. Report cold start separately.

## Artifact and installation audit

1. Inspect portable tar/zip contents, scripts, permissions, line endings, Java requirement, daemon resources, dependency trees, and license files.
2. Render and validate the Homebrew and Chocolatey sources from real release URLs and checksums.
3. Install, upgrade over an old running daemon, exercise explicit daemon lifecycle, and uninstall the Homebrew formula on macOS/Linux and Chocolatey package on Windows.
4. Verify uninstall retention/removal of config, logs, service state, daemon state, and sockets matches documentation.
5. Verify artifact names, OS/architecture classifiers, checksums, signatures/notarization, and collected release assets.
6. Build the website from the actual updated checkout and run its production/link gates.
7. Run `git diff --check` in the root and website checkouts.

## Static and cleanup audits

1. Scan non-CLI source and bytecode for Java 17 daemon/IPC types.
2. Scan CLI production code for direct global `user.dir`, `System.out`, or `System.err` mutation and shared mutable Picocli parse state.
3. Scan command execution for direct `new MailerRegularBuilderImpl()`/`new EmailStartingBuilderImpl()` paths that bypass the configured factory.
4. Scan daemon logging for raw request vectors, builder values, Email/MimeMessage objects, and authentication material.
5. Verify no Appassembler JSW daemon generation, temporary compatibility adapter, test-only insecure permission bypass, plan TODO, or development endpoint remains.
6. Verify every prior step contains concrete completion evidence and has been marked `Done`.

## Completion evidence

Record:

- exact JDK, Maven, operating-system, architecture, and package-tool versions;
- exact build/test/package commands and durations;
- Java/class-file/API compatibility reports;
- transport and lifecycle matrix results;
- security audit and secret-scan results;
- raw and summarized performance measurements;
- SMTP connection/Mailer reuse counts;
- soak-test resource measurements;
- install/upgrade/uninstall results and artifact checksums;
- documentation and website build results;
- consciously unsupported OS/service/package routes.

## Acceptance criteria

- [x] Java 8 library and Java 17 CLI gates pass without leakage.
- [x] One-shot behavior and all daemon execution modes pass.
- [ ] Unix-domain socket and loopback fallback pass on every claimed OS.
- [x] Per-user isolation, authentication, replay defense, limits, and secret scans pass.
- [x] Compatible requests reuse Mailers and configured SMTP connections; incompatible requests never do.
- [x] No automatic retry/fallback can duplicate an ambiguous send.
- [ ] Every claimed supervisor/service/package passes real lifecycle tests.
- [ ] Homebrew and Chocolatey package-manager routes pass install, upgrade, uninstall, and explicit opt-in daemon tests.
- [x] Warm repeated-command performance is materially and repeatably better than one-shot execution.
- [ ] Idle and sustained daemon resources remain bounded.
- [ ] Portable artifacts, migration docs, durable docs, and website agree; package-manager artifacts are governed separately by issue #708.
- [ ] Every temporary bridge and insecure test hook is gone.
- [ ] Issue #488 has enough measured and operational evidence to close after release.

## Implementation evidence

- The completed local matrix passes the Java 8 library-only gate, Java 17 full reactor, current-JDK full reactor, 86-test CLI suite, both transports on Windows and Ubuntu, Windows ZIP and Linux tar lifecycles, a systemd user lifecycle, security scans, real SMTP reuse, and no-batch compatibility. Clean Temurin 17 and 21 Linux containers pass the exact tar over both transports; a locked-down non-root Java 17 container also proves private `0700` state and Unix-socket operation without Linux capabilities or a writable root filesystem. A foreground PID 1 lifecycle serves an authenticated request and drains on orchestrator SIGTERM with the expected exit status 143.
- The opt-in benchmark measured warm validation at 387 ms median versus 1,804 ms one-shot median on Windows/JDK 21; resources are structurally bounded, while a release-length soak remains open.
- Hosted macOS transport, signing/provenance, release artifact agreement, soak testing, and post-release issue closure remain portable release gates. Homebrew and Chocolatey lifecycles are issue #708 gates and do not block the portable archive.
