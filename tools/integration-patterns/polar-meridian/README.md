# Polar Meridian: shared enterprise mail service

The existing article Dispatcher, Mailer helper and JDBC limiter compile unchanged. This module supplies their concrete application adapters instead of
forking orchestration into SJM. Two dispatcher processes share PostgreSQL and use separate urgent/failover/bulk SMTP fixtures.

## Start here

Run from the matching SJM checkout root with Docker's Linux containers. The scenario starts two dispatchers, holds bulk work, then sends urgent work through failover and recovery.

```text
docker compose -f tools/integration-patterns/polar-meridian/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/polar-meridian/compose.yaml run --rm scenario
```

## Follow one job

Start at [`PolarMeridianApplication`](src/main/java/PolarMeridianApplication.java): its constructor wires the unchanged Dispatcher to concrete application adapters. Follow a claimed job through [`PolarStorage`](src/main/java/PolarStorage.java) into the original [`PolarMeridianDispatcher`](../../../simplejavamail.org/src/assets/journal/articles/polar-meridian/examples/PolarMeridianDispatcher.java). Return to the application's `preparation` method for composition, rehearsal and protection, then `PolarStorage.recordOutcome` for the result. Keep [`JdbcDispatchLimits`](../../../simplejavamail.org/src/assets/journal/articles/polar-meridian/examples/JdbcDispatchLimits.java) beside that route to see the separate, shared application allowance.

Only Docker with Linux containers/Compose is needed locally. All mail and development identities are synthetic; SMTP fixtures never forward.

`/jobs`, `/attempts`, `/events` and `/limits` on localhost:8787 expose synthetic evidence. Operator mutations use `X-Demo-Operator: local-operator`.
They are not a production management API. Producers select approved workload/scenario values, not SMTP endpoints, secret material or arbitrary SQL.

The scenario holds a bulk backlog while urgent mail completes, checks that two instances share the same application allowance, drives A→B→A connection
recovery, verifies/decrypts protected mail and compares retained/captured bytes, then injects a visible result-archive failure.

## Try the evidence boundary

Inspect `/jobs`, `/attempts` and `/events` after the scenario. Protected urgent and bulk jobs should both have matching archived/captured bytes; the injected result-write failure should have an attention event. Neither failed evidence storage nor an uncertain SMTP result permits another send. This is the useful contrast: SMTP success and successful result storage are different facts.

Application quota rows are separate from SJM factory-local limits. Jobs have fresh claim tokens; updates match owner and token. Unfinished claims remain
unavailable for blind replay, including if their process disappears. This is conservative reconciliation, not exactly-once SMTP or automatic recovery.
Bulk/routine/urgent allocations are fictional, not provider defaults. Do not infer the story's throughput from this small example.

Archived EML is AES-GCM encrypted in PostgreSQL with a PUBLIC development key; S/MIME uses the generated synthetic partner identity. Replace key management,
trust, access/audit/retention and reconciliation before considering production use. Metadata-only jobs do not retain an EML body.

## Optional OpenTelemetry

Add `-f tools/integration-patterns/polar-meridian/observability.yaml` to every command above. The application owns its SDK and exports to the local Collector.
`docker compose ... logs collector` shows traces; `http://localhost:8789/metrics` exposes aggregate metrics. IDs/recipients are not metric dimensions.
Keep that additional file on cleanup so its container is removed too. Reference docs and the dated verification record state what was actually tested.

Start the optional stack from a fresh ordinary run:

```text
docker compose -f tools/integration-patterns/polar-meridian/compose.yaml down --volumes --remove-orphans
docker compose -f tools/integration-patterns/polar-meridian/compose.yaml -f tools/integration-patterns/polar-meridian/observability.yaml up --build -d --wait
docker compose -f tools/integration-patterns/polar-meridian/compose.yaml -f tools/integration-patterns/polar-meridian/observability.yaml run --rm scenario
```

Use the same two files with `logs collector` to read traces, and with `down --volumes --remove-orphans` to clean up that variation.

For the ordinary stack, cleanup is:

```text
docker compose -f tools/integration-patterns/polar-meridian/compose.yaml down --volumes --remove-orphans
```

Run the complete allowance/failover experiment against a fresh stack. Its synthetic quotas are deliberately small and persist until you remove the project's volumes.
