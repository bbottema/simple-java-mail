# Optional OpenTelemetry integration: research and verification

- Tracking: [#762](https://github.com/bbottema/simple-java-mail/issues/762).
- Target: 10.0.0, not released.
- Architecture and production-only review: accepted; final holistic review completed on 2026-10-16.
- Decision: [ADR 0034](../adr/0034-optional-opentelemetry-integration.md).
- Maintained behavior: [telemetry ownership and lifetime](../concurrency/13-mail-send-telemetry.md).
- Implementation: [runtime/API](https://github.com/bbottema/simple-java-mail/commit/55362d1e5c7a8eda3368074143706a8b0ba4f7d6), [Spring/CLI integration](https://github.com/bbottema/simple-java-mail/commit/e0fd0ac62394a652d2d617ba08e6ed9311436b0a).
- Website guidance: [reviewed documentation commit](https://github.com/simple-java-mail/simplejavamail.org/commit/dd758efff549328f72ff0ce99dadd404a6d3bf53).

This record preserves the useful research and dated verification from the completed implementation plan. The ADR owns the design,
the website owns application guidance, and GitHub owns delivery status. Earlier checks below describe their tested revisions, not a claim
that the full reactor and real-server suite were repeated after every API refinement.

## Research findings (2026-10-15)

### OpenTelemetry sources inspected

"Official" referred specifically to repositories maintained by the OpenTelemetry project:

| Source | Inspection and limitation |
| --- | --- |
| [Semantic Conventions](https://github.com/open-telemetry/semantic-conventions/tree/331541c4057c53d133c32efadaa7b656636eda77) | Full, non-truncated tree: 958 paths; no SMTP/mail-specific convention path found. |
| [Java Instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/24316c305a04881e399e28a18cfd65c51577a541) | Full, non-truncated tree: 20,607 paths; no dedicated SMTP/JavaMail/Jakarta Mail instrumentation path found. |
| [Java agent supported libraries](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/) | The inspected list did not identify dedicated JavaMail/SMTP instrumentation. |

These searches inspected mail-related path names, not every source line or the whole ecosystem. They support a bounded absence finding,
not a claim that SMTP has no conventions or third-party instrumentation. The implementation uses SJM-owned instrument names rather than
forcing unrelated messaging semantics onto SMTP.

The primary guidance established the integration boundaries:

- [Library instrumentation](https://opentelemetry.io/docs/concepts/instrumentation/libraries/): library API use versus application SDK/exporter ownership.
- [Java API/context](https://opentelemetry.io/docs/languages/java/api/): capture context across handoff; retire thread-bound scopes before suspension.
- [Error recording](https://opentelemetry.io/docs/specs/semconv/general/recording-errors/): a retried failed child does not require a failed recovered parent.
- [Java SDK](https://opentelemetry.io/docs/languages/java/sdk/): processing, batching, aggregation and export remain application responsibilities.
- [Java agent](https://opentelemetry.io/docs/zero-code/java/agent/): optional JVM instrumentation; coexistence uses public API/context and OTLP, not private agent APIs.

The later histogram review used the [HTTP duration scale](https://opentelemetry.io/docs/specs/semconv/http/http-metrics/), in seconds, from
5 ms through 10 s, extended with 30, 60, 120 and 300 seconds for SMTP setup and rate/retry waits. This is aggregation advice, not a claim
that SMTP follows HTTP semantics. SDK Views can replace the advice; longer durations remain in the overflow bucket.

### Existing SJM boundaries reused

`MailSendAttempt` owns the logical observation and once-only terminal snapshot. `MailSendOperation` already owns admission, execution,
retry suspension/resumption and completion. Telemetry observes those owners rather than adding a state machine or callback dispatcher.
`TransportRunner` identifies actual provider invocation and result capture. `MailSendDiagnosticsRecorder` supplies fixed portable timings
without requiring public connection/submission histories. Spring continues using its shared default-Mailer construction route.

Typed adaptation is limited to `OtelAdapter.wrap(OpenTelemetry)`, returning the opaque core `MailSendTelemetry` handle. Ordinary settings,
properties, locks and defaults remain on core/main APIs. `ModuleLoader` caches a stateless support entry point, never SDKs or bound runtimes.
The initiating Mailer owns telemetry through selected-destination failover and retries. Actual selected endpoint facts remain optional.

## Verification sequence

### Initial complete lanes, 2026-10-15–16

These checks preceded the final property/typed-adapter refinements and remain evidence for their tested implementation:

| Check | Recorded result and local evidence |
| --- | --- |
| Real Java 11 library clean reactor | Passed; `telemetry-java11-complete.log`. Excluded CLI and its JaCoCo aggregator. Main module: 1,833 tests, no failures/errors, one existing skip. |
| Java 21 complete non-live clean reactor with `-Ppublish-cli` | Passed; `telemetry-modern-complete.log`. Included Javadocs, regenerated CLI caches, daemon/process tests and packaged classpath/JPMS consumers. |
| Java 11 post-cleanup focused verification | Passed; `telemetry-final-focused.log`; initial optional module had 24 passing tests. |
| Boot 2.7.18 / Spring 5.3.39 / Java 11 | Passed in the library lane, including integration-bean activation and metadata. |
| Boot 3.0.13 / Spring 6.0.14 / Java 17 | Passed; `telemetry-spring30.log`, with SLF4J 2.0.9. |
| Boot 3.5.16 / Spring 6.2.19 / Java 21 | Passed; `telemetry-spring35.log`, with SLF4J 2.0.18. |
| Real-server conformance | All 34 Postfix/Exim scenarios passed; `target/smtp-conformance/20261015T222159Z-c3b2f976/evidence/summary.json`. |
| Website | 84 checks, TypeScript/Eleventy, clean build and 4,234 local links across 68 pages passed. |
| License cleanup | Actual goal passed; hashes confirmed no changes to 458 main Java sources. |

The initial eight-fork Java 11 run encountered timeouts in existing `ScopedMailSenderTest` and `RecipientDsnSubmissionTest` cases. Both
passed in isolation and the complete two-fork rerun passed. No normal fork settings or test thresholds were relaxed. The deliberate
deadline fixture permits the expected client socket reset, without masking ordinary peer failures.

An earlier license-plugin pass hit Windows' mapped-file write failure and truncated three unchanged sources. Their original contents
were restored before verification. Subsequent cleanup used isolated backups and source-hash comparison; no substantive restoration was
needed in the final license checks.

### Review corrections and API/configuration verification, 2026-10-16

| Revision | Recorded verification |
| --- | --- |
| Coding-guide and public-Javadoc sweep | 80 focused Java 11 tests, consumers and Javadocs passed; `telemetry-style-java11.log`. Java 21 CLI metadata, cache and daemon checks passed. |
| Explicit metric contexts | Four new cases reproduced wrong/missing exemplar context before correction; all five new cases passed after it. Java 11 and Java 21 focused lanes each passed 42 tests, consumers and Javadocs. |
| Factory activation and ordinary module loading | Java 11 focused lane passed 60 tests; Java 21 passed 86. Both public Boolean settings, missing-module behavior and isolated factories were covered. |
| Activation Spring matrix | Eight telemetry/metadata tests passed in each supported Boot 2.7/3.0/3.5 lane, using their recorded Java/SLF4J combinations. |
| Typed foreign-instance adapter | Clean Java 11 lane passed 62 tests; Java 21 passed 68; a separate 17-test CLI cache/daemon lane passed. |
| Adapter and packaging boundaries | Wrong foreign inputs failed compilation, custom implementations of OpenTelemetry's public interface worked, core/main had no direct OpenTelemetry dependency, and packaged classpath/JPMS consumers passed without an SDK/exporter. |
| CLI distribution | Both metadata caches were regenerated through the documented Java 21 `publish-cli` build. The Windows standalone launcher completed a property/option-enabled logging-only send with exit 0; no SDK/exporter was bundled. |
| Adapter license cleanup | Actual goal passed; all 464 main Java source hashes remained unchanged. |

The agent fixture uses the installed application-global instance rather than a competing SDK. JVMs with and without instrumentation
exercise both actual loopback SMTP and opaque CustomMailer callbacks, inspecting public OTLP/protobuf export. Tests do not promise
duplicate suppression for arbitrary vendor instrumentation or inspect private agent internals.

Metric recording now supplies its stored send/submission context explicitly after work scopes close. This preserves SDK-selected
exemplar links without adding per-send metric labels. Unsampled sends still contribute counts and durations.

The adapter revision removed the interim module-owned configuration API and internal CLI facade. Only the optional module interprets
the supplied handle; ordinary construction needs no internal lifecycle interface. Scalar activation and endpoint permission use normal
configuration provenance, locks, Spring metadata and generated CLI options. These unreleased changes need no migration entry.

### Histogram and per-span detail, 2026-10-16

Both real Java 11 clean verification and Java 21 focused verification passed 89 tests per lane, plus consumers and Javadocs. Website
checks, a clean build and all 4,240 internal links passed. The actual license-removal goal left all 297 main Java sources in the three
affected production modules byte-identical.

Tests verify separation of 80 ms and 600 ms measurements, long-wait buckets and application View precedence. All available frozen phase
durations also appear on the logical span, in seconds. Measured zero remains present; unavailable phases are omitted. The existing primary
failure measurement supplies failure location without replacing it with secondary cleanup failure. Coverage includes all eight phases,
both report orders, recovery, unknown attribution and accepted SMTP submission followed by failed direct cleanup.

### Final holistic review and delivery checks, 2026-10-16

The holistic review traced lifetime, exception, context and configuration seams through failover, retry suspension, queued cancellation,
lazy batches and pinned callbacks. No further production correction was identified. A fresh Java 21 focused reactor passed 101 tests,
including SDK/loopback, agent, core API, Spring and CLI cases. Javadocs and packaged classpath/JPMS consumers passed. An isolated clean
website build passed all 84 checks and 4,240 internal links without replacing the concurrently used preview output.

The selective delivery audit additionally found a stale reflective test helper: `EmailHelper.createDummyOperationalConfig(...)` supplied
36 arguments after `OperationalConfigImpl` gained its 37th argument. `MailerImplTest` reproduced the mismatch. The helper now passes
both centralized telemetry defaults explicitly; production code and behavior did not change. The delivery rerun includes that existing
test class as well as the telemetry, Spring OAuth2, packaging and CLI suites:

| Final delivery check | Result |
| --- | --- |
| Java 21 focused reactor `verify` | 118 tests passed with no failures/errors/skips, plus Javadocs and packaged classpath/JPMS consumers; `delivery-java21.log`. |
| Real Java 11 clean focused reactor `verify` | 114 tests passed with no failures/errors/skips, excluding the Java 17+ CLI lane; Javadocs and packaged consumers passed; `delivery-java11.log`. |
| Website checks and isolated clean build | All 84 checks and 4,240 internal links passed; TypeScript, Eleventy and search generation passed; `website.log`. |

The failing pre-correction helper reproduction is retained separately as `legacy-fixture-before.log`; it is not a production-send failure
or an unresolved verification result. These focused delivery checks do not replace the earlier full-reactor/Spring-matrix/conformance dates.

## Reproducing the final focused lane

Use Java 21 and the normal reactor so matching core, main and optional-module classes are built together:

```powershell
mvn -B -ntp -pl modules/open-telemetry-module,modules/spring-module,modules/cli-module -am `
  '-Dtest=MailerImplTest,MailSendTelemetry*Test,Otel*Test,TelemetryAgentCoexistenceTest,MailSendDiagnosticsRecorderTest,MailSendAttemptDiagnosticsTest,PublicApiBoundaryTest,SpringMailSendTelemetryTest,SpringModulePackagingTest,SimpleJavaMailSpringOAuth2ProviderTest,CliMailSendTelemetryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlicense.skip=true' verify
```

Use the workstation's Windows certificate store when local TLS interception requires it; never disable certificate verification.
The recorded broader lanes used real JDK 11 and the normal modern-JDK `publish-cli` reactor separately. Website verification uses
`npm run check`, a clean Eleventy/TypeScript/search build and the internal-link checker. An isolated output directory can preserve a live
preview during that verification.

## Evidence boundaries

These are local results, not hosted CI execution or an SJM release. The final focused runs supplement the earlier full-reactor, Spring
compatibility and SMTP conformance records; they do not imply those complete lanes were repeated at every revision. Temporary logs use
the `sjm-telemetry-adapter-*`, `sjm-otel-resolution-*`, `sjm-otel-holistic-review-*` and `sjm-otel-delivery-*` directories outside the repository.

Tests cover exact receipt/exception identity, scope order, reference retirement, sampling independence and unchanged attachment reads,
SMTP commands and authoritative exact bytes. They are not a universal heap-leak proof for arbitrary application SDK processors/exporters.
No live-email demo, benchmark or SpotBugs run was performed. The shared resource infographic remains accurate: this integration observes
the existing owners and introduces no worker, pool, connection or sending state machine.
