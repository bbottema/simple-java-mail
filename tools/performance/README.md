# Local mail-send audit

This manual harness investigates [#749](https://github.com/bbottema/simple-java-mail/issues/749).
It sends synthetic content only to an in-process, loopback-bound SubEthaSMTP sink. It never sends
real email. Normal test runs execute small correctness checks, not performance workloads.

```powershell
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' -RunName initial-749
```

The runner builds the reactor test classes, obtains their actual classpath from the harness test,
and runs three fresh JVMs per variant in rotated order. Each JVM warms every scenario, then records
three batches. Defaults are 2,000 small emails or 200 attachment/exact/signed emails per measured
batch, 256 KiB binary attachments, eight pooled send workers and at most 32 outstanding sends.
The sequential case uses the public simple-batch API; warm-up and measurement share its connection.
Pool/JIT warm-up happens before measurement; there are no sleeps or artificial network delays.

Results, environment metadata, logs and generated overlays go under
`target/mail-send-performance/<RunName>`. Existing run directories are rejected. No benchmark
threshold is enforced in CI. Use `-Scenarios 'small-.*|exact-.*'` to narrow a run and `-SkipBuild`
only when the test classes and exported classpath are current. Use another name for each run.

## Comparison variants

- `baseline`: the current production inspection source, compiled without any behavioral edits.
- `no-size`: an isolated copy of `ManagedAngusTransport` skips the pre-MAIL SIZE call. The
  message-size receipt field becomes absent and no automatic SIZE parameter is sent.
- `no-inspection`: an isolated copy of `AngusContentNegotiation` skips body/header inspection
  for these known-safe synthetic inputs. Envelope checks and other negotiation remain.
- `neither`: both omissions.
- `bulk-inspection` (explicit selection only): an internal optimization experiment that reads
  body bytes in blocks while keeping the existing byte checks and cross-buffer line state.
  It retains SIZE and content checks; this historical prototype uses the same committed source
  as `reference`, rather than patching the subsequently optimized production loop.
- `reference`: the pre-optimization inspection class from commit
  `0d100a2051fe4e35c83a66e8c90d8354801adbe5` (override with `-ReferenceCommit`). All other
  classes remain current. Compare with `baseline` to measure the actual production change.
- `discovery-reference`: the pre-cache discovery and MIME-entry-point classes from the reference
  commit, retaining the optimized content inspector. When this variant is selected, those same
  classes are compiled into **every** variant's overlay with matching compiler settings and
  classpath-entry count. Compare only with a fresh `baseline` in the same run; this isolates
  provider discovery from the earlier content-inspection optimization. All overlay class hashes
  are recorded, alongside the original inspection fingerprints.

`-PackagedClasspath` puts each class-directory entry into a JAR while preserving classpath order,
dependencies and service resources. This isolates packaging effects, not dependency count. For
a production-dependency comparison, first export the runtime classpath in a reactor build:

```powershell
mvn -pl modules/simple-java-mail -am test-compile dependency:build-classpath `
    '-DincludeScope=runtime' '-Dmdep.outputFile=target/audit-runtime-classpath.txt' '-Dlicense.skip=true'
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName packaged-runtime-749 -SkipBuild -PackagedClasspath `
    -RuntimeClasspathFile modules/simple-java-mail/target/audit-runtime-classpath.txt
```

This keeps the application's optional SJM modules and runtime dependencies, plus the audit
classes and loopback server/logging dependencies. It excludes JUnit, Mockito, Surefire and other
test-only dependencies. It is still a synthetic application, not a customer deployment or a
shaded executable JAR. Each run records its classpath, reference commit and inspection source/
compiled-class fingerprints. No provider is pinned, cached globally or selected through a
JVM property by the default packaging comparisons.

For a separate diagnostic experiment only, `-DiagnosticStreamProviderClass <implementation>`
sets Jakarta Mail's stream-provider system property in that audit JVM. It isolates the cost of
repeated service discovery; it is **not** a proposed library change or a general application
recommendation. Verify the implementation against the dependency's service registration first.
A library must not silently pin providers across application/classloader boundaries. The flag
and selected class are recorded in the environment metadata; it does not bypass content checks
or pin Simple Java Mail's separate transport-adapter discovery.

These are unsafe measurement experiments, not a proposed public API. They must never be used
for real mail. Copies are compiled into private output directories prepended **only** to audit
JVM classpaths; the script never edits or replaces production sources or reactor artifacts.
Source-pattern checks fail if the implementation changes instead of silently benchmarking a
different bypass. The observer verifies expected SIZE facts and successful receipts; the sink
and observer counts must both match the measured batch.

The follow-up runner compiles the inspection class for **every** variant, including baseline,
with identical `javac --release 11`/Lombok settings. Each gets one extra classpath entry, packaged
as a JAR when requested. This avoids giving only the reference an extra directory for provider
discovery to search. Other classes use the normal build output; these standalone overlay classes
do not receive Maven's nullability instrumentation. The original initial-audit runner used the
reactor inspection class directly for baseline; compare fresh matched runs, not absolute timings
from the two runner versions.

## Read the measurements carefully

`wall_ms / messages` is elapsed batch time per email, not async request latency. `p50_ms`,
`p95_ms` and `p99_ms` come from individual send outcomes. Async latency includes bounded queueing;
`mean_queue_ms` reports that part separately. The identical lightweight observer is present in
every variant. Sequential wall time includes final connection close, amortized over the batch.

`sender_cpu_ms` and `sender_allocated_bytes` cover the caller and observed send-worker threads.
`fixture_cpu_ms` covers the sink's workers. `process_cpu_ms` also includes JIT, GC and background
threads. CPU counters have platform-dependent granularity; compare batches, not single sends.
Allocation counters are approximate. Attachment counters count opened streams and bytes read;
exact EML is already in memory, so zero attachment reads does not mean zero content passes.

File scenarios use warm filesystem cache, not cold-disk latency. Emails and their attachment
sources are reusable; conversion and per-send signing remain in the measured path. DKIM uses
the existing `supersecret-testing-domain.com` fixture convention to avoid DNS/public-key lookup.
Its results cover preparation/signing, not DNS or external key validation. SubEthaSMTP parses
SMTP DATA but does not retain messages or add Received headers. TLS, real network latency and
downstream delivery are deliberately absent. Recheck machine activity and variance before
inferring production benefit.

## Profile separately

```powershell
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName profile-749 -SkipBuild -Forks 1 -Samples 3 -Variants baseline `
    -Scenarios 'small-batch|memory-batch|exact-batch|dkim-batch' -Profile
```

Each scenario records a JDK Flight Recorder `profile` recording after initial warm-up. Profiling
times are **not** mixed into the unprofiled comparison. `AuditFlightRecording` can summarize JFR
files using the exported test classpath. CPU samples and sampled allocation weights are estimates;
socket/monitor/park totals include only events lasting at least 1 ms and may overlap across threads.
The recording also includes each sequential batch's short connection-local warm-up.
Do not use JFR allocation weights as exact per-scenario totals: sampled weights, including the
first sample when recording starts, can account for allocations outside the recording window.
Use the unprofiled thread allocation counters for amounts and JFR stacks to locate allocation sites.

`ContentCostAudit` complements these end-to-end runs by repeating conversion, content inspection,
SIZE counting and MIME output separately for timed blocks. It consumes every result and records
thread CPU/allocation after warm-up. This amplifies CPU work beyond the coarse Windows counter
resolution. It reuses prepared MIME for inspection/counting, so those figures are isolated stage
costs, not additive send timings. After building the harness, launch it with:

```powershell
./tools/performance/run-content-cost-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' -RunName components-749
```

The script uses the exported classpath and matching heap/logging options. Do not run it alongside
the end-to-end audit. The CSV uses nanoseconds per operation; it is intentionally separate from
the end-to-end summary. Use `-Payloads 'small|memory|exact|dkim'` and
`-Operations 'content-inspection|size-measurement'` to narrow the components. `-ClasspathFile`
can select a completed end-to-end run's `reference-classpath.txt` or `baseline-classpath.txt`,
including its packaged runtime and matching inspector overlay.
Use `summarize-content-cost-audit.ps1 -RunDirectory <run>` for the raw-block CSV and median/range
summary; missing or duplicate samples are rejected.

Method references: [JFR configurations](https://docs.oracle.com/en/java/javase/21/jfapi/flight-recorder-configurations.html),
[thread CPU counters](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html),
[allocation counters](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.management/com/sun/management/ThreadMXBean.html).

See the [investigation results and final decision](../../docs/research/smtp-performance/provider-discovery-results.md#final-sizeapi-decision)
for the completed investigation and decision. The internal optimizations were accepted;
current SIZE behavior is retained without a performance-control API. The harness remains
available to investigate a concrete workload, not as an ongoing benchmark gate.
