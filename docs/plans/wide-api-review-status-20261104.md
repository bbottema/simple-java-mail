# Wide/deep public API audit: current position

Last reconciled: 4 November 2026. This file records where the original audit paused while the Email inspection design expanded.
The [Email inspection roadmap](email-inspection-roadmap-20261104.md) owns the implementation status/specification of #961.
Do not infer a new unresolved finding from older retired plans or from the length of that design discussion.

Current outcome: #961 is delivered and closed/completed, its project item is Done, and the
[AFTER API example and resolution](https://github.com/bbottema/simple-java-mail/issues/961#issuecomment-6642682274) are posted.
The original review's implementation and documentation follow-ups are complete for unreleased 10.0.0; #965 remains explicitly deferred.

## Original review and dispositions

The original read-only round reviewed six caller-visible lanes: composition/templates, import/conversion, protection, SMTP/connections,
sending/lifecycle and configuration/integrations. Its review baseline was main `6eddfa3cc1d5641647b5de8dc4ea80d2a04f3e3c` and website
`3122a1bd551e7b8f259d9f768899751586ae7843`. The consolidated list had seven findings.

| Finding | Disposition | Tracking |
| --- | --- | --- |
| 1. Daemon startup logs could truncate through a symlink/nonregular target | Delivered; owning issue closed. | [#488](https://github.com/bbottema/simple-java-mail/issues/488), main `7e0cf4303` |
| 2. DKIM helper example ignored the returned signed message | Delivered; owning issue closed. | [#855](https://github.com/bbottema/simple-java-mail/issues/855), main `0a112bd16` |
| 3. Stream-valued plain/HTML bodies were stringified | Delivered with the chosen content-handler charset/ownership behavior; issue closed. | [#959](https://github.com/bbottema/simple-java-mail/issues/959), main `fdff5f2b6` |
| 4. S/MIME helper Message-ID handling appeared to replace an input ID | Existing identity decision retained; helper documentation/usage clarified and delivered without a new issue. | Main `880aae8b7`, [ADR 0008](../adr/0008-minimal-mime-structures-and-protection-order.md) |
| 5. Reused supplied Sessions could retain a conflicting historical OAuth strategy marker | Delivered; owning issue closed. | [#958](https://github.com/bbottema/simple-java-mail/issues/958), main `a2eb1d953` |
| 6. Protected Outlook content merging could omit Calendar fields | Delivered, including copied/reset merge behavior; issue closed. | [#960](https://github.com/bbottema/simple-java-mail/issues/960), main `0a568fe47`, [ADR 0040](../adr/0040-retained-protected-content-merging.md) |
| 7. README starter/core versions appeared inconsistent | Finding withdrawn under the maintainer's rule: all current 10.0.0-branch docs use unreleased 10.0.0, regardless the POM build version. The approved README/version-policy alignment is delivered separately. | Ticketless documentation commit `7531595fc251ca127cd2d9dc7867f611d9a3516a`; no new defect/feature issue |

These dispositions have been reconciled with current local history and live issue states. Delivered development-branch work is not a
published release. No original numbered finding remains undiscussed.

## Follow-up that became the current major feature

After the Calendar/documentation discussion, received-header exclusions and conversion failures raised the broader question of whether
Email should preserve inspectable received evidence independently of outgoing composition. That became [#961](https://github.com/bbottema/simple-java-mail/issues/961),
including generic source data, problem origins/history, partial body/resource recovery, selective originals and optional non-Email output
diagnostics. The final retention/Problem distinction is settled in [ADR 0041](../adr/0041-email-source-inspection-and-problems.md).

On 4 November the maintainer authorized a dedicated subagent to implement and complete the entire review workflow. The owner is
`/root/email_inspection_delivery`. Root updated #961's problem statement/accepted direction and applied the exact canonical `major feature`
label. Implementation, root combined review, documentation and selective delivery are complete. Main feature commit is
`734058b7874084e72cfdb04aaf3944a86c4555b7`; website `ad01c8fa29153553f8d99962820b6a1e6656f2bf` was pushed first.
Root independently read back the remote heads, closing comment, completed issue and Done state. Unrelated staged/dirty work was preserved.

Whole-Email Java serialization without optional producer classes is explicitly deferred to child [#965](https://github.com/bbottema/simple-java-mail/issues/965),
Parked on the existing board without a milestone. It is outside #961 acceptance and does not block completion of the current feature.

## Remaining coordination

- #961 and its separate approved README/maintainer version-policy companion are delivered. No publication, closing-comment or board-state
  step remains for this item.
- Historical scratch/retired plans remain registered in the guarded cleanup script. The old Calendar dependency-junction deletion rejection
  is separate; do not bypass it or make broad cleanup part of this feature.
- There is no pre-existing next numbered finding queued from this seven-item list. A further finding requires fresh source/usage/ADR
  evidence rather than inventing another item or reopening the already accepted design.

Root owns this audit-status file; the Email delivery worker should not include it in feature staging. Update it when the outstanding
delivery/documentation companion changes state, and preserve settled decisions in permanent ADRs before any plan retirement.

## #961 workflow correction checkpoint

On the maintainer's request, root checked the actual #961 process against all eight review-workflow stages. The earlier local-delivery
stall, late detailed preflight, retrospective portions of the baseline-red evidence and insufficient expanded-feature before example
are recorded in the owned [root checklist audit](../../.local/email-source-inspection-20261103/implementation-20261104/root-workflow-audit-20261104.md).
The GitHub before example was corrected and the documentation-context follow-up completed. Root inspected the actual combined code,
confirmed final corrections, selected trees and final evidence before granting publication clearance. The accepted implementation was
published, the AFTER-first resolution posted, and #961 closed/completed and marked Done. Scratch/spec retirement paths are registered
with the existing guards and WhatIf support; no broad deletion or old Calendar-junction retry occurred.

The historical process deviations remain recorded accurately. Retrospective runs have not been relabelled strict TDD, and independent
review was supplemented by actual root acceptance. There is no remaining #961 workflow blocker; #965 is the separate parked follow-up.
