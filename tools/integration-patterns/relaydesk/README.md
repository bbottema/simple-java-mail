# RelayDesk: customer-owned mail routes

Runnable companion to the RelayDesk story, not a replacement for its article or helpers. The build compiles the original
`RelayDeskMailExamples.java` unchanged, using its Mailer construction, result-observer, asynchronous handoff and authenticated-probe helpers.

## Start here

From the matching SJM checkout root, use Docker with Linux containers. Start with the scenario: it makes one customer wait while the other finishes, then exercises credential replacement.

```text
docker compose -f tools/integration-patterns/relaydesk/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/relaydesk/compose.yaml run --rm scenario
```

## Follow one job

Begin at [`RelayDeskApplication.enqueue`](src/main/java/RelayDeskApplication.java), then follow `dispatchNext` and `submit` in the same file. The database claim comes before the article's send helper, and `recordOutcome` keeps the result with its tenant. Read `TenantRoute.replace` next to see why replacing A's password pauses A rather than the whole service. The original [`RelayDeskMailExamples`](../../../simplejavamail.org/src/assets/journal/articles/relaydesk/examples/RelayDeskMailExamples.java) supplies the Mailer and send helpers unchanged.

The application, PostgreSQL and two STARTTLS/authenticated fixtures need no host JDK/Maven or real account. Reserve Docker memory as described in the
shared workbench README. Synthetic credentials/certificates are public demo material, never production identities. Fixtures have no forwarding code.

`/jobs` uses `X-Demo-Tenant-Token: tenant-a-local` or `tenant-b-local`; requesting another tenant returns 403. `POST /jobs?id=job1` queues an application job.
`/routes` needs `X-Demo-Operator: local-operator`. Route replacement retires only the affected tenant's Mailer and probes its replacement before resuming.
These inspectable local tokens are not production authentication or an SSRF defense. Only the preconfigured fixture hosts are eligible.

Inspect A's evidence without installing a host HTTP client:

```text
docker compose -f tools/integration-patterns/relaydesk/compose.yaml exec app curl --silent -H "X-Demo-Tenant-Token: tenant-a-local" http://localhost:8080/jobs
```

The scenario holds A at DATA permission while B completes, rejects cross-tenant access, repeats input, changes A's credential revision, and verifies
partial/unknown submission is held. It also rejects cross-tenant feedback correlation, pauses A after an unsuccessful credential check while B still
progresses, then resumes A after a valid replacement. PostgreSQL is the durable backlog; SJM's small bounded queue and local pacing are not fleet-wide allowance.

## Try the isolation boundary

Read the scenario's `tenantIsolation`, `partialAndUnknownHeld` and `failedReplacementPausedOnlyItsTenant` checks, then inspect A's `/jobs` with the command above. A failed password replacement should leave that customer's work waiting; B's job still reaches `SUBMITTED`. A held uncertain result must not be turned back into ready work. Use a fresh stack when rerunning the complete credential-rotation experiment.

Source: `src/main/java/RelayDeskApplication.java`. A per-tenant read/write lock coordinates admission versus retirement; no global registry lock blocks
other customers. Failed replacement stays paused. This finite registry does not demonstrate unlimited churn; cluster/registry metadata needs explicit
production capacity planning. Missing result writes and interrupted SENDING records require reconciliation, not automatic replay.

```text
docker compose -f tools/integration-patterns/relaydesk/compose.yaml down --volumes --remove-orphans
```

This removes only the example's synthetic data. See [the shared notes](../README.md) for ports, resources and development certificate renewal.
