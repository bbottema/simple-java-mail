# Srcset CID interpretation (#893)

Delivered: main `1baba7525c7f88e94a24be647a7586d9ba7c58e3`, website `dc18268c6e8748b37daca0af28e45afe7ad36fbb`.
Issue #893 is closed/completed and its project item is Done. Implementation and publication are complete; only disposable-artifact cleanup
and plan removal remain deferred after automatic approval review rejected the exact guarded deletion as "blocked by policy". The shared cleanup script retains
the exact pending targets; its pre-existing unrelated reparse-point blocker remains untouched.

The maintainer accepted the narrow correction and authorized implementation, delegation and workflow completion on 28 October 2026.
Deliver on `codex/10.0.0` and `codex/10.0.0-content` as applicable after lead review; no release, deployment or history rewriting.
Issue: https://github.com/bbottema/simple-java-mail/issues/893. Architecture: ADR 0008, ADR 0035 and ADR 0036.

## Impact map

Before: importing `<img srcset="cid:logo&#64;example.org 1x">` with the corresponding inline MIME part puts the image in attachments.
After: equivalent literal/entity spellings identify the same embedded image, without changing HTML, Content-ID, content or exact bytes.

Execution paths: static and fluent EML/MimeMessage import and exact Email inspection -> `MimeMessageParser.moveInvalidEmbeddedResourcesToAttachments`
-> `CidReferences.inHtml` -> decoded attribute interpretation. Ordinary composed re-export uses the resulting resource classification.
Exact/protected content remains authoritative; this change introduces no new resource read or public configuration surface.

| Scenario | Intended result |
| --- | --- |
| One/multiple encoded or literal srcset CID candidates, density/width descriptors | Each candidate selects only its complete CID. |
| Comma inside a CID URL; separator after a URL; quotes/entities/percent escapes | Apply source-set token boundaries before one-pass CID decoding. |
| Ordinary src, CSS attributes/raw text, malformed escapes, case-sensitive IDs | Existing interpretation is preserved. |
| Eager/lazy imports, static/fluent EML/MIME, copying/re-export, exact inspection | Same classifications and metadata; exact bytes remain unchanged. |

## Ownership and surfaces

Worker owns `CidReferences.java`, new `SrcsetCidReferenceTest.java`, ADR 0035 clarification/index, release history,
OpenRewrite research/implementation-plan target controls, applicable website import paragraphs and bounded task artifacts.
Root's ADR 0035 edit is included in this packet. Other root/nested changes and index entries remain untouched.
Finding 2 reply behavior is design discussion only and outside this work.

Inspect public converter Javadocs, implementation integrations, README, Journal, case studies, downloadable examples and CLI/Spring surfaces;
update only applicable content and retain a no-change rationale for unaffected surfaces in the delivery report.
No new 9.x source recipe follows from an unreleased 10.0.0 import interpretation correction.

| Surface | Reconciliation |
| --- | --- |
| Public API/Javadocs | Signatures and existing converter identity/ownership contracts remain correct; no public surface changes. |
| Implementation/integrations | Only shared CID interpretation changes; no properties, locks, serialization, CLI options/cache inputs or Spring mapping changes. |
| Architecture | ADR 0035 records the accepted candidate boundary; its ADR index points to #893. ADR 0008 classification and ADR 0036 ownership stay unchanged. |
| Root docs | Unreleased release-history entry added. README setup/sending usage is unaffected. |
| Website | `src/pages/features.hbs` and `src/pages/migration-notes-10.0.0.hbs` explain srcset resource identity. Configuration guidance is unaffected. |
| Journal/case studies/examples | Searched maintained prose and downloadable asset sources; no srcset interpretation claims or obsolete calls need changes. |
| OpenRewrite | Both research and implementation plan add frozen-target candidate/ownership controls and explicitly avoid a source recipe/report topic. |

Bounded artifacts start in `tmp/api-review-893` and the website's `.local/api-review-893`; after checks finish they relocate to
root `.local/api-review-893` and `.local/api-review-893-site` for existing guarded cleanup. This plan is disposable after delivery.

## Verification and delivery order

1. Add a public-import regression and observe the reported failure on current production code.
2. Correct candidate interpretation with focused single/multiple/boundary cases and existing CID controls on a real JDK 11 reactor.
3. Reconcile relevant documentation, run website checks/build if changed, and inspect affected rendered content/links.
4. Present combined diff, actual check results and documentation matrix to the lead; await lead review acceptance.
5. Selectively commit owned changes (preserving the shared index), publish website before parent gitlink if applicable,
   verify remote heads, post after-usage completion, close the issue and set its project item Done.
6. Retire this plan and bounded task scratch via exact guarded cleanup. Keep execution evidence out of GitHub issues and commits.
