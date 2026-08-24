# Step 11: Build the bounded reusable Mailer registry

- Status: Done
- Depends on: Step 10
- Primary modules: `cli-module`, with existing Mailer and batch APIs from `simple-java-mail` and `batch-module`
- Primary areas: Mailer leases, creation serialization, idle eviction, pool lifecycle, one-shot ownership adapter

## Goal

Reuse one thread-safe `Mailer` for compatible daemon requests without leaking Mailers, connection-pool registrations, executors, or failed construction state.

## Tests first

1. Send two compatible requests and assert one Mailer construction, one batch registration, and reuse of the eligible SMTP connection.
2. Send incompatible profile requests and assert distinct Mailers and connection pools.
3. Race many first requests for one profile and prove only one live Mailer is published.
4. Fail Mailer construction and prove no poisoned entry blocks a later successful construction.
5. Hold an active send while the entry becomes idle/eviction-eligible and prove it is not closed until the lease and future finish.
6. Exceed the registry entry limit with idle and active profiles. Assert deterministic eviction or bounded rejection without closing active entries.
7. Exercise idle timeout, explicit daemon shutdown, startup failure cleanup, and abrupt command failure. Count every `Mailer.close()` and batch deregistration.
8. Run send, connect, and validate through both one-shot and daemon providers. Assert one-shot continues to close after each command.
9. Configure cluster aliases/UUIDs and conflicting pool settings. Prove registry identity and the batch engine cannot merge incompatible profiles through a shared cluster accidentally.
10. Vary connection expiry/core size and measure actual SMTP connection reuse without changing the configured values silently.
11. Run the registry suite without `batch-module`. Prove daemon execution and Mailer reuse remain supported while batch registration and pooled SMTP-connection assertions are correctly absent.

## Implementation

1. Introduce an internal `MailerProvider` used by the shared command executor.
2. Implement `OneShotMailerProvider` with the current build, wait, and close-after-command lifecycle.
3. Implement `DaemonMailerRegistry` keyed by the full `CliMailerProfile` from Step 10.
4. Return an `AutoCloseable` lease that increments active ownership. Closing a lease does not close the Mailer; it makes the entry eligible for idle retirement.
5. Serialize creation per profile while allowing unrelated profiles and established Mailers to operate concurrently.
6. Bound registry entries and define least-recently-used idle retirement. Active entries count toward the bound and can cause a clear overload response rather than unbounded growth.
7. Track request futures separately from lease acquisition. An entry is closeable only after active leases and its accepted work reach zero.
8. On retirement, remove the entry from lookup before closing it so new work cannot acquire a closing Mailer.
9. Close every Mailer exactly once and surface non-secret close failures without preventing the rest of the registry from closing.
10. Expose secret-safe counts and ages to daemon status; do not expose profile digests if they permit activity correlation unnecessarily.

## Pool behavior

`batch-module` is supported but is not required by the daemon protocol or registry. The official standalone distribution includes it at runtime. When present, each cached `Mailer` owns the corresponding batch registration, executor, and SMTP connection-pool lifecycle. When absent from a custom distribution, caching the `Mailer` still avoids reconstruction work but cannot promise pooled SMTP transport reuse.

The registry does not override `DEFAULT_CONNECTIONPOOL_CORE_SIZE`, `DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS`, claim timeout, max size, cluster key, or load balancing. Tests and documentation show that:

- keeping a Mailer alive always saves reconstruction/configuration work;
- a live SMTP transport is reusable only while pool configuration retains it;
- the default five-second idle expiry may be too short for intermittent CLI use;
- setting a core size above zero intentionally keeps connections open and consumes server resources;
- every cached clustered Mailer must be closed so its registration is removed.

## Acceptance criteria

- [x] Compatible repeated requests construct exactly one reusable Mailer.
- [x] Incompatible profiles cannot share.
- [x] Concurrent first use cannot publish duplicate Mailers.
- [x] Registry size and idle retention are bounded and configurable through daemon-specific settings.
- [x] Active or in-flight work is never closed by eviction.
- [x] Every retired Mailer and batch registration closes exactly once.
- [x] One-shot mode retains close-after-command behavior.
- [x] SMTP connection reuse is demonstrated with a controlled server and explicit pool settings.
- [x] A custom distribution without `batch-module` remains functional and has an explicit reduced-reuse contract.
- [x] No library-wide pool default changes as a side effect of the daemon.

## Completion evidence

- Registry tests cover single construction under contention, compatible reuse, incompatible isolation, LRU/idle retirement, lease-aware eviction, bounded shutdown, and exactly-once close.
- A controlled SMTP process test observes two daemon sends on one pooled SMTP session while an equivalent one-shot send uses a separate session.
- A runtime assembled without `batch-module`, pool, and clustering jars completes repeated daemon validation with one cached Mailer and no linkage failure.

## Stop condition

If batch cluster state retains incompatible settings after the last Mailer for a profile closes, make daemon restart the supported boundary for that pool-setting replacement and record it. Do not reuse a cluster whose effective settings cannot be proven.
