# Build-time image configuration validation

Tracking: [#957](https://github.com/bbottema/simple-java-mail/issues/957), milestone 10.0.0, existing Simple Java Mail 10.0.0 project.
The same eager unused-base parsing exists in published 9.3.9; this is a separate defect in the dynamic-image feature introduced by #260.

Retired after delivery: main `9aff74d3c74a39e94e68df12584310e3c8aaad2c` and website `3122a1bd551e7b8f259d9f768899751586ae7843`
are published on their authorized development branches. #957 is closed/completed with its final usage and commit references in comment
6585398395. No implementation, companion documentation or review work remains. Only this retired plan and exact task scratch await bounded cleanup.

Automatic approval review rejected the exact guarded PowerShell deletion command before execution with "blocked by policy". Neither target
was deleted and deletion was not retried. Both exact paths remain in the guarded cleanup allowlists with literal ShouldProcess-protected
manual statements. Syntax validation passed; the scoped WhatIf passed before the manual block was added. Automatic review also blocked the
combined preparation/updated-WhatIf command. No cleanup script was executed after that rejection. The exact 12-line cleanup delta is delivered
in `a8385df51e2139eeafcf81f66e8af4b83c70eeb4`, preserving all unrelated script edits. #957 is Done on its project board. Only manual disposal
of these two paths remains; this plan is retired and disposable.
Private evidence: `.local/image-validation-20261102/library-results.json`, `cli-verification-summary.json`, `cli-final-smoke-results.json`
and website logs. Each actual JDK 11/21 lane has 156 focused passing cases, final CLI has 40 passing cases and 10 passing package/help
smokes, JAR/ZIP/TAR fresh-JVM checks pass, and generated/package Javadocs match. Website has 107 passing cases and one existing
standalone-source skip, with 5,959 links across 83 pages and 52-page indexability checks passing. The lead reviewed the actual combined
changes and independent reviewer found no remaining blocker after the completion ownership distinction below was preserved.
Unrelated working-file hashes and original staged patches were preserved through delivery. The final cleanup commit contains two exact
allowlist entries and manual statements. Syntax validation passed; the earlier scoped WhatIf passed, while the updated dry-run command was
blocked as recorded above. Scratch and this retired plan remain solely for manual disposal.

The maintainer accepted the proposed design on 2 November 2026, then said "Implement" and subsequently authorized finishing this issue's
review workflow. Execution, delegation and reviewed delivery are authorized. No release, deployment or history rewrite is included.

Baseline: main `1501d2e3f821e785e2118d5c1449320d8770f009`, nested website `8954c95a2eaf43a43ae371b6916ed59fde9e8943`.
Preservation snapshots, frozen exports and execution artifacts belong only to `.local/image-validation-20261102`.
Preserve all unrelated working changes, three staged #699 blobs, nested work and registered worktrees.
The separate SMTP/SOCKS policy decision is published in `6eddfa3cc1d5641647b5de8dc4ea80d2a04f3e3c`; #922 implementation remains owned by the existing SOCKS/Angus session.

## Accepted behavior

Effective automatic image-resolution settings are resolved and validated at `buildEmail()` before image content access or returning an Email.
Ordinary inherited image-base URLs remain uninterpreted at builder creation and group application. Inactive, replaced or explicitly cleared
ordinary bases are ignored. An active selected malformed base fails at the build boundary. Callers may disable, replace or clear ordinary
inherited choices first. Mandatory activation/base/containment locks remain enforced, including inactive constraints where required.
Getters inspect supplied choices; configured restoration, group replacement, copying and repeated builds retain their meanings.
Basic argument checks and explicit standalone String-to-URL conversion remain unchanged. No new API, option or signature is introduced.

Per-Email image choices do not replace the settings of independently constructed governance templates. The no-argument completion path
still builds its defaults and overrides through the governance factory. An independently active malformed base remains invalid there;
the canonical factory-disabled malformed base can complete normally. Preserve that ownership rather than broadening this repair into
propagating local image choices across template construction.

ADRs: [0003](../adr/0003-immutable-configuration-snapshots.md#email-image-resolution-validation-accepted-2-november-2026),
[0031](../adr/0031-public-api-and-internal-construction.md) and [0036](../adr/0036-structural-message-snapshots-and-borrowed-content.md).

## Ownership and remaining work

- `image_validation` owns EmailPopulatingBuilderImpl, a focused timing regression, the necessary active-base witness adjustment in
  EmailSourcePropertyContractTest and public EmailPopulatingBuilder/ImageResolutionConfig Javadocs. Use red-first focused JDK 11/21 checks
  in frozen lanes, preserving instrumentation and inspecting Surefire XML. Cover inactive/active, replacement/clear/inheritance/restoration,
  re-enable/repeated build/copying, locks, failure before image access and recovery after failure. Existing atomic image resolution remains intact.
- `image_validation_docs` owns README, release history, both OpenRewrite inventories and clean website features/configuration/
  understanding-configuration/migration-notes pages. Inspect Journal/case studies/downloads and update only applicable clean companions.
  Run isolated website install/check/build and inspect affected rendered pages. Coordinate CLI help/metadata impacts with the code worker.
- The lead owns tracking, ADRs/index, this plan, cleanup entries, dirty-state preservation and actual combined review. Website publication
  is delegated to the documentation worker after accepted review; the lead serializes the parent gitlink, main publication and closure.
  Return any new design choices to the maintainer without broadening the approved repair.
- `image_validation_cli` owns the narrow CliEmailConfigurationBuilder help clarification and isolated fresh CLI metadata/package checks.
  Its frozen lane is `.local/image-validation-20261102/cli`; code and API inputs are recorded in its overlay manifest. Preserve normal CLI
  argument conversion, generated metadata integrity, package startup and independent governance settings.

Generated CLI metadata/help and fresh package behavior must be reconciled if public help inputs change. No invented migration recipe is needed;
target fixtures and guidance must reflect final behavior, distinguishing the released defect from unreleased API grouping.
Keep test/verification evidence in the maintainer conversation or private artifacts, never GitHub issue or commit prose.

Keep #957 open and In Progress until accepted delivery is complete. Record exact scratch and retired-plan paths in the guarded cleanup script;
adding entries does not authorize deletion, and earlier policy-blocked cleanup paths must not be retried.
