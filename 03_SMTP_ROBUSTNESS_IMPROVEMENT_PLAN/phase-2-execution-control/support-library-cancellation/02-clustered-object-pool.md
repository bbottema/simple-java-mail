# 2. Clustered Object Pool: propagate the same claim operation

- Status: Implemented and released as [4.1.0](https://github.com/bbottema/clustered-object-pool/releases/tag/4.1.0) on 8 September 2026; [#25](https://github.com/bbottema/clustered-object-pool/issues/25) closed
- Repository: `bbottema/clustered-object-pool`; planning baseline 4.0.4
- Depends on: [Generic Object Pool cancellation](01-generic-object-pool.md)
- Main implementation boundary: `ResourceClusters`, `ResourcePools`, `ResourcePool`, allocator factory integration and tests

## Why this layer needs work

The cluster layer chooses or creates a pool and then delegates to Generic Object Pool. Today it forwards configured claim timeouts, not a caller-owned cancellation request. Adding cancellation only to the bottom layer would leave cluster users without a usable entry point and could restart a total budget after selection.

Normal cluster selection chooses one pool. This work does not add failover or a scan of every pool for spare capacity.

## API changes

1. Reuse Generic Object Pool's `ClaimOptions` and `ClaimControl`, including `withClaimControl(...)` and `requestCancellation()`; do not create a second control type or expose the internal token machinery.
2. Add one options-based overload for each distinct route: `claimResourceFromCluster`, `claimResourceFromPool` and `claimMatchingResourceFromPool`. Retain existing methods, return types and configured-timeout behavior.
3. For the new route, apply the earlier of the caller's remaining budget and the applicable configured cluster claim limit. Resolve that effective deadline once, rather than restarting either limit in `ResourcePool` or Generic Object Pool.
4. Forward the read-only allocation context to context-aware allocators without making existing allocator factories implement new abstract methods. Preserve the existing cluster/pool configuration and load-balancing APIs.

## Implementation sequence

1. Check cancellation and establish the claim deadline before selecting a pool or performing lazy registration.
2. Keep registry bookkeeping separate from allocation or user load-balancing/allocator-factory work. Inspect the current `synchronized` entry points so cancellation does not sit behind unbounded application work on a cluster-wide monitor.
3. Pass the same request and remaining deadline through `ResourceClusters`, `ResourcePools`, `ResourcePool` and the generic claim. Keep protocol-independent ownership decisions in Generic Object Pool.
4. Preserve one-time registration under concurrent first claims. A cancellation that wins before registration starts creates nothing. If shared registration already completed, do not delete a pool another caller may use; only cancel this claim. Core-pool background work belongs to the pool, not the first caller's `ClaimControl`.
5. Keep the matching route non-allocating: an absent cluster/pool remains absent. Retain existing round-robin/random selection semantics and exception behavior for legacy routes.
6. Keep shutdown separate from cancellation. Cancelling one claim must not retire its pool, clear a cluster or cancel a neighbour's claim.

Arbitrary allocator factories and custom load-balancing callbacks still need cooperation to stop while running. They must not hold a registry-wide lock while blocking. Do not move them to a hidden executor merely to return sooner.

## Required tests

- Pre-cancelled claims through every route: no allocator invocation or lazy registration.
- One blocked claim cancelled among simultaneous claims to the same pool, other pools and other clusters; unaffected claims still succeed.
- Cancellation while waiting for registry access or delegated pool capacity, with all waits bounded in the test.
- No budget reset during selection, lazy registration, repeated wake-ups or delegation; configured cluster timeout can shorten but never lengthen the caller's limit.
- Concurrent first registration, cancellation immediately before/after registration, and core refill belonging to the pool rather than the requesting job.
- Matching claims do not register or allocate and stop promptly when cancelled.
- A shared job `ClaimControl` intentionally cancels that job's pending claims only when `requestCancellation()` is called; detached claims and other jobs remain unaffected.
- Cancellation racing pool retirement/shutdown and new registration cannot lose resources, strand cleanup or corrupt metrics.
- Existing load-balancing order, cluster-specific limits and all waiter/shutdown regressions remain intact.
- A legacy compiled caller and allocator factory still work against the new artifacts; automatic module identity remains unchanged.

## Delivery gate

Run focused tests, full Java 8 verification and a modern-JDK lane against the exact new Generic Object Pool artifact, plus compatibility and module checks. Update README examples, Javadocs and next-release notes. Record the dependency and its published version before release; follow the local maintainer workflow. This step can ship without SMTP cancellation support.
