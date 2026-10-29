# Inside a mail send — shared infographic

This is the maintenance source for the [architecture infographic](assets/inside-a-mail-send.png). Keep its wording and the approved artwork together so updating it does not depend on finding the original image-generation conversation.

## Scope

- Version shown: Simple Java Mail 10.0.0 development line.
- Path shown: a pooled asynchronous send with configured connection failover and managed Angus transport.
- Last architecture review: 2026-10-29, including authenticated SOCKS setup/forwarding separation; see the [architecture guide](../failover/architecture.md), [bridge guide](14-authenticated-socks-bridge.md) and ledger below. Website encoding reviewed on 2026-10-11.
- Not a release-status graphic, a lock-order graph, or an exhaustive map of every send path.
- Proposed changes stay out of the picture until implemented. Do not add them ahead of the code.

The outer layers group responsibilities rather than literal object containment. The reporting bracket spans the send attempt; observation does not start only after the socket finishes. Synchronous sends, simple batches, open connections, custom mailers, and caller-owned Sessions have their own paths and contracts in the catalogue and user documentation.

## One master, optimized website derivative

| Surface | Source or consumer |
| --- | --- |
| Canonical artwork | [`assets/inside-a-mail-send.png`](assets/inside-a-mail-send.png) in this repository |
| Editable wording and update brief | This Markdown file |
| Concurrency catalogue | [Overview in the index](README.md#start-with-the-overview), using the local master |
| Website asset | `simplejavamail.org/src/assets/architecture/inside-a-mail-send.png`, a web-optimized derivative of the master |
| Website component | `simplejavamail.org/src/_includes/components/mail-send-architecture.hbs`, shared by any page that embeds it |
| Website entry point | `sending-and-execution.html#section-send-architecture`; diagnostics and result guidance link there |
| Umbrella issue | [#722](https://github.com/bbottema/simple-java-mail/issues/722), embedding the master through the development branch's raw-file URL |

Keep artwork and wording changes in the canonical master and derive the website asset from it. The website PNG may use a reduced palette or different compression while preserving the dimensions, labels, connections and readable lettering. Its bytes and hash may therefore differ from the master. The current website derivative is an indexed-color PNG; the master retains its RGB encoding.

The issue's branch URL follows the canonical asset; do not upload a competing permanent issue attachment. Use the SHA-256 prefix of the asset served on each surface for its `v` query value. The issue embed follows the master hash; the website component follows the optimized website hash. Update the website query after an encoding-only optimization too.

| Current asset | SHA-256 revision prefix | File size |
| --- | --- | --- |
| Canonical master / issue embed source | `3e51924c4e9c` | 2,050,207 bytes |
| Optimized website source / component | `64c285f68384` | 366,973 bytes |

Both images are 1536 × 1024. The [archived image-edit prompt](https://github.com/bbottema/simple-java-mail/blob/9f094c845b5bf403ce74ccd24036d21fa7cf532b/output/imagegen/inside-a-mail-send-failover-prompt.txt) records the exact requested wording and preserved layout. The generated working files were subsequently removed; the immutable history link retains this reference.

## Text and layout source

Preserve the five-layer cutaway onion, numbered callouts, readable class names, and the separate connected reporting band. Update the relevant callouts in place instead of redesigning the whole image. No slogans, handwritten asides, scope glossary, batch footnotes, or disconnected completion diagram.

Title: **Inside a mail send**

Subtitle: **Simple Java Mail 10.0.0 · layers, responsibilities and control**

Path caption: **Illustrated path: pooled async send with configured connection failover**

| Layer | Label | Role text |
| --- | --- | --- |
| 01 — Entry & supervision | `MailerImpl` | Admits work; async preparation runs on the executor. |
| 01 — Entry & supervision | `MailSendOperations` | Tracks active calls and coordinates shutdown. |
| 02 — Execution & control | `MailSendExecutor` | Admission, queue and send workers. |
| 02 — Execution & control | `MailSendOperation` | One call: start, stop and result completion. |
| 02 — Execution & control | `MailSendControl` | Deadline budget and registered stop actions. |
| 03 — Per-email send & routing | `MailConnectionRoute · ConnectionRecovery` | Fail over before submission; coordinate failure rechecks. |
| 03 — Per-email send & routing | `SendMailClosure · TransportRunner` | Submit once through the selected Mailer. |
| 03 — Per-email send & routing | `AcquiredMailTransport` | Owns transport cleanup and pooled lease release. |
| 04 — Connection pool | `BatchTransportEngine · transport lease` | Select candidates; acquire, release or invalidate. |
| 05 — SMTP & network | `AngusMailTransportAdapter` | Captures provider-specific send facts. |
| 05 — SMTP & network | `ManagedAngusTransport` | Tracks SMTP setup, commit and socket abort. |

Routing chooses a destination before that destination's message preparation and connection acquisition. All visits remain inside the initiating operation, under its executor, deadline and cancellation control. `ConnectionRecovery` shares availability observations and coordinates operation-led rechecks; it does not add a health executor. The picture's failover path requires configuration, and local preparation errors or submission failures do not authorize routing that email again. For synchronous sends, preparation runs during the caller's operation execution; async execution follows the configured executor, including its direct or caller-runs behavior.

The core is labeled **Socket**. The dashed control route is labeled **Cancellation / deadline signals**, with **Stop requested ≠ work finished** alongside it. It represents stop/abort signals, not Java thread interruption or proof that SMTP did not accept the message.

The separate **Outcome reporting** band starts with:

> Follows each email through the layers above, then reports how the attempt ended.

Its three connected stages read:

| Stage | Role text |
| --- | --- |
| `MailSendAttempt` | Collects Message-ID, timings, connection history and outcome. |
| `MailSendObserverNotifier` | Passes the outcome to your observer. |
| Your application | Receives the callback inline or on your configured executor. |

Never turn SMTP acceptance into an inbox-delivery claim. Keep the reporting bracket connected to all layers, not just the SMTP socket. Check the generated lettering as well as the architecture before accepting a revision.

## Phase completion check

Review this overview at the end of **every remaining step**, at each phase boundary, and again before the 10.0.0 release. Also review it whenever a change affects one of the pictured responsibilities. This is a human semantic review: matching file hashes can catch a missed copy, but cannot establish that the picture still describes the code.

1. Compare the class names and responsibilities above with the implementation and relevant [state-machine pages](README.md#start-with-the-question-you-have). Check new connections as well as renamed or removed classes.
2. Decide whether the high-level picture changed. A new capability can fit inside an existing layer without needing another box. If unchanged, record that decision and its reason; do not regenerate the image merely to advance the phase number.
3. If changed, update this wording source and edit the canonical PNG together. Keep planned behavior out of the image and preserve the simpler reporting layout.
4. Derive and web-optimize the approved PNG into the website asset path, preserving the artwork and readable lettering. Update the website component's revision query from the website file's hash. Reuse the component wherever the website needs the image.
5. Check the catalogue's relative image link, the website's inline and full-size views, and the image in #722. On an artwork change, update #722's existing embed and its revision query rather than adding another competing diagram comment. Do not claim publication until the relevant repository changes have actually been pushed.
6. Record the review in the ledger below before completing the child issue or phase. Repeat the check before release; versioned release documentation should retain the diagram for its own implementation, not silently acquire a later release's behavior.

After replacing either asset, this check from the root reports its own revision and verifies that all three website component URLs follow the optimized website file:

```powershell
$masterRevision = (Get-FileHash docs/concurrency/assets/inside-a-mail-send.png -Algorithm SHA256).Hash.Substring(0, 12).ToLowerInvariant()
$websiteRevision = (Get-FileHash simplejavamail.org/src/assets/architecture/inside-a-mail-send.png -Algorithm SHA256).Hash.Substring(0, 12).ToLowerInvariant()
Write-Output "Master revision: $masterRevision"
Write-Output "Website revision: $websiteRevision"
$component = Get-Content -Raw simplejavamail.org/src/_includes/components/mail-send-architecture.hbs
$websiteUrl = "inside-a-mail-send.png?v=$websiteRevision"
if ([regex]::Matches($component, [regex]::Escape($websiteUrl)).Count -ne 3) {
    throw 'The website component does not use the optimized image revision everywhere.'
}
```

Compare dimensions and visually check the optimized lettering and diagram against the master. Hash equality is not required between the master and derivative; the generated website image must match the optimized website source. Run the website check/build and internal-link verification. Those check publication mechanics, not the truth of a concurrency claim.

## Review ledger

Add a dated row for each completed step. Record the change, or explicitly say why the overview remains accurate.

| Checkpoint | Review |
| --- | --- |
| SIZE diagnostic separation, 2026-09-28 | Reviewed unchanged for #748. Operational SIZE facts remain connection-owned under the transport monitor; the complete EHLO scan is independent of the probe's 64 KiB reporting budget. No new connection, pool, worker, lock or ownership boundary is introduced. |
| Phase 2, 2026-09-10 | Initial approved overview of the implementation under review. Shared supervision, operation control, pooled cleanup, managed Angus abort, and connected outcome reporting are represented. |
| Phase 2 completion, 2026-09-11 | Reviewed unchanged against the accepted implementation. Exceptional pooled cleanup now preserves the first failure and still waits for invalidated disposal before outcome reporting. This strengthens the pictured ownership boundary without changing any layer or responsibility; the website uses the same approved PNG. |
| Phase 3 — diagnostics and authentication policy, completion 2026-09-15 | Reviewed unchanged against the accepted #733 and #735 implementations. The dedicated probe remains outside the send path; the mandatory-STARTTLS guard runs before send-operation, proxy, lifecycle and pool setup. Neither changes the pictured owners, locks, lease boundaries or outcome reporting. The master, website copy and #722 embed retain the approved image. |
| Phase 3, step 4 Java probe, 2026-09-11 | Reviewed unchanged. `SmtpConnectionProbeAdapter` and its dedicated Angus inspection transport are outside the illustrated send path: no Email, pooled send lease, deadline controller or observer is involved. The new path reuses proxy accounting but adds no state to `ManagedAngusTransport`. Step 4 CLI follow-through and the separate authentication-policy step remain open. |
| Phase 3, step 4a execution views, 2026-09-12 | Reviewed unchanged for #734. `Mailer.sync()` and `Mailer.async()` are cached entry adapters over the same `MailerImpl`; they own no workers, connections, pool registrations or locks. The receipt-discarding wrapper is removed, but operation/control/cleanup and observer boundaries remain the same. The diagram names implementation responsibilities, not the superseded public overloads. |
| Phase 3, step 4 CLI probe, 2026-09-15 | Reviewed unchanged for #733. One-shot and daemon commands use the same dedicated synchronous probe, with request-owned output and existing Mailer leases. Invocation-only authentication stays outside the reusable Mailer profile. Retaining supplied SMTP settings in logging-only mode lets explicit probes use the configured endpoint without changing send execution or observation. |
| Phase 3, step 4 adapter boundary and holistic review, 2026-09-15 | Reviewed unchanged for #733. A separately packaged adapter/provider fixture now exercises the public SPI without Angus on the classpath and module path. Partial diagnostics, authentication, private Session changes and dedicated cleanup remain local to the probe. This adds evidence for the existing provider boundary, not another production layer, send lock or lifecycle owner; the root and website PNGs remain identical. |
| Phase 3, step 4 completion, 2026-09-15 | Java and CLI probe implementation, partial-provider coverage and final holistic review accepted under #733. The overview remains unchanged for the reasons above. The separate authentication-policy step still requires its own review before Phase 3 can be declared complete. |
| Phase 3, step 5 characterization, 2026-09-15 | Reviewed unchanged for the test-only [authentication/TLS investigation](../research/smtp-authentication-tls-characterization.md). SJM configures Sessions, Angus negotiates TLS/authentication, and the pool controls physical connection reuse. No production owner, lock, or state transition changed, and no probe-to-send dependency was added. The existing opportunistic default is retained; any strategy-hardening implementation still needs its own review. |
| Phase 3, step 5 documentation and guardrail proposal, 2026-09-15 | Reviewed unchanged. Javadoc and website explanations now distinguish absent STARTTLS from a failed upgrade. The [proposed configuration check](https://github.com/bbottema/simple-java-mail/blob/2653e804838e14e4b10291bdb9863b2d9a1ff67c/03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-3-diagnostics-and-security/05b-mandatory-starttls-configuration-guardrail.md) is not implemented; no runtime owner, lock, transition or infographic content changed. |
| Phase 3, step 5 construction guard, 2026-09-15 | Reviewed unchanged for #735. `MailerImpl` rejects a contradictory mandatory STARTTLS override before send-operation, proxy, lifecycle or cluster setup. This adds no send owner, lock, state transition or probe-to-send dependency. Caller-owned Sessions and CustomMailer keep their existing boundaries; the approved infographic remains accurate. Implementation and rejection messages accepted. |
| Phase 4 — modern ESMTP | Steps 6 through 9 are complete and accepted. The #748 production diff review completed on 28 September 2026; the separate performance audit is tracked under #749. |
| Phase 4, step 6 characterization, 2026-09-15 | Reviewed unchanged at #736's test-only DSN checkpoint. Existing message-wide DSN settings remain outside MIME and do not leak between pooled sends; exact content and duplicate envelope entries are preserved. The subsequent approved slice is recorded below. |
| Phase 4, ENVID-only implementation, 2026-09-15 | Overview remains accurate. Under the existing transport send monitor, the adapter checks DSN on the actual connection, chooses a fresh UUID or the Email's fixed identifier, and adds ENVID to a fresh message facade. The effective identifier travels through the existing result/receipt path, not shared state. No new owner, lock, worker or resource lifetime is introduced; lease cleanup and observer ordering remain in their existing layers. Identifier-isolation and pre-submission rejection tests cover pooled/shared sends. ORCPT and recipient-specific NOTIFY remain deferred; this is not Phase 4 completion. |
| Recipient NOTIFY / automatic ORCPT implementation, 2026-09-16 | Overview remains accurate. Immutable per-attempt recipient options use the existing managed Angus command hook and transport monitor, and are cleared in finally. No new lock, worker or resource lifetime is introduced. Pre-MAIL capability failures retain healthy leases; transaction failures retain invalidation. Duplicate-recipient, concurrent-pool and pool-size-one observer-reentrancy tests cover policy and response isolation. This separately approved local follow-up is uncommitted for review, not Phase 4 completion. |
| Phase 4, step 6 completion, 2026-09-17 | ENVID (#736), shared Email configuration (#737), and recipient NOTIFY/ORCPT (#738) passed final review and are accepted. The approved overview remains accurate: the work stays within preparation, the existing transport owner and outcome reporting. No new lock, worker or resource lifetime was added. The earlier local checkpoints above are historical; the rest of Phase 4 remains pending. |
| Phase 4, per-message REQUIRETLS implementation, 2026-09-17 | Reviewed unchanged and accepted under #741. The Email flag follows the existing preparation and provider-envelope path. Angus validates TLS, trust, identity and the post-TLS capability under the existing transport send monitor, then reports actual MAIL FROM use through the existing transport result and receipt. Its per-message facade state is not shared between sends. No new owner, lock, worker or resource lifetime was introduced. |
| Phase 4, per-message SMTP content negotiation, 2026-09-19 | The approved overview remains accurate for #742: layers and resource ownership are unchanged. Owned stock Angus Sessions enable UTF-8 once before MIME or transport creation. The selected Session's configuration supplies immutable legacy permission in `PreparedMail`. The adapter checks actual requirements against that connection's post-STARTTLS capabilities, and its existing message facade carries per-submission command choices. The managed transport installs and clears these choices in `finally` under its existing send monitor. No Session policy toggling, additional lock, worker, pool or reconnect is introduced. Custom socket factories retain protocol hooks without acquiring unsupported physical-abort capability. |
| Phase 4, step 9 SIZE preflight, 2026-09-26 | Reviewed unchanged for the local #748 implementation. Counting and the connected limit check run under the existing Angus transport monitor after provider conversion, before MAIL FROM. Abort uses the existing flag; only complete counts enter the per-attempt facade/result/receipt. Local compatibility rejection keeps a healthy pooled lease reusable. No new worker, lock, buffer owner or resource lifetime is introduced. The existing image and website copy remain accurate; user review is still pending. |
| Phase 4 completion, 2026-09-28 | #748 and its diagnostic-budget correction are accepted. The overview remains accurate: the complete operational SIZE scan, per-attempt measurement, cleanup and outcome propagation use the existing transport owner and monitor. No new ownership or resource boundary was added. The earlier review-pending checkpoints are historical. |
| Performance follow-up, 2026-09-28 | Reviewed unchanged for accepted #749. Body inspection remains attempt-local. Provider factory metadata is cached per application class loader; initialization synchronization stays outside adapter construction/calls and owns no send state or transport resources. Send workers, leases, cancellation and outcome ordering retain their existing boundaries. SIZE behavior and all content/security checks are unchanged; no performance-control API is added. The master, website copy and #722 embed remain accurate. |
| Phase 5 completion, 2026-09-29 | #747 is accepted after the [hosted conformance run](../../tools/smtp-conformance/README.md#first-hosted-run). The [scenario matrix](../../tools/smtp-conformance/README.md#what-each-layer-proves) exercises existing preparation, provider, lease, cancellation and observer boundaries. Shared peers and real-MTA delivery captures are test infrastructure, not new production owners or locks. The overview remains accurate, and the master, website copy and #722 embed use the same approved image. All five robustness phases are complete; this is not a release. |
| 10.0.0 release | Pending. Reconcile the final implementation, catalogue, website image, and umbrella issue before publication. |
| Sending limits, local #751 integration, 2026-10-01 | The resource layers in the overview remain useful, but it is not a complete gate/lock diagram. Factory-local allowance now adds a bookkeeping monitor before ordinary connection acquisition, with per-email gates inside retained scopes. The [sending-limit page](09-sending-limits.md) and catalogue graph show that detail. No worker, proxy or transport owner is added. The artwork and website copy are unchanged; revisit the overview wording at final feature acceptance. |
| Sending limits, holistic-review corrections, 2026-10-02 | Allowance ownership is explicit on the Mailer and pool registration, with provisional construction rollback and selection ownership checks. These are bookkeeping changes within the existing layers, not new resource owners. The image remains a responsibility overview; [sending-limit concurrency](09-sending-limits.md) supplies the detailed locks and transitions, and the website now explains the pre-acquisition wait and retained-scope exception. No image regeneration is needed. |
| Per-email recipient rejection, #754 acceptance, 2026-10-05 | Reviewed unchanged. A nullable choice travels through Email governance and the attempt's envelope to the provider-owned message facade. Dispatch checks adapter support; Angus checks the selected Session's incompatible advanced setting under its existing transport monitor. No new worker, lock, scheduler, lease or resource owner is introduced. [The provider concurrency page](07-angus-transport-abort.md#per-email-recipient-rejection-handling) records the submission and healthy-lease boundaries. The master and website copy remain identical; production-only and holistic reviews are complete. |
| Connection failover, local #755 architecture review, 2026-10-10 | Updated the five-ring artwork for the [current implementation](../failover/architecture.md). Entry wording now places async preparation on the executor. Ring 03 names `MailConnectionRoute`, `ConnectionRecovery` and the cleanup owner `AcquiredMailTransport`; routing happens before submission, and recovery remains operation-led bookkeeping. Pool selection, provider setup evidence and connection-history reporting are visible within the existing layers. The canonical PNG and website asset are identical, and the website revision query follows the updated image hash. This is a local artwork update; GitHub/publication surfaces still require publication. |
| Website image optimization, 2026-10-11 | The user optimized the website PNG to 366,973 bytes, an 82.1% reduction from the 2,050,207-byte master. Its encoded PNG color type is 3 (indexed color); the master retains color type 2 (RGB). Both remain 1536 × 1024 with the same architecture wording and layout. The website component now uses the derivative's own `64c285f68384` revision. The maintenance workflow permits optimized encoding differences while keeping content aligned with the canonical master. |
| Connection failover, recovery-contract corrections, 2026-10-11 | Resource layers remain unchanged. Recovery now distinguishes an observation generation from a recheck visit's cancellation lifetime; connection failures retain the public `MailException` boundary. The [recovery transition table](10-connection-failover.md#publication-rules), rendered state/sequence diagrams and public retained-scope documentation describe the details. Failover-enabled batches/callbacks can retain a selected pool lease, while ordinary scopes remain dedicated. No artwork or website image was modified for these corrections. |
| Connection failover, explicit recovery-state refactor, 2026-10-11 | Reviewed unchanged: `ConnectionRecovery` now manages private state objects and explicit observation owners. Routing returns an owned connection scope before submission; upstream claim identity connects foreground observations without ambient ownership. These are refinements within ring 03 and the existing resource owners, not another executor, transport layer or public health model. Updated state/sequence/class diagrams render successfully. No raster or website artwork was changed. |
| Connection failover, architecture and review-seam refresh, 2026-10-12 | Rechecked the current SJM and upstream claim-context implementations. The [architecture guide](../failover/architecture.md) now shows `ReadyMailSend` and common `RoutedConnectionScope` ownership, the acquisition-only routing catch, explicit observation generation/visit lifetimes and provisional pool handoff. Its deeper-review priorities follow those boundaries. These helpers refine ring 03; claim identity refines ring 04. The representative five-ring artwork remains accurate, and both the canonical PNG and the user's optimized website derivative are preserved. |
| Connection failover, Session ownership correction, 2026-10-12 | A construction token now reserves each Session/Properties object for one live Mailer before installing configuration. Successful rollback or completed shutdown retires it; failed cleanup cannot free another owner's context. This tightens ring 03's existing destination/lifecycle boundary without adding a resource layer. The [pool ownership contract](06-pool-claims-and-leases.md#session-ownership-before-registration) and migration notes document the deliberate rejection of simultaneous Session sharing. No artwork or website image changes are needed. |

## Retry review, 2026-10-13

Reviewed for #756: the five resource layers still apply. Retry coordination adds one lazy dispatcher within execution control, bounded pending tickets and attempt-owned replay bytes; it is not another transport layer or a durable job queue. The current overview is intentionally non-exhaustive, so use [retry concurrency](11-mail-send-retries.md) for these new actors and the updated [operation](01-send-operation.md) and [shutdown](02-mailer-lifecycle.md) diagrams. No raster regeneration or concurrent website artwork changes were made.

## Telemetry review, 2026-10-15

Reviewed for #762: the optional integration observes existing operation, submission and terminal boundaries. SDK resources stay application-owned;
context scopes are local to execution turns and do not add another worker, connection, lock owner or state machine. The overview remains accurate.
See [telemetry concurrency](13-mail-send-telemetry.md) for scope retirement, retry suspension and independent observer delivery. No image changes.


## Local preparation and submission facts

The [local preparation boundary](01-send-operation.md#local-preparation-and-provider-entry) remains inside the existing Mailer/execution/transport resource layers. A connected socket alone supplies no submission facts; a local MIME/envelope mismatch completes as PREPARATION with no receipt. The shared responsibility overview and artwork keep the same owners and layer relationships. Use the operation guide and ADR 0011 for classification and narrow lease-health provenance under [#865](https://github.com/bbottema/simple-java-mail/issues/865); no new resource owner or graphical layer follows.

## Authenticated SOCKS setup capacity, 2026-10-29

The canonical artwork's five responsibility layers remain accurate for #921: the internal proxy setup executor and forwarding handoff are not pictured as application send workers or transport-pool leases. The [bridge guide](14-authenticated-socks-bridge.md) now maps those inner actors. Established tunnels release setup workers while retained/pooled transport ownership, send supervision, cancellation requests and terminal outcomes keep their existing layer relationships. The master, optimized website derivative and umbrella-issue embed require no image change.
