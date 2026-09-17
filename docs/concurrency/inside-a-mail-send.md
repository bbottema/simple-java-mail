# Inside a mail send — shared infographic

This is the maintenance source for the [architecture infographic](assets/inside-a-mail-send.png). Keep its wording and the approved artwork together so updating it does not depend on finding the original image-generation conversation.

## Scope

- Version shown: Simple Java Mail 10.0.0 development line.
- Path shown: an ordinary pooled asynchronous send with managed Angus transport.
- Last reviewed: 2026-09-10, against the Phase 2 working-tree implementation under review.
- Not a release-status graphic, a lock-order graph, or an exhaustive map of every send path.
- Later phases remain proposals until implemented. Do not add them to the picture ahead of the code.

The outer layers group responsibilities rather than literal object containment. The reporting bracket spans the send attempt; observation does not start only after the socket finishes. Synchronous sends, simple batches, open connections, custom mailers, and caller-owned Sessions have their own paths and contracts in the catalogue and user documentation.

## One master, shared publication

| Surface | Source or consumer |
| --- | --- |
| Canonical artwork | [`assets/inside-a-mail-send.png`](assets/inside-a-mail-send.png) in this repository |
| Editable wording and update brief | This Markdown file |
| Concurrency catalogue | [Overview in the index](README.md#start-with-the-overview), using the local master |
| Website asset | `simplejavamail.org/src/assets/architecture/inside-a-mail-send.png`, an exact copy of the master |
| Website component | `simplejavamail.org/src/_includes/components/mail-send-architecture.hbs`, shared by any page that embeds it |
| Website entry point | `sending-and-execution.html#section-send-architecture`; diagnostics and result guidance link there |
| Umbrella issue | [#722](https://github.com/bbottema/simple-java-mail/issues/722), embedding the master through the development branch's raw-file URL |

Do not edit the website copy independently or upload a separate permanent GitHub issue attachment. The issue's branch URL follows updates to the canonical asset. Refresh its `v` query value to the new image's SHA-256 prefix when the artwork changes, so an image proxy does not keep showing the previous revision. The website component uses the same revision value.

For the current edition, that revision is `6678f77dd107`.

## Text and layout source

Preserve the five-layer cutaway onion, numbered callouts, readable class names, and the separate connected reporting band. Update the relevant callouts in place instead of redesigning the whole image. No slogans, handwritten asides, scope glossary, batch footnotes, or disconnected completion diagram.

Title: **Inside a mail send**

Subtitle: **Simple Java Mail 10.0.0 · layers, responsibilities and control**

Path caption: **Illustrated path: pooled async send with managed Angus transport**

| Layer | Label | Role text |
| --- | --- | --- |
| 01 — Entry & supervision | `MailerImpl` | Prepares and validates on the caller thread. |
| 01 — Entry & supervision | `MailSendOperations` | Tracks active calls and coordinates shutdown. |
| 02 — Execution & control | `MailSendExecutor` | Admission, queue and send workers. |
| 02 — Execution & control | `MailSendOperation` | One call: start, stop and result completion. |
| 02 — Execution & control | `MailSendControl` | Deadline budget and registered stop actions. |
| 03 — Per-email send | `SendMailClosure · TransportRunner` | Execute the send and manage transport cleanup. |
| 04 — Connection pool | `BatchTransportEngine · transport lease` | Borrow, release or invalidate a connection. |
| 05 — SMTP & network | `AngusMailTransportAdapter` | Captures provider-specific send facts. |
| 05 — SMTP & network | `ManagedAngusTransport` | Delegates SMTP to Angus; tracks commit and socket abort. |

The core is labeled **Socket**. The dashed control route is labeled **Cancellation / deadline signals**, with **Stop requested ≠ work finished** alongside it. It represents stop/abort signals, not Java thread interruption or proof that SMTP did not accept the message.

The separate **Outcome reporting** band starts with:

> Follows each email through the layers above, then reports how the attempt ended.

Its three connected stages read:

| Stage | Role text |
| --- | --- |
| `MailSendAttempt` | Collects Message-ID, timings and the final outcome. |
| `MailSendObserverNotifier` | Passes the outcome to your observer. |
| Your application | Receives the callback inline or on your configured executor. |

Never turn SMTP acceptance into an inbox-delivery claim. Keep the reporting bracket connected to all layers, not just the SMTP socket. Check the generated lettering as well as the architecture before accepting a revision.

## Phase completion check

Review this overview at the end of **every remaining step**, at each phase boundary, and again before the 10.0.0 release. Also review it whenever a change affects one of the pictured responsibilities. This is a human semantic review: matching file hashes can catch a missed copy, but cannot establish that the picture still describes the code.

1. Compare the class names and responsibilities above with the implementation and relevant [state-machine pages](README.md#start-with-the-question-you-have). Check new connections as well as renamed or removed classes.
2. Decide whether the high-level picture changed. A new capability can fit inside an existing layer without needing another box. If unchanged, record that decision and its reason; do not regenerate the image merely to advance the phase number.
3. If changed, update this wording source and edit the canonical PNG together. Keep planned behavior out of the image and preserve the simpler reporting layout.
4. Copy the approved PNG into the website asset path and update the website component's revision query. Reuse the component wherever the website needs the image.
5. Check the catalogue's relative image link, the website's inline and full-size views, and the image in #722. On an artwork change, update #722's existing embed and its revision query rather than adding another competing diagram comment. Do not claim publication until the relevant repository changes have actually been pushed.
6. Record the review in the ledger below before completing the child issue or phase. Repeat the check before release; versioned release documentation should retain the diagram for its own implementation, not silently acquire a later release's behavior.

After copying, this check from the root confirms that the website has exactly the same image:

```powershell
$master = (Get-FileHash docs/concurrency/assets/inside-a-mail-send.png -Algorithm SHA256).Hash
$website = (Get-FileHash simplejavamail.org/src/assets/architecture/inside-a-mail-send.png -Algorithm SHA256).Hash
if ($master -ne $website) { throw 'The website infographic differs from its master.' }
```

Then run the website check/build and internal-link verification. Those check publication mechanics, not the truth of a concurrency claim.

## Review ledger

Add a dated row for each completed step. Record the change, or explicitly say why the overview remains accurate.

| Checkpoint | Review |
| --- | --- |
| Phase 2, 2026-09-10 | Initial approved overview of the implementation under review. Shared supervision, operation control, pooled cleanup, managed Angus abort, and connected outcome reporting are represented. |
| Phase 2 completion, 2026-09-11 | Reviewed unchanged against the accepted implementation. Exceptional pooled cleanup now preserves the first failure and still waits for invalidated disposal before outcome reporting. This strengthens the pictured ownership boundary without changing any layer or responsibility; the website uses the same approved PNG. |
| Phase 3 — diagnostics and authentication policy, completion 2026-09-15 | Reviewed unchanged against the accepted #733 and #735 implementations. The dedicated probe remains outside the send path; the mandatory-STARTTLS guard runs before send-operation, proxy, lifecycle and pool setup. Neither changes the pictured owners, locks, lease boundaries or outcome reporting. The master, website copy and #722 embed retain the approved image. |
| Phase 3, step 4 Java probe, 2026-09-11 | Reviewed unchanged. `SmtpConnectionProbeAdapter` and its dedicated Angus inspection transport are outside the illustrated send path: no Email, pooled send lease, deadline controller or observer is involved. The new path reuses proxy accounting but adds no state to `ManagedAngusTransport`. Step 4 CLI follow-through and the separate authentication-policy step remain open. |
| Phase 3, step 4a execution views, 2026-09-12 | Reviewed unchanged for #734. `Mailer.sync()` and `Mailer.async()` are cached entry adapters over the same `MailerImpl`; they own no workers, connections, pool registrations or locks. The receipt-discarding wrapper is removed, but operation/control/cleanup and observer boundaries remain the same. The diagram names implementation responsibilities, not the superseded public overloads. |
| Phase 3, step 4 CLI probe, 2026-09-15 | Reviewed unchanged for #733. One-shot and daemon commands use the same dedicated synchronous probe, with request-owned output and existing Mailer leases. Invocation-only authentication stays outside the reusable Mailer profile. Retaining supplied SMTP settings in logging-only mode lets explicit probes use the configured endpoint without changing send execution or observation. |
| Phase 3, step 4 adapter boundary and holistic review, 2026-09-15 | Reviewed unchanged for #733. A separately packaged adapter/provider fixture now exercises the public SPI without Angus on the classpath and module path. Partial diagnostics, authentication, private Session changes and dedicated cleanup remain local to the probe. This adds evidence for the existing provider boundary, not another production layer, send lock or lifecycle owner; the root and website PNGs remain identical. |
| Phase 3, step 4 completion, 2026-09-15 | Java and CLI probe implementation, partial-provider coverage and final holistic review accepted under #733. The overview remains unchanged for the reasons above. The separate authentication-policy step still requires its own review before Phase 3 can be declared complete. |
| Phase 3, step 5 characterization, 2026-09-15 | Reviewed unchanged for the test-only [authentication/TLS investigation](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-3-diagnostics-and-security/05a-authentication-tls-characterization.md). SJM configures Sessions, Angus negotiates TLS/authentication, and the pool controls physical connection reuse. No production owner, lock, or state transition changed, and no probe-to-send dependency was added. The existing opportunistic default is retained; any strategy-hardening implementation still needs its own review. |
| Phase 3, step 5 documentation and guardrail proposal, 2026-09-15 | Reviewed unchanged. Javadoc and website explanations now distinguish absent STARTTLS from a failed upgrade. The [proposed configuration check](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-3-diagnostics-and-security/05b-mandatory-starttls-configuration-guardrail.md) is not implemented; no runtime owner, lock, transition or infographic content changed. |
| Phase 3, step 5 construction guard, 2026-09-15 | Reviewed unchanged for #735. `MailerImpl` rejects a contradictory mandatory STARTTLS override before send-operation, proxy, lifecycle or cluster setup. This adds no send owner, lock, state transition or probe-to-send dependency. Caller-owned Sessions and CustomMailer keep their existing boundaries; the approved infographic remains accurate. Implementation and rejection messages accepted. |
| Phase 4 — modern ESMTP | Pending. Check where DSN, REQUIRETLS, SMTPUTF8/8BITMIME, and SIZE requirements are decided and enforced. Extend a role only when that changes the overview. |
| Phase 4, step 6 characterization, 2026-09-15 | Reviewed unchanged at #736's test-only DSN checkpoint. Existing message-wide DSN settings remain outside MIME and do not leak between pooled sends; exact content and duplicate envelope entries are preserved. The subsequent approved slice is recorded below. |
| Phase 4, ENVID-only implementation, 2026-09-15 | Overview remains accurate. Under the existing transport send monitor, the adapter checks DSN on the actual connection, chooses a fresh UUID or the Email's fixed identifier, and adds ENVID to a fresh message facade. The effective identifier travels through the existing result/receipt path, not shared state. No new owner, lock, worker or resource lifetime is introduced; lease cleanup and observer ordering remain in their existing layers. Identifier-isolation and pre-submission rejection tests cover pooled/shared sends. ORCPT and recipient-specific NOTIFY remain deferred; this is not Phase 4 completion. |
| Recipient NOTIFY / automatic ORCPT implementation, 2026-09-16 | Overview remains accurate. Immutable per-attempt recipient options use the existing managed Angus command hook and transport monitor, and are cleared in finally. No new lock, worker or resource lifetime is introduced. Pre-MAIL capability failures retain healthy leases; transaction failures retain invalidation. Duplicate-recipient, concurrent-pool and pool-size-one observer-reentrancy tests cover policy and response isolation. This separately approved local follow-up is uncommitted for review, not Phase 4 completion. |
| Phase 4, step 6 completion, 2026-09-17 | ENVID (#736), shared Email configuration (#737), and recipient NOTIFY/ORCPT (#738) passed final review and are accepted. The approved overview remains accurate: the work stays within preparation, the existing transport owner and outcome reporting. No new lock, worker or resource lifetime was added. The earlier local checkpoints above are historical; the rest of Phase 4 remains pending. |
| Phase 4, per-message REQUIRETLS implementation, 2026-09-17 | Reviewed unchanged and accepted under #741. The Email flag follows the existing preparation and provider-envelope path. Angus validates TLS, trust, identity and the post-TLS capability under the existing transport send monitor, then reports actual MAIL FROM use through the existing transport result and receipt. Its per-message facade state is not shared between sends. No new owner, lock, worker or resource lifetime was introduced. |
| Phase 5 — conformance | Pending. Compare the illustrated boundaries with the conformance scenarios; do not add an unqualified safety or delivery claim. |
| 10.0.0 release | Pending. Reconcile the final implementation, catalogue, website image, and umbrella issue before publication. |
