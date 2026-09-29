# Content-inspection optimization follow-up

Follow-up to the [initial audit](initial-audit-results.md), tracked in
[#749](https://github.com/bbottema/simple-java-mail/issues/749). The optimization was accepted
on 28 September 2026 for 10.0.0.
This report covers the first, content-inspection change; the later discovery fix is documented
separately in [provider-discovery results](provider-discovery-results.md).
That follow-up also records the final decision: retain current SIZE behavior, with no opt-out
API or further fine-tuning. The intermediate recommendation below is historical.

## What changed

Only `AngusContentNegotiation` changes production behavior internally:

- Its private serialization buffer is inspected directly, without making a second whole-message
  array through `toByteArray()`. Only written bytes are inspected, never unused buffer capacity.
- Finalized bodies are scanned directly in that array. Ordinary unencoded bodies are read in
  8 KiB blocks, replacing the previous BufferedInputStream's buffer and per-byte calls.
- A body-local accumulator preserves line length across blocks and stops at the same first
  unsupported byte. No inspection state is shared between sends or cached on a reusable Email.

Header validation still uses the JDK UTF-8 decoder. MIME structure checks, binary/NUL/overlong-line
rejection, capability requirements, legacy permission, exact/protected bytes, SIZE accounting and
security policies are unchanged. Encoded attachment streams are still skipped by the content
inspector. This does not remove their separate SIZE serialization pass.

No public API, property, Spring/CLI integration, provider-discovery policy, Session mutation or
connection lifecycle changes. The API expansion guide's corresponding additions are not needed;
its exact/protected-content boundary checks still apply. The concurrency diagrams and infographic
remain accurate.

## Comparison method

The `reference` variant compiles the committed pre-optimization inspection class from
`0d100a2051fe4e35c83a66e8c90d8354801adbe5` into a private audit overlay. The baseline uses the
current production source, also compiled into an overlay. Both use the same compiler settings
and classpath-entry count; overlays are packaged when the rest of the runtime is. This avoids
accidentally charging extra directory-based provider discovery only to the reference. Standalone
overlay compilation omits Maven's nullability instrumentation for both inspection classes.
Other classes, messages, settings and fixture behavior are shared. Source and compiled-class
hashes identify the uncommitted implementation under test.
Experimental classes never replace reactor output or production artifacts.

Separate runs inspect classpath effects:

1. The original reactor test classpath, including class directories.
2. The same classpath with directories packaged into JARs, preserving dependencies and service
   resources. This isolates packaging from dependency selection.
3. Maven's production runtime dependency list, plus the audit classes, SMTP fixture and logging.
   Production modules are packaged; test-only JUnit, Mockito and Surefire dependencies are absent.

These remain loopback workstation measurements, not Internet-delivery or customer throughput
promises. The initial report's CPU-counter, sampling, caching and fixture caveats still apply.
No timing run is performed concurrently with Maven verification or a profiled workload.

## Results

28 September 2026; the same Windows 11/i7-11700K/JDK 21+35 workstation as the initial audit.
Jakarta Mail API 2.1.5, Angus 2.0.5, 512 MiB G1 heap, 256 KiB attachments. Each end-to-end
scenario/variant has six measured batches across three JVMs, with rotated variant order:
1,000 small emails or 40 attachment/exact/signed emails per batch, 40 warm-up sends, and the
same eight-worker/32-outstanding pooled setup. The four unprofiled comparisons total **141,120
measured sends in 504 batches**, excluding warm-up, smoke tests and profiling.

### The internal optimization works on a packaged runtime too

Median elapsed batch milliseconds per email, **not CPU time or async request latency**:

| Packaged production-dependency workload | Before | Optimized | Change |
| --- | ---: | ---: | --- |
| Small composed, sequential | 1.817 | 1.701 | Small, overlapping ranges; no claim of improvement |
| Memory attachment, sequential | 9.204 | 9.212 | Essentially unchanged |
| File attachment, sequential | 9.811 | 9.630 | Small, overlapping ranges |
| Exact EML, sequential | 14.878 | 9.000 | 39.5% less elapsed time |
| DKIM-signed, sequential | 23.805 | 17.929 | 24.7% less elapsed time |
| Exact EML, pooled | 2.206 | 1.531 | 30.6% less aggregate elapsed time |

Exact sequential batches ranged from 14.63–15.29 ms before and 8.93–9.24 ms after; DKIM from
23.56–24.14 ms before and 17.51–19.19 ms after. Sender allocation for exact sends fell from
1,443,005 to 1,083,174 bytes/email: roughly one 351 KiB serialized-message copy removed.
This is reduced allocation churn, not a claim that peak heap fell by that amount per email.

The original reactor-style classpath also improved: exact sends went from 15.793 to 9.816 ms,
DKIM from 25.731 to 19.303 ms. These fresh matched comparisons use the same runner version;
do not attribute differences from the initial audit's absolute baseline solely to this change.

### Isolated inspection cost

Each cell is the median of nine timed blocks across three JVMs, on the packaged runtime.
Prepared MIME is reused, so these figures cannot be added to other stages as send-time accounting.

| Payload | Inspection elapsed, before → after | Thread CPU, before → after | Allocation, before → after |
| --- | ---: | ---: | ---: |
| Exact EML | 6.110 → 0.329 ms | 4.069 → 0.217 ms | 739,716 → 380,576 bytes |
| DKIM-signed | 6.153 → 0.359 ms | 4.557 → 0.205 ms | 751,776 → 391,928 bytes |

Current ordinary inspection takes about 0.0032 ms for small composed mail and 0.0085 ms for the
composed memory-attachment case. Encoded attachments are not scanned by that path.
CPU counters remain coarse and noisy; the repeated blocks amplify work but do not make these
precision CPU estimates. In particular, unchanged SIZE components also showed CPU variation.

The new profile contains direct `BodyInspection.inspect` samples rather than the old synchronized
per-byte input-stream loop. MIME parsing, serialization buffers and signing still do real work;
this is not zero-copy sending. The fixture is a substantial remaining contributor: the exact
scenario contains 203 Java execution samples attributed to the SMTP sink versus 26 to content
inspection. Native waits and overlapping idle-worker parks are separate. Those sample counts
are not a complete CPU-time breakdown and are not directly comparable with differently sized
recordings from the initial audit. Do not use these loopback timings to size a production fleet.

### SIZE remains separate

With the optimized inspector and packaged runtime:

| Sequential workload | Normal SIZE behavior | Experimental SIZE omission | Median difference |
| --- | ---: | ---: | ---: |
| Small composed | 1.701 ms | 1.536 ms | 0.165 ms; batch ranges overlap |
| Memory attachment | 9.212 ms | 8.397 ms | 0.815 ms |
| File attachment | 9.630 ms | 8.765 ms | 0.865 ms |
| Exact EML | 9.000 ms | 8.759 ms | 0.241 ms; batch ranges overlap |
| DKIM-signed | 17.929 ms | 17.855 ms | 0.074 ms; within observed variation |

Ordinary 256 KiB attachments still require 528,384 read bytes with SIZE, versus 266,240 without:
two full attachment reads plus a 4 KiB encoding probe versus one read plus that probe. This
optimization does not change those counts. Already-finalized exact content does not reopen an
attachment source. A separate application maximum-size check keeps its own contract and pass.

### Provider discovery is not just a class-directory artifact

For the optimized small-message case, replacing directories with JARs reduced elapsed time from
2.472 to 1.746 ms; using the production-dependency classpath yielded 1.701 ms. Allocation fell
from approximately 406 KB to 385 KB and then 368 KB. Packaging matters, but discovery remains.

A **separate diagnostic experiment** set `jakarta.mail.util.StreamProvider` to the exact class
registered in the Angus dependency, `org.eclipse.angus.mail.util.MailStreamProvider`, only in
the fresh audit JVM. No SMTP/content checks were disabled. Compared with the unpinned packaged
runtime, optimized small-mail time fell from 1.701 to 1.053 ms and allocation from 367,706 to
91,978 bytes; memory-attachment time fell from 9.212 to 7.753 ms and allocation from 782,181 to
152,803 bytes. With that diagnostic selection, the measured SIZE-omission difference was about
0.040 ms for small mail and 0.338 ms for the memory-attachment case; the extra attachment read
still remained when SIZE was enabled.

JFR traces locate repeated Jakarta `StreamProvider.provider()`/`FactoryFinder` discovery in
MIME output, encoding, header parsing and construction. Simple Java Mail's separate transport
adapter lookup also appears and was not pinned by this experiment. Allocation amounts above
come from thread counters, not JFR sample weights, whose start-of-recording effects remain
unsuitable for per-scenario totals. The experiment narrows attribution; it does **not** justify
setting a JVM-wide provider from a library or promise those savings under other classloaders,
providers, operating systems or deployments. No caching or provider-selection change is shipped.

## Intermediate recommendation

1. Keep this internal optimization. It removes the largest demonstrated inspection cost without
   giving up any check or byte-preservation guarantee.
2. Do not add a general content-inspection bypass. Ordinary inspection is already small in this
   workload, and finalized inspection is now much cheaper. A bypass would discard real validation.
3. A **SIZE-only advanced opt-out, defaulting to the current enabled behavior**, is the only
   performance-control candidate supported by this audit. Its strongest case is the extra full
   read of expensive/reopened attachment sources, not a universal latency improvement. The
   experimental omission loses local advertised-limit preflight, automatic SIZE declaration and
   measured receipt facts; server rejection, normal MIME output, content/security checks and
   independently configured application limits remain. Exact/signed workloads have little
   demonstrated elapsed-time benefit. Agree the semantics and API separately before implementing.
4. Treat provider-discovery reuse as a separate, provider/classloader-aware optimization question.
   Some of this work belongs to Jakarta Mail, not a Simple Java Mail performance switch. The
   diagnostic system property must not become an automatic fix.

No new public control was implemented at this checkpoint. The later
[final decision](provider-discovery-results.md#final-sizeapi-decision) retains the current
SIZE behavior without a new API; the discovery follow-up was implemented and accepted.

## Verification

- Seventeen new inspection cases exercise streamed and serialized bodies: 8 KiB edges, 998/999-byte
  lines, split CR/LF, short reads, first-failure priority, successful/failed stream closure,
  original I/O causes, unused buffer capacity, malformed UTF-8 and unchanged finalized bytes.
- Java 11: the focused rerun passed 31 inspection tests and all 15 result-handling tests. The
  broader library attempt had exposed one existing synchronization-test timeout before its
  CustomMailer callback; that path never calls this inspector. The test now supplies `mail.from`,
  matching its async counterpart so Message-ID hostname lookup cannot consume the two-second
  synchronization window. Assertions and production behavior were not weakened.
- A Java 11 packaging retry and early modern build attempts hit Windows JAR locks. The other
  journal session was left alone. Cleaning module outputs in a separate Maven invocation and
  then verifying produced a successful **full JDK 21 non-live reactor**: 1,689 tests, zero
  failures/errors, one skipped. Classpath/JPMS consumers, Spring/starter, CLI and Javadocs passed.
  The initial Java 11 attempt is not being reported as a clean full-lane pass.
- A smoke run covered all twelve scenarios and six audit variants (72 scenario/variant cases),
  with sink/observer counts and receipt facts checked. Packaged runtime comparisons also check
  those invariants on every measured batch.
- Coding-guide review, PowerShell syntax checks and `mvn license:remove` completed. No generated
  license headers, dependency changes or website edits are part of this work.
  Existing staged research, conformance-plan edits and Journal work were preserved.

## Evidence and reproduction

All measurements below are retained under `results/2026-09-28`; raw JFR recordings and generated
runtime/overlay JARs remain ignored under `target/mail-send-performance`.

- [Matched classpath environment](results/2026-09-28/optimized-matched-environment.json),
  [216 batches](results/2026-09-28/optimized-matched-samples.csv), [summary](results/2026-09-28/optimized-matched-summary.csv).
- [Packaged test-classpath environment](results/2026-09-28/optimized-packaged-test-environment.json),
  [24 batches](results/2026-09-28/optimized-packaged-test-samples.csv), [summary](results/2026-09-28/optimized-packaged-test-summary.csv).
- [Packaged runtime environment](results/2026-09-28/optimized-runtime-environment.json),
  [216 batches](results/2026-09-28/optimized-runtime-samples.csv), [summary](results/2026-09-28/optimized-runtime-summary.csv).
- [Provider diagnostic environment](results/2026-09-28/optimized-provider-diagnostic-environment.json),
  [48 batches](results/2026-09-28/optimized-provider-diagnostic-samples.csv), [summary](results/2026-09-28/optimized-provider-diagnostic-summary.csv).
- Isolated components: [reference environment](results/2026-09-28/optimized-components-reference-environment.json),
  [raw blocks](results/2026-09-28/optimized-components-reference-all-samples.csv),
  [summary](results/2026-09-28/optimized-components-reference-summary.json);
  [optimized environment](results/2026-09-28/optimized-components-current-environment.json),
  [raw blocks](results/2026-09-28/optimized-components-current-all-samples.csv),
  [summary](results/2026-09-28/optimized-components-current-summary.json).
- [Profile environment](results/2026-09-28/optimized-profile-environment.json) and
  [stack attribution](results/2026-09-28/optimized-profile-attribution.txt).

```powershell
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName optimized-matched-749 -SkipBuild -Forks 3 -Samples 2 -Messages 1000 -LargeMessages 40 -Warmup 40 `
    -Variants @('reference','baseline','no-size')
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName optimized-runtime-749 -SkipBuild -PackagedClasspath `
    -RuntimeClasspathFile modules/simple-java-mail/target/audit-runtime-classpath.txt `
    -Forks 3 -Samples 2 -Messages 1000 -LargeMessages 40 -Warmup 40 -Variants @('reference','baseline','no-size')
./tools/performance/run-content-cost-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName optimized-components-current-749 -Forks 3 `
    -ClasspathFile target/mail-send-performance/optimized-runtime-749/baseline-classpath.txt `
    -Payloads 'small|memory|exact|dkim' -Operations 'content-inspection|size-measurement'
```

Use fresh run names; existing directories are rejected. The [runner guide](../../../tools/performance/README.md)
documents the classpath export, packaging and diagnostic-provider options. Do not benchmark
alongside Maven or another session's CPU-heavy work. Clean only the module outputs if retaining
audit evidence in the root `target` directory.
