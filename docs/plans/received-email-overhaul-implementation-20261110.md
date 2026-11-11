# Received Email overhaul: implementation and review packet

Status: approved for implementation and delivery in a dedicated Codex session, 10 November 2026. The maintainer read the exported plan and instructed: "Start a new Codex session with this plan. 6.1 Sol Max. Let it carry it to completion (code pushed and issue closed)." The caller-facing behavior is recorded in the [design ledger](received-email-api-design-20261109.md). Follow the current [API expansion workflow](../API_EXPANSION_WORKFLOW.md), [public API review workflow](../API_REVIEW_WORKFLOW.md), [development guide](../DEVELOPMENT.md) and coding style guide. The dedicated session is the responsible lead for actual combined review and authorized delivery; it need not wait for the originating chat to approve delivery again.

## Outcome and scope

Make a parsed Email useful through its ordinary getters regardless of whether conversion needed to open S/MIME/OpenPGP content. Select the logical message and its representations deliberately; replace the outer/inner Email merge heuristic. Preserve best-effort reading and actual source evidence. Put protection assessment behind a small optional API, without turning Email into a source tree, a trust engine or an assertion framework.

The main caller journeys are:

```java
Email received = mail.converter().fromEml(input).toEmail();
AttachmentResource document = received.getAttachments().get(0);
byte[] usableContent = document.readAllBytes();
Email attachedEmail = document.getEmail(); // Already parsed, or null.

ProtectionRequirements requirements = ProtectionRequirements.builder()
    .requireVerifiedSignatureFrom(approvedPartner)
    .requireDecryptedContent()
    .build();

ProtectionCheck check = requirements.check(
    received.getInspection().getAttachment(0));
```

For original-preserving use, `getInspection().getOriginalMessage()` and `getOriginalAttachment(index)` expose complete retained input where available. `forwarding(received)` renders the current interpreted view; `forwardingOriginal(received)` carries retained original input in its actual format. Reading a child does not edit its parent's selected attachment payload.

For a reply/forward that automatically includes confidential content, suitable outgoing protection is required by default. `ReplyOptions.allowUnencryptedContent()` / `ForwardOptions.allowUnencryptedContent()` permit unencrypted output for that draft. Conversion also supports a narrower permission:

```java
byte[] eml = mail.converter().fromEmail(draft)
    .allowUnencryptedContent().toEmlBytes();
// The source draft still requires protection for a later unencrypted send.
```

The full accepted rules, including body fallback, authored headers, dates, resource associations, Problems, private subjects and copying, remain in the ledger. Do not reopen those principles or add new product features while implementing supporting types.

Excluded: grouped/cross-field signature checks, a generic matcher/rule DSL, automatic PKI trust/revocation discovery, new cryptographic algorithms, full outbound RFC 9788 header protection, DKIM/ARC verification, POP3/IMAP, SMTP extension work, the deferred whole-Email optional-module portability project, and broad ADR restructuring.

## Authorization, baseline and tracking

| Gate | Current state |
| --- | --- |
| Behavior design | Agreed in the ledger, including the final conversion permission. |
| This execution packet and implementation delegation | Approved explicitly on 10 November 2026, in a new dedicated session using gpt-6.1-sol with Max reasoning. |
| Delivery | Explicitly authorized: complete implementation/upkeep/review, selectively commit and push main `codex/10.0.0` and website `codex/10.0.0-content`, verify both, close the owning completed issue and set Done. The dedicated lead performs the actual combined review. Do not request the same authorization again. |
| Release, deployment, force push, history rewrite | Outside this packet. |

Observed planning baseline: root `codex/10.0.0` at `a7040730c633fd1569ad2a04783b83390f47122b`; nested website at `9ceb7644d318429f8a7f76c6a247c818b1ed1af5`. Both checkouts are dirty. Root changes include concurrent Angus/dependency/CLI packaging work, workflow/development guidance, SMTP characterizations, ADR 0037 and private test configuration. Website changes include Strategic Patterns layout/styles, case studies, Journal and related tests. None is owned by this overhaul merely because a broad build touches it.

At execution start, inventory root/index/nested/worktrees again. Establish the live baseline, including applicable dirty source and current workflow instructions, in an isolated task-owned checkout. Keep baseline changes distinguishable from this feature's diff. Coordinate any dependency-baseline change with its owner; do not quietly test against stale installed JARs or commit another track's POM/fixture changes. If another delivery lands, replay the owned changes and repeat only the verification affected by that change, followed by required final combined gates.

Released source reference: tag `9.3.10`, commit `ec6cb6ddf258a294800679779b4a09ce8282fe40`. Its source explicitly contains `getOriginalSmimeDetails()`, `getDecryptedAttachments()` and the single-S/MIME merge controls. Released 9.3.10 also contains `OutlookMessageData.getBodyHTML()` and `getBodyRTF()`; current 10.0.0 lacks those two read getters. Preserve them in the target through a narrow forward-port, without merging the 9.x release/version changes.

Read-only tracking check on 10 November: #961 and #704 are closed for their earlier delivered scopes; #764 and #766 remain open, all on milestone 10.0.0. Use #961 as the owning unreleased inspection-feature continuation, preserving its original request and earlier completion. After execution approval, add the agreed follow-on scope and reopen it/In Progress on the existing 10.0.0 board. Link the OpenPGP work in #704, runnable-pattern upkeep in #764 and migration contributions in #766 without claiming to finish those entire projects. Recheck live labels/milestone/project IDs before mutation.

Public issue/commit prose contains behavior, usage and architectural/delivered references only. Never put commands, test counts/results, review verdicts, hashes, environment troubleshooting or execution diaries in GitHub issues/comments or commit messages. Evidence belongs in the maintainer conversation and CI artifacts.

## Source-grounded impact map

Paths below are ownership targets, not permission to overwrite concurrent work. All Java paths are relative to `modules/`.

| Responsibility | Actual paths and change boundary |
| --- | --- |
| Email/resource read contracts | `core-module/.../api/email/{Email,AttachmentResource,EmailStartingBuilder,EmailPopulatingBuilder,EqualsHelper}.java`, source/problem contracts and new core read/configuration types. Remove legacy flattened/merge surfaces; add ordinary child access and optional inspection. Preserve stream/snapshot contracts. |
| Core-owned evidence | `core-module/.../internal/email/{EmailInspection,EmailInspectionBuilder,OpenPgpDetailsImpl,OpenPgpSignatureResultImpl}.java`. Reuse captured source occurrences and owned storage; avoid a second source tree and name collision with the new public EmailInspection contract. |
| Main model/edits/copies | `simple-java-mail/.../email/internal/{InternalEmail,InternalEmailPopulatingBuilder,EmailPopulatingBuilderImpl,EmailStartingBuilderImpl,ComposedEmailSource}.java`. Replace constructor-level two-Email merging; preserve field associations and helper requirements through intended copy/edit paths. |
| Input conversion/original capture | `simple-java-mail/.../converter/internal/{ConfiguredEmailConverterImpl,ImportContext,SourceReadException,InspectionAssembly,OutlookSourceCapture,MimeMessageEmailMapper}.java` and `converter/internal/mimemessage/{InputInspection,MimePartCapture,CapturedMimeContent,MimeMessageParser}.java`. The current converter preprocesses a root, parses an effective message and later reparses an EML artifact into a retained inner Email; replace that flow with explicit role/layer observations and one selected view. |
| Artifact output | `core-module/.../api/converter/ConfiguredEmailConverter.java`; `simple-java-mail/.../converter/internal/{FluentEmailConversions,ArtifactConversions,InternalEmailConverterImpl}.java`; supported static `converter/EmailConverter.java` routes. `FluentEmailConversions.FromEmail` owns the new local permission; all its terminals share the same decision. |
| Final MIME/protection | `simple-java-mail/.../converter/internal/mimemessage/{MimeMessageProducerHelper,SpecializedMimeMessageProducer,MimeMessageHelper}.java` and affected producer variants. Preserve structure/protection ordering; distinguish internal base rendering from finalized output and exact relay. |
| Sending/governance | `simple-java-mail/.../mailer/internal/{EmailGovernanceImpl,SessionBasedEmailToMimeMessageConverter,SendMailClosure}.java`, affected normal/simple-batch/retained/pool/CustomMailer entry points and `mailer/MailerHelper.java`. Resolve outgoing settings first, enforce before provider submission or releasing an artifact, and never weaken mandatory encryption. |
| S/MIME | Existing core internal SMIMEModule/SmimePreprocessingResult/SmimeParseResult contracts; `smime-module` support/assembly and `simple-java-mail/.../internal/smime` bridges. Capture individual observations and actual opened content, not just a nullable global Boolean. Preserve factory-owned key caching/capacity. |
| OpenPGP | Existing core OpenPGP module/parse-result contracts and `openpgp-module` receive/verification support. Reuse actual per-signature results, distinguish unavailable verification and bind observations to content occurrences. Preserve RFC 3156 scope and module boundaries. |
| Outlook | Core OutlookMessageData and `outlook-module/.../outlooksupport/{converter/OutlookEmailConverter,model/OutlookMessageDataImpl}.java` plus existing RTF-derived body handling. Capture native bodies/derivation once; distinguish input/native properties, real inner headers and generated adapter headers. |
| CLI | `cli-module/.../internal/clisupport/{CliBuilderApi,BuilderApiToPicocliCommandsMapper}.java`, existing core internal scalar adapters, value interpreters, request application and packaged-help tests. Preserve scalar use; use an internal adapter for helper options rather than forcing complex Java values into CLI text or multiplying overloads. |

The source map must be completed to exact descriptors and all delegates in phase 0. Paths must be discovered before reads; do not assume a separate API module or a migration module that does not exist.

## Target public surface and minimal supporting contracts

The primary caller shape below is the target. Freeze the complete source-signature inventory with the lead before implementation; supporting result members must express the accepted facts rather than add policy features. A material behavior conflict returns to the maintainer, not to an implementation worker's private redesign.

| Owner | Target |
| --- | --- |
| Email | Add `EmailInspection getInspection()`. Keep ordinary content getters, direct `getProblems()` and existing generic `getSourceData()` evidence. Keep `getHeaders()`'s existing generic return signature and implement the agreed case-insensitive immutable read view. Do not add sending-mode/permission setters or a second set of content values under inspection. |
| AttachmentResource | Add nullable passive `Email getEmail()`. Ordinary metadata/streams describe the selected usable resource. Preserve `readAllBytes`, `readAllData`, `openStream` and their failure/ownership contracts. Do not add `getOpenedResource()` or an application setter for parsed children. |
| EmailInspection | Named ValueInspection getters for plain text, HTML, subject, From, To/Cc/Bcc, Reply-To, Bounce-To, message ID, sent date and calendar; indexed `getAttachment(int)` / `getEmbeddedImage(int)`; `getSmime()` / `getOpenPgp()`; nullable complete-original resource getters `getOriginalMessage()` / `getOriginalAttachment(int)`. Match existing getter spelling where applicable. No field-selector enum or catch-all string selector. |
| ValueInspection | Metadata only: selected origin/derivation, availability, captured source references, signature/encryption summaries and justified per-signature coverage details. Non-null immutable result even when a content getter is unavailable. No value duplication, public builder or getter-triggered work. |
| ProtectionRequirements | Immutable core value, explicit builder/toBuilder and one `ProtectionCheck check(ValueInspection)` operation. Exactly the three agreed requirements: approved verified/full signature, decrypted content and optional all-relevant-signatures verification. No varargs/group or general AND/OR/quorum language. |
| ApprovedSigners | Immutable set of explicitly approved signing keys; accepted S/MIME `builder().addSmimeCertificate(X509Certificate)` setup and rotation. Normalize actual public keys, not whole-certificate identities. For the initial OpenPGP route, accept explicit full signing-key fingerprints; document actual signing subkeys and never accept short key IDs or automatically approve unrelated/unvalidated subkeys. No automatic PKI engine or key loading during check(). |
| ProtectionCheck | `getOutcome()`, `isSatisfied()` and immutable `getReasons()`. Outcomes are SATISFIED / NOT_SATISFIED / INDETERMINATE. Reasons have stable codes, human explanations and captured-source references where available; they do not mutate Email Problems. |
| ReplyOptions / ForwardOptions | Immutable core operation options with the agreed static `allowUnencryptedContent()` convenience and safe defaults. Reply options also hold the existing custom quotation choice, avoiding template-by-permission overload combinations. Keep useful existing default/source/quotation convenience routes when independently justified; no deprecated legacy-merge compatibility aliases. |
| EmailStartingBuilder | Existing reply/reply-all/interpreted-forward source routes plus the appropriate typed options; add `forwardingOriginal(Email)`. Preserve the separate caller-supplied MimeMessage route without claiming original wire bytes. Do not add original-forward overloads without a real retained-input contract. |
| ConfiguredEmailConverter.EmailConversion | Add `allowUnencryptedContent()` and a local clear/reset/inspection contract. All MIME/EML/direct/result terminals use that conversion's permission. A fresh fromEmail call has no local permission; the source draft remains unchanged. Avoid new terminal overload families. |

Supporting inventory is deliberately read-only. Use core contracts for field/family inspection, individual signature results, a field's coverage of a result, signer/key facts and decryption results; assemble internally. Reuse one captured signature observation across views. Field coverage and family processing must not silently change subject. There must be no public bootstrap/builders for library-generated evidence and no foreign crypto types in continuing application signatures.

Complete the following finite details in the phase-0 target manifest, then review them together rather than adding methods ad hoc:

- Origin distinguishes received, extracted, derived, caller-supplied and unknown facts. Availability distinguishes present (including empty), established absence, unavailable processing, deliberately uninspected and uncaptured/unknown history.
- Signature summaries retain VERIFIED, INVALID, UNVERIFIED, UNSIGNED, MIXED and UNKNOWN meanings. A VERIFIED field needs full coverage of all contributing content. Known partial coverage must remain explicitly visible in the coverage result and cannot satisfy a full-coverage requirement; propose conservative MIXED for an aggregate containing known covered/uncovered contributions, with UNKNOWN for genuinely unestablished coverage. Review this projection in the target table before coding.
- Encryption summaries use the four agreed basic states. A DECRYPTED claim needs established coverage of the selected content, not just the existence of an encrypted sibling. Detailed attempts distinguish deferred, unavailable and failed work.
- Individual signature results carry a stable observation reference, verification outcome, observed signer/key identifiers, algorithms, failure information and source links. Coverage is contextual to the selected value; do not expose a family result as a whole-Email coverage verdict. Keep independent child observations out of parent success.
- Normalize S/MIME signing public-key identity consistently at approved-key setup and capture, including same-key certificate renewal and JDK/provider representation differences. OpenPGP approval uses the full actual signing-key fingerprint. Snapshot only core-owned identifiers/bytes; never store optional-provider objects or private key material in these evidence/approval values.
- Reason codes cover missing signature, unapproved signer, invalid signature, incomplete coverage/evidence, required decryption and stricter-signature failures without requiring exception-message parsing. Empty/uninspected evidence is not an automatic success. Define absent versus present-empty values and no-requirement/strict-without-base builder combinations explicitly.
- Public configuration builders expose supplied choices without build/crypto, with named clear/reset companions and explicit getters/Javadocs. Validation occurs at the documented boundary. Read-only facts and application-owned checking requirements create no EmailProperty, factory properties, locks or Spring configuration fields. Helper/conversion permission is explicit operation consent; templates must not manufacture it.

Remove the released flattened S/MIME/decrypted-list/merge-control surfaces, including corresponding builder setters/getters and retained-inner-Email controls, after the phase-0 published-descriptor check. Relocate unreleased original-OpenPGP details to the shared inspection entry point; remove superseded target-only public views without inventing 9.x migrations. Preserve useful signer/algorithm/failure facts through correctly scoped results. Do not retain a legacy flattened type as the internal model or add deprecated aliases to evade the clean 10.0.0 break.

## Internal model and critical ordering

Maintain one main-runtime logical content selection with explicit component roles: this message's body/protection envelope, an ordinary file, an independent attached message, and embedded resources associated with selected HTML. Use existing MIME/native readers as grammar owners. Do not classify by filename, attachment count or the presence of a generated EML adapter alone.

Core-owned immutable snapshots retain the selected field/resource associations and captured source/protection observations. Original input storage is separate from the usable view. Share owned capture where safe; do not retain parent Email objects, live parsers or external callbacks in the source graph. An attached Email has its own identity/view and a cached parsed child; it is not promoted into the parent. A child edit changes the attachment only through explicit replacement.

Keep helper-derived output requirements distinct from incoming verification metadata. Copies/snapshots preserve the draft's requirement/permission. Ordinary edits do not waive it merely by changing incoming status to UNKNOWN; explicit clearing removes that component. Governance does not import permissions from template Emails or erase the submitted draft's requirement. Conversion permission is local to its conversion object. These internal facts need no public Email mode getter and must not be encoded into MIME.

Final output ordering is: resolve applicable outgoing choices/locks, construct/protect the complete output, establish that actual protection covers required material (or applicable consent exists), then release the artifact/invoke provider submission. Internal reconstruction of a forwarded Email is not an early finalized boundary: suitable outer encryption can cover it later. Do not apply an unrelated Mailer's defaults to standalone conversion.

Keep the safe private-subject choice independent of permission: actual known public counterpart, otherwise `Re: Your message` / `Fwd: Message`. Native/cache/generated headers are not proof of a public counterpart. Audit other private automatically reused headers by role; do not substitute a different carrier identity for the logical message or claim body encryption hides an outer header.

Complete a header-role table before changing emission: typed authored values have one authority; builder changes cannot leave stale active generic values. Additional selected header lists retain occurrence order. Trace/authentication history is readable but not automatically emitted as current claims; MIME structure/encoding is producer-owned. Retained Return-Path interpretation still supplies the normal Bounce-To envelope choice. Characterize explicit reserved/custom-header configuration, complete ordinary/exact paths and fail incompatible explicit choices clearly rather than silently weakening them. Do not implement a global parsed-versus-composed provenance gate.

## Representative acceptance cases

Use real entry points and inspect resulting values, observations, payload bytes and final output. These are requirements, not claims that tests have run.

| Case | Required outcome |
| --- | --- |
| Plain ordinary EML; present-empty body/calendar/header values | Existing usable interpretation; emptiness is not absence; no fabricated protection or Problems. |
| Protected plain text and native HTML fallback | Both usable alternatives; each has its actual origin/coverage. Invalid signature alone does not discard readable content. |
| Native content itself covered by a valid enclosing layer | Protection is recognized despite native origin; no origin-equals-unprotected shortcut. |
| Signed/encrypted nesting in both family orders and repeated layers | Correct same-message selection and individual facts; shared budgets and factory credentials; no flattening global success. |
| One independently protected attachment, including as the only attachment | It remains a file/message attachment; usable opened resource when available; complete protected original separately accessible. |
| Independent attached protected email | One parent attachment, passive parsed child, child body/files remain child-owned, located Problems aggregated once. |
| Unable to open one file; missing matching key; unsupported/corrupt content | Preserve complete original or actual incomplete evidence; continue with neighbors; no complete-looking prefix. |
| Invalid signature versus unavailable verification | Actual INVALID versus UNVERIFIED/UNKNOWN; readable content remains. Unselected processing creates no fabricated failure. |
| Wrong configured keystore password/module request; interruption | Preserve the agreed setup/cancellation exception boundary; no blanket exception catch or message-text classifier. |
| Selected HTML and colliding/missing inline resources | Prefer associated resources, allow only unambiguous same-message fallback, never borrow from an independent child; unused materialized images become attachments. |
| Protected calendar; authored headers; deliberate omission | Calendar/method stay paired; From/To/Cc/Bcc/Reply-To come from one chosen authored view, with no hidden outer fill or inferred Bcc. |
| Identity/date/threading | Real selected Message-ID and chain, no generated-adapter identity; client-submit or actual Date input, no creation/modification/current-time fallback presented as received sent date. |
| Original EML/MSG capture; bounded/failed acquisition; MIME-object-only input | Complete actual originals retained automatically, format preserved; prefixes are diagnostic only; no invented original by reserializing an object. |
| Ordinary/interpreted/original forwarding and child replacement | Exact retained attachment payload when explicitly selected; interpreted forwarding uses represented data; parsed child reads do not rewrite its selected resource. Verify payload preservation at output. |
| Copy, setters, clear, resource/collection edits, templates and snapshots | Unchanged fields retain input evidence; replacements are caller-supplied/UNKNOWN, originals remain historical; component occurrences are not matched by names/equality/hash reads. |
| One fully verified approved signature plus invalid/unknown other signature | Base requirement can pass the qualifying same witness; optional strict requirement does not. Partial/unknown coverage cannot become a qualifying witness. |
| Key rotation and approval | Same-key renewed certificate matches; a new key needs explicit approval; an unapproved valid signer fails; a short PGP ID cannot approve a key. |
| Default confidential reply/forward; permission; explicit clear | Unencrypted output refused by default; accepted permission permits it; clearing both quoted bodies can remove the requirement, while editing only one does not. Configured/locked encryption remains binding. |
| Private subject and other relevant header ownership | Received getter retains private subject; safe new public/neutral Subject; no false wire-privacy claim or unrelated carrier-ID fallback. |
| Conversion-only permission then later send/fresh conversion | That export may return unencrypted content; source Email remains unchanged; later unencrypted send/fresh conversion still refuses absent draft consent. Apply to all MIME/EML/result terminals. |
| Mandatory encryption failure with permission present | Permission never authorizes a downgrade. Diagnostic results report recognized refusal without a successful unprotected artifact; source Problems are not mutated. |
| Exact relay, recipients/envelope, scoped factories, ordinary crypto/cache/import controls | Authoritative bytes and ordered envelope behavior survive; no shared-state leakage, new global cache or source-data-based send gate. |

Exercise the hardest slice first: a real eager MIME import with a protected body alternative, native HTML, and an independently protected attached message; then copy/edit and render a protected interpreted forward, comparing the retained originals and actual emitted attachment bytes. Pair it with ordinary/exact/module-absent controls. Add real MSG paths and their native/RTF variations after that model works; do not prove the core flow solely through mocked provider results.

## Implementation sequence and review checkpoints

1. **Freeze the inventory and baseline.** Produce exact released descriptors from published/tagged 9.3.10, preserving existing older migration witnesses; freeze the target API/member/CLI/header/equality inventory and finite coverage projection against the actual source. Lead checks scope before production changes. Do not assume Git source grep finds Lombok-generated binary methods.
2. **Build the model vertically.** Add core read contracts/internal assembly and a focused behavioral red, then implement the hardest slice above. Remove reliance on the constructor merge. A missing-method compile failure is only the initial red; demonstrate behavior too. Keep changes small enough to inspect intermediate real outputs.
3. **Integrate readers and lifecycles.** Implement MIME/EML/Outlook and both crypto families, originals/roles/budgets/Problems, then all source selection, copying/clearing/governance/resource/snapshot paths. Preserve selective equality's existing purpose; do not compare entire evidence graphs or perform content reads in equality. Record any necessary removal of a legacy equality comparand as an explicit target consequence.
4. **Implement the checks and final output contract.** Finish pure key matching, requirements/outcomes/reasons, reply/forward options and private subjects, original forwarding, shared final-output enforcement and conversion-local consent. Cover both enclosing protection and local-output isolation before broader testing.
5. **Reconcile public/migration/consumer surfaces.** Update descriptors, Javadocs, core-only/JPMS/negative consumers, CLI adapters/generated metadata, examples and migration contributions. Remove unsupported old aliases from actual supported API, not only docs. Every changed public surface has its normal ownership/clear/reset contract.
6. **Complete documentation and application slice.** Do the upkeep below against final code, run the required build lanes/packaged examples, then a separate production-focused review. Repair findings within scope; return genuinely new decisions to the lead.
7. **Lead acceptance and authorized delivery.** Root inspects the actual combined Java/docs/migration/website diff and evidence, not just worker/reviewer summaries. Rerun only checks affected by changes plus required final gates. Deliver only with recorded authorization; keep work/issues open if required implementation, review or delivery remains.

Use existing tests as starting points: `ReadSmimeAttachmentsTest`, `NestedSmimeEncryptionInspectionTest`, `SmimeSignedOutlookOutcomeTest`, `CopiedSmimeEditTest`, `SmimeReceiveBoundaryTest`, `EmailSourceInspectionTest`, `EmailSourceGraphTest`, the OpenPGP per-signature/independent-file cases, artifact-result tests, `PublicApiBoundaryTest`, configuration/CLI packaging controls and import-limit/cache-capacity controls. Extend scenario protection rather than replacing regressions with tests that merely mirror new helper implementation.

## Migration and project upkeep

| Surface | Required work |
| --- | --- |
| ADRs | Focused dated amendments to 0006/0008/0013/0031/0035/0036/0037/0040/0041 as actually affected. Preserve IDs, paths, anchors and history; no merge/split/renumber/redistribution. Do not overwrite concurrent ADR 0037 work. |
| API/Javadocs | Setter-owned contracts, passive getter links, null/default/clear semantics, module ownership and complete examples. Inspect generated HTML for supported contracts and internal-type leaks. |
| Root docs | README, unreleased release history, maintained source/conversion guidance and relevant send-stage diagrams. Describe the new behavior as development work, not a published release. |
| Website reference | `src/pages/{security,features,use-cases,migration-notes-10.0.0}.hbs`, relevant navigation/reference links. Current feature prose uses current API rather than version badges; breaking released behavior belongs in migration notes. |
| Security workflow | One complete application journey using requirements checks first, then explanations/diagnostic detail: actual consumed value, coverage/verification, expected key, decision, original retention and outgoing reuse. Include honest native fallback, MIXED and unavailable evidence. |
| Tactical/use case | New receiving use case; focused partner-document recipe in `src/guides/email-workloads.md`, matching `.11tydata.json`, downloadable `FieldGuideExamples.java`/notes. Preserve existing anchors and outbound recipes. |
| Runnable integration | `tools/integration-patterns/protected-partners`, its README/canonical Java, needed shared protection/fixture code, article `src/strategic-patterns/protected-partners.md` and demonstration metadata. Show processing/holding one document under an application-owned rule; keep SMTP uncertainty separate. No invented joint-content helper scenario. |
| Other examples | Audit surrounding recommended usage in Journal, case studies and downloads, including protected archives/custom handoff/AWS. Existing searches already locate legacy usages in `PolarMeridianApplication`, `CorrespondenceWorker` and `HandoffReceiver`; update actual behavior, not just names. Preserve unrelated article/style edits. |
| Downloads | Regenerate `src/assets/guides/integration-patterns/strategic-patterns.zip` using `tools/integration-patterns/package_examples.py`, run its consistency check and reconcile tactical downloads. Never package private fixtures/configuration. |
| Summit companion | Update `docs/research/simple-java-mail-world-class-smtp-progress-report.md` with the accepted scope/status, then implemented assessment after acceptance. Credit a meaningful but moderate overall advance, concentrated in protected-mail usability/coherence; keep protocol/competitor baselines and original PDF intact. Account for actual retention costs; older outbound soaks do not prove this model. |
| OpenRewrite | Update both migration research/implementation inventories, released-to-target symbol manifests, behavior/report cases and frozen-target fixtures. See below. |

For migration, reuse existing R028 conversion/F27 resource, R033 protection-inspection and applicable R040/F19 advanced-setting responsibilities; add a new topic only if those cannot own the actual change. Removal of the decrypted list, retained-inner-Email/merge controls and flattened S/MIME details is semantic: automatic replacement with getAttachments(), the Email itself, or a global valid Boolean is not generally safe. Provide concrete manual-target examples and report guidance, preserving source when intent is unknown. Do not infer approved keys or source attachment indices.

Unreleased OpenPGP result refinements, source/problem contracts, status enums and new helper/check APIs are target fixtures rather than invented released-9.x edits. Keep the published 9.3.10 native-body accessors available in 10.0.0. Validate against frozen final target artifacts, not a moving worktree or stale installed core JAR.

The current #766 inventories explicitly say production recipes/report companion are not implemented. This packet does not silently implement all #766 or call an inventory update an executable migration. Contribute exact source/target fixtures and expected manual/report behavior through the existing tooling track; implement applicable report/recipe changes where that executable baseline is available. Report the actual readiness and leave #766's release obligation explicit. No unsafe new AST rewrite is required merely to make this overhaul look automatically migratable.

## Verification and completion criteria

No tests/builds have been run for this plan. After execution approval, verify in dependency order:

1. Source/model behavior through real selected input and final output, then core-only read/check use without optional modules, classpath/JPMS contracts, supported serialization and negative construction/internal-access cases. Inspect old snapshots with uncaptured history without inventing proof.
2. Both protection modules, missing modules, raw/native source, eager/deferred reads, cancellation/read/close failures, import budgets, equality/occurrence ownership and two factories. Use synthetic public fixtures where possible; existing private fixture settings remain local and must never be printed, staged or copied to public artifacts.
3. Reactor-based library/Javadoc validation on an actual JDK 11: `mvn -pl '!modules/cli-module,!modules/jacoco-aggregator-module' clean verify`.
4. JDK 17+ full non-live gate, including CLI distributions: `mvn clean verify -Ppublish-cli`. Use the current development-guide Windows/license/environment requirements. Full gates must evaluate the final combined sources; inspect Surefire/Failsafe XML and enabled/skipped cases rather than trusting Maven's exit alone.
5. Build-time generated CLI metadata, actual packaged JAR/ZIP/TAR startup/help and affected local/daemon paths. Do not commit generated metadata or generate it at runtime. Run applicable Spring metadata/compatibility controls; record why no new property schema is needed for read-only facts and explicit operation consent.
6. Website checks/build/links and rendered review of changed guides/examples; compile/run applicable downloaded examples against the final target. Run the protected-partners success and failure/uncertainty scenarios, shared affected pattern controls, archive-generation check and migration fixture validation against frozen artifacts.
7. Measure representative large-input original/decoded retention and copy/snapshot lifetime using bounded task-owned inputs. Confirm shared captured storage and that repeated reading/copying does not accumulate parser/ancestor objects or reopen deferred resources. Report measured coverage, not a whole-JVM memory bound or universal leak/competitive-throughput claim.
8. Separate production-only review, then actual lead review of the combined source and all upkeep. Completion requires no unresolved required boundary, migration classification or documentation mismatch. A failed/blocked lane is explained; it is not silently counted as passing.

Document actual commands/results privately for the maintainer and retain durable logs in CI artifacts. Maintain a concise surface/scenario ledger with update/no-change reasons, without publishing execution evidence in issues/commit/release prose.

## Subagent ownership and handoff

The approved dedicated session owns lead/coordinator responsibility for the core/main/protection/Outlook changes, tests, applicable CLI/Spring integration, migration contributions, focused ADR/root docs, website/pattern/download upkeep and issue bookkeeping under this packet. It may request narrowly owned implementation assistance or a separate read-only production reviewer; coordinate shared files explicitly. It must be told: **you are not alone in the codebase; preserve other edits, never revert them, and accommodate concurrent changes.**

The dedicated lead must read this packet, the ledger and current workflows in the actual checkout. It completes the vertical slice, required checks, supporting docs and a separate production review, and personally inspects the real combined diff and evidence before accepting and publishing. It inherits the plan's lead responsibility from the originating chat. Return a genuinely new material behavior/scope conflict to the maintainer; ordinary implementation repairs continue autonomously. The temporary PDF download setup (`eleventy.config.mjs` dev-only .local/downloads passthrough and local PDF files) is separate delivery assistance, not part of the feature to publish.

Use a bounded task directory, provisionally `C:/workstation/sjm-received-email-overhaul-20261110/`, with separately owned source/website/evidence locations. Do not disturb registered worktrees, shared `.local`, nested source, private properties or unrelated plans. Add only exact disposable task/retired-plan paths to the existing guarded cleanup script when appropriate. Preserve its guards and WhatIf behavior, validate syntax/dry run, and report existing blockers without bypassing them or deleting unrelated paths. Scheduling cleanup does not authorize deletion.

If delivery is authorized, selectively commit only the owned accepted changes, with governing ADRs and issue suffixes, publish website first and parent gitlink second to the explicitly authorized branches, verify the resulting heads, and complete the behavior/after-usage issue comment and board status. Keep #961 open while required work or delivery remains; do not close #764/#766 wholesale. No release/deploy/force push. If delivery is not authorized, return the fully reviewed local result and request only that remaining authorization.
