# Provider-discovery follow-up

Follow-up to the [content-inspection optimization](inspection-optimization-results.md), under
[#749](https://github.com/bbottema/simple-java-mail/issues/749). Implementation and the final
no-new-API decision were accepted on 28 September 2026 for the unreleased 10.0.0 line.

## Scope and ownership

`MailProviderDiscovery` retains the submission, lifecycle and probe adapter factories for each
thread-context class loader. It still invokes their constructors or JPMS provider methods on
every operation. Transport-instance matching, ambiguity checks, generic fallback and
operation-specific abort actions remain in their existing resolvers. Adapter instances and
transport facts are not cached.

Factories belong to a `ClassValue` attached to a JDK-cached SPI proxy class defined in that loader.
The class is only an identity/lifetime anchor: no proxy instance is created. A global map with
weak loader keys would not suffice, because cached provider classes can retain that same
loader through the map values. The short concurrent-map discovery section publishes only a
complete immutable factory list; construction and adapter calls take place outside it.
If the loader cannot see the SPI or cannot supply that class, ordinary uncached discovery
preserves the original lookup behavior instead of introducing a cache-specific failure.

Registrations are fixed for a loader's lifetime. Changing service registrations within a
running loader is not supported; a replacement loader receives independent discovery. Failed
discovery is retried rather than publishing a partial list. A failing constructor/factory is
invoked again by the next operation, not stored as a permanently failed adapter.

MIME entry points no longer perform a redundant `StreamProvider.provider()` lookup when their
Session/message has already been constructed. Conversion Session creation still translates
missing-implementation failures into the existing helpful diagnostic and retains the cause.

No global provider property, context-loader mutation, Angus pinning, transport cache, new
dependency or application configuration is introduced. The [API expansion guide](../../API_EXPANSION_WORKFLOW.md)
does not require builder/property/Spring/CLI additions for this internal optimization. The
send ownership and concurrency infographic remain unchanged; this cache does not coordinate
send state or hold its initialization lock while constructing/calling application adapters.
The architecture decision is recorded in [ADR 0007](../../docs/adr/0007-provider-neutral-mime-boundary.md).

## Remaining upstream work

Jakarta Mail 2.1.5 still performs repeated discovery inside static MIME utilities and some MIME
constructors. Session itself already retains its selected StreamProvider. This change removes
SJM's redundant calls; it does not claim to fix every lookup performed by Jakarta Mail.

The upstream [StreamProvider documentation](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/util/streamprovider)
recommends caching. [Jakarta Mail #861](https://github.com/jakartaee/mail-api/issues/861) proposes
a loader-scoped fix for repeated MimeUtility discovery. Its status was checked during the
investigation: open, not a released implementation. No upstream change is part of this work.

## Verification and measurements

The discovery comparison retains the
optimized content inspector in both variants and compiles the same discovery/conversion
classes with identical compiler settings and one private overlay classpath entry per variant.
Neither variant changes content checks, SIZE behavior or SMTP fixtures. The reference sources
come from `0d100a2051fe4e35c83a66e8c90d8354801adbe5`.

### Discovery counts and loader lifetime

After one warm-up operation, 100 repetitions produced these service-resource enumeration
counts on the packaged runtime. Counts are not timing results or counts of physical disk reads.

| Operation | Before | After |
| --- | ---: | ---: |
| SJM submission adapter discovery | 100 | 0 |
| SJM lifecycle adapter discovery | 100 | 0 |
| SJM probe adapter discovery | 100 | 0 |
| Email-to-MIME conversion with a reused Session | 200 | 100 |
| Jakarta `MimeMessage.writeTo` | 200 | 200 |

The unchanged static `MimeUtility.encodeText` and `StreamProvider.provider()` calls still each
produced 100 lookups. Session's retained
provider getter produced none. This separates the fixed SJM calls from remaining upstream work.

A separate diagnostic created 100 application loaders, each defining its own Angus submission
adapter class and populating all three caches. After restoring the thread context and dropping
the application references, all 100 loaders were collected. This is observed cleanup evidence,
not a timing-sensitive GC assertion added to CI.

### Matched loopback runs

28 September 2026, Windows 11 / i7-11700K / JDK 21+35, 512 MiB G1 heap, packaged Maven runtime
dependencies plus the harness/sink/logging dependencies. No provider override, profiling,
verification or other active Java test workload ran alongside these timings. The same old,
idle CLI daemon remained present, untouched; it was not stopped for this audit.

Three JVMs per variant, rotated order, three samples per scenario per JVM: 2,000 small sends
or 100 attachment/exact/signed sends per measured batch, 100 warm-up sends, 256 KiB attachments.
This is **77,400 measured loopback sends in 90 batches**, excluding warm-up. All receipts and
server counts passed the harness checks. Pooling used eight workers with 32 outstanding sends.

Median elapsed batch milliseconds per email; pooled values are aggregate throughput costs,
not individual future latency or CPU time:

| Workload | Before | Cached discovery | Sender allocation before → after |
| --- | ---: | ---: | ---: |
| Small composed, sequential | 1.343 | 1.122 | 356.8 → 287.4 KiB/email |
| Small composed, pooled | 0.418 | 0.383 | 356.9 → 287.2 KiB/email |
| Memory attachment, sequential | 9.054 | 8.927 | 760.0 → 690.4 KiB/email |
| Exact EML, sequential | 8.713 | 8.700 | 1057.0 → 1025.8 KiB/email |
| DKIM, sequential | 17.515 | 17.492 | 6539.4 → 6469.6 KiB/email |

Small sequential median elapsed time fell about 16.5%; the ranges still overlap (1.251–1.810
ms before versus 1.001–1.537 ms after). Small pooled medians fell about 8.2%, with ranges
0.380–0.452 versus 0.326–0.387 ms. The attachment/exact/DKIM timings overlap substantially;
do not claim a meaningful speedup there. Allocation fell by approximately 69–70 KiB per
ordinary composed send and 31 KiB per exact send. This is allocation churn, not retained heap.

Sender CPU counters do **not** establish an overall CPU improvement: their pooled median
increased in this run even as elapsed time and allocation fell. Windows counter granularity,
JIT and scheduling make these a separate observation, not interchangeable with wall time.
No CPU-saving percentage is claimed. Absolute timings from earlier runs are not directly
comparable: this comparison uses its own matched overlays and warm-up/sample settings.

A separate, warmed single-thread diagnostic measured only discovery plus adapter construction,
consuming each result. Across five alternating blocks, median elapsed cost was 113.7 microseconds
per uncached lookup versus 0.094 microseconds cached. Thread CPU readings were also much lower
for cached discovery, but remain coarse Windows counters. Blocks used 5,000 uncached or 500,000
cached operations so the faster case was measurable. This supports removing discovery work;
it does not turn the pooled end-to-end CPU readings into a measured saving or establish a
production throughput ratio. No Maven/test workload ran alongside this diagnostic.

The final Java 11 verification rerun covers cache isolation, concurrent cold discovery,
per-operation construction, retry after lookup/construction failure, invisible-SPI fallback,
stateful adapters, transport-instance matching, ambiguity, missing-implementation diagnostics
and both classpath/JPMS provider consumers (including a static JPMS provider factory).
The full Java 11 non-live library lane also passed, including Spring/starter, MIME, pooling,
observer, cancellation, SIZE and protected-content regressions. The clean modern-JDK non-live
reactor passed with **1,702 tests, zero failures/errors and one intentionally skipped manual
CLI performance test**, including CLI daemon/concurrency tests, packaging and Javadocs.

The coding-guide pass kept the shared cache small, documented its loader-lifetime constraint
beside the code, and retained matching/dispatch in the existing resolvers. No new interface,
configuration default or transport synchronization was added. Generated license headers are
removed after verification.

Reproduce the matched run after compiling the harness and exporting the runtime classpath
as described in [the runner guide](../../tools/performance/README.md):

```powershell
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName discovery-comparison -SkipBuild -PackagedClasspath `
    -RuntimeClasspathFile modules/simple-java-mail/target/audit-runtime-classpath.txt `
    -Variants @('discovery-reference', 'baseline') -Forks 3 -Samples 3 `
    -Messages 2000 -LargeMessages 100 -Warmup 100 `
    -Scenarios 'small-batch|small-pooled|memory-batch|exact-batch|dkim-batch'
```

Retained evidence: [environment and class fingerprints](results/2026-09-28/discovery-environment.json),
[all 90 measured batches](results/2026-09-28/discovery-samples.csv),
[median/range summary](results/2026-09-28/discovery-summary.csv),
[resource counts and loader cleanup](results/2026-09-28/discovery-resource-counts.txt) and
[isolated discovery blocks](results/2026-09-28/discovery-isolated-costs.csv).

## Final SIZE/API decision

Keep the current SIZE behavior and the internal optimizations; add no opt-out or opt-in API.
The maintainer accepted this decision on 28 September 2026: no known application workload
justifies this level of fine-tuning now. The earlier SIZE-only opt-out recommendation is
superseded, not deferred implementation scope for #749.

Measurement, automatic declaration where supported, advertised-limit preflight and receipt
facts remain unchanged, including measurement when SIZE is not advertised. Content/encoding
negotiation, binary and injection checks, exact/protected-byte handling, TLS/REQUIRETLS, DSN
requirements and independently configured application limits also remain intact. No automatic
threshold or "fast send" mode is introduced.

The prior large-component measurements found roughly 10–13 ms of SIZE work per 5 MiB ordinary
attachment and 36–45 ms per 20 MiB attachment, on this workstation with warm file caching.
They are isolated stage costs, not a guaranteed end-to-end gain from a future switch. Batch
size scales absolute saved work; there is no universal break-even number. The measurements
remain a baseline for a future real-world performance problem, not a reason to add speculative
controls. Early rejection can avoid transmitting a message that the server would reject;
successful synthetic sends do not measure that benefit.
