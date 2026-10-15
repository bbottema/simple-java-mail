# Mail-send memory pressure: retained-memory results

Recorded on 15 October 2026. These are synthetic retention experiments, not throughput benchmarks or proof that every sending path is leak-free.

## Workbench and interpretation

The maintained [memory runner](../../tools/memory/README.md) starts a fresh client JVM for each profile and separate discard-only SMTP JVMs.
It uses the real public Mailer API, normal provider/pool implementations, fresh Emails and bounded outstanding completions. The server streams content
away rather than retaining Wiser's mailbox. No real addresses, accounts or external SMTP services participate.

Every quiet checkpoint uses `jcmd GC.class_histogram` and its live-object byte total. Loaded checkpoints instead report the ordinary heap-usage reading;
they are not guaranteed peaks. JFR, class histograms and Native Memory Tracking remain separate evidence. In particular, NMT's total committed figure
includes Java heap and must not be described as native-only memory or process RSS. Checkpoint collection pauses work, so elapsed run time is not a
production send-rate measurement.

The client uses JDK 21/G1 and a fixed 256 MiB heap. The runs record source/class/dependency hashes, exact JVM arguments and dirty Git state. Reactor
classes are frozen into run-local JARs before starting, so a later build cannot replace their executing code. Local reports and raw artifacts are kept
under ignored `target/mail-send-memory/`; this document preserves the conclusions without committing large recordings or generated reports.

## Completed sustained runs

### Feasibility pilot

The earlier 15 October pilot used JDK 21/G1, a 256 MiB heap, four workers/connections, at most 16 outstanding sends, 4 KiB synthetic text and both
observers. Each fresh client sent 1,000 warm-up plus 10,000 measured emails to a separate discard server. Client/server counts agreed at 11,000.
The normal run's first/last measured live sets were 18,739,048 and 18,778,648 bytes; a positive control retaining completed Emails grew from
24,005,768 to 71,726,016 bytes. Threads stayed at 17 during sending and fell to nine after closure. JFR and histograms were produced successfully.

The pilot's NMT committed categories grew by roughly 48 MiB, mostly Arena Chunk, Tracing and Code, without matching Java live-set growth. The longer
runs below investigated that settling behavior rather than treating a short heap curve as whole-process evidence. Explicit synthetic hostnames
removed a local reverse-DNS stall. Raw pilot evidence remains under `target/memory-review/soak-pilot-local-host/` and `soak-positive-control/`.

### Sustained success profiles

The initial 100,000-message comparison used 2,000 additional warm-up sends, 16 send workers, at most 64 outstanding sends, 16 reused pooled connections
and 4 KiB synthetic text. JFR was enabled. Each row compares the first and last **measured drained checkpoints**, not startup against shutdown.

| Profile | Measured sends | First live heap | Last live heap | Change across 90,000 intervening sends |
| --- | ---: | ---: | ---: | ---: |
| Async, neither observer | 100,000 | 19,384,272 bytes | 19,386,320 bytes | +2,048 bytes |
| Async, result and progress observers | 100,000 | 19,569,360 bytes | 19,570,872 bytes | +1,512 bytes |

Both profiles completed with matching client/server counts. The observed profile delivered 102,000 result callbacks and 1,227,589 coalescible progress
callbacks, including warm-up. No accumulating Email/send-operation population appeared in its first-to-last histogram comparison. Its final NMT total
committed amount was 449,094 KiB, 25,283 KiB below the warm baseline; the earlier feasibility pilot's short native-category increase did not continue
in this run. These are narrow observations, not a universal memory threshold.

Raw run: `target/mail-send-memory/20261015-observed-100k/`.

### Million-send observed soak

The longer run used the same 16-worker/64-outstanding shape, 25,000-message batches and a 256 MiB heap, without JFR. It completed **1,000,000 measured
sends plus 2,000 warm-up sends**, with exactly 1,002,000 server acceptances and result callbacks, 12,596,624 progress callbacks and 16 connections.
The first measured drained checkpoint, at 27,000 completions, contained 17,790,664 live bytes. The last, at 1,002,000, contained 17,792,768 bytes:
**+2,104 bytes across 975,000 intervening sends**. The final after-close checkpoint contained 17,626,688 bytes. Reported client threads stayed at 28
during measured work and fell to eight after closure.

The complete first-to-last histogram comparison shows only small runtime/String/array changes, not accumulating library operation or message objects.
NMT total committed memory finished at 403,874 KiB, 46,597 KiB below the warm baseline. Normal temporary allocations caused much larger uncollected
heap readings during sending; those are not retained memory. The live-set result is strong evidence for this observed pooled-success path, not a
promise about other providers, workloads or arbitrarily long runtimes.

Raw run and generated report: `target/mail-send-memory/20261015-observed-million/`.

## Fault-profile evidence and incomplete runs

A separate slow-progress run completed 1,000 warm-up plus 10,000 measured sends while its progress callback slept 100 ms. It delivered all 11,000
terminal results but only 98 progress callbacks, as expected for lossy latest-state reporting. Its final drained live set was 19,815,104 bytes and
fell to 19,278,472 bytes after Mailer closure. The bounded reporting backlog did not prevent SMTP work from completing.

The first larger retry sweep was **operator-stopped**, not passed: it reached 6,000 completed logical sends with 6,000 real temporary refusals and
6,000 subsequent acceptances. Pooled invalidation/disposal made that workload much slower than successful reuse, and a sequential cancellation driver
would have magnified that cost. The revised driver cancels bounded concurrent groups that have actually reached retry waiting. The stopped run retains
its nonzero exit status and explicit operator-stop reason; its partial curve must not be presented as a completed profile.

Raw run: `target/mail-send-memory/20261015-recovery-pressure/`.

### Completed retry, cancellation, recovery and churn profiles

| Profile | Work completed, including warm-up | First measured live heap | Last measured live heap | After Mailer closure |
| --- | ---: | ---: | ---: | ---: |
| Retry | 1,064 sends, each refused once then accepted | 21,464,936 bytes | 21,536,144 bytes | 19,192,456 bytes |
| Cancellation | 1,064 refused submissions, each cancelled while waiting to retry | 21,392,904 bytes | 21,478,376 bytes | 19,171,160 bytes |
| Failover/recovery | 11,000 accepted sends | 18,124,312 bytes | 18,452,136 bytes | 17,994,744 bytes |
| Mailer churn, result observer | 2,100 accepted sends through newly created Mailers | 19,525,720 bytes | 19,032,256 bytes | 18,910,400 bytes |
| Mailer churn, both observers | 2,100 accepted sends through newly created Mailers | 19,912,824 bytes | 19,436,728 bytes | 19,067,144 bytes |

Retry and cancellation used 16 workers, at most 64 outstanding sends and JFR. Cancellation actually reached 64 concurrent retry waiters; every
cancellation retained its refused receipt, and no cancelled attempt was replayed. Their modest settling growth is visible rather than rounded away.
The histogram comparisons show runtime/String/array changes, not accumulating library operation or message objects.

Failover alternated primary availability ten times and verified five actual recoveries. The primary accepted 5,925 emails and the failover 5,075.
Its first measured checkpoint retained one destination's 16 connections; later checkpoints legitimately retained both destinations' bounded pools
(32 connections), explaining the additional pool/transport objects. This is not evidence that every route topology is bounded, but it is not a
per-message accumulation. Each batch asserts new acceptance at the appropriate destination rather than inferring failover from send completion alone.

Both churn runs kept an anchor Mailer alive throughout, so creating an entirely fresh global engine could not conceal retired-cluster retention.
Each completed one anchor send followed by 2,099 temporary Mailers and their connections; the retained live set decreased over its measured checkpoints. The fully observed
run also started and closed per-Mailer progress dispatchers and delivered 2,100 results and 31,524 progress callbacks. The permanent
actual-Mailer/cluster-retirement regressions provide the complementary ownership assertions.

The positive control deliberately kept 10,000 completed Emails. Its measured live set grew from 23,253,496 to 71,434,720 bytes (+48,181,224 bytes
across 9,000 intervening retained Emails) and remained 71,239,544 bytes after closure. This demonstrates that the workbench detects genuine retained
message growth rather than drawing a reassuring curve regardless of the workload.

Raw runs: `target/mail-send-memory/20261015-retry-cancel/`, `20261015-failover-control/`, `20261015-churn-2000/` and `20261015-churn-observed-2000/`.

## Large attachments

Both in-memory and file-backed attachments were exercised with **both observers enabled**, JFR and a 256 MiB client heap. Each 5 MiB profile completed
110 emails (10 warm-up plus 100 measured), with two workers and at most four outstanding sends. Each 20 MiB profile completed 44 emails (four warm-up
plus 40 measured), with two workers and at most two outstanding sends. Client/server and result-callback counts agreed.

| Attachment profile | First measured live heap | Last measured live heap | Change | After Mailer closure |
| --- | ---: | ---: | ---: | ---: |
| 5 MiB, in memory | 19,011,488 bytes | 19,020,632 bytes | +9,144 bytes | 19,012,864 bytes |
| 5 MiB, file-backed | 18,867,904 bytes | 18,877,536 bytes | +9,632 bytes | 18,872,456 bytes |
| 20 MiB, in memory | 18,890,896 bytes | 18,909,176 bytes | +18,280 bytes | 18,894,616 bytes |
| 20 MiB, file-backed | 18,946,992 bytes | 18,955,608 bytes | +8,616 bytes | 18,955,408 bytes |

The profiles did not retain attachment-sized buffers after completion. Loaded ordinary heap readings were much higher, especially for in-memory
attachments; that includes live outstanding payloads and temporary allocations, not just retained objects. These small byte-budgeted samples do not
establish a long-run large-attachment limit or throughput comparison.

Raw runs: `target/mail-send-memory/20261015-attachments-5mib-observed/` and `20261015-attachments-20mib-observed/`. An earlier unobserved 5 MiB baseline
also passed under `20261015-attachments-5mib/`; its manifest correctly records that neither observer was enabled.

## Direct transport path

Removing the optional batch module from the frozen classpath exercised the real non-pooled implementation. The synchronous and observed-async
profiles each completed 100 warm-up plus 1,000 measured sends and opened 1,100 connections. Their measured live sets changed by +45,488 and +35,072
bytes respectively; after closure they contained 18,796,336 and 18,740,704 bytes. The observed profile delivered 1,100 results and 19,267 progress
callbacks. This checks direct setup/teardown rather than accidentally measuring pooled reuse again.

Raw run: `target/mail-send-memory/20261015-direct/`.

## Verification and limits

All 13 profiles passed the initial small 10-warm-up/100-measured smoke matrix. That validates profile wiring, not sustained behavior. Nine focused
workbench tests additionally cover real concurrent retry cancellation, failed-checkpoint cleanup and the separation between server connection capacity
and retry-key capacity. The matching reactor also passed the dispatcher, actual-Mailer retention and batch regressions (27 tests in total, on real
JDK 11 and JDK 21). The wider lifetime selection passed 321 tests across 32 suites, including the intended isolated direct-runtime launchers.
An initial overly broad selector incorrectly invoked two pool-free helper suites directly on the pooled classpath; their explicit runtime checks
failed. Correcting the selector, without changing those assertions or production code, produced the successful wider run. Early development checks
used the local snapshot dependency; final publication verification is recorded separately below.

The maintained profiles cover normal direct/pooled sends, independent observers, slow progress, retries, cancellation, failover/recovery, Mailer churn,
attachments and deliberate retention. They do not yet provide a long-run matrix for every exact/protected format, lazy batch/open-connection scope,
nested route, rejecting observer executor or indefinitely blocked callback. Existing deterministic tests cover several of those contracts, but that is
different evidence from a sustained soak. Extend the workbench when investigating those lifetimes rather than claiming coverage from a success profile.

The accompanying corrections detach discarded progress notifications and forget fully retired clusters only after disposal is acknowledged.
Permanent tests cover retained executor tasks, replacement/registration races and repeated actual Mailer closure while an anchor remains alive.
Additional weak-reference checks found no retained original Email/content after retry success or cancellation in the checked paths, and no extra
cluster entries after 30 create/close cycles. The [runner guide](../../tools/memory/README.md) describes the repeatable procedure. The manual
workbench complements those short lifecycle regressions rather than replacing them.

## Publication and final integration

[Clustered Object Pool 4.4.0](https://github.com/bbottema/clustered-object-pool/releases/tag/4.4.0) is published to Maven Central. Its
[implementation issue](https://github.com/bbottema/clustered-object-pool/issues/32) and milestone are closed. SJM now depends on that released version,
not the development snapshot. The resolved JAR's SHA-256 is:

```text
55B0EA1E5C5DB0C96DE96578150BAB732CBC311FC1D38301A56592326CD1C4C0
```

The longer pressure runs above used the prepublication candidate. All 13 released production source files match that candidate after generated
license-header/line-ending normalization, and all 19 production classes have identical ordered instructions and signatures. The raw class files are
not binary-identical: debug line numbers and a private synthetic lambda's compiler-generated annotation differ. Do not relabel those earlier
manifests as having used a released dependency.

After publication, the maintained runner rebuilt through its ordinary command, with normal dependency enforcement, and passed **all 13 profiles**
(10 warm-up plus 100 measured sends each). The actual release artifact was confirmed by the hash above. Separate real-JDK-11 `verify` passed the
27 focused workbench/dispatcher/Mailer-retention/batch tests, Javadocs, core-only API checks, classpath/JPMS consumers and third-party provider fixtures.
The ordinary release/convergence checks passed without `-Denforcer.skip`. These are focused integration gates, not a claim that this task reran the
entire non-live reactor or website.

The upstream CI deployment timed out while Sonatype was still `PUBLISHING`; an authenticated status-only check confirmed there was no validation
error. The accepted bundle subsequently published without a second upload. The upstream release guide and CI timeout handling were corrected;
repository version/tag bookkeeping and GitHub release/issue/milestone completion followed actual artifact verification.

Final raw evidence: `target/mail-send-memory/20261015-published-smoke/`, `target/memory-review/published-dependency-enforcement.log`,
`published-java11-verify.log` and `lifetime-regressions-corrected-selector.log`.
