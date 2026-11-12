Delivered on codex/10.0.0 at 1a2a1d74a4f172780a899af364f70121646fad65 and codex/10.0.0-content at a0f5007e10f69cbb10d99c8ac7580aaf23262783. Approved issues #900 through #907 are closed/completed and Done; configuration-source maintenance has no issue. Only guarded scratch/plan cleanup remains pending because the existing cleanup script stops at an older node_modules reparse point.

# ConfigLoader required source inputs

The maintainer approved finding 12 as maintenance on 29 October 2026 and explicitly waived a GitHub issue. Design, implementation and delivery on the current `codex/10.0.0` branch are authorized; the lead reviews the combined diff and evidence before committing or pushing. ADR 0031's required-input rule and ADR 0003's lazy source sampling govern this correction.

Implementation and the selected local checks are complete. Remaining work is the lead's combined-diff acceptance, shared documentation reconciliation and selective delivery. Retire this plan after acceptance; the existing ADRs and public loader Javadoc retain the contract.

## Contract and ownership

Reject a null source, property map, Properties object, file path, resource name or ClassLoader before registering that source. A rejected registration leaves the loader usable with all earlier sources. Preserve deferred source-name/content validation, optional missing classpath resources, per-load sampling and precedence, snapshot isolation, stream ownership and existing supported values.

The configuration worker owns `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java`, its focused core regression tests and this plan. Other workers are active; preserve their edits and the shared index. The lead owns shared release notes, ADR index, migration plans, website pages and cleanup allowlists. No daemon implementation is part of this change.

## Paths and scenarios

Public source registration flows through `withSource`, map/Properties wrappers, file/resource wrappers and snapshot copying into `load()`. Check required references before wrappers enter `sources`; do not sample source data or probe files while registering.

| Scenario | Expected behavior |
| --- | --- |
| Required reference is null through each source-registration overload | Fail at registration; previous loader configuration still loads and accepts later valid sources. |
| Custom source or mutable caller map is registered | Remain unread until each `load()`; subsequent loads sample the current values once. |
| A required file is missing at registration | Registration succeeds; loading retains its existing missing-file error. |
| File contents change after registration | Each load sees the then-current contents; prior snapshot stays detached. |
| An optional classpath resource is absent | Registration remains lazy; loading contributes no values. |
| Source name or returned content is invalid | Existing validation remains at `load()`. |
| Caller stream is used | Existing immediate consumption and close behavior remain. |

## Surface alignment

Only core loader validation and its public Javadoc change. No signatures, typed keys, configuration grammar, source precedence, CLI builder contracts/cache inputs, Spring metadata or optional-module behavior change. Existing ADR 0031 supplies the decision. The lead reconciles any release note and migration guidance; this maintenance correction does not create a 9.x rewrite recipe.

## Verification and disposable files

Add focused failing null-registration regressions first, then the guards and unchanged-behavior controls. Run the selected core tests in an isolated HEAD-plus-overlay snapshot on JDK 21 and real JDK 11. Use the `test` phase for this narrow run; do not claim the full configuration contract gate or full reactor was run. Generate current core Javadocs if the updated API prose requires publication inspection.

All disposable files for this task are contained under `.local/feature-review-config-inputs-20261029/`: `source/` is the isolated snapshot, `source.tar` bootstraps it, and `logs/` retains command output. The lead adds this exact root and the retired plan to the guarded cleanup allowlists. No deletion is authorized by that bookkeeping alone.
