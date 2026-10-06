# Managed Angus resource corrections: verification record

Reviewed and accepted: 2026-10-06. Target: unreleased 10.0.0. Tracking: [#726](https://github.com/bbottema/simple-java-mail/issues/726),
with related security/probe follow-ups under [#735](https://github.com/bbottema/simple-java-mail/issues/735) and
[#733](https://github.com/bbottema/simple-java-mail/issues/733). These existing issues remain closed.

## Scope and implementation

The correction addresses default custom-factory fallback, failed initial TLS-wrapping cleanup, and per-socket write-timeout scheduling
identified in [the characterization report](SMTP_TRANSPORT_OWNERSHIP_CHARACTERIZATION.md). The reviewed implementation is
[`d17884ad`](https://github.com/bbottema/simple-java-mail/commit/d17884adc9dca23d92beadd8a2c8ccf8b5cd5ea5).
It adds no public API, configuration option or dependency, and preserves caller-owned Sessions, alternate providers and CustomMailer.

Maintained contracts are recorded in [ADR 0015](../adr/0015-execution-views-and-transport-pooling.md),
[ADR 0017](../adr/0017-deadlines-and-physical-cancellation.md), [ADR 0020](../adr/0020-dedicated-smtp-connection-diagnostics.md),
[ADR 0021](../adr/0021-mandatory-starttls-configuration-consistency.md), and the
[Angus ownership catalogue](../concurrency/07-angus-transport-abort.md). This document retains verification evidence after completion;
the active implementation plan has been retired. The shared infographic's resource layers remain unchanged.

## Initial implementation verification

Recorded 2026-10-06, before the holistic-review correction below. The real-JDK-11 library `verify` passed:
1,841 tests across 158 suites, zero failures/errors and one existing
disabled case in `MiscUtilTest`. The packaged provider-neutral classpath/JPMS consumers, third-party probe fixture and managed-Angus
JPMS consumer also ran successfully. The latter checks exported class factories, an actionable failure for an inaccessible class,
and an ordinary supplied instance without reflective package access.

The first broad run exposed four obsolete assertions: three expected the unwrapped SSL instance in Session properties, and one
expected two independent Sessions to have identical runtime controllers. The corrected tests verify delegation to the selected factory
and distinct controllers. The complete Java 11 rerun passed, not merely the four corrected assertions.

Focused final coverage includes eight SSL-decorator cases, five scheduler cases (including 200 last-release/acquire races), 13 abort cases,
and 16 write-timeout ownership cases. The retained-scope cases verify that simple-batch/open-connection callbacks retain the worker until
scope teardown; disposal also retries a raw-socket close that failed during abort. All peers are loopback-only or test doubles.

The complete modern-JDK-21 reactor `verify` also passed: 2,027 tests across 192 suites, zero failures/errors and one existing opt-in
CLI performance case skipped. This includes the CLI, Javadoc packaging, coverage aggregation and packaged classpath/JPMS consumers.
No implementation changed after these complete runs; the final factory-identity Javadoc clarification was included in the subsequent
CLI metadata regeneration.

All three existing Spring combinations passed: the default Java 11 lane (plain Spring 5.3.39 and the Boot 2.7.18 starter's managed
Spring 5.3.31) in the full library run, and Boot 3.0.13 / Spring 6.0.14 on Java 17 and Boot 3.5.16 / Spring 6.2.19 on Java 21
with 62 selected factory/Spring/starter tests each.
The Java 17 launcher initially pointed one directory above the installed JDK; correcting the path allowed that lane to start and pass.

Both CLI metadata caches were regenerated through the documented clean `publish-cli` build. The following 46 help, mapper, cache,
TLS-configuration and concurrent-daemon tests passed against the regenerated files.

Website checks passed: 73 tests, TypeScript checking and an Eleventy dry run. A clean production build and internal-link verification
also passed: 3,401 local links across 63 pages. The concurrent Journal refactor initially prevented verification; after it settled,
the checks passed without changes to that work.

The coding-guide pass, relative documentation links and whitespace checks passed; added Java lines stay within 160 columns.
`mvn license:remove` completed, and the touched Java sources contain no generated license headers.
No live-email test, benchmark study, SpotBugs or hosted conformance rerun is claimed here.
These initial results predate the trust-gate correction and must not be read as complete verification of the final implementation.

## Holistic-review correction

Review found that a generic SSL cleanup decorator hid `MailSSLSocketFactory` from Angus's post-handshake trusted-host gate.
A real loopback reproduction rejected a non-allowlisted host with the original factory but accepted it through the decorator, even with
hostname checking enabled. The guard now retains the recognized subtype and delegates the trust check to the original factory selected
for each socket. It does not copy trust settings, perform an early handshake or replace the returned socket. Class factories remain lazy
and can return different instances for overlapping connections. Entries are removed at the trust check, with weak keys for failed handshakes;
the original callback runs outside the bookkeeping monitor, and a missing entry cannot authorize a socket.

The new trust regressions failed before the correction. Verification after this review correction is recorded below; the earlier full-reactor
results above describe the pre-correction implementation.

### Windows verification boundary

Local Maven and forked test JVMs use `-Djavax.net.ssl.trustStoreType=Windows-ROOT`. Positive TLS fixtures combine their test certificate
with the configured system roots in memory; they do not install certificates into Windows or disable chain/hostname validation.
Norton's mail shield re-signs the self-signed implicit-TLS fixture using a separate, untrusted root, which Windows-ROOT does not authorize.
The generic-factory positive SMTPS case remains enabled in the source suite for an unintercepted environment, but is excluded from these
local verification commands by its method name, `ordinaryImplicitTlsFactoriesStillUseTheirCertificateTrustManager`.
Generic-factory STARTTLS acceptance, certificate rejection and hostname-verifier rejection remain exercised locally, as do trusted-host
allowlist acceptance/rejection for both protocols and both instance/class factories.

### Post-review verification

The focused trust rerun passed 44 cases: 11 decorator cases and 33 socket-factory wire cases, with the one named local exclusion above.
The broader real-JDK-11 library `verify` then passed 1,857 cases across 158 suites, zero failures/errors and one existing disabled case. It also ran the
packaged classpath/JPMS consumers, third-party probe fixture and managed-Angus JPMS consumer. The local exclusion remains explicit;
these results do not claim that the intercepted positive SMTPS fixture passed.

The first broad rerun had three timeout errors in existing pooling/legacy-content tests. Those tests passed unchanged in the focused
rerun and the complete Java 11 rerun with two concurrent main-module test JVMs. No timeouts or assertions were relaxed to obtain that result.

JDK 21 focused `verify` passed 275 cases across 14 suites with no failures, errors or skips: provider trust/lifecycle/scheduler cases,
socket-factory, authentication/TLS, probe and ownership regressions, plus CLI help, mapping, metadata-concurrency, TLS and daemon tests.
The provider-neutral classpath/JPMS consumers and third-party probe fixture also passed on this JDK. This was a focused modern-JDK run,
not a second complete reactor rerun after the trust-gate correction, and it used the same named local SMTPS exclusion.

The coding-guide pass, 160-column added-Java check, whitespace checks and correction-document links passed. Final `mvn license:remove`
completed after verification; generated headers are absent from the changed Java sources. No production trust-store default changed,
and the Windows store was only read. The website was untouched by this review correction; its earlier checks were not rerun.
These results cover the reviewed implementation linked above. The feature remains unreleased; this verification did not reopen issues,
change project-board completion status, or rerun the hosted conformance job.
