Delivered on codex/10.0.0 at 929b1699cecf39708497ff5bab6dd4824335cbe5 and codex/10.0.0-content at 841614b4825117ccde6bb8c57119e36f71ccec41. Issues #914 and #915 are closed/completed and Done. Only guarded scratch/plan cleanup remains pending at the existing older node_modules junction; all original feature-review findings have been delivered.

# Keep daemon controls available during command saturation

The maintainer approved original feature-review finding 8, its split-lane design, implementation and full delivery on 29 October 2026. [#915](https://github.com/bbottema/simple-java-mail/issues/915) tracks the correction for unreleased 10.0.0. ADR 0019 owns the decision. The lead reviews the actual combined diff and evidence before serialized commit, push and closure on `codex/10.0.0` and the relevant website content branch.

Implementation, independent review and the selected local checks are complete. Remaining work is the lead's actual combined-diff acceptance, shared documentation reconciliation and serialized delivery/closure. Retire this plan after acceptance; ADR 0019, module guidance, GitHub and regression cases retain the useful contract.

## Before and after

Eight concurrent `sjm connect --daemon=require --mailer:withSMTPServer smtp.example.org 2525 --mailer:withReadTimeout 0` commands against a slow relay currently occupy every connection worker. `sjm daemon status` and `sjm daemon stop` then wait behind SMTP work. The accepted result keeps those controls responsive through separate bounded authentication/control and execution-reply handling, with authenticated busy refusal before new work is admitted when reply capacity is full.

Keep the endpoint, framing, authentication, public settings, application worker limits and request-result ledger. Do not introduce an asynchronous reply framework, another endpoint, automatic retry or weaker authentication fallback.

## Ownership and concrete impact map

The configuration/integration worker owns daemon implementation and focused tests in `modules/cli-module`, plus ADR 0019 and this plan. Its delegated process-test worker owns only new `DaemonControlSaturationProcessTest.java`; an independent reviewer challenges admission and shutdown design. Other workers remain active. Preserve their edits, the existing index and unrelated research/site work. The lead owns shared release notes, ADR index, OpenRewrite plans, website content and cleanup bookkeeping.

| Path | Intended change and preserved behavior |
| --- | --- |
| `daemon/DaemonServer.java` | Existing eight authentication/control workers plus queue of 32 read authenticated requests and answer controls. New eight execution-reply workers have an independent limit of 40 active-plus-queued owners. Their bounded backing queue also has room for 40, avoiding a finishing-worker enqueue race; stable saturation is eight active plus 32 queued. Existing four application workers plus queue of 32 remain. No command-held global permit can consume control capacity. |
| `daemon/DaemonServer.java` | Reserve a reply slot before ledger registration, then capture its Registration/future and enqueue the owning task under the admission gate. A queued NEW task is still application-unadmitted. Capacity-full refusal sends authenticated BUSY before ledger registration. Defensive unexpected-enqueue rollback terminalizes only a NEW unadmitted registration. Track every accepted channel continuously across ownership transfer so forced shutdown cannot lose it. |
| `daemon/DaemonServer.java` | One admission gate orders READY checks, reply reservation, captured ledger identity, waiter publication and later application submission against QUIESCING. No socket I/O, future wait or application execution runs inside that gate. Waiters retain the captured Registration/future instead of registering again after a long queue wait, preventing replay after ledger expiry. |
| `daemon/DaemonServer.java` | Admitted duplicate IDs attach to the same result; changed payload and retained tombstones retain existing meanings. Reply interruption/disconnect never cancels or replaces accepted application work. Waiting on results becomes interruptible for bounded shutdown. |
| `daemon/DaemonServer.java` | STOP closes listener intake and completes only NEW application-unadmitted owners as BUSY, including active waiters paused before admission. Duplicate observers never complete another owner's future. Drain application work under the existing 30-second policy, then fence remaining incomplete ledger results as ambiguous and drain both network lanes within one shared 30-second budget. Forced retirement closes active and abandoned channels; adding a lane does not add another shutdown interval. |
| `daemon/DaemonProtocol.java` | Retain HMAC, frame/session/freshness validation, wire operations, limits and endpoint selection unchanged. |
| `daemon/DaemonClient.java` and `DaemonMailerRegistry.java` | Retain current client deadlines, no-retry behavior and close-once Mailer leasing unless implementation exposes a necessary narrow companion correction. |
| `docs/adr/0019-local-cli-daemon.md` | Record the accepted separation, bounded admission, ownership and unchanged ambiguity/endpoint boundaries. No execution evidence belongs in the ADR. |

The two network lanes hold at most 40 tasks each; the accept loop can briefly own the one channel it is dispatching. The tracked-channel set is bookkeeping, not a shared admission semaphore. Management remains available under execution saturation; arbitrary clients exhausting the short authentication lane are still subject to its existing read deadlines.

## Scenario matrix

| Scenario | Expected observable result |
| --- | --- |
| At least eight actual CLI SMTP commands remain in flight | Status answers while SMTP is held; authenticated STOP acknowledges before release; admitted commands drain without replay. |
| Eight reply workers and 32 queued reply tasks are occupied | Controls retain intake capacity; another EXECUTE receives authenticated BUSY without ledger/application admission. |
| STOP races with authenticated handoff or first admission | Exactly one channel owner remains; no new application admission after QUIESCING; queued unadmitted commands are refused. |
| Reply queue waits beyond ledger retention | The queued waiter retains its captured identity/future and cannot register as new work or replay an older ID after ledger retirement. |
| In-flight duplicate or changed payload | Identical UUID/content observes one application execution; changed content refuses; output-pressure tombstones never replay. |
| Slow reader or disconnected command client | Bounded reply write and channel cleanup; accepted work retains its original result and continues independently. |
| Wrong MAC, session, version or stale frame | Existing rejection before dispatch; no fallback or weaker endpoint. |
| Application drain expires or a task leaves an incomplete result | Retain ambiguous outcome, retire reply observers and channels within the shared shutdown budget, and preserve Mailer lease cleanup. |
| TCP and supported Unix-domain sockets | Same command/control behavior through the existing transport choice; unsupported UDS remains an explicitly limited platform lane. |

## Verification and documentation alignment

First reproduce the original starvation through real CLI processes against a latched loopback SMTP fixture. Then implement and run focused server/admission, protocol, ledger, registry and process-mode cases on a real Java 17+ CLI runtime. Use independent gate controls for captured queued identities and stop/admission races. No whole-repository suite is requested.

Builder signatures, generated CLI option/help caches, Spring metadata, configuration keys, library Java baseline and serialized application values do not change. The lead reconciles release history, ADR index, website daemon guidance and the OpenRewrite target-only inventory. Journal/case-study examples need changes only if their daemon lifecycle explanation contradicts the final contract.

All build/probe material is isolated under `.local/feature-review-daemon-control-20261029/`, using `source/` for a HEAD archive plus the owned source/test overlay, `source.tar` for bootstrap and `logs/` outside Maven targets. The lead adds that exact root and this retired plan to guarded cleanup allowlists. No shared targets, unrelated worktrees or broader scratch parents may be cleaned.

A temporary `V:` alias pointed only to this task's `logs/tmp/` to keep Windows Unix-socket paths short. It was removed after an exact-target check and after the owned Java processes finished. `logs/source-manifest.json` binds the final owned sources to the snapshot; `logs/cleanup-state.txt` records the final process/mapping state. The snapshot remains for lead review and later guarded cleanup.
