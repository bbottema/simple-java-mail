# #979: expose native Outlook body data on the Java 8 maintenance line

Status: completed and released as 9.3.10 on 9 November 2026; #979 is closed/completed. Release commit: `ec6cb6ddf258a294800679779b4a09ce8282fe40`. This completed plan remains in place because automatic approval review rejected its retirement with only "blocked by policy"; it is not an active implementation task. The owned Maven cache is also held, while both source worktrees are retired. Private final evidence/custody is at `C:\workstation\sjm-outlook-source-bodies-9x-20261109\evidence\delivery-final.json`.

Original approval: implementation, delegation, review and the normal 9.x patch-release workflow were authorized by the maintainer on 9 November 2026. The maintainer accepted the preceding recommendation and requested this plan, a subagent to finish the workflow, and a brief public preview of the intended 10.0.0 inspection API. The completed scope below records that authorization and its boundaries.

Issue: https://github.com/bbottema/simple-java-mail/issues/979

## Ownership, baseline and authorization

Root coordinates the ongoing received-Email design and retains the actual combined-diff acceptance gate. The delegated issue agent owns the complete 9.x implementation, compatibility checks, independent review coordination, documentation, normal delivery/release, issue bookkeeping and bounded upkeep described here. It must submit the concrete patches and evidence for root acceptance before publishing implementation/release changes; this is internal review, not another user permission request. Continue independent work while root reviews. After root acceptance, finish autonomously.

You are not alone in the codebase. Do not revert others' edits or switch/clean the shared root checkout. Preserve concurrent work and accommodate changes made by others. Use isolated main and website checkouts under `C:\workstation\sjm-outlook-source-bodies-9x-20261109`, with absolute evidence paths outside those checkouts from the outset. Preserve the current root/nested work, including the received-Email design ledger, ADR0037 clarification, API_REVIEW changes, staged Angus research, private settings and cleanup script. Never stage credentials, active plans or execution evidence.

Live main `origin/develop` and `origin/master` were both `cbbbed2bd26c265ccea66b638426fe0855bf3669`, the released 9.3.9 source, when this packet was prepared. The 9.x website `origin/develop` was `c88bb321f53feb2fbb72d9a860f2d994fc8c6eb0`. Refresh and verify live refs, release tags and issue state before choosing the isolated baseline. Account for any intervening work without overwriting it. The expected next patch is 9.3.10 if the published baseline remains 9.3.9; CircleCI owns the actual version bump/tag.

The request is a 9.x patch in an explicit release discussion, with the subagent to carry it to completion. This authorizes the existing patch-release route after required review/checks: ordinary selective commits, website/main publication, stable-branch fast-forwards, the exact CircleCI patch approval, Central/assets verification, release notes and owned issue completion. It does not authorize force-pushes, history rewriting, unrelated fixes, minor/major release gates, or a merge into the ongoing 10.0.0 code branch. Do not ask the maintainer to reapprove this same release scope.

## Governing workflow and 9.x applicability

Read the current [review workflow](../API_REVIEW_WORKFLOW.md), including its current uncommitted policy text, and apply its design/execution/delivery gates, evidence policy, actual combined review and affected-surface accounting. Use the 9.x checkout's root `API_EXPANSION_WORKFLOW.md`, `MAINTAINER_WORKFLOW.md`, `DEVELOPMENT.md`, `PROJECT_MECHANISMS_CATALOGUE.md` and CircleCI configuration for branch-specific procedures. The Java 8 patch-release skill at `C:\Users\Benny Bottema\.codex\memories\skills\sjm-java8-patch-release\SKILL.md` supplies additional maintenance guidance. Existing maintainer authorization takes precedence over older approval wording.

Adapt the 10.0.0 review guide deliberately:

- Target the actual 9.x line, its real next patch milestone and existing applicable board workflow. Do not assign this maintenance release to the 10.0.0 milestone/board merely because the general review guide names them. Preserve the reporter's original issue/body and before example; use `enhancement` for this additive source-inspection capability.
- 9.x compatibility is required. The 10.0.0 clean-break policy does not permit removing/changing old 9.x constructors, methods or serialization meaning.
- OpenRewrite plans/recipes and the 10.0.0 finite-configuration inventories do not exist on this baseline and are not 9.x deliverables. Record them as inapplicable, not as invented new files. Root will account for the newly released APIs in the eventual 10.0.0 overhaul/migration work.
- Do not create a 9.x ADR tree or copy the 10.0.0 architecture wholesale. Use the maintained 9.x source contracts, Javadocs, developer guidance and release notes. The accepted principle is native source evidence independent of the interpreted Email.
- This is a library-produced result expansion, not application configuration. There are no new Email/Mailer builder settings, defaults/overrides, Spring properties, locks or CLI flags. Inspect existing adapters/generation and explain unaffected surfaces; do not mechanically add configuration to satisfy an inapplicable checklist.

## Accepted caller contract

Add exactly these nullable String accessors to `org.simplejavamail.api.outlook.OutlookMessageData`:

```java
String getBodyHTML();
String getBodyRTF();
```

`getBodyHTML()` returns the native Outlook HTML body exposed by the already used reader, without SJM's RTF fallback. Preserve null versus an explicitly empty String. `getBodyRTF()` returns the reader-provided RTF body text after its existing decompression/decoding. It is not a promise of exact compressed RTF or MSG bytes. Do not decode, normalize, sanitize or render either value again.

These facts describe the native MSG input, not necessarily the HTML of the final Email after protected-content processing or later builder edits. A null value can mean unavailable/uncaptured source information, including a legacy constructor or old serialized result. Null does not prove that the original message was plain text. Captured body strings remain immutable, detached data; getters do no I/O or processing and retain no parser or entire native message/attachment graph.

Keep the current best-effort HTML fallback and ordinary Email output unchanged. Do not add an `isPlainText()` heuristic, a fallback-disable property, converter overloads, new public builders/setters, source-format guesses, dependency upgrades or RTF renderer changes. The upstream reader already performs RTF-to-HTML conversion while parsing; this patch exposes information and does not claim to suppress that work.

Keep both released public OutlookMessageData constructor descriptors. Add one compatible full constructor carrying the two new source fields if needed by cross-module assembly; existing constructors delegate with unknown/null source bodies. Keep `serialVersionUID = 1L` and old field meanings. Existing metadata-only consumers and snapshots remain usable.

Existing usage remains:

```java
OutlookEmailConversionResult result =
    EmailConverter.outlookMsgToEmailBuilderWithOutlookData(msgFile);
Email email = result.buildEmail();
```

The new source inspection is:

```java
OutlookMessageData source = result.getOutlookMessageData();
String nativeHtml = source.getBodyHTML();
String rtf = source.getBodyRTF();
```

Once an application's policy has selected plain-text output for an ordinary unprotected MSG, its existing `result.getEmailBuilder().clearHTMLText().buildEmail()` route remains available. Do not publish a rule that native HTML absence alone implies plain text: RTF can carry encapsulated original HTML or genuine rich text. Do not present native source facts as provenance of protected HTML selected later.

## Owned implementation and upkeep paths

- Core API/assembly compatibility: `modules/core-module/src/main/java/org/simplejavamail/api/outlook/OutlookMessageData.java`, its focused core tests and published-9.3.9 compatibility fixtures/helpers.
- Actual extraction/wiring: `modules/outlook-module/src/main/java/org/simplejavamail/internal/outlooksupport/converter/OutlookEmailConverter.java`. Read existing native body values from the object already in hand and pass them into the result data. Change internal bridge/result plumbing only if the real route requires it.
- Public entry-point coverage: existing `EmailConverter.outlookMsgToEmailBuilderWithOutlookData` String/File/InputStream routes, overloads with selected S/MIME options, and the existing `modules/simple-java-mail/src/test/java/org/simplejavamail/converter/EmailConverterTest.java` or a narrowly owned additional test class/resource helper. Do not change the converter surface just to obtain the fields.
- Documentation: API/result Javadocs, root `RELEASE_HISTORY.md`/`RELEASE.txt` as appropriate to the 9.x release, and the 9.x website conversion/source-data guide and relevant examples. Inspect README, Journal, case studies and downloadable bundles for affected usage; update only relevant material and record justified nonchanges in private evidence.
- Issue #979: accepted scope, appropriate 9.x milestone/labels/status, a concise public plan/usage explanation and the after-usage completion comment. Preserve original context. Briefly describe the intended 10.0.0 shape as planned, not as an available 9.x API or a guaranteed release date.
- Root coordination/cleanup helper changes require narrow ownership and fresh preservation snapshots. Do not edit the root received-Email design ledger, current 10.0.0 runtime/API, existing policy-held cache/import directories or unrelated plans.

## Meaningful acceptance cases

| Case | Required result |
| --- | --- |
| Native HTML present, including empty HTML | Source getter returns that reader value; existing Email HTML selection remains unchanged. |
| No native HTML, RTF carrying plain text | Native HTML stays null, RTF is exposed, existing generated HTML and plain text remain unchanged. |
| RTF encapsulating original HTML | Preserve the original RTF and existing recovered HTML; do not label this inherently plain text or synthetic generation. |
| Genuine rich-text RTF | Preserve source text and existing rendered HTML without inventing original-format certainty. |
| RTF absent, native HTML present or no body | Preserve the actually available values and nulls without inventing a format. |
| Protected MSG / selected body differs from native data | New facts remain owned by the native MSG; ordinary protected-content processing/results keep their meaning. |
| Existing String/File/InputStream journeys and builder/result terminals | Same source facts through the common extraction path, no second MSG parse, getter I/O, change to stream ownership or attachment/body processing. |
| Existing 13/14-argument constructor consumers | Old source usage and already compiled code still link and behave correctly against the patched artifacts. |
| Existing serialized data and new result round trip | Old metadata survives, older snapshots have unknown/null new bodies, new source values round-trip; no native parser/runtime graph is serialized. |

Exercise actual public conversion with suitable existing or safely generated MSG fixtures, not only a manually constructed metadata object. Use public test keys/fixtures where protection is needed. The reporter supplied no sample message; that does not block proving the new source-data API. Do not claim to reproduce/fix their exact sanitizer policy without evidence or broaden into renderer changes. Do not backport unrelated 10.0.0 date/sender/body-merge repairs to make a fixture pass.

## Verification and review order

1. Preserve current native/derived HTML, attachments, source metadata and stream behavior with relevant existing controls. Add focused failing coverage for missing source-body access and actual converter wiring, then implement/refactor. Missing-method compilation is only the initial red; the tests must assert real observed body data and unchanged conversion behavior.
2. Use an owned Maven local repository so 9.x builds cannot overwrite same-GAV 10.0.0 artifacts in the shared local cache. Resolve the actual released 9.3.9 compatibility baseline from published artifacts/tagged source, not a possibly overwritten development JAR. Keep absolute fixture/evidence/artifact paths.
3. Run the focused core/Outlook/main reactor selection with dependencies. Compile/run an existing-API consumer against the old/new artifacts on actual Java 8, including both constructor signatures; exercise legacy serialization and new round trips. Confirm new API signatures are core-owned with declared dependencies only.
4. Complete the existing 9.x release-grade actual-Java-8 lane, including `clean verify -Ppublish-cli -DexcludeLiveServerTests=true` and applicable branch procedures. Use the Windows-root trust-store setup when needed, not insecure TLS flags. Inspect XML outcomes and enabled/skipped cases; Maven success with ignored test failures is not a clean result. Inspect bytecode/package/Javadoc/CLI artifacts and preserve existing generated help/cache behavior.
5. Compile new usage examples against the final Java 8 artifacts. Build/check links and inspect the affected rendered 9.x guide with supported tools. Keep the future 10.0.0 example explicitly labelled planned and separately checked against the agreed design, not falsely compiled as released API.
6. Obtain independent source/API/compatibility/documentation review. Submit actual combined main/website patches, source manifests, compatibility and build evidence, applicable-surface accounting and limitations to root. Root must inspect the concrete unit before implementation publication/release. Do not repeat passing whole gates without new changes/failures or an unresolved concern.

## Public communication and the 10.0.0 preview

A concise maintainer plan reply on #979 is authorized. Explain that best-effort fallback stays, the 9.x addition exposes already parsed native HTML/RTF for application decisions, and the full source/protection inspection is intended for 10.0.0. For example, the brief future preview may show:

```java
// Intended 10.0.0 inspection shape; not a released 9.x API.
email.getInspection().getHTMLText();
```

That metadata will describe origin/derivation and protection of the HTML actually returned by Email, including protected/nested source selection. Detailed result types remain under design. Avoid a firm public date or claiming the overhaul is already implemented.

**Never publish execution evidence in GitHub issue titles/bodies/comments or either repository's commit prose.** No commands, pass/fail narratives, test counts, CI/build/package/link/Javadoc/browser verdicts, hashes, environment troubleshooting or verification claims there. Public text is the problem, before/after usage, accepted behavior, target version, future preview and delivered references. Private evidence/direct maintainer reporting carries the execution record.

## Delivery, patch publication and completion

After root accepts the exact unit, follow the real 9.x maintenance workflow: publish any changed website source before its parent gitlink; selectively deliver the main change to `develop`, fast-forward `master` normally, await `build-and-test` for the exact release head and approve only `approve-deploy-patch-version`. CircleCI owns version bump/tag/deployment. Do not approve minor/major gates or start a second publication merely because Central is slow.

Verify the actual released artifacts, sources/signatures/checksums where applicable, Java 8 compatibility, CLI assets and tag/branch state. Create/update the correct GitHub release following the branch workflow; synchronize `develop` with the released `master` and keep the 10.0.0 branch stationary. Do not manually update release POM versions or force-push. If another stable release/commit intervenes, inspect it and re-establish the reviewed release unit rather than publishing unrelated work unchecked.

Complete #979 with the final supported after-usage example, behavior explanation, brief planned-10.0.0 preview and delivered references. Close only the owned completed issue; retain its classification and actual release milestone. Close the milestone only when appropriate to its complete scope. Read back remote heads/tag, website gitlink if changed, release artifacts, issue/project state and preservation snapshots before reporting delivery complete.

Retire the task-owned active plan/scratch material after preserving durable source contracts and evidence. Follow the guarded root cleanup-script convention for exact owned paths/direct parents and validate syntax/WhatIf without deleting unrelated work. Existing automatic-review holds on other tasks' archive/plan removals and containing checkouts remain binding. If a new action is rejected, do not retry it through another mechanism or delete a containing directory indirectly; leave exact custody/cleanup records, disclose the automatic-review reason and continue independent authorized work. No broad cleanup of shared `.local`, temporary parents, credentials or registered foreign worktrees is authorized.
