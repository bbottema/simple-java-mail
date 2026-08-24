# Step 16: Build portable archives and cross-platform CI

- Status: Portable archive work done; package-manager work deferred to issue #708
- Depends on: Steps 14 and 15
- Primary files: `modules/cli-module/pom.xml`, assembly descriptors, approved OS CI workflow
- Primary areas: portable archives and OS/JDK matrix; Homebrew and Chocolatey are tracked by issue #708

## Goal

Publish portable Java 17 archives that match the support claims. Issue #708 separately tracks Homebrew and Chocolatey packages. Self-contained MSI, pkg, deb, rpm, native-image executables, and machine-service installers are not part of this improvement.

## Tests first

1. Build the portable tar and ZIP from a clean checkout, unpack each into a path with spaces, and run one-shot and daemon smoke suites on JDK 17 and the current release JDK.
2. Inspect every bundled jar and launcher for the expected version, Java baseline, duplicate dependencies, licenses, executable bits, line endings, and secret or test fixtures.
3. Exercise TLS, proxies, certificates, logging, attachment files, EML/MSG conversion, DKIM, S/MIME, OpenPGP, batch pooling, and every optional module included in the standalone distribution.
4. Test version replacement while an old daemon is running and verify the mismatch and restart path.
5. Test x64 artifacts on all claimed operating systems and every additionally published architecture on real hardware or an approved equivalent.
6. Produce checksums, software/license inventory, provenance, and signing evidence required by the release policy.
7. Render Homebrew and Chocolatey sources from versioned HTTPS archive URLs and SHA-256 checksums; fail on invalid inputs or unresolved template values.
8. Parse the rendered Homebrew formula on macOS and pack the rendered NuSpec with Chocolatey on Windows.
9. Install, upgrade, exercise, and uninstall both packages against published release archives. Prove package installation never enables daemon autostart implicitly.

## Portable artifacts

1. Retain `sjm` and `sjm.bat` in tar/ZIP archives and make their Java 17 requirement explicit.
2. Continue using Appassembler's ordinary launcher generation while it handles Java 17 and daemon arguments correctly. Do not enable its JSW daemon goal.
3. Include daemon documentation, license notices, and the tested systemd user-unit example.
4. Keep future package-manager authoring files outside the end-user archive; they are release inputs, not runtime resources.
5. Verify executable permissions and CRLF/LF behavior after extraction on each platform.

## Package-manager packages

1. Maintain one project-owned Homebrew formula for macOS and Homebrew on Linux. It consumes a versioned tar, supplies the tested Java 21 LTS runtime for the Java 17-compatible CLI, includes a functional test, and defines `sjm daemon run` through `service do` for explicit `brew services` use.
2. Maintain one Chocolatey package for Windows. It consumes the versioned ZIP, exposes `sjm`, and handles uninstall with a running default daemon.
3. Maintain the draft sources on the issue #708 branch until their real lifecycle is proven. Pin downloads by checksum and reject unresolved values.
4. Do not start, enable, schedule, or register the daemon merely because either package is installed.
5. Treat publication to a project tap/feed, Homebrew/core, or the Chocolatey Community Repository as an explicit release action with its own lifecycle evidence.

## CI matrix

| Job | Required evidence |
| --- | --- |
| Java 8 library compatibility | Non-CLI modules and bytecode/API gate |
| JDK 17 full CLI | Compile, unit, forked process, UDS, TCP fallback, portable archive |
| Current JDK full reactor | Modern runtime, metadata generation, full tests |
| Windows | UDS/TCP, hidden background start, ZIP lifecycle |
| Linux | UDS/TCP, tar lifecycle, systemd user lifecycle where available |
| macOS | UDS/TCP, tar lifecycle |
| Homebrew release gate | Formula install/test/upgrade/uninstall and `brew services` on macOS and Linux |
| Chocolatey release gate | Package install/upgrade/uninstall and explicit daemon lifecycle on Windows |

Package install tests may run in maintained release VMs when hosted CI is insufficient, but their commands and evidence remain repeatable and release-blocking before package publication.

## Acceptance criteria

- [x] Portable tar/ZIP work on JDK 17 and the current release JDK.
- [x] Non-CLI artifacts remain Java 8 and CLI artifacts are Java 17.
- [x] Appassembler JSW daemon generation is absent.
- [x] Draft Homebrew and Chocolatey templates have one repeatable renderer; they are parked under issue #708.
- [x] Package-manager sources and untested platform service files are absent from the portable archive.
- [ ] Homebrew and Chocolatey packages pass their target-platform lifecycle tests and never enable background startup on install.
- [ ] OS/architecture classifiers, checksums, licenses, provenance, and signing status are unambiguous.
- [ ] Version upgrade with a running daemon produces a safe restart path.
- [ ] CI support claims match real jobs or documented release VMs.

## Implementation evidence

- Maven builds portable tar and ZIP distributions containing the Java 17 CLI, Java 8 library dependencies, daemon documentation, and the tested systemd unit. Windows ZIP and Ubuntu tar lifecycles pass; the tar explicitly preserves executable launcher permissions.
- `.github/workflows/cli-daemon.yml` defines Windows, Ubuntu, and macOS jobs on JDK 17/current JDK, archive extraction/lifecycle checks, deterministic metadata checks, and a Java 8 library-only gate.
- The draft release renderer on the issue #708 branch validates version, URLs, checksums, and unresolved values, then creates correctly named Homebrew and Chocolatey source trees.
- Homebrew and Chocolatey publication and clean lifecycle evidence, signing/provenance, and package upgrade evidence remain release work.

## Stop condition

If a package-manager route cannot pass its real lifecycle gate, release the tested portable archive and do not claim that package as available. Do not add an OS installer or another service mechanism merely to fill the gap.
