# SMTP transport ownership characterization

Date: 2026-10-05. Target: unreleased 10.0.0. Production baseline: `codex/10.0.0` at `a5d8511ce44aa732ece452586a3520f309d39444`.
Provider: Angus Mail 2.0.5. The original pass added tests and recorded evidence without changing production behavior.
The findings below describe that baseline. The [correction verification record](726-managed-angus-resource-verification.md) records the
2026-10-06 implementation follow-up under existing closed issues, rather than a new released-version bug ticket.

This follows the resource-ownership and custom-factory findings in the [Angus capability parity research](ANGUS_CAPABILITY_PARITY_RESEARCH.md).
It is not another protocol implementation, a performance benchmark, or a proposal to wrap every Angus property in public API.

## Findings

### Write-timeout resources multiply with physical connections

Simple Java Mail's default Session timeout enables a 60-second write timeout. With no supplied scheduler, four simultaneously open,
managed SMTP connections create four distinct scheduled executors and four scheduler workers after EHLO writes.
Successful writes remove their cancelled timeout tasks. Normal connection close shuts down each socket-owned scheduler, including
when closing its underlying socket throws. This is avoidable per-connection overhead, not evidence that normal socket closure leaks threads.

Closing a tracked raw socket through SJM's abort action does not itself shut down the wrapper's scheduler. Subsequent transport close does.
Any future shared-scheduler ownership must preserve that full cleanup boundary rather than treating abort as completed disposal.

An object-valued `mail.smtp.executor.writetimeout` supplied through `withProperties(Properties)` already works: four managed connections
share one scheduler. Three concurrent asynchronous pooled sends also share one worker. Closing transports or the pooled Mailer leaves
the caller-owned scheduler usable. An actual Angus timeout interrupts the controlled blocked-write fixture without closing another
socket using the same scheduler.
Stopping that scheduler too early causes later writes to fail before writing their payload.

### Caller-selected socket factories can be abandoned

SJM disables fallback for its own tracked socket factory. With a caller's custom factory, it preserves the supplied fallback setting;
absence retains Angus's `true` default.

| Failure path | Observed behavior |
| --- | --- |
| Plain custom factory throws an ordinary `IOException`; fallback absent or `true` | A default-factory connection submits the synthetic message successfully; the chosen factory failure is discarded. |
| Plain factory fails; fallback `false` | No replacement connection is opened; the caller sees the original failure. |
| Configured factory class cannot load | The same fallback distinction applies. |
| Custom factory throws `SocketTimeoutException` | Failure is preserved without fallback, regardless of the fallback setting. |
| Custom implicit-TLS factory throws while wrapping its connected socket | Absent/`true` fallback opens a replacement connection; `false` preserves the chosen factory failure. |
| Custom STARTTLS factory throws while wrapping the existing SMTP connection | Both fallback settings fail without replacement; the assigned socket is closed. |

Implicit-TLS fallback still attempts TLS: these tests do not establish a plaintext downgrade or successful implicit-TLS submission
under alternative trust. The replacement peer closes immediately, proving the extra connection and changed failure identity without
altering JVM-wide TLS defaults or trusting an external server.

### Initial implicit-TLS factory failure leaves its socket open

The implicit-TLS factory receives an already-connected socket. When it throws before returning a wrapped socket, Angus's acquisition
path does not close that original socket. After the failed send and Mailer close, the captured socket is still open. This occurs with
fallback disabled as well as enabled.

SJM cannot dispose of that connection through the ordinary transport path: `SocketFetcher.getSocket(...)` never returned it, so
`SMTPTransport` never acquired its handle. The tests explicitly close the captured socket afterward to avoid leaking their own fixtures.
They prove missing deterministic cleanup, not how long an unreachable socket survives garbage collection in every JVM.

This is narrower than "TLS failure leaks": STARTTLS wrapping failures close their assigned connection correctly. It also does not
claim that every TLS-handshake or certificate-validation failure has this defect.

## Coverage and evidence

- [Write-timeout ownership tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpWriteTimeoutOwnershipCharacterizationTest.java):
  default resources, supplied-scheduler sharing, concurrent pooled sends, abort/close ordering, failed close, cancelled tasks,
  blocked writes, stopped schedulers, and non-positive timeout values.
- [Socket-factory fallback tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpSocketFactoryFallbackCharacterizationTest.java):
  managed/custom factory ownership, instance/class failures, explicit fallback, timeout exceptions, implicit TLS, STARTTLS,
  original failure identity, and connected-socket cleanup.
- Related existing provider suites: `AngusMailTransportAdapterTest`, `AngusMailTransportLifecycleAdapterTest`,
  `AngusSocketAbortTest`, and `AngusCancellationBoundaryTest`.

The original tests characterized defects as well as healthy behavior. Passing those characterization tests did not mean the
fallback and acquisition-cleanup defects were corrected. The accepted follow-up below updates the assertions to the corrected contract.

Verification completed on both a real JDK 11.0.16.1 and JDK 21: 92 test cases across six suites per lane, with no failures, errors,
or skips. This comprises 25 new characterization cases and 67 existing provider cases. The same fallback and cleanup findings
reproduce on both JDKs. License-header cleanup, the coding-guide pass, 160-column Java checks, whitespace checks, and local report
links also passed. These were characterization results before the implementation follow-up, not verification of its corrections.

Reproduction, after selecting the desired JDK:

```powershell
mvn -q -pl modules/simple-java-mail -am `
  '-Dtest=SmtpWriteTimeoutOwnershipCharacterizationTest,SmtpSocketFactoryFallbackCharacterizationTest,AngusSocketAbortTest,AngusMailTransportLifecycleAdapterTest,AngusCancellationBoundaryTest,AngusMailTransportAdapterTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.javadoc.skip=true' '-Dlicense.skip=true' test
```

The test-only socket/executor introspection relies on private Angus 2.0.5 fields; production code does not use reflection for this.
All SMTP peers are loopback-only, with synthetic content and no live accounts. No whole-reactor verification, live-email test,
benchmark, or SpotBugs run is claimed for this characterization pass.

## Accepted follow-up

The implementation corrects these findings in SJM's managed Angus integration without copying the provider's SMTP/TLS implementation:

- Omitted custom-factory fallback becomes false; explicit true/false and compatible locks remain supported. The cancellation-tracked factory still
  forbids fallback, with a conflicting lock rejected before network activity.
- Thin SSL decorators close the connected socket after a failed handoff. Checked/unchecked failures retain their identity; close failures are suppressed.
  Instance and class factories retain their selection and SSL type. Public class-factory access is lazy and respects module exports.
  The guard also preserves Angus's post-handshake trusted-host gate and the original factory selected for each connection; it must not hide
  `MailSSLSocketFactory` behind a generic SSL subtype. Loopback trust regressions cover allowed/rejected hosts without weakening hostname checks.
- Each owned Session/protocol shares a stable timeout controller and one lazy daemon worker while connections are live. Failed setup and full disposal
  release connection references; raw abort and healthy lease return do not. The last release stops its generation, and a later connection can restart it.
- Private probes rebind owned resources; selected clustered Sessions own their controllers. Application schedulers, caller-owned Sessions,
  alternate providers and CustomMailer remain outside this new ownership.

Existing characterization assertions were changed into regressions for this contract. Additional scheduler/decorator tests and packaged JPMS
fixtures cover generation races, suppression, laziness and module-access remedies. Verification results are retained in the linked correction record;
the earlier 92-case characterization run above does not prove the later implementation passed full verification.

Released-version migration was checked against tag `9.3.5`: its factory configuration does not set socket-factory fallback, leaving Angus's default
in effect. Migration guidance therefore documents a real changed default, not a correction to an intermediate unreleased API.

These findings do not justify a new split-timeout API, another public scheduler option, or a claim of expanded SMTP protocol breadth.

Relevant provider sources:

- [Angus SMTP timeout and socket-factory documentation](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html).
- [WriteTimeoutSocket at 2.0.5](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/WriteTimeoutSocket.java).
- [SocketFetcher at 2.0.5](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java).
