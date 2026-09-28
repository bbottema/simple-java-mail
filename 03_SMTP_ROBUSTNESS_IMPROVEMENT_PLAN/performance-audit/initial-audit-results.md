# Initial mail-send performance audit

28 September 2026. Investigation for [#749](https://github.com/bbottema/simple-java-mail/issues/749),
part of [#722](https://github.com/bbottema/simple-java-mail/issues/722), targeting 10.0.0.

This records the original, pre-optimization audit. See the [implemented follow-up and fresh
measurements](inspection-optimization-results.md) for the current result.
The later [provider-discovery follow-up and final decision](provider-discovery-results.md)
complete the audit: retain the implemented optimizations and current SIZE behavior, without
adding performance controls. Recommendations below describe the original investigation stage.

## Conclusion

There is measurable overhead, but a broad switch to disable inspection would be premature.
The largest isolated problem is the byte-by-byte scan of exact/protected content. A temporary
buffer-based implementation retained the checks and cut roughly 5.8 ms from exact EML and
6.0 ms from DKIM-signed sends in a matched local comparison. That is the first optimization
to develop and verify, not a reason to ask applications to give up the checks.

SIZE has a different cost profile: it repeats MIME output for composed messages, including
attachment reads and provider discovery. Counting already finalized exact content is much
cheaper. Keep SIZE and content inspection separate in any eventual API discussion. No public
API or production behavior changed during this audit.

## What was measured

- Production baseline: `0d100a2051fe4e35c83a66e8c90d8354801adbe5` on `codex/10.0.0`.
- Windows 11 build 22631, Intel i7-11700K, eight cores / sixteen logical processors.
  This is a shared developer workstation, not a dedicated benchmark machine.
- Oracle JDK 21+35, `-Xms512m -Xmx512m -XX:+UseG1GC`; no coverage agent or live mail.
  This is the locally installed initial JDK 21 build, not a claim about every later JDK update.
- Jakarta Mail API 2.1.5 and Angus 2.0.5. Reactor/test classpath, not a minimal packaged
  application; classpath-sensitive costs need a packaged-runtime follow-up.
- In-process SubEthaSMTP sink bound to loopback, no TLS/authentication, no retained messages
  or inserted Received headers. Sender and fixture counters are separate.
- One reusable Mailer per scenario; streaming sequential batches or eight pooled send workers,
  at most 32 outstanding sends. Binary attachments are 256 KiB; their MIME messages are larger.
- Four comparison variants, each in three independent JVMs with rotated order. Each scenario
  has two measured batches after warm-up: 2,000 small messages or 60 larger messages per batch.
  There are 288 measured batches and **156,960 measured sends**, excluding warm-up.
- Forty warm-up sends per scenario and per sequential connection. Large-message results are
  stable; small-message samples still show warm-up/runtime variation. Do not read tiny differences
  as regressions or use these throughput figures as a production promise.

The [harness instructions](../../tools/performance/README.md) describe the counters, artificial
bypasses, loopback sink and reproducible commands.

## End-to-end comparison

Median elapsed batch milliseconds divided by messages; six batches per cell. These are not
CPU milliseconds. The omitted-check variants are intentionally unsafe experiments with known-safe
synthetic content, not proposed behavior.

| Sequential workload | Current implementation | Omit SIZE | Omit content inspection | Omit both |
| --- | ---: | ---: | ---: | ---: |
| Small composed email | 3.83 | 3.15 | 3.83 | 3.09 |
| 256 KiB memory attachment | 13.80 | 11.87 | 14.04 | 11.66 |
| 256 KiB warm file attachment | 14.92 | 11.45 | 13.93 | 11.66 |
| Exact EML with 256 KiB attachment | 16.93 | 17.00 | 8.45 | 8.25 |
| Per-send DKIM, 256 KiB attachment | 29.20 | 28.59 | 20.98 | 20.05 |
| Memory attachment plus application size limit | 15.57 | 13.57 | 15.67 | 13.28 |

The exact-message SIZE difference is within run variation; omitting SIZE did not show a useful
end-to-end improvement there. Its component cost is small but not zero. Ordinary encoded content
does not pay the same inspection cost as exact/protected content: encoded attachment bodies are
not scanned as though they were raw 8-bit bytes.

Baseline batch ranges include 3.46–4.43 ms for small messages, 12.93–14.59 ms for memory
attachments and 16.63–17.53 ms for exact EML. All ranges, including outliers, are retained in
the [summary](results/2026-09-28/ablation-summary.csv); none were silently discarded.

With eight pooled workers, baseline elapsed batch time per message was 0.69 ms for small email,
2.48 ms for memory attachments and 2.62 ms for exact EML. Those are throughput measures, **not
individual latency**: their median batch p95 latencies were 25.2, 82.8 and 81.4 ms, respectively,
including approximately 16.0, 40.3 and 43.9 ms mean queue time. An application keeping 32 requests
outstanding creates that queue. Pooling overlaps work; it does not make inspection disappear.

## Where the work goes

### Exact and protected content: scanning and copies

JFR recorded 241 content-inspection Java execution samples for the exact-message scenario;
221 samples ended in `inspectBody` or `ByteArrayInputStream.read`. SIZE had 14 samples.
The DKIM scenario had 237 inspection samples, 105 protection samples and 13 SIZE samples.
These are sampled Java stacks, not exact CPU shares; native samples and fixture work are separate.

The source matches the profile: `AngusContentNegotiation` serializes finalized content into a
new buffer, copies it to an array, inspects MIME structure and reads body bytes one at a time.
The array-backed input stream synchronizes each read. Omitting inspection saved about 964 KiB
of sender allocation per exact email in the end-to-end run.

The follow-up experiment changes only that read loop to consume buffers, preserving the existing
byte checks and line state. It does not disable SIZE, alter receipt expectations or change the
message. A separate matched comparison used three JVMs per variant and two batches of 1,000
small or 40 large emails:

| Workload | Matched baseline | Buffer-based prototype | Reduction |
| --- | ---: | ---: | ---: |
| Exact EML | 17.18 ms | 11.35 ms | 34% |
| DKIM-signed email | 28.93 ms | 22.98 ms | 21% |

All measured sends produced accepted receipts and matching sink/observer counts. This is a
proof of benefit, **not production correctness approval**. The prototype leaves full-message
copies in place and allocates an extra buffer, including for small unencoded bodies. Ordinary
message timings did not improve. A production implementation should avoid that unnecessary
small-body allocation and must pass the existing malformed-input, encoding, line-boundary,
exact-byte and protected-content regressions.

### SIZE: repeat output, not expensive arithmetic

Continuous component measurements amplify CPU work without SMTP waits. Each operation has
nine samples across three JVMs, with 300 ms warm-up and 500 ms measured blocks. Prepared MIME
is reused for inspection/counting; these costs are **not additive end-to-end accounting**.

| Operation | Payload | Wall ms/operation | Sender CPU ms/operation, approximate |
| --- | --- | ---: | ---: |
| Content inspection | Small composed | 0.0036 | 0.0022 |
| Content inspection | Memory attachment | 0.0084 | 0.0045 |
| Content inspection | Exact EML | 6.20 | 4.07 |
| SIZE measurement | Small composed | 0.66 | 0.38 |
| SIZE measurement | Memory attachment | 2.16 | 0.90 |
| SIZE measurement | Exact EML | 0.24 | 0.13 |
| MIME output to a counter | Memory attachment | 1.72 | 0.93 |
| Render and sign with DKIM | Memory attachment | 11.34 | 6.18 |

For ordinary MIME, the SIZE operation includes another serialization/encoding pass. Merely
writing that attachment's MIME to a counter already costs most of the SIZE time. By contrast,
exact content is already serialized; SIZE mostly scans its bytes for transmitted line endings.
The counter sink deliberately does not simulate socket writes or copy already serialized arrays.

The stream counters make the extra I/O concrete:

| 256 KiB composed attachment, per email | Stream opens | Attachment bytes read |
| --- | ---: | ---: |
| Current implementation | 3 | 528,384 |
| Omit SIZE | 2 | 266,240 |
| Current implementation plus application size limit | 4 | 790,528 |

Each added pass costs one complete attachment read; the remaining 4 KiB is the MIME encoding
probe. Removing content inspection did not remove those encoded-attachment reads. Exact EML
has no attachment-source reads during sending because its bytes are already retained in memory.
DKIM also measures finalized bytes after signing rather than reopening the attachment for SIZE.

The application maximum and the SMTP server maximum protect different contracts. Their counts
cannot simply be merged if provider conversion changes the submitted representation.

### Repeated provider discovery is also visible

Allocation stacks repeatedly pass through `jakarta.mail.util.StreamProvider.provider()` and
`FactoryFinder`, including from MIME output, header parsing and MIME-part construction. SJM's
per-send `MailTransportAdapterResolver` discovery appears too, but it is not the only source.
For example, small composed emails allocate about 423 KiB on sender threads; omitting their
SIZE pass reduces this by about 94 KiB, despite their tiny bodies.

Investigate this on a packaged application classpath before choosing a remedy. A library must
not silently pin a JVM-wide provider or cache across incompatible classloader/provider contexts.
This observation does not justify disabling SMTP safeguards or pretending all allocation comes
from Simple Java Mail's new code.

### CPU, waits and allocation limits

- Windows thread CPU counters in this run advance in 15.625 ms units. End-to-end values were
  noisy and sometimes zero for brief bursts; the raw CSV retains them, but they are not reliable
  per-email CPU estimates. Continuous component runs improve resolution; they still include
  scheduling effects and are not precision microbenchmarks.
- JFR separately identifies fixture work, client socket waits and executor parks. Park/wait totals
  overlap across threads, include idle warmed resources and omit events below the 1 ms threshold.
  They must not be subtracted from wall time as a complete accounting equation.
- JFR allocation samples locate stacks; their weights are not used as per-scenario byte totals.
  Startup/cross-recording sample weights can represent work outside a recording window. The
  unprofiled thread counters supply the reported allocation amounts.
- Recorded GC time was 0–13 ms per measured batch in the main comparison. GC was not the main
  elapsed-time cost here, although allocation may matter under different heaps or concurrency.
- File content was in the warm filesystem cache. TLS, WAN latency, external attachment services,
  multiple recipients, S/MIME/OpenPGP variants and cold application startup were not measured.
  DKIM uses the existing DNS-free test-domain convention; no DNS/key-publication cost is included.

## Recommendation and next implementation scope

Accepted by the maintainer. The subsequent implementation and fresh comparisons are recorded
in the [inspection optimization follow-up](inspection-optimization-results.md). The measurements
below and above remain the original baseline, not claims about the optimized implementation.

1. Optimize the existing exact/protected body inspection, keeping every check and byte-preservation
   guarantee. Start with the demonstrated per-byte stream overhead and unnecessary temporary copies;
   do not introduce a new configuration setting.
2. Re-run the same workload after regression verification. Check a packaged runtime and the repeated
   StreamProvider/adapter-discovery paths before attributing their entire cost to SIZE.
3. Then reconsider **SIZE separately**, especially for large or expensive-to-reopen attachments.
   A SIZE opt-out would remove the reliable local oversize preflight, automatic declaration and
   measured size receipt fact; server rejection and ordinary MIME output would remain.
4. Do not offer a blanket content-inspection bypass on this evidence. It can remove malformed/raw
   header and body checks, capability requirements, binary/NUL and DATA-line validation. Legacy
   content permission is not such a bypass and must not be repurposed. TLS/REQUIRETLS remain outside
   any performance-control discussion.

The API decision stays open while these internal savings are developed. The current evidence
supports **optimization first, no new public switch yet**, not a conclusion that SIZE is free or
that every deployment can afford extra content reads.

## Evidence and reproduction

- [Main environment](results/2026-09-28/ablation-environment.json), [288 raw batches](results/2026-09-28/ablation-samples.csv),
  [summary and ranges](results/2026-09-28/ablation-summary.csv).
- [Buffer experiment environment](results/2026-09-28/bulk-environment.json), [48 raw batches](results/2026-09-28/bulk-samples.csv),
  [summary](results/2026-09-28/bulk-summary.csv).
- [Component environment](results/2026-09-28/component-environment.json), [180 raw blocks](results/2026-09-28/component-samples.csv),
  [component summary](results/2026-09-28/component-summary.json).
- [Profile environment](results/2026-09-28/profile-environment.json) and [sampled stack attribution](results/2026-09-28/profile-attribution.txt).
  The four original `.jfr` files remain under `target/mail-send-performance/profile-749/fork-1-baseline`.

```powershell
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName repeated-749 -Forks 3 -Samples 2 -Messages 2000 -LargeMessages 60 -Warmup 40
./tools/performance/run-mail-send-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName bulk-749 -SkipBuild -Forks 3 -Samples 2 -Messages 1000 -LargeMessages 40 -Warmup 40 `
    -Variants @('baseline','bulk-inspection') -Scenarios 'small-batch|memory-batch|exact-batch|dkim-batch'
./tools/performance/run-content-cost-audit.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName component-749-v2 -Forks 3
```

Use new run names when reproducing; the scripts reject existing output directories. Early smoke
failures, an interrupted calibration and the first component run with a logging-configuration
error are excluded, not mixed into these results. The retained evidence above contains only
complete measurement runs. At this initial checkpoint, the audit was still uncommitted;
later implementation and acceptance are recorded in the follow-up reports linked above.

Verification: the focused Java 11 reactor build passed all nine harness correctness tests. A
final smoke run exercised all twelve scenarios with each of the five variants, checking success,
receipt expectations and sink/observer counts. PowerShell syntax and documentation links pass;
the new Java sources were checked against the coding guide and its 160-character line limit.
`mvn license:remove` completed. There are no production-source changes or generated license
headers. This test-only audit did not rerun the full application reactor, website or live-mail tests.
