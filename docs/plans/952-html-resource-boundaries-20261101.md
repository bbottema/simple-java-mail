# Shared HTML resource interpretation

Retired after delivery: #952 is closed/completed and Done in milestone 10.0.0. Source/ADRs
`e03a54d419bb691de2a4b61ee2a0d32612db0277` and documentation/gitlink `70f2bca51869001433d000283cb99979e0d5e074`
are published on `codex/10.0.0`; website `62fa4ec523215fe92662b0a8e48f97c7906bfdb5` was published first on `codex/10.0.0-content`.
The lead's cleanup-only bookkeeping commit `b75f513c9b0f30c401ef033948cffa10cfe6a9c9` is also published on the main development branch.
The lead accepted the actual combined source/tests/ADRs/docs and final artifacts before selective integration and delivery.

Both relevant library lanes completed 585 selected / 581 executed, zero failures/errors and four pre-existing skips, on actual Java 11 and 21.
Both CLI lanes completed 46 cases, including three new help cases and the concurrency witness. After correcting the new helper's initial
JPMS split-package placement, clean final-source builds reran all 63 new automatic/MIME/MSG/copy/reply/exact cases on 11 and 21 plus three
fresh Java21 help cases. Named-module public automatic embedding/MIME conversion/exact-byte consumers passed on both JDKs. Final source/binary
jars contain the main-only `internal.html` helper with Java11 bytecode and no old helper; generated/packaged Javadocs, CLI/standalone generated
caches and actual Java17 packaged cold help were read back. Website checks/render/build/links passed. No required implementation, companion,
verification, publication or issue work remains. The earlier split-package artifact is superseded by the final clean artifacts.

Only bounded disposal remains. The lead delivered the cleanup-script update; its guarded full dry run stops at the older
`.local/tls-inspection-20261030/source/simplejavamail.org/node_modules` reparse point. No deletion was attempted or authorized.
The owned website export is relocated under `.local/review-html-boundaries-20261101/website`; this round root and this retired plan are
registered for later guarded cleanup. Unrelated staged research, workflow/cleanup edits, eight tracked website edits and untracked work remain.

The following sections retain the accepted implementation packet and local execution record, rather than ongoing issue status.

The maintainer accepted broadened finding #8 on 1 November 2026 and explicitly authorized delegated review, implementation, documentation,
verification, selective publication on `codex/10.0.0` and `codex/10.0.0-content`, and issue completion. [#952](https://github.com/bbottema/simple-java-mail/issues/952)
is open/In Progress in milestone 10.0.0 and the existing project. No release, deployment, history rewrite or runtime CLI-generation repair is included.
Lead actual-diff acceptance precedes serialized integration and delivery; this is a coordination checkpoint, not a new permission gate.

Main baseline `4ea060213c81744f1319f032ac28f1220b7cae2e`; website baseline `52cf2042126125bc9b7a02cf2dc35240982f5c28`.
Preservation indexes, binary staged/unstaged patches, statuses, worktrees and compact untracked inventories are under
`.local/review-html-boundaries-20261101/preservation`. The unrelated staged patch SHA256 is
`4E51B9F07A9087DFFD15AEC309588CF7D539EC1AC6304BC4383E4B4AEB60780F`. Frozen source export is the round's `source` directory.

## Caller behavior and preserved contracts

`withHTMLText("<img src=\"a&amp;b.png\">")` with file auto-resolution enabled and base `/srv/mail-images` currently selects the literal
entity spelling. It must select `/srv/mail-images/a&b.png`. HTML decoding happens once: `a&amp;amp;b.png` selects a literal `a&amp;b.png`.
Stored filename `a&amp;b.png` stays literal. `cid:logo%2F1@example.org` selects Content-ID `logo/1@example.org` once, with plus signs and case preserved.
ADRs 0035/0036 own the accepted clarification; 0008/0013 retain protected/exact-byte boundaries and 0031 owns internal placement.

Preserve opt-in settings, file/classpath/URL lookup order, quoted img-src consumer policy, source markup outside replacements, repeated-source
handling, CID skips, containment after interpretation, required/optional failures and atomic failed-pass state. Preserve actual filename/CID
separation, missing metadata, legacy name-as-CID shorthand, intentional resource occurrences, eager/lazy ownership and current reply selection.
No public API, dependency, universal metadata decoder or speculative malformed-HTML redesign is approved.

## Impact map and ownership

| Path | Concrete sources and scenarios |
| --- | --- |
| Shared HTML interpretation | New main-runtime `org.simplejavamail.internal.html.HtmlAttributeValues` reader using existing jsoup tracked decoded values/raw spans; `CidReferences.inHtml` reuses it while retaining CID/srcset/CSS policy. The main-only package avoids a split with core's existing `internal.util` package. |
| Auto lookup/rewrite | `EmailPopulatingBuilderImpl.resolveDynamicEmbeddedImageDataSources` consumes the same decoded quoted img-src values and raw ranges; resolver order, guards and state application remain owned there. Preserve public `EmailPopulatingBuilder.IMG_SRC_PATTERN` and its contract/tests. |
| Filename/CID mapping audit | `MimeMessageEmailMapper`, `MimeMessageParser`, `MimeDataSource`, `MimeMessageHelper.ResourcePartMetadata`, `OutlookEmailConverter`, copy/serialization and `EmailStartingBuilderImpl` reply projection. Repair only concrete violations of the approved representation contract. |
| MSG shared interpretation | The audit found upstream `OutlookMessage.fetchCIDMap` uses raw prefix matching without HTML/percent interpretation. Root confirmed repair belongs to the approved cross-path scope. Add an interpreted-CID method to core `internal.util.InternalEmailConverter`, implement it in main `InternalEmailConverterImpl`/`CidReferences`, and classify in optional `OutlookEmailConverter` using that set. Preserve explicit CID -> short filename -> long filename priority, nonempty data, map/set collision and occurrence policy, nested/protected paths and raw proxy view. ADR 0006 records the existing seam's ownership. |
| Regression matrix | Shared role-specific cases exercised by actual auto file/classpath/loopback-URL routes, MIME/EML eager/lazy/static/fluent import-export, MSG conversion, copy/serialization and reply paths. Include double encoding, literal percent/plus/case, quotes/brackets, CID scheme entities, decoded containment, repeat and failed-pass controls. |
| Protection/exact controls | Existing/new public-path controls retain original exact/protected bytes and cryptographic inspection; no resource reads are added outside existing ownership boundaries. |

Implementation worker owns the export's relevant production/test sources, ADRs/index, root docs, OpenRewrite inventories and applicable website guidance.
Nested cross-path worker owns only unique shared test fixture and representation regression classes; no production or shared checkout edits.
The lead owns cleanup script changes and actual combined review. All workers preserve concurrent edits and coordinate overlapping files.

## Surface matrix

Public API/signatures stay unchanged. Review `ImageResolutionConfig` Javadocs for accurate HTML meaning; if CLI-exposed help changes, regenerate
metadata at build time using actual reactor classes and isolated resources. Inspect configuration/locks/Spring but retain unchanged setup semantics.
Update ADRs 0035/0036 and topic index, unreleased release history, both OpenRewrite inventories, current attachment/embedded-image and migration
guidance as applicable. Search README, Journal, case studies, downloaded Java/assets and concurrency docs; record explicit unchanged reasons where
the contract does not affect them. Distinguish released 9.x behavior changes from unreleased target-only fixes.

## Verification order

1. Characterize representative valid existing cross-path resource identities and add focused failing automatic-embedding regressions in the frozen export.
2. Implement shared parser-backed interpretation and run narrow public-path controls; include real double-encoded filename and decoded guarded path cases early.
3. Run final relevant library tests on actual JDK 11 and JDK 21 with normal JaCoCo, Windows `-Dlicense.skip=true`, no shared source installs or target use.
4. Generate/package public Javadocs and inspect changed HTML if touched; run isolated build-time CLI generation/help checks only if applicable.
5. Check/build/render/link affected website pages in owned export and compile complete current examples against final reactor artifacts where applicable.
6. Lead reviews actual combined source/tests/docs and evidence. Integrate only clean owned paths, semantic commits with `(#952)` and Architecture references,
   website publication before parent gitlink, remote/head readback, behavior-only after example/comment, close completed and project Done.

Issues and commit messages contain no verification evidence. Root preservation and task-owned paths are rechecked before every shared integration.
No cleanup deletion is authorized. Root will register exact scratch/retired-plan paths and report any guarded dry-run blocker.

## Current execution record

The initial new regression compilation exposed a missing checked-exception declaration in the owned borrowed-stream assertion; it was corrected
before the behavioral baseline. The unchanged production baseline then ran 61 new cases on actual Java 11: 27 assertion failures and three expected
resolution errors reproduced the wrong interpretation, with 31 unchanged controls passing. The 16-case MIME/EML/copy/serialization/reply/exact
matrix already passed; MSG missed/misclassified 13 of its 25 cases and automatic resolution failed 17 of 20.

After the shared reader/internal bridge correction, all 61 passed. Added disabled-resolution/borrowed-content and table/template/foreignObject
ordering controls bring the new library cases to 63. The relevant final Java 11 main-module selection completed 567 selected cases, no failures/errors,
four pre-existing disabled cases (563 executed). Its selected source-pattern controls in core are recorded separately in the retained reports.
Actual Java 21 clean compatibility execution is underway. All dated logs and reports are task-owned and remain outside GitHub/commit prose.

The source/Javadoc/help surface assessment requires build-time cache regeneration: both `ImageResolutionConfig` and the separately maintained
`CliEmailConfigurationBuilder` adapter explain the attribute meaning. `CliImageSourceHelpTest` checks the three real option descriptions without
opening a Mailer. Current-source metadata generation uses the normal `CliListAllSupportedOptionsDemoApp` build entry point in an isolated first
resource directory, then promotes generated source/target caches atomically. No runtime-generation behavior is changed.

The docs companion changed release history, both OpenRewrite inventories, features and 10.0.0 migration guidance. Released tag 9.3.9 confirms both
raw automatic source lookup and specialist-owned MSG regex classification, so current migration notes describe real behavior changes without a
signature rewrite or production recipe claim. README, configuration keys/locks/Spring, Journal, case-study/download snippets and concurrency docs
need no edits: their current recommended usage does not depend on the changed interpretation, setup signatures and send lifecycle are unchanged.
The owned website archive has passed its 107 applicable checks; one absent-workbench archive skip is covered by the actual read-only teaching-entry
check. Build, rendered entity spellings and 5,950 internal links/83 pages passed. Root reviewed actual source/tests/ADRs/docs and requested no
further contract change; final actual lane/artifact and preservation acceptance still precedes integration.

Final packaging inspection found the new helper's initial `main/internal/util` placement duplicated core's existing JPMS package. Classpath
lanes did not establish named-module compatibility. The helper and both imports were moved to main-only `internal.html`, without behavioral
changes. Clean final artifact rebuild, affected classpath reruns and a real named-module automatic-image/MIME-conversion witness are required
before delivery; the earlier artifact is provisional and must not be integrated. No source API or dependency change follows from this correction.
