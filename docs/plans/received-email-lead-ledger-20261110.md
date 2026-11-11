# Received Email delivery coordination, 10 November 2026

The approved implementation packet and design ledger govern this work. The maintainer explicitly authorized implementation, delegation, lead acceptance, selective publication to `codex/10.0.0` and website `codex/10.0.0-content`, then #961 completion. No further approval is needed within that contract. Release and deployment are excluded.

## Baseline and ownership

Task root: `C:/workstation/sjm-received-email-overhaul-20261110/`. Source and nested website are isolated registered worktrees. Baseline tracked/index patches and status inventories are outside source under `baseline/`; they distinguish concurrent Angus/dependency, workflow, private configuration, Journal/case-study/style and test work from feature edits. The original checkout remains untouched. The private test properties are copied locally and excluded from all staging/public artifacts. The temporary website PDF passthrough is excluded from publication.

The pre-code checkpoint is complete through the six source-grounded inventories linked below. The lead reviewed/froze the core member/coverage/check manifest, one-layer optional-module result, main-owned repeated-layer selection and occurrence tracking, and helper/output boundary before production implementation. The released descriptor inventory includes published 9.3.10 `javap` output, not only Lombok source. Source/target/migration cases remain separate.

| Responsibility | Owner / inventory |
| --- | --- |
| Core evidence/read contracts, key normalization, pure requirements | inspection_core / [core inventory](received-email-core-inventory-20261110.md) |
| Main logical selection, parser/mapper, Email/resource contracts, copy/edit associations | inbound_model / [inbound inventory](received-email-inbound-inventory-20261110.md) |
| Optional S/MIME/OpenPGP one-layer receive processing | crypto_receive / [crypto inventory](received-email-crypto-inventory-20261110.md) |
| Helper options, final output/send boundaries and local conversion consent | output_guard / [output inventory](received-email-output-inventory-20261110.md) |
| Published baseline, migration fixtures, focused ADR/root/companion upkeep | migration_upkeep / [migration inventory](received-email-migration-inventory-20261110.md) |
| Website/reference/tactical/runnable/downloads | website_patterns / [website inventory](received-email-website-inventory-20261110.md) |
| Outlook native body getters/derivation/date/child integration, CLI adapters, build coordination, combined review/publication | lead |

No owner may revert another's edits or publish independently. Main shared Maven builds are sequenced through the lead. A separate baseline-source worktree runs behavioral reds without blocking production edits.

## Lead-owned concrete inventory

- Native bodies: forward-port published `OutlookMessageData.getBodyHTML/getBodyRTF` to the core read interface and both immutable implementations, including `InspectionOutlookData.copyOf`. Capture once in `OutlookEmailConverter.extractOutlookMessageData`; getters remain passive and nullable for old snapshots.
- Native HTML selection: native HTML wins even when empty; otherwise use the already reader-generated converted HTML. `EmailFromOutlookMessage.getHTMLDerivation` carries native/RTF encapsulated HTML, encapsulated text, rendered RTF or unknown. Detection follows the actual rtf-to-html reader's mode selection; no second MSG acquisition or HTML conversion.
- Native dates: replace the reader's generic creation-derived fallback with client-submit time, otherwise actual Date interpreted by the existing shared received-date grammar. Native `getDate/getCreationDate` remain historical evidence. Unknown zones/impossible rollover remain unavailable.
- Native children: the optional module materializes a child through `InternalEmailConverter.completeOutlookChild(result, childScope)` under the same main receive configuration/allowance, then attaches that immutable Email using internal `ParsedAttachmentResource.withEmail`. Generated EML stays selected payload, not original native input. Root source capture reuses the child's source identity and aggregates Problems once.
- Native occurrences: preserve ordered duplicate file occurrences; select associated CIDs conservatively and keep unused/colliding resources as ordinary attachments. Do not classify independent protected files by filename/count.
- CLI: preserve existing scalar helper entry points through internal adapter interfaces; exclude typed option objects from direct CLI reflection. No factory property/lock/Spring schema is added for read-only evidence, application checks or explicit per-operation permission. Verify actual generated metadata/package help after the final API freeze.

## Acceptance and evidence

Run real eager protected-body/native-HTML/independent-child red first, alongside confidential helper output and native creation-only date reds in baseline-source. Main owners implement real selected content/output green cases, not just compile checks. Preserve ordinary/exact/budget/cache/cancellation/module-absence controls. The final combined source must pass actual JDK11 library/Javadoc and JDK17 full/CLI gates, with XML inspection and enabled/skipped counts recorded privately under `evidence/`.

Before publication, freeze target artifacts for migration/consumer/downloaded examples, complete rendered website review and runnable partner decision scenarios, measure bounded original/decoded/copy/snapshot retention, obtain a separate production-only review, and personally inspect the complete diff. Feature-only staged changes must exclude recorded baseline work and private/test-generated artifacts. Publish website first, then parent with its correct gitlink; verify both remote heads before the behavior/usage completion comment, issue closure and Done status.

Retired direct legacy-model test mapping: `api/email/OpenPgpDetailsTest` only checked a two-MiB mutable flat-result byte array. Its complete-input defensive-array and ordinary-copy coverage is now the real-input `ReceivedOriginalOwnershipTest.largeCompleteOriginalIsDetachedFromSuppliedAndReturnedArraysAndSurvivesCopies`; the same suite covers actual signed input snapshots, while the OpenPGP receive suites retain family-specific original-input cases. No separate legacy-model builder remains to exercise.
