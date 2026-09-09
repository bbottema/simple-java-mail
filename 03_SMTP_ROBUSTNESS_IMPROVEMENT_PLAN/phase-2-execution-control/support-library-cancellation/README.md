# Cancellation in the supporting pool libraries

- Status: Implemented and all three minor releases published on 8 September 2026; Simple Java Mail integration remains separate
- Created: 8 September 2026
- Part of: [SMTP robustness step 3](../03-give-deadlines-and-cancellation-protocol-meaning.md), [Simple Java Mail #726](https://github.com/bbottema/simple-java-mail/issues/726), under [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- Baseline inspected: generic-object-pool 2.4.3, clustered-object-pool 4.0.4, SMTP Connection Pool 4.0.2, Angus 2.0.5
- Scope: Implement and release cancellation in the three supporting libraries; Simple Java Mail integration remains separate

## Why this is useful without Simple Java Mail

A request can disappear while its worker is waiting for a pooled resource: an HTTP client disconnects, a background job is stopped, or a batch is abandoned. That worker should stop competing for capacity. If it is already preparing a resource, the allocator should be able to stop that work too when its underlying operation supports cancellation. Neither case should require shutting down the pool or disturbing another borrower.

The recent waiter-wake-up bug was fixed and released separately. This work adds an explicit per-operation cancellation contract; it is not another patch for that bug.

## Split the guarantees

| Layer | Guarantee to add | Boundary that remains explicit |
| --- | --- | --- |
| Generic Object Pool | Cancel one pending claim and cooperate with resource allocation/reuse preparation | The pool cannot forcibly stop an arbitrary allocator or cancel work after ownership was handed to the borrower |
| Clustered Object Pool | Carry the same cancellation request and remaining time through selection and the selected pool | No cluster-wide shutdown, automatic failover, or fresh timeout at each layer |
| SMTP Connection Pool | Cancel acquisition; offer attempt-scoped transport abort when the physical provider supports it | Ordinary `Transport.close()` is not proof that SMTP I/O can be interrupted |
| Simple Java Mail | Join queue, acquisition and supported transport cancellation while retaining SMTP outcome facts | An accepted email cannot be recalled; a lost final reply can leave an unknown outcome |

The generic and clustered changes can ship independently of Angus. SMTP acquisition cancellation can also be useful without in-flight abort. Document those as different capabilities, not one universal cancellation promise.

## Proposed API direction

Keep ordinary claim methods and return types. Pool acquisition is synchronous today; do not introduce internal executors or future wrappers just to make a claim cancellable. Add one advanced options-based overload per distinct claim operation, not every timeout/argument permutation.

The shared vocabulary is available in Generic Object Pool 2.5.0:

- `ClaimControl`: the caller creates it to retain control over a pending claim. Creating it expresses no intent to cancel; only `requestCancellation()` does that. A request is idempotent; a return value indicating a new request is not confirmation that all work has stopped. Any source/token split remains internal, rather than becoming additional public types.
- `ClaimOptions`: immutable options containing the acquisition time limit and optional `ClaimControl`, supplied through `withClaimControl(claimControl)`. The options retain the live control reference; they do not freeze its request state. No mutable global or per-pool cancellation configuration.
- `AllocationContext`: the allocator-facing, read-only view of cancellation state and the remaining acquisition budget. It lets an allocator react to a request, not issue one. It is not a bag of pool internals or SMTP settings.
- A detachable cancellation registration: cooperative allocators can register a quick wake-up/abort action and remove it before resource handoff or reuse. Registration after a request must not lose that request. Handlers must be non-blocking and must not perform ordinary resource cleanup; isolate handler failures so one cannot prevent the remaining notifications.

Usage available since Generic Object Pool 2.5.0:

```java
ClaimControl claimControl = new ClaimControl();
ClaimOptions options = ClaimOptions.withTimeout(30, TimeUnit.SECONDS)
    .withClaimControl(claimControl);

// On the job's existing worker:
PoolableObject<Connection> connection = pool.claim(options);
if (connection != null) {
    try {
        useConnection(connection.getAllocatedObject());
    } finally {
        connection.release();
    }
}

// From the job's existing stop handler, possibly while claim() is waiting:
claimControl.requestCancellation();
```

`ClaimControl` controls acquisition, not `useConnection(...)` after a successful handoff. A job can share a control across its own pending claims, but unrelated jobs use separate controls. A control's cancellation state is one-shot: it is never reset and rebound to another job.

The separate caller-owned object is needed because `claim(...)` blocks before returning a resource. It does not change the accepted mail-send API: `MailSend.getCancellation().request()` remains secondary API on the immediately returned send operation. Likewise, cancellation of an already-acquired SMTP lease is a separate capability, not an extension of `ClaimControl` beyond handoff.

The advanced claim follows the existing nullable timeout result, throws `CancellationException` when cancellation wins, and retains `InterruptedException` for thread interruption. Existing overloads and their timeout/exception contracts remain compatible. The new options path starts one monotonic acquisition budget at the outermost claim entry, including lock waiting and allocation; delegation passes that running deadline, not a fresh duration. The reusable options object does not itself hold a running attempt. Late completion of non-cooperative code can exceed the budget, but must not hand the caller a resource after timeout/cancellation won.

## Order of work

1. [Generic Object Pool: cancellable acquisition and cooperative allocation](01-generic-object-pool.md).
2. [Clustered Object Pool: propagate one operation across pool selection](02-clustered-object-pool.md).
3. [SMTP Connection Pool: cancellable acquisition and safe lease abort](03-smtp-connection-pool.md).
4. Resume the existing [Simple Java Mail step 3](../03-give-deadlines-and-cancellation-protocol-meaning.md) only for capabilities actually proven by those changes and the provider integration.

Each step has its own review and release boundary. No benchmark work is included.

## Angus is not a scheduling dependency

Do not plan a new Angus PR as the route to delivery, and do not wait for an Angus merge before implementing the generic/clustered improvements. No fork, provider replacement or socket-owning integration is authorized by this planning document either. A bounded local feasibility investigation is part of the SMTP step; implementation of any substantial alternative requires its own review.

Live status checked on 8 September 2026: [Angus PR #210, Preserve leading dots in SMTP BDAT content](https://github.com/eclipse-ee4j/angus-mail/pull/210) is still open, with its one commit from 9 August. Its last activity was assignment to `jbescos` on 18 August. It has no submitted reviews or follow-up comments. The ECA check passed; the [workflow run](https://github.com/eclipse-ee4j/angus-mail/actions/runs/31331040092) remains `action_required`. This is the existing BDAT fix, not a cancellation contribution.

Our loopback characterization proves that `CompletableFuture.cancel(true)` plus ordinary `SMTPTransport.close()` does not stop the blocked Angus send. It does not prove that every supported socket-factory or custom-provider integration is impossible. Investigate those locally, preserve Session/TLS/proxy ownership, and keep full in-flight cancellation gated if no safe integration can be demonstrated.

## Shared invariants and review gates

- Library-controlled cancellation signalling does not wait for a pool-wide lock, provider monitor, allocator callback or physical disposal. Cooperative handlers must honor their non-blocking contract; the library cannot make a non-conforming user handler safe. Terminal completion is separate and must not be published while relevant work still runs unnoticed.
- Each claim has one ownership winner: either return the resource to its borrower, or retain it for safe cleanup. Never both. A stale callback cannot affect the next borrower.
- No allocator I/O under the pool-wide claim lock. Reserve capacity under the lock, prepare outside it, then publish or dispose under explicit ownership rules. Apply this consistently to foreground and core-pool allocation and reuse/release preparation.
- Preserve existing per-pool serialization of allocation/reuse/release preparation unless the maintainer accepts a change. Moving callbacks out of the pool lock does not silently make those callbacks concurrent. Final deallocation already has its own worker and keeps that separate contract. Waiting for any separate allocator gate must itself remain cancellable.
- A request or deadline is not a licence to abandon cleanup. Distinguish removal from reusable capacity, operation exit and physical deallocation; the current `invalidate()` only schedules the last of these.
- No unsafe thread stopping, reflection into provider sockets, mutation of shared Sessions, global socket factories, or hidden unbounded executor for blocked callbacks.
- Use deterministic fault injection, latches and bounded joins. Re-run the released waiter-recovery and shutdown regressions at every layer.
- Preserve Java 8 for both object pools and SMTP core/provider. Preserve the existing Java 17 baseline for SMTP Camel/demo modules. Simple Java Mail 10 remains Java 11.
- Follow [API_EXPANSION_WORKFLOW.md](../../../API_EXPANSION_WORKFLOW.md) for applicable public surfaces, [CODING_STYLE_GUIDE.md](../../../CODING_STYLE_GUIDE.md) for implementation, and each supporting repository's local maintainer/release workflow. Public Javadocs own the contract; delegate documentation with links.

## Tracking and release plan

Keep `simple-java-mail#722` as the narrative parent and `simple-java-mail#726` as the downstream delivery issue. The three supporting issues below are linked as native sub-issues of #726 and cross-reference both parent issues. No second umbrella issue was created.

| Library | Issue | Classification | Minor release |
| --- | --- | --- | --- |
| Generic Object Pool | [#22](https://github.com/bbottema/generic-object-pool/issues/22) | major feature | [2.5.0](https://github.com/bbottema/generic-object-pool/releases/tag/2.5.0), published 8 September 2026 |
| Clustered Object Pool | [#25](https://github.com/bbottema/clustered-object-pool/issues/25) | enhancement | [4.1.0](https://github.com/bbottema/clustered-object-pool/releases/tag/4.1.0), published 8 September 2026 |
| SMTP Connection Pool | [#31](https://github.com/simple-java-mail/smtp-connection-pool/issues/31) | major feature | [4.1.0](https://github.com/simple-java-mail/smtp-connection-pool/releases/tag/4.1.0), published 8 September 2026 |

All issues are assigned to the maintainer and their exact-version milestones. Never combine `major feature` and
`enhancement` on an item. SMTP's optional provider-neutral abort capability is distinct from a built-in Angus adapter:
the latter remains unsupported, so these releases do not yet make Simple Java Mail's full in-flight cancellation available.

Release in dependency order after acceptance and explicit release authorization. Verify published upstream artifacts before each downstream release; preserve module names, existing binaries and Java baselines. Update each README, `RELEASE.txt`, Javadocs and tag-specific GitHub release, then close the corresponding issue with concise usage guidance and mark its board item Done. Link the supporting issues from #726 and record actual versions in #722. No automatic backport of this new API to Simple Java Mail 9.x.

Use semantic commits without a `Signed-off-by` trailer for accepted work. Put the local child issue in the subject and `Part of bbottema/simple-java-mail#726; tracked by bbottema/simple-java-mail#722` in the body. Follow the maintainer's direct `codex/10.0.0` instruction for Simple Java Mail; use the supporting repositories' own branch workflows unless directed otherwise. Leave all implementation reviewable before committing. Journal work is outside this plan.
