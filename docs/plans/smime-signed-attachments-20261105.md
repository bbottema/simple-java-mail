# Independent attachments inside a detached-signed S/MIME parent

Accepted on 5 November 2026. The maintainer agreed with review finding 3's narrow restoration and explicitly requested implementation by a subagent through the complete review workflow while finding 4 is discussed. This authorizes implementation, verification, synchronized documentation/ADRs, reviewed selective commits and publication to the existing SJM/website development branches, and owning issue/project completion. No SJM release, unrelated supporting-library release, broad recursive decryption, or finding 4 daemon repair is authorized.

Baseline: SJM code `a0dd339b62eb8a9824798c2f8063940ff49ef1ed` plus subsequent #966 cleanup-only commit(s), currently `e458b76c7f99c3a6a27a909098c803b54869cf50`; website `86ce310498495ee9cadc408bcb20f2c4351d1867`. Confirm live heads before mutation. Finding 1's username fix is delivered, and finding 2's explicit pool authentication work runs independently under `oauth_registration_delivery` in `C:/workstation/sjm-oauth-registration-20261105`. Preserve canonical dirty files, staged research, all other plans/worktrees and retained evidence.

## Approved behavior

```java
Email message = mail.converter()
    .fromEml(Path.of("signed-mail-with-encrypted-attachment.eml"))
    .withSmimeDecryption(recipientKeys)
    .toEmail();
```

When a detached S/MIME parent can be opened into its clear MIME entity, process its independent protected attachments under the same supported attachment/decryption contract as an unsigned parent. The same encrypted resource that opens under an unsigned parent must not remain unopened solely because the parent has a detached signature.

`getAttachments()` keeps the original protected resources. `getDecryptedAttachments()` includes supported opened representations under its existing retention/materialization rules. Original parent protected bytes, detached signature resources, source metadata, signature verdict and message-level mode remain history for that parent. Decrypting an unrelated attached file does not make the parent itself ENCRYPTED/SIGNED_ENCRYPTED or establish that new clear output has a valid original signature.

Do not introduce a public API/flag. Preserve current missing-key, explicit wrong-key/password, malformed content, optional interpretation, I/O/interruption and materialization-failure boundaries as applicable to independent attachments; do not generalize exception swallowing. Keep extractable input readable under an invalid parent signature with the existing honest verdict. Arbitrary additional encrypted envelope layers, opaque/encrypted-parent recursive materialization, and new trust policy are outside this scope.

## Cause, impact map and ownership

`ConfiguredEmailConverterImpl.mimeMessageToEmailBuilder` parses the effective entity, then returns in its recognized-S/MIME branch before `decryptAttachments` at the ordinary-parent boundary. A detached-signed parent has no effective-message artifact/retained inner Email to perform the missing scan. `applySmimePreprocessingResult` installs parent artifacts/facts and copies original child resources into the processed list. The old helper cannot simply overwrite that result: `handleSmimeParseResult` replaces parent details and may trigger the single-attachment retained-content heuristic.

Separate parent preprocessing from independent child attachment results. Scan only the clear parent's actual independent attachments, excluding the parent signature artifacts introduced by preprocessing. Merge processed results without losing parent signature occurrences, duplicating siblings, flattening source origins or promoting a sole independent encrypted attachment into the parent's body. Preserve body/HTML/calendar/headers and exact/protected-source authority, occurrence order, metadata and existing borrowed/eager content boundaries.

Worker `smime_signed_attachments_delivery` owns implementation/tests and applicable ADR 0008/0035/0040 clarifications in isolated worktrees `C:/workstation/sjm-smime-signed-attachments-20261105/{sjm,website}`. Likely production paths: main converter orchestration/inspection assembly, S/MIME attachment processing and core internal result/handoff contracts only where needed. Supported public methods/serialization formats remain unchanged. Never depend on optional implementation types from main/core or alter crypto libraries to mask an orchestration defect.

The worker also owns its feature documentation in its isolated checkout, plus exact recommendations/changes for ADR index, release history, both OpenRewrite inventories and website security/migration surfaces. Finding 2 owns OAuth/pool/core-batch handoff, ADRs 0015/0039 and overlapping shared-doc drafts. Coordinate with that worker and root; serialize delivery and incorporate accepted remote head advances without force-push or overwriting shared documentation. Root owns this plan, canonical cleanup script and final combined acceptance. You are not alone: preserve all other work and accommodate concurrent changes. Do not build in the canonical dirty checkout or install local-only test artifacts into the shared Maven repository.

Governing decisions: ADR 0008 source-specific protection facts/bounded recursion; ADR 0035 tolerant received interpretation and parameterized independent attachments; ADR 0036 structural/content ownership; ADR 0040 retained-content ownership and separate views; ADR 0041 source evidence and linked Problems. Existing single-attachment merging rules retain their own boundaries and must not be accidentally activated by this new child scan.

## Tracking and verification

Before issue mutation establish introduction history and released provenance. Local 9.3.9 source unconditionally enters attachment processing; the new early return is in the unreleased preprocessing orchestration. Identify its owning feature issue and use a dated addendum/reopening for a target-only correction, instead of inventing another published-defect issue. If an affected published version is actually established, classify it with that evidence. Resolve live labels/milestone 10.0.0/existing project item; keep work open/In Progress until accepted delivery.

Follow docs/API_REVIEW_WORKFLOW.md, API_EXPANSION_WORKFLOW.md, DEVELOPMENT.md and CODING_STYLE_GUIDE.md. Record the accepted scope in the owning ADR(s) before implementation; keep acceptance criteria/specification in the issue/active plan. No test/build/verification evidence prose in GitHub issues/comments, commits or release bodies. Use plain-English before usage, expected behavior, ADR/commit references, and AFTER-first completion usage; report all execution evidence directly to root and retained scratch/CI artifacts.

Start with a focused failing real-entry regression on pristine baseline. Create a valid detached-signed multipart parent containing an independently encrypted supported attachment; assert original/clear bytes and parent verification before and after import, not only list size or mocks. Compare the same attachment under an unsigned parent. Use both MIME/EML static/fluent routes where affected, eager/lazy extraction, one-child and mixed siblings/order, valid/invalid extractable parent signatures, preserved parent signature filenames/duplicate occurrences, source/Problem links, copies/serialization, explicit separate-view options and ordinary export where relevant.

Boundary controls: missing key retains protected resource; explicit wrong key/password and required I/O failure keep their contracts; optional malformed signed-child interpretation retains existing recovery; unsupported clear materialization preserves original with honest facts. Parent remains signed/NOT_ENCRYPTED when only its independent file is encrypted. Repeated import/build/copy does not duplicate artifacts; getters do not perform additional processing. Existing encrypted/opaque envelope and nested-layer controls remain unchanged.

Run focused controls first, then appropriate clean Maven reactor/library checks on a real JDK 11 and supported modern Java, inspecting XML/current-run configuration contracts, packaging/Javadocs/JPMS and any generated metadata implications. Java 21 focused runs may be useful; prior full Oracle Java 21 Mockito native-agent initialization failures must stay a distinct harness limitation, not be masked by skip lists or unrelated dependency changes. Use Java 17 full controls as appropriate. Preserve original failures and coherent corrected results separately; no repeated full runs after sufficient checks without a new reason.

Reconcile public Javadocs/implementation/integrations/config/locks/copies/serialization/CLI metadata as applicable; README/release history, ADR/index, website security/migration, Journal/case studies/downloadable examples, and both OpenRewrite inventories. Give specific no-change reasons. This target-only correction creates no invented 9.x rewrite transformation; describe current supported attachment behavior accurately. Validate final rendered output/anchors/links and applicable snippets; do not claim browser QA if local settings prohibit it.

## Completion

Send root the actual combined production/test/doc/website diff, accepted caller behavior, evidence/current-run outcomes, source/dependency provenance and limitations for final acceptance before publication. Continue independent approved preparation while awaiting review; raise a different public contract/scope choice promptly rather than broadening it.

After root accepts, selectively publish website first, then main including the matching gitlink on existing branches. Account for finding 2 or cleanup-only head advances. Subjects end in owning issue numbers; bodies explain behavior/compatibility/ADRs without execution evidence. Post AFTER-first resolution, close/completed and project Done, verify remote heads and metadata. Do not stop at a local patch or passing test suite.

Report exact commits/issues, rendered/docs decisions, preserved original and final evidence, active helpers and exact disposable paths. Root coordinates canonical dirty-checkout alignment and bounded cleanup registration/retirement. Do not target another task's parent/evidence or retry previously rejected cleanup; retain execution evidence for maintainer inspection.
