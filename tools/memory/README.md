# Mail-send memory pressure runner

This manual workbench sends synthetic email to discard-only, loopback SMTP servers and measures what remains in the sender after work drains.
It exercises the production API; its fixtures live in test sources and add no published dependency or runtime option.
The server runs in a separate JVM so retaining received messages cannot be mistaken for sender growth. Wiser normally retains received messages;
the discard-only SubEthaSMTP fixture uses the same SMTP engine without keeping that mailbox.

Run it with **PowerShell 7** and a **JDK 21** installation:

```powershell
./tools/memory/run-mail-send-pressure.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' -RunName first-pressure
```

The isolated run requires `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` to be unset in the invoking process. These variables can silently
change heap/agent settings, and the Java launcher can echo their contents into diagnostic output. The runner rejects them before launching Java or Maven,
reports only the variable name and leaves the environment untouched. Supply the workbench's documented parameters for its JVM settings.

The default run executes `async`, `observers` and `churn` sequentially. Each profile gets a fresh client JVM, 1,000 warm-up sends and 10,000 measured
sends in batches of 1,000. The client heap is fixed at 256 MiB. Four send workers and at most 16 outstanding sends bound the driver's own retained work.
Within a profile, the client stays alive between batches so accumulation across completed operations remains visible.

The runner builds the matching Maven reactor and reads its smoke test's actual classpath. It records the classpath, dependency and compiled-class hashes,
source hashes, dirty-diff fingerprint, Git revision, JDK, selected options and process IDs under `target/mail-send-memory/<RunName>`.
Classpath directories are copied into run-local JARs, preserving resource contents and entry order. This freezes the client and server classes while
another development session rebuilds the reactor. The runner checks for changes during copying and records both original and effective classpaths.
Both each copied directory and the complete group are checked, so a module rebuilt after its own copy cannot silently combine with later modules.
Per-class hashes are read from the frozen JAR entries, so later reactor rebuilds cannot change the recorded launch bytecode.
Existing run directories are rejected. Use `-SkipBuild` only after the matching reactor build; stale installed module JARs are unsuitable for this tool.

Open `report.html` for heap graphs, `summary.csv` for changes across measured batches, and each profile's `summary.json` for workload correctness counts.
`all-samples.csv` includes the baseline and final closure checkpoint. `heap-*.txt`, `native-baseline.txt`, `native-diff.txt` and optional `client.jfr`
provide evidence for investigating growth. The exact per-profile JVM arguments are saved in `launch.json`.
The report also compares the first and last measured live histograms in `class-growth.csv`, listing instance and shallow-byte changes per class.
Shallow bytes count the objects themselves; they do not describe the complete object graph retained by those objects.
`all-samples.csv` also includes `jvm_tracked_committed_bytes`, parsed from NMT's Total committed value. It includes the Java Heap category;
the separate graph measures JVM-tracked committed memory, not native-only memory or the operating system's process working set.

## Choose the workload

| Profile | Work exercised |
| --- | --- |
| `sync` | Reused Mailer with synchronous sends |
| `async` | Bounded asynchronous sends without observers |
| `result-only`, `progress-only`, `observers` | Independent and combined observer registration |
| `slow-progress` | Slow progress callbacks and coalescing while SMTP work continues |
| `retry` | Temporary submission refusal followed by retry |
| `cancellation` | Cancellation of work waiting for retry |
| `failover` | Repeated connection failure and recovery across two destinations |
| `churn` | Repeated Mailer creation/closure while another Mailer stays alive |
| `attachment-memory`, `attachment-file` | In-memory or file-backed binary attachments |
| `positive-control` | Deliberate retention of completed Emails, expected to demonstrate growth |

Cancellation runs use concurrent groups of up to `min(MaxInflight, 64)` emails. Every email reaches genuine retry waiting after a temporary server
refusal before cancellation is requested. The loaded checkpoint samples that waiting group; the result records the maximum concurrency actually reached.
Pooled retry/cancellation also exercises disposal acknowledgement before replacement connections are usable. This can dominate the duration of a large
fault workload even on loopback. Start those profiles with smaller counts and inspect their completion rate before choosing a long soak or timeout.

Focused observer/retry runs, with optional bounded JFR:

```powershell
./tools/memory/run-mail-send-pressure.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName observer-and-retry -Profiles result-only,progress-only,observers,slow-progress,retry,cancellation -RecordJfr
```

Run direct transport acquisition by removing the optional batch module from this child JVM's classpath:

```powershell
./tools/memory/run-mail-send-pressure.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName direct-sync -Profiles sync -NoPooling -Messages 2000 -BatchSize 250 -SkipBuild
```

`-NoPooling` records the full and effective classpaths and the excluded entries. It uses SJM's supported direct path, without changing module-loader
state or replacing production classes. Pooled profiles use the ordinary optional batch module.

Large attachments need a smaller message/concurrency budget. This sends 5 MiB file-backed attachments; change `AttachmentBytes` to `20971520` for 20 MiB:

```powershell
./tools/memory/run-mail-send-pressure.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName five-mib-files -Profiles attachment-file -Messages 200 -Warmup 20 -BatchSize 20 `
    -Concurrency 2 -MaxInflight 4 -AttachmentBytes 5242880 -RecordJfr -SkipBuild
```

Use fresh names for the normal and deliberate-retention controls:

```powershell
./tools/memory/run-mail-send-pressure.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName retention-control -Profiles observers,positive-control -Messages 10000 -SkipBuild
```

After the short profiles establish useful baselines, a million-send soak is explicit:

```powershell
./tools/memory/run-mail-send-pressure.ps1 -JdkDirectory 'C:/Program Files/Java/jdk-21' `
    -RunName million-observed -Profiles observers -Messages 1000000 -BatchSize 25000 -TimeoutMinutes 240 -RecordJfr -SkipBuild
```

At most 60 measured batches are allowed, so increase `BatchSize` for long soaks to keep histogram/checkpoint counts practical. Do not combine a million-message run with deliberate retention or huge
attachments as the first experiment. `Messages` counts measured operations, with warm-up additional; retry profiles can invoke SMTP more than once per operation.
`TextBytes` controls synthetic message text, `ObserverDelayMillis` controls the slow callback, and `HeapMiB`, `Concurrency` and `MaxInflight` adjust pressure.
Long soaks remain separate from ordinary JUnit verification.

## Interpret the evidence

Compare equal-work intervals after warm-up. A steady Java live set with a changing process working set can be healthy: JIT compilation, class loading,
GC regions, native arenas and tracing buffers have different lifetimes. Increasing live operation, Email, callback, registration or replay objects after
drained checkpoints deserve investigation even when total byte growth looks small.

The report deliberately applies no universal flatness threshold. It shows changes per completed send; it does not certify the absence of leaks.
The final closure sample is shown separately from workload deltas. Some test-scope references can still be live there, and closing a Mailer does not
necessarily unload every class or release every committed native page.

Checkpoint collection and live histograms can pause the client. These runs measure retention, not production throughput. JFR sampling helps locate
allocation/retention paths but can miss a small leak. Preserve a suspicious run, repeat it in a fresh process, then use a heap dump if retaining paths
are needed. A reproducible library defect should also receive a short deterministic lifecycle regression.

See [JDK memory-leak diagnosis](https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshooting-memory-leaks.html) and
[diagnostic-command impact](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html).

## Bounds and process ownership

Each profile has a controller timeout. Cancellation stops only that launched client and its child server processes; unrelated Java/Maven processes are
never selected by process name. Normal client termination closes its servers. PowerShell 7 supplies argument-safe process launching and process-tree
termination; this runner is intended for the Windows maintainer environment.

JFR is optional and capped at 64 MiB. Client logs have a combined 32 MiB budget, checked twice a second. Each profile's artifacts have a budget of at
least 1 GiB, or twice the configured client heap plus 256 MiB when larger; the controller checks this every five seconds and stops that profile on overflow.
An out-of-memory failure can produce one heap dump, bounded by the configured heap. Checks can overshoot slightly while output is being written.
The workbench keeps failed artifacts and exit information for diagnosis; it never cleans another run or overwrites its files.
The report includes the controller status, each process exit/failure and the last sampled completion count. A profile is complete only when the
process exits successfully and writes its verified workload result; interrupted runs remain visibly partial. Optional operator-stop annotations
explain intentional stops without replacing the recorded failure or exit code. Active client logs can be read while the profile runs.

To regenerate the report after inspecting a failed or interrupted run:

```powershell
./tools/memory/summarize-mail-send-pressure.ps1 -RunDirectory target/mail-send-memory/first-pressure
```

See the [recorded pressure results](../../docs/research/mail-send-memory-pressure-results.md) for the initial pilot, completed workloads,
retention corrections and remaining coverage limits.

The controller's classpath-fencing regression is independent of Maven and SMTP:

```powershell
./tools/memory/tests/test-classpath-freezing.ps1
```
