Delivered on codex/10.0.0 at 1a2a1d74a4f172780a899af364f70121646fad65 and codex/10.0.0-content at a0f5007e10f69cbb10d99c8ac7580aaf23262783. Approved issues #900 through #907 are closed/completed and Done; configuration-source maintenance has no issue. Only guarded scratch/plan cleanup remains pending because the existing cleanup script stops at an older node_modules reparse point.

# Preserve calendar choices when required replacement inputs are missing

Issue: [#904](https://github.com/bbottema/simple-java-mail/issues/904). Governing decisions: [ADR 0031](../adr/0031-public-api-and-internal-construction.md#required-choices-and-explicit-removal-accepted-22-october-2026) and [ADR 0002](../adr/0002-email-defaults-and-overrides.md#calendar-method-and-text-form-one-value-accepted-22-october-2026).

The maintainer approved the required-input correction, implementation, delegation and delivery on 29 October 2026. The root agent reviews the actual diff before serialized delivery to `codex/10.0.0`; related shared documentation is coordinated by the root agent.

`withCalendarText(method, text)` must reject either null argument before changing either stored value. An earlier valid pair and previously built Emails survive rejection; an absent calendar stays absent. `clearCalendarText()` still removes both values and permits ordinary template fallback. Non-null free-form text, including empty text, remains accepted without parsing iCalendar contents. Imported-calendar handling is unchanged.

The calendar worker owns the setter in `EmailPopulatingBuilderImpl`, the matching public Javadoc, `CalendarRequiredInputTest`, a narrow ADR 0031 clarification, genuine CLI metadata regeneration if required by the Javadoc change, and this plan. Other workers are active; preserve unrelated source/index changes. The root agent owns release history, the ADR index, migration inventories, shared website pages and cleanup allowlists.

The affected path is public calendar composition through `buildEmail`, ordinary copying and completion. Focused checks cover null method, null text and both null on populated and blank drafts; retained snapshots; successful free-form replacement; clearing and template fallback. Existing `CalendarTemplateGovernanceTest` supplies calendar precedence, suppression, serialization and exact-EML controls. Run these suites red first, then green on real JDK 11 and JDK 21 from a task-owned source snapshot at `.local/feature-review-calendar-20261029/source`; keep all outputs within that scratch root and use current reactor dependencies.

The documentation sweep includes the Java contract, ADRs, CLI help/cache inputs, root guidance, website calendar guide, migration guide, Journal/case studies and OpenRewrite inventories. No signature, configuration key, Spring metadata, serialization shape or optional-module contract changes. The issue remains open and In Progress until the root agent accepts the complete implementation and delivery.
