# Explicitly clear the forwarded message from an editable Email

Accepted on 5 November 2026. The maintainer agreed that copies should preserve forwarded content by default, accepted the proposed clear operation, and instructed: "Proceed with the proposal. Subagent style." In this ongoing review that authorizes implementation through the complete review workflow: verification, affected documentation/ADRs/generated metadata, reviewed selective delivery on existing SJM/website development branches and owning issue/project completion. No SJM release, replacement-forward setter, template-policy redesign or logging-only receipt repair is authorized.

Baseline main `e458b76c7f99c3a6a27a909098c803b54869cf50`, website `86ce310498495ee9cadc408bcb20f2c4351d1867`; verify current live heads. Auth registration (#967), S/MIME independent attachments (#704) and daemon paths (#488) run independently. Keep canonical dirty root/nested files, staged Angus research, untracked work and prior evidence intact.

## Approved API and behavior

Add to `EmailPopulatingBuilder`:

```java
EmailPopulatingBuilder clearEmailToForward();
```

```java
Email edited = mail.emailBuilder().copying(forwardedEmail)
    .clearEmailToForward()
    .withSubject("Updated note")
    .buildEmail();
```

Clear only the local forwarded `MimeMessage` reference, returning the same builder. Preserve subject/body/ordinary attachments/embedded resources, recipients, protection, source/problem history, suppression choices and all other fields. Do not read, clone, serialize, close or mutate the borrowed original; previously built values and the source Email remain unchanged. Copying still preserves forwarded content by default. This is an additive editing improvement also absent in 9.3.9, not a defect in `clearAttachments()`.

Clear means absence and therefore permits normal defaults/overrides fallback, consistently with ADR 0031. Existing `MAIL_TO_FORWARD` exclusions separately suppress ordinary template contributions under ADR 0002; mandatory locks retain authority. Do not make clear rewrite a `Fwd:` subject, clear other resources, suppress templates, or introduce a global forwarding-disable flag. Ordinary MIME rendering must omit the cleared forwarded message unless governance supplies another eligible source. Preserve other `message/rfc822` ordinary attachments.

Governing ADRs: 0002 (forwarded content/template precedence), 0031 (named clear/public boundaries), 0036 (borrowed content/copies), 0024 (library-owned builder contracts). Clarify existing decisions/index as needed rather than creating redundant architecture.

## Ownership and delivery coordination

Worker `forward_clear_delivery` owns isolated `C:/workstation/sjm-clear-forward-20261105/{sjm,website}` plus bounded evidence. It owns public interface/Javadocs, main builder implementation, focused behavioral/API/example tests, CLI input/help/generated-cache alignment if affected, feature docs and applicable ADR 0002/0031/0036 changes. You are not alone: do not revert others; accommodate all concurrent work. No canonical-root build/targets, broad staging or local-only shared Maven installation.

Root owns this plan, canonical cleanup script and final acceptance/alignment. Auth worker `oauth_registration_delivery` owns ROOT-AUTHORIZED combined integration worktrees `C:/workstation/sjm-review-combined-20261105/{sjm,website}`. It will merge the frozen selected patches for auth/S/MIME/daemon/this clear operation, preserving per-issue ownership and all tests. Supply it your reviewed local candidate commits/patches and exact hashes; review your combined hunks before final freeze. Do not overwrite its or any peer checkout. Root serializes publication after actual combined review/evidence.

Heavy-job policy: an unrelated SOCKS checkout is running on the host. Do not stop unknown/user processes. One bounded S/MIME final-oracle focus with `-Dtest.forkCount=1` currently goes first; your focused validation can follow after its owner signals completion/root clearance. No separate broad reactor: a single frozen combined modern Java 17 full `-Ppublish-cli` plus actual Java 11 library lane will cover all approved issues, using explicit `test.forkCount=1`, unchanged tests/timeouts/reuseForks=false, with recorded scheduling scope. Root starts it after combined freeze/resource check. Until your small-test window, read/implement/document without new JVM jobs; coordinate directly or through root.

## Workflow and verification

Read docs/API_REVIEW_WORKFLOW.md, API_EXPANSION_WORKFLOW.md, DEVELOPMENT.md, CODING_STYLE_GUIDE.md and any AGENTS. Establish released API/introduction history, then create appropriate independent enhancement tracking (not an invented released bug count), milestone 10.0.0 and existing project/In Progress. Existing forward template feature #774 is related but does not automatically own this independent editing capability; assess issue context. Record accepted contract in owning ADR(s). Public issues/comments/commits contain problem, before/after usage, compatibility and ADR references only; no commands/test counts/build claims/verification narrative. Keep execution evidence in bounded scratch/direct maintainer report.

Start with a meaningful baseline/API absence or behavioral demonstration, distinguishing compilation absence from runtime-red evidence. After implementation validate public usage and actual MIME omission, builder return identity and repeated clears, already-built/copy/source identity preservation, zero borrowed-source reads during clear/build/copy, unrelated fields and ordinary nested message attachments, defaults/overrides fallback, both-source exclusions and applicable locks. Compare current forwarding/copy/template/serialization controls. No automatic field cascading or exact-source rewriting.

Inspect CLI generation from the new no-arg public builder method. Align annotations/help and fresh `cli.data`/`therapi.data` where required; do not hand-edit binary caches. Capture precise generation inputs and emitted API/help, and compile illustrative snippets against fresh reactor outputs. Coordinate final generated resources with combined integration so they describe all accepted final sources. Keep generated/public Javadocs linked to the authoritative clear method and existing starter/getter.

Reconcile README/release history, ADR/index, website feature/migration guides, Journal/case-study/downloadable examples and both OpenRewrite inventories with exact no-change rationale. This additive clear method needs no forced migration or automatic 9.x rewrite; do not invent recipe obligations. Render/check applicable content, links and examples; do not claim browser QA if settings prohibit local browser use.

Send root actual focused production/test/doc/website diff, tests/API/generation proof and limitations before any publication; continue independent preparation while awaiting review. Give integrator exact source/test/generated hashes. Final shared-tree configuration gates, packaging/Javadocs/JPMS, CLI artifacts and same-source evidence are part of combined acceptance, not a reason to run multiple competing full reactors.

After root accepts combined result, coordinate semantic per-issue source/docs/cache commits, website-first publication and matching main gitlink on existing branches, AFTER-first resolution, closure/completed/Done and remote readbacks. No force push or unrelated cleanup. Report exact delivered commits/issues, source/evidence, owned helpers and disposable paths; root coordinates preserved-dirty canonical alignment and bounded cleanup. Do not stop at a local patch or passing focused test.
