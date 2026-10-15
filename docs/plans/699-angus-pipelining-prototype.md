# Angus PIPELINING prototype

Implementation plan, 2026-10-14. Start with the [Angus contribution guide](../research/699-angus-contribution-conventions.md) and use it again for the final diff review. The [posted proposal](699-angus-pipelining-proposal.md) remains the scope agreement; this file tracks the local experiment. [Results and reproduction commands](../research/699-angus-pipelining-prototype-results.md).

**Current status: two separate prototypes, awaiting a direction decision.** The reader-thread Angus integration can stall under valid-command TLS pressure. A standalone SSLEngine client avoids the reproduced stall without reconnecting; it is not yet an Angus transport replacement. Bounded reconnect-and-sequential recovery has also been tested separately, not added to Angus. The sections below retain the sequence of investigations. No upstream publication is authorized.

The [repeated-measurement round](../research/699-angus-tls-stall-rate-measurements.md) is now recorded separately: no stalls in fifty ordinary pipelined connection windows (740 messages, including Postfix and Exim), versus six of ten under the established pressure configuration. Other boundary cases and shared Exim key-update rejections are documented there. This distinguishes tested conditions; it does not estimate a production failure rate or resolve the recovery-policy decision.

The [bounded recovery spike](../research/699-angus-tls-recovery-and-io-models.md) subsequently recovered nine confirmed stalls, then reused each new sequential connection. It also checked no replay after content and a strict one-retry limit. Recovery remains test-side, with caller-owned timeout scheduling; default timeout-executor cleanup, public events and integration policy still need work.

The separate [SSLEngine experiment](../research/699-angus-sslengine-prototype.md) is now complete: eighteen matching pressure trials completed with the engine, versus nine stalls with the existing reader-thread client; sequential controls passed all eighteen. Fragmented-I/O and failure checks also completed as expected. The engine demonstrated continued input processing while writes were blocked, without reconnecting. This proves the tested approach in a standalone client; a production Angus TLS adapter remains unimplemented. The [reuse survey](../research/699-angus-reusable-tls-io.md) records external alternatives and their integration costs. The existing upstream patch and user-edited public-comment draft were left unchanged.

The user authorized committing #699 planning and research to SJM on 2026-10-15. These documents stay out of both Angus patches. Raw results, local harnesses, downloaded references and private Maven artifacts remain under the excluded `.local/angus-pipelining` directory. Keep reproducible tests and their running instructions in the Angus patches. Draft communications in the chat first; nothing is published automatically.

The SSLEngine experiment now has its own Angus worktree and a portable, opt-in JUnit entry point. Its patch changes test code/resources only, plus the fixture's copyright exclusion. It does not inherit the reader-thread production changes. The [engine report](../research/699-angus-sslengine-prototype.md) records packaging and validation separately from the original measurements. PR drafts: [reader-thread integration](699-angus-pipelining-prototype-pr-draft.md) and [SSLEngine experiment](699-angus-sslengine-prototype-pr-draft.md). The user-edited progress-comment draft is retained unchanged and is not ready to post without reconciling the later experiments.

## Scope and baselines

Opt-in MAIL FROM / RCPT TO pipelining, disabled by default. All replies must be processed before DATA or BDAT. No chunk scheduling changes, BINARYMIME, automatic replay, new runtime dependency, asynchronous public API or connection pool.

| Checkout | Branch | Starting commit |
| --- | --- | --- |
| `C:\workstation\angus-mail-pipelining-baseline` | detached upstream master | `a7bd3fed964d9c9bc2981d4b2c9867358453c571` |
| `C:\workstation\angus-mail-pipelining-prototype` | `213-pipelining-prototype` | `a7bd3fed964d9c9bc2981d4b2c9867358453c571` |
| `C:\workstation\angus-mail-sslengine-prototype` | `213-sslengine-prototype` | `a7bd3fed964d9c9bc2981d4b2c9867358453c571` |
| `C:\workstation\simple-java-mail-pipelining-integration` | `codex/699-pipelining-integration` | `ff73530421bd1c4766f7c3232d766705d61244c9` |
| `C:\workstation\simple-java-mail-pipelining-baseline` | detached SJM control, alignment profile only | `ff73530421bd1c4766f7c3232d766705d61244c9` |

Keep new private Maven repositories and logs under `.local/angus-pipelining` in the main SJM checkout; the old `target` evidence directory was removed between turns. Compare runs against the same aligned dependency graph. SJM's production Angus 2.0.5 pin stays unchanged. Existing Mail/Activation 2.2 upgrade and old-binary compatibility concerns remain separate.

## Stages

1. **Conventions and isolation.** Write the guide first. Create worktrees, retain exact commits, build the unmodified control, inspect NioSmtpClient's ordering, capability, validation and failure cases.
2. **Reader-helper experiment.** One helper per enabled envelope. It reads immutable complete replies while the sender retains the transport monitor. Reserve capacity before writes. Start with 32 commands / 32 KiB groups, an oversized command alone. Test concurrent drain, socket abort, original failure preservation, interruption and joining with plain SMTP, STARTTLS and implicit TLS. Record limitations before expanding the socket design.
3. **Envelope behavior.** Add protocol-specific Boolean property and fresh capability checks. Share command builders and response classification; validate final commands. Drain before RSET; close on 421, EOF, timeout or malformed replies. Preserve MAIL rejection as primary and all existing recipient/reporting semantics. Never replay.
4. **Subclass and SJM experiment.** Add provisional protected `sendEnvelope()` and `isPipeliningCompatible()`. Exact stock transports permit pipelining, subclasses opt in. All callbacks stay on the sender thread. Move SJM size preparation to the common envelope hook and track every response-bearing command in order. Preserve parameters, receipts, cancellation and pool ownership.
5. **Verification and handoff.** Focused protocol/security/lifecycle tests; JDK 11/17/21 Angus CI-equivalent lanes; relevant SJM suites; baseline/prototype SpotBugs comparison; copyright and generated Javadoc inspection. Small optional reused-connection latency comparison for 1/10/100 recipients, with helper overhead and no CI timing thresholds. Review every file against the guide.

## Required invariants

- The helper must not acquire the transport monitor, call subclass hooks or update delivery state. It only reads and publishes complete replies and can abort the physical socket on terminal failure.
- Stop and join before DATA, BDAT, RSET or reuse. Outstanding replies cannot escape into a later transaction.
- Capability permission belongs to the physical connection and current TLS state, never the outer pool.
- Partial delivery does not imply a broken connection. I/O/protocol breakage does. Preserve Jakarta exceptions, recipient arrays and TransportListener events.
- The outer pool remains the only connection-pooling owner. No Angus/Nio implementation types enter public SJM batch results.
- Tests cover legacy and opted-in subclasses, final-command CR/LF checks, unchanged content and TLS/security behavior.

## Deliverables and progress

- [x] Contribution guide written before prototype code.
- [x] Isolated worktrees and private-repository baseline/prototype package builds. Full baseline verification is not green; see the separate DNS findings in the results.
- [x] Reader-helper experiment and bounded reproducer, including TLS 1.3 key updates.
- [x] Actual-transport TLS follow-up: valid key-update/reuse cases pass; the existing write timeout below TLS bounds the adversarial stall and allows reader cleanup.
- [x] Compare valid commands under TLS 1.3 pressure against unmodified and prototype-sequential controls, with restricted buffers, frequent key updates and reused connections.
- [x] Repeat specified configurations with per-connection denominators, one-variable comparisons, and real Postfix/Exim controls; retain explicit rejections as well as successful runs.
- [ ] General TLS forward-progress/abort requirement resolved. Valid STARTTLS traffic can stall only in the pipelined comparison. Timeout-only handling is not a complete compatibility solution; bounded reconnect/sequential fallback is an additional design option to investigate.
- [x] Envelope semantics and subclass hooks, with focused wire tests.
- [x] Separate SJM integration diff, ordered command tracking and two enabled wire tests.
- [x] Reproducible results report covering the reader experiment, envelope implementation and separate SJM integration, with limitations and logs.
- [x] Envelope latency comparison, explicitly scoped to the tested conditions and not evidence that the TLS concern is resolved.
- [x] Per-file diff review, separate patch exports and local [draft explanation](699-angus-pipelining-prototype-pr-draft.md). This is not a feature PR ready to publish.
- [x] Local feature hygiene review and generated Javadoc inspection; unresolved protocol and full-CI limitations remain documented.
- [x] Full SJM default-on/default-off comparison, path counts, unchanged assertions and suite timing; temporary measurement hooks removed afterward.
- [x] Actual-transport TLS follow-up and clean-artifact checks on JDK 11/17/21. Existing timeout behavior is now distinguished from the standalone fixture's wrapper order.
- [x] Local-only research exclusions and fresh separate Angus/SJM patch exports. No communication posted.

Offer a more isolated sequential implementation if maintainers prefer it. The
observed SIZE/AUTH differences also support a narrower option: track fresh
PIPELINING permission separately and review legacy capability-cache cleanup in
its own change. Neither alternative has been implemented. Keep release-readiness
test expansion behind agreement on the current direction.

The SJM pool characterization found a separate existing policy mismatch:
ordinary partial submission exceptions invalidate a healthy connection, with
pipelining either on or off. The physical Angus delegate remains reusable.
Record this for the later SJM outcome-versus-health work; do not fold a pool
policy change into the upstream Angus contribution.

Completion requires safe flow control/cleanup, preserved tested delivery semantics, a latency benefit and an Angus-style diff. Compilation alone is not completion. Publishing a comment or PR remains a separate step.

## Concerns to carry through implementation

The helper is not an independent reader at the TLS layer: processing a TLS 1.3 KeyUpdate can acquire the same output lock held by a blocked write. A read timeout does not time out acquisition of that Java lock. The actual Angus write-timeout wrapper below TLS does abort the blocked write in the tested cases. This establishes bounded failure and cleanup when configured, not successful forward progress or a portable guarantee for every custom socket arrangement.

Continue envelope semantics, subclass hooks, SJM integration and controlled tests locally. Collect design choices, observed limitations and evidence in the results report, and present those together with the implementation. An `SSLEngine` or other nonblocking TLS approach remains a candidate to investigate, not a silent replacement of Angus's socket infrastructure. Do not disable TLS 1.3/key updates, weaken TLS validation, or assume the 32 KiB grouping bound guarantees a TCP window. Do not claim production readiness while the TLS progress and abort concerns remain unresolved.
