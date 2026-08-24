# Step 12: Add concurrency, backpressure, and duplicate-request safety

- Status: Done
- Depends on: Steps 9 through 11
- Primary module: `cli-module`
- Primary areas: bounded execution, request ledger, reconnect behavior, overload, client disconnects

## Goal

Allow safe concurrent local requests while bounding every queue/resource and ensuring a reconnect with the same request ID observes the original execution instead of resending mail.

## Tests first

1. Run many compatible sends concurrently through one Mailer and verify thread-safe completion and the configured pool's maximum connection behavior.
2. Run unrelated profiles concurrently and prove a slow profile does not serialize all daemon work.
3. Fill connection, parse, execution, Mailer-registry, and output limits independently. Assert a stable overload result and bounded memory/thread counts.
4. Disconnect the client before parse, during queued wait, during SMTP send, after SMTP acceptance, and while returning the result.
5. Reconnect with the same authenticated request UUID while the original is queued, running, complete, failed, and recently evicted from the result ledger.
6. Replay a captured authenticated request within and outside its freshness window.
7. Restart the daemon after SMTP acceptance but before client response and assert the client reports an ambiguous outcome without automatically executing again.
8. Stop the daemon with queued and active requests. Assert queued-not-accepted work is rejected, accepted work follows the frozen drain policy, and status reports progress.
9. Make a Mailer future ignore interruption and prove shutdown reaches the documented timeout/escalation state without claiming clean delivery.
10. Fuzz request ordering and repeat concurrency tests enough times to expose state races.

## Implementation

1. Use bounded accept/handshake capacity and a bounded application executor. Do not create one unbounded thread per connection or request.
2. Authenticate and validate frame limits before occupying application execution capacity.
3. Register the request UUID atomically before command execution. The ledger stores request digest, state, and bounded terminal result for the retention window.
4. A duplicate UUID with the same authenticated request attaches to or retrieves the original result. The same UUID with different content is rejected.
5. Continue accepted work after a client disconnect unless the command has not crossed the documented acceptance boundary. Retain its bounded result for reconnect/status.
6. Distinguish transport timeout, queue timeout, command timeout, SMTP result, daemon shutdown, and unknown/ambiguous result. Do not map all failures to one retryable error.
7. Apply backpressure before building Emails, opening files, constructing Mailers, or claiming SMTP connections where possible.
8. Keep recent-result retention bounded by count, bytes, and time. Retain a lightweight request-ID/content tombstone for at least the protocol authentication-freshness window even after terminal output bytes are discarded. A later query may return outcome unknown, but the same application request is never executed again in that daemon session.
9. Make status counters lock-safe and secret-free.
10. Document that the ledger is memory-only and is not a durable spool.

## Delivery safety contract

- Client retry with the same request UUID is an observation/re-attachment operation, not a new send.
- The client never creates a new UUID automatically after losing an application response.
- An explicit new CLI invocation is a new user decision and receives a new UUID.
- A daemon crash can erase the ledger. If SMTP acceptance may have happened, the result is ambiguous.
- No daemon mode claims exactly-once delivery across process failure.

## Acceptance criteria

- [x] Connections, workers, queued work, profiles, result entries, and retained output are bounded.
- [x] Duplicate request IDs cannot execute twice in one daemon session.
- [x] Changed content under an existing request ID is rejected.
- [x] Client disconnects do not cancel or duplicate already accepted sends silently.
- [x] Overload and shutdown have stable non-success exit categories.
- [x] Concurrent profiles do not share parser state or incompatible Mailers.
- [x] Crash ambiguity is preserved rather than hidden by retry/fallback.
- [x] Stress tests complete without leaked threads, futures, files, channels, or Mailers.

## Completion evidence

- Daemon connection, application, profile, output, and request-ledger resources have explicit count/byte/time bounds and stable overload outcomes.
- `RequestLedger` reattaches the same UUID/content digest, rejects changed content, retains freshness-window tombstones, and marks accepted incomplete work ambiguous during shutdown.
- Shutdown and malformed/slow-client tests verify rejected queues and abandoned channels are closed without silently retrying application work.

## Stop condition

If a requested automatic retry can duplicate an SMTP-accepted message, omit the retry. Durable retry requires a separately designed spool and delivery-identity contract.
