# 1. Generic Object Pool: cancel acquisition without disturbing borrowers

- Status: Implemented and released as [2.5.0](https://github.com/bbottema/generic-object-pool/releases/tag/2.5.0) on 8 September 2026; [#22](https://github.com/bbottema/generic-object-pool/issues/22) closed
- Repository: `bbottema/generic-object-pool`; planning baseline 2.4.3
- Depends on: Acceptance of the [shared cancellation contract](README.md#proposed-api-direction)
- Main implementation boundary: `GenericObjectPool`, `Allocator`, `PoolableObject`, `PoolMetrics`, and focused cancellation types

## Current behavior to preserve and improve

`claim(...)` and `claimMatching(...)` already declare `InterruptedException`. The condition wait is interruptible, but acquiring `claimLock` is not. Foreground allocation, core-pool allocation, reuse preparation and release preparation currently invoke allocator code while that lock is held. A slow callback therefore also delays unrelated claims, release, metrics and shutdown.

Ordinary waiting currently reuses the supplied timeout after a wake-up; matching waits use a wall-clock deadline. The new advanced options path needs a single monotonic budget. Keep old overload behavior compatible and characterize it before sharing implementation; an intentional change to a documented legacy timeout contract needs separate review.

Invalidation removes an object from reusable capacity and schedules deallocation on the existing worker. It does not mean the physical resource has already been closed. Keep that existing method contract.

## API changes

1. Add the shared `ClaimControl` with `requestCancellation()`, immutable `ClaimOptions` with `withClaimControl(...)`, and allocator-facing `AllocationContext` described in the parent plan. Creating a control does not request cancellation. Keep any read-only token machinery internal and the public types Java 8 compatible and free of SMTP dependencies.
2. Add `claim(ClaimOptions)` and `claimMatching(Predicate<PoolableObject<T>>, ClaimOptions)`. Retain the current methods and return types; do not add every duration/unit/control permutation.
3. Add context-aware allocation and reuse-preparation hooks on `Allocator`, with concrete defaults delegating to the existing callbacks. Existing compiled subclasses must keep working without implementing a new abstract method.
4. Document cancellation, timeout, interruption, callback threading and handoff semantics on the public API. Include the non-cooperative allocator example: the request is recorded promptly, but the claim settles only when that callback exits and its result is dealt with safely.
5. Keep allocation callbacks serialized by default, matching the current lock-based behavior. Do not require existing allocators to become thread-safe. A separate serialization gate may protect callbacks, but never pool bookkeeping or the cancellation request itself.

## Implementation sequence

1. **Characterize the existing lifecycle.** Add bounded tests for lock contention, slow allocate/reuse/release callbacks, core allocation and pending shutdown before restructuring the lock boundary. Record callback ordering and metrics expectations.
2. **Represent one claim attempt.** Track its monotonic budget, request registration and terminal ownership decision in one focused internal abstraction. Keep requested cancellation separate from a settled claim.
3. **Make library-owned waiting cancellable.** Use cancellation/deadline-aware lock acquisition and a per-waiter wake-up signal. Register and recheck availability without a lost-wake window. Cancellation must signal its own waiter without waiting for `claimLock`; do not manufacture an interrupt against a possibly reused executor thread.
4. **Reserve, prepare, publish.** Reserve capacity or exclusively remove an available object under the lock; run allocator preparation outside the lock; then recheck shutdown/cancellation/deadline and hand off or dispose. Count reservations so concurrent claims and core replenishment cannot exceed capacity or allow early shutdown completion.
5. **Handle release preparation the same way.** Keep an object unavailable while `deallocateForReuse` runs outside the claim lock. Preserve allocator serialization and invalidate on failure. Signal waiters on every transition that creates usable resources or replacement capacity.
6. **Detach registrations at handoff.** A successful claim ends acquisition cancellation. If cancellation wins before handoff, no resource escapes; a prepared or partially reused object is safely disposed. A request after handoff does not revoke the borrower's object.
7. **Acknowledge disposal.** Retain `invalidate()` as scheduling-only. Add a secondary `getDisposalCompletion()` stage on the pool object for the SMTP consumer to await actual deallocation. It completes only after the deallocator returns, exceptionally on cleanup failure; callers cannot complete the pool's internal signal by mutating their returned view. A healthy release does not mean disposal. When a new context-aware claim is cancelled after preparation began, settle its cancellation only after preparation exits and required disposal finishes, outside the pool lock. A slow non-cooperative cleanup can therefore delay settlement, but not the cancellation request. Do not create a general lifecycle event API.

The top-level flow should read as reserve, prepare, hand off or dispose. Keep lock and registration mechanics below it; do not spread cancellation branches through every allocator and every caller.

## Required tests

- A newly created `ClaimControl` does not cancel anything; attaching it through `withClaimControl(...)` permits ordinary successful acquisition.
- Cancellation already requested through `ClaimControl.requestCancellation()`: no allocation, no waiter leak, no handoff.
- Cancellation while waiting for the pool lock, resource availability and the allocator serialization gate.
- Ordinary and matching claims; matching remains non-allocating and predicates remain fast, side-effect-free callbacks.
- Cancellation, timeout and interruption remain distinguishable; no cancellation-generated stale interrupt reaches a later job on the same executor thread.
- Repeated wake-ups cannot extend the new acquisition budget; cover zero/expired and effectively unlimited budgets without arithmetic overflow.
- Cancellation during cooperative allocation and reuse; hook registration before and after a request; throwing hook isolation; listener removal.
- A non-cooperative allocator remains blocked only until the test releases it; a late resource is disposed once and never handed out after cancellation won.
- Cancel/handoff, cancel/release, cancel/shutdown and cancel/core-refill races, including pool size one.
- Reservations, live/claimed/deallocating metrics and capacity stay consistent through failures and cancelled preparations.
- An old request cannot touch a healthy resource already handed to a different borrower; callbacks cannot run after their detached lifetime against reused resources.
- Slow release/deallocation cannot prevent other waiters from registering cancellation; disposal acknowledgement and shutdown include outstanding cleanup.
- Existing allocator subclasses, including a fixture compiled against the previous release, still work; serialization expectations remain intact.
- All released allocation-failure, waiter-wake-up and shutdown recovery regressions remain green.

## Delivery gate

Run focused concurrency tests, full Java 8 verification, a modern-JDK lane, source/binary compatibility and automatic-module-name checks. Update README usage, API Javadocs and next-release notes; audit touched classes against the root style guide. Follow the repository maintainer workflow and review before commit/release. Do not wait for SMTP or Angus work to make this independently useful change available.
