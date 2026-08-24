# Step 14: Integrate Windows background and Chocolatey lifecycle

- Status: Portable Windows route done; Chocolatey deferred to issue #708
- Depends on: Steps 7, 8, and 13
- Primary platform: supported Windows desktop and server releases
- Primary areas: hidden per-user launch, Chocolatey, ACLs, upgrade/uninstall

## Goal

Provide a reliable no-console per-user daemon and an easy Chocolatey installation route on Windows. The daemon is part of `sjm`; there is no separate Windows daemon product, Scheduled Task, or Windows Service to install.

## Tests first

1. Run `daemon run`, start, status, stop, and restart from `cmd.exe`, PowerShell, and the packaged `.bat` launcher.
2. Start the portable daemon through the matching `javaw.exe` and prove no console window, inherited pipe, or parent-process lifetime keeps either process alive.
3. Exercise install paths, user profiles, TEMP paths, and working directories with spaces, Unicode, long paths, and restrictive ACLs.
4. Run Unix-domain socket transport on supported Windows builds and forced TCP fallback on an unsupported or capability-denied path.
5. Validate discovery, log, socket, and configuration ACLs using two ordinary Windows users. The second user must not authenticate to or control the first user's daemon.
6. Kill the daemon at each lifecycle phase and verify stale endpoint/state recovery without stopping an unrelated PID.
7. Run on x64 and inspect or prove ARM64 artifact behavior before claiming ARM64 support.
8. Render and pack the Chocolatey source, then install, use `sjm send -d`, upgrade with a running daemon, and uninstall in a clean VM. Prove installation alone does not start a daemon or register autostart.

## Windows implementation

1. Keep portable `daemon start` rooted in the installed CLI's own Java/classpath and use `javaw.exe` when available.
2. Store per-user operational state and logs under a private current-user location, while deriving a short socket path that satisfies Windows AF_UNIX limits.
3. Make `-d` and `--daemon=acquire` the ordinary on-demand background route. `sjm daemon start` remains available for an explicit early start.
4. Preserve direct `daemon run` for diagnosis and for any external supervisor a user deliberately chooses.
5. Do not ship a Scheduled Task installer or claim a machine-wide Windows Service.

## Chocolatey package

Publish a Chocolatey package that installs the tested Windows CLI ZIP, declares the Java 17 runtime dependency, and exposes `sjm` on `PATH`. Install and upgrade do not start the daemon or add autostart. Uninstall first asks a running default daemon to stop, then removes only package-owned files while retaining user configuration and logs.

Draft package sources and their publication lifecycle are parked under [issue #708](https://github.com/bbottema/simple-java-mail/issues/708). They must be rendered from versioned HTTPS archive inputs and SHA-256 checksums and pass Chocolatey validation, verification, moderation, and maintenance rules before availability is claimed.

## Acceptance criteria

- [x] Per-user background start works without a visible console or administrator rights.
- [x] Unix-domain socket and forced TCP paths both pass on Windows.
- [x] Windows x64 support is proven; every additional architecture claim has a real artifact test.
- [x] Draft Chocolatey templates render without unresolved values and build a package locally; they are parked under issue #708 rather than included in the portable release branch.
- [ ] The Chocolatey package passes clean install, explicit daemon use, upgrade, and uninstall without enabling background startup.
- [ ] Two Windows users cannot control or inspect each other's per-user daemon.
- [x] No Scheduled Task, Windows Service, or legacy JSW binary is introduced.

## Implementation evidence

- Windows x64 process and packaged-ZIP tests prove console-free per-user start, authenticated status/use/stop, Unix-domain sockets, and forced loopback fallback.
- Owner-only state ACL handling and process-identity checks are covered locally.
- The parked package renderer rejects malformed release inputs and unresolved tokens, and a local `choco pack` succeeds.
- Clean Chocolatey install/upgrade/uninstall and two-user isolation remain release gates. No autostart or privileged service is claimed.

## Stop condition

The portable Windows ZIP may be released independently. Do not claim Chocolatey availability until issue #708 passes its lifecycle gates, and do not complicate the daemon core with a second Windows lifecycle mechanism.
