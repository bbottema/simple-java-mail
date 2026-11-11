# Received Email API: caller contracts under discussion

Status: behavioral design agreed through 10 November 2026; this file is the decision ledger. The maintainer approved the [consolidated implementation/review packet](received-email-overhaul-implementation-20261110.md) for a dedicated gpt-6.1-sol Max session through pushed code and issue closure. Its authorization section governs execution/delivery. The attachment/forwarding, body-fallback, calendar-selection, HTML/resource-association, subject, authored-address, Message-ID, sent-date and logical-threading contracts and shared-inspection shape were agreed on 9 November; the later sections record accepted protection checks, key approval, retention, copying and helper/output permissions. Unused materialized images belong in ordinary attachments. Use explicit inspection getters, not a field-selector enum. Remove Email.getOriginalSmimeDetails(); 10.0.0 is a clean API break with OpenRewrite migration, not deprecated compatibility aliases. The full supporting-type/descriptor inventory is a pre-code checkpoint in the execution packet.

The agreed API contract will guide the eventual internal representation. Ordinary callers should use familiar Email/attachment operations; native Outlook, MIME and protection-layer structure belongs in interpretation and optional inspection. Preserve best-effort recovery, honest source evidence, passive getters and original input identity. Follow the [API expansion workflow](../API_EXPANSION_WORKFLOW.md) and [review workflow](../API_REVIEW_WORKFLOW.md) once a complete implementation packet is agreed.

The maintainer explicitly reaffirmed that Email itself is the convenient parsed-message read model. Do not move useful interpreted message/transport values behind provenance/getInspection() and thereby turn that namespace into a second Email model. Ordinary getters expose the best available meaningful parsed data; the optional shared inspection layer explains its source, derivation, protection and limitations and retains additional raw/history evidence. Source-only retention is not a replacement for a usable ordinary interpretation. Protocol processing results such as incoming S/MIME/OpenPGP details remain inspection evidence under the already agreed grouping.

Current baseline: import limits are delivered under #978 at `0a13cd195`; configurable S/MIME cache capacity is delivered under #977 at `a7040730c`, with website source at `9ceb7644`. Those tracks do not implement this document's ordinary message/list/body overhaul. Preserve their work and root's separate ADR 0037 completion clarification.

## ADR maintenance scope: restructuring canceled

On 9 November 2026 the maintainer canceled the broader ADR restructuring/streamlining initiative because existing issues and historical commits refer to the current records. Preserve existing ADR numbers, paths, anchors and identifiable decision history. Do not merge, split, renumber or redistribute the existing records as part of this overhaul. Feature-specific upkeep remains: use focused dated amendments, clear supersession/status links and a new record only for a genuinely distinct new architectural decision. Keep previous decisions understandable through their original references. The received-Email API/model discussion and implementation planning continue independently of the canceled documentation initiative.

## Agreed: ordinary attachments and retained sources

The maintainer agreed in principle to logical message attachments, checked Problems and recursive attached-Email views, then accepted the initial forwarding refinement with "Agreed. That settles getAttachments()." On 10 November 2026 the maintainer refined that payload boundary: successful decryption should make ordinary attachment access usable transparently; forwarding an interpreted Email and forwarding retained original input are explicit different operations. The maintainer subsequently accepted the concrete interface below, including its names and signatures.

`getAttachments()` is the ordinary logical attachment view. A protection container carrying this message's body is not itself an ordinary user attachment. An independently attached protected file or message remains an attachment, including when it is the only attachment. Determine that relationship from interpreted source structure and metadata, rather than filename or attachment count alone. Preserve the original file when its role cannot be established.

Conversion selects each logical attachment's usable representation, including successfully opened protected content. Its normal resource metadata and read methods describe that representation; callers do not select an opened/decrypted resource separately. The selected resource remains stable after conversion, and sending that resource uses the payload its read methods expose. Preserve original protected input separately as source evidence rather than making ordinary attachment reads return ciphertext solely to support original forwarding. This dated refinement supersedes the initial rule that an independently protected attachment's ordinary resource must always retain its protected payload.

| Input/component | Ordinary logical attachment view |
| --- | --- |
| Plain message with `invoice.pdf` | The PDF with its payload intact. |
| Protected message-body container containing `invoice.pdf` | The PDF from the selected message content; the body container remains available through inspection. |
| Independently attached protected file, with an opened representation | The usable opened resource through ordinary access; retain the original protected payload separately for inspection/original-preserving use. |
| Independently attached protected email | One logical message attachment, with its own materialized readable Email view where recognition/read policy permits. Child files/body are not promoted into the parent. Preserve its original input separately when retained. |
| Protected file cannot be opened | Retain its original payload; report the encountered processing limitation/failure through the agreed inspection/Problem contract. |

Ordinary use must not require walking a format-specific source tree or choosing between encrypted and decrypted attachment lists. Parsed attached-Email access belongs on the ordinary attachment; original payloads and protection evidence belong in optional inspection. The concrete proposal below removes getDecryptedAttachments() without adding getOpenedResource().

### Problems and recursive message views

An encountered inability to open a selected attachment preserves the original and registers a source-linked Problem. Distinguish unavailable processing/missing keys from actual cryptographic failure. Deliberately deferred processing is unprocessed rather than a fabricated failure; a selected import stopped by a limit has its actual limit observation. Invalid configuration, cancellation and programming errors retain operation-failure behavior. The accepted table below settles the intended per-file versus operation-wide behavior; the eventual implementation packet must characterize and reconcile all actual routes, since current explicitly requested decryption failures can throw.

A recognised independent attached email can expose its own recursively parsed Email. Its identity, addressing, content, attachments, protection facts and Problems belong to the child. Recognising only an opaque protected payload or generating an adapter `.eml` does not prove the source was an independently attached email.

Materialization follows the selected read policy and shared import budgets. Getters expose already materialized views and never start I/O, parsing or crypto. Child Problems also appear once in the root aggregate, retaining child origin and attachment association. A child signature/decryption result must not become a result for the parent.

### Agreed: best-effort input inspection and operation failures

The maintainer accepted this recovery table on 10 November 2026 and explicitly classified it under the existing best-effort inspection rule. It adds no opt-in mode or recovery switch. Apply the following boundary to automatic incoming processing that produces an Email; do not generalize it into broad exception swallowing or change standalone cryptographic utility, outgoing production/send or later explicit borrowed-resource read contracts.

| Encountered case | Agreed incoming Email result |
| --- | --- |
| A valid loaded private key does not match one protected attachment | Retain that complete protected payload, record the appropriate unavailable/failed processing Problem and continue with readable neighbors. A key that does not match this input is different from an unusable configured keystore. |
| Corrupt or unsupported protected input cannot be opened/interpreted | Retain available original evidence and a source-linked Problem; do not expose an incomplete/failed transformation as successful normal content. Preserve readable neighbors. |
| A signature is invalid but its content is readable | Keep the readable content and report INVALID plus its source-linked Problem. Application acceptance policy remains separate. |
| An incoming component read fails | Preserve available evidence/prefix with its read Problem; continue where the reader can safely reach other content. A prefix is diagnostic evidence, not a complete normal resource or original-forwarding payload. |
| Application setup is unusable, such as a wrong configured keystore password/alias or explicitly requested decryption without the required module | Throw a clear operation/configuration exception rather than report every attachment as bad input. Ordinary recognition without a requested unavailable capability retains its existing best-effort path. |
| Cancellation, interruption, invalid API use or an unrelated programming/provider defect | Preserve the operation failure/cancellation boundary and original causes; do not turn it into an attachment Problem and continue. |

The same input-failure principle applies when the protected content carries the main message body: retain readable metadata and permitted native fallback, captured original input and actual Problems, without fabricating recovered protected content. An unavailable independently attached child does not become a parent body candidate. Deliberately deferred/unselected processing is unprocessed rather than a fabricated failure; captured limitations and actual unsuccessful selected work remain distinguishable.

Use the example of invoice.pdf, contract.p7m and photo.png: if only the contract cannot be opened with an otherwise usable key, all three complete attachment resources remain available, the contract stays protected and a located Problem explains its processing result. An actual configured-keystore failure can still abort conversion. The eventual implementation must classify failures at their actual content versus key-material/configuration boundaries; do not classify by exception-message text or a blanket RuntimeException catch. Cover S/MIME and OpenPGP incoming routes and preserve interruption/resource ownership/close-failure evidence.

### Forwarding and editing

- Reading a parsed child Email leaves its already selected attachment resource unchanged.
- Forwarding an interpreted parent uses the represented message and its selected ordinary attachment resources, including content recovered during conversion. It does not silently switch those resources back to their protected originals. Explicit original-preserving forwarding uses retained input instead.
- Editing a copy of the child produces another Email. It changes the parent's attachment only when the caller explicitly renders/selects the replacement and replaces that attachment.
- Passing a parsed child directly to `forwarding(Email)` deliberately forwards that interpreted view. The existing method renders Email to MIME; it does not promise the original input bytes or continuing validity of old signatures.
- A resource must expose the same bytes that sending that resource uses. Do not make a resource read as opened bytes while silently sending different original bytes.
- Java inspection objects, source graphs and Problems are not encoded as MIME metadata or extra attachments. The receiving application can inspect the transmitted payload through its own conversion.

The new transparent-reading and explicit original-forwarding boundaries are contracts to implement and verify, not claims that existing conversion/forwarding already satisfies every nested case.

### Agreed refinement: transparent attachment reading, 10 November 2026

The maintainer challenged the proposed per-attachment getOpenedResource() branch, then accepted the revised direction with "Yes, that makes sense. Let's settle that interface right away." Decryption is an intermediate conversion step: with suitable credentials and successful processing, the ordinary Email API should work the same for encrypted and unencrypted input. Moving the old getDecryptedAttachments() branch onto every attachment would make the common caller account for input protection structure again. The getOpenedResource() proposal is withdrawn.

Expose successfully interpreted resources through normal attachment access, with originals and protection evidence available through inspection. An attached Email view likewise exposes interpreted fields without an encryption-specific branch. Actual unavailable/failed processing retains the best-effort available resource and its appropriate processing observations. Getter-triggered processing remains excluded.

Forwarding an interpreted Email uses its represented content under the agreed outgoing-confidentiality guard. Forwarding retained original input is a deliberate different operation and preserves that input's payload/protection. No hidden split between the bytes read and sent by the same resource is allowed. Implementation and the remaining acceptance cases still need their own approved execution packet.

### Agreed interface for this refinement

The maintainer accepted these names/signatures on 10 November 2026. Preserve existing normal attachment access and add only a materialized child-Email getter on AttachmentResource:

```java
// Email: existing access, with the revised transparent conversion contract.
List<AttachmentResource> getAttachments();

// AttachmentResource: new passive getter; null when no parsed child is available.
@Nullable Email getEmail();

// EmailInspection: advanced original-input access; neither getter starts work.
@Nullable AttachmentResource getOriginalMessage();
@Nullable AttachmentResource getOriginalAttachment(int index);

// EmailStartingBuilder: preserve the existing interpreted-view operation.
EmailPopulatingBuilder forwarding(Email email);
EmailPopulatingBuilder forwardingOriginal(Email email);
```

getOriginalAttachment(index) associates with the occurrence at the same position in getAttachments(), without matching names or resource equality. It exposes retained input for that logical attachment, not a substitute enclosing message-body container. Unchanged payloads may be shared; do not duplicate them merely to satisfy the getter. Newly composed/unretained input has no fabricated original. Out-of-range indexes remain caller errors.

The maintainer explicitly confirmed this distinction on 10 November 2026: after opening an independently attached invoice.pdf.p7m, ordinary attachment access exposes the usable PDF, while getOriginalAttachment(index) exposes the retained protected file and its actual original metadata/bytes. If the PDF was merely inside an encrypted whole message, its original attachment payload can still be the same PDF; the enclosing protected message belongs to getOriginalMessage(), not getOriginalAttachment(index). Untransformed payloads can be equal/shared; lack of separately retained original input remains null.

getOriginalMessage() exposes retained original input for this logical message in its actual captured format, such as EML or MSG, not a newly rendered EML attributed to native MSG input. Both original-input getters are nullable when no appropriate payload was retained. They expose historical input even after edits to the represented Email; they do not regenerate content to reflect those edits. Exact retention policy/budget ownership must be inventoried in the implementation packet, without assuming every existing import already retains complete original input.

forwarding(email) constructs a new outer forward from the current represented Email, including edits. forwardingOriginal(email) constructs a new outer forward carrying retained original input, including its original protection, and does not render the edited Email as that original. When suitable original input is unavailable, fail clearly during preparation instead of silently falling back to reconstructed content. Preserve EML versus native MSG attachment format; original forwarding is not original-message SMTP relay. Use an available public subject or a neutral outer subject rather than disclose a private subject. Outgoing caller/governance choices apply to the new outer message.

The existing MimeMessage forwarding overload remains a separate caller-supplied MIME route; do not add matching new original-forward overloads without an actual retained-input contract. The operation-specific confidentiality overrides already agreed are not finalized by these signatures. No getOpenedResource() or duplicate original-resource getter is added to ordinary AttachmentResource, and getDecryptedAttachments() is removed in the clean 10.0.0 target with the required migration treatment.

### Agreed: automatic retention of available raw originals

The maintainer accepted this default retention policy on 10 November 2026. Keep the complete supplied raw EML/MSG input automatically when conversion acquires it, under the selected import limits/read contract. Share captured immutable storage where possible, including across ordinary copies, rather than cloning whole source archives on each copy. This gives getOriginalMessage()/forwardingOriginal() useful default behavior without an additional retention/inspection activation switch. Current raw EML and MSG conversion routes already acquire source bytes; this agreement extends their lifetime in the resulting Email, rather than requiring a second source read.

Preserve an independently protected attachment's original payload before a successful transformation exposes usable content. Where the original attachment is unchanged and its payload is already safely retained, the inspection getter can share that storage. A borrowed mutable/external source is not automatically an immutable retained original. Do not read unrelated deferred content or retain every generated representation merely for diagnostics.

A MimeMessage or native reader object without actual captured original input does not supply an original file: keep its useful interpreted Email but leave the original-message resource unavailable. Newly composed messages likewise have no fabricated input original. For EML String input, any retained byte resource is the UTF-8 interpretation actually consumed by the converter; do not claim an unknown external wire encoding or generalize this into re-encoding reader-provided native text as purported original bytes. Native MSG retains MSG format, not generated EML.

Only fully acquired original input qualifies for the original resource getters/forwarding. A captured prefix remains incomplete source evidence with its actual observation; it must not be returned as a complete original attachment/message. An actual import-limit/read failure retains existing best-effort behavior and Problems. Original forwarding fails clearly when a complete suitable original is unavailable, while ordinary parsed access remains usable where possible. Honor existing limits without treating maximumInputBytes as a whole-JVM memory/retention cap or inventing an unapproved hard limit.

Keeping raw input alongside readable/decoded content can increase retained memory; share existing buffers where safe but do not promise zero additional storage for genuinely different representations. The eventual implementation packet must account for original and decoded storage ownership and close/copy/serialization behavior. This requires a focused dated clarification of ADR 0041's selective-retention rule for the original-forwarding capability, preserving its no-fabricated-source, no-getter-work and deferred-read rules. The broader ADR restructuring remains canceled.

## Agreed: best-effort body fallback with representation-specific facts

The maintainer accepted this contract with "Agreed. Let's use that fallback contract." First identify this logical message's body and its meaningful representations. An independently attached message is never a body candidate for the parent merely because it is the only attachment.

For each existing convenience getter, prefer the successfully interpreted protected alternative when present, including an explicitly empty String. When that alternative is absent or unavailable, allow an already materialized native body alternative to fill the getter. Do not silently rewrite original source bytes or attachment payloads. An outer/native origin alone is not a reason to discard a genuine body representation.

| Source situation | Agreed plain/HTML result |
| --- | --- |
| Opened body has plain text and HTML | Return both interpreted alternatives. |
| Opened body has plain text only; native wrapper has HTML | Return the opened plain text and native HTML, each with its own origin and protection facts. |
| Opened body has an explicitly empty plain alternative | Return the empty String, without replacing it with another rendering. |
| Protected body could not be materialized; a native body is readable | Return the available native fallback while preserving the actual processing limitation and original protected content. |

Plain text and HTML may describe different versions: a native rendering can be stale or altered independently. The library generally cannot prove semantic equivalence from the available strings. Staying within one source view would not prove that its alternatives agree semantically either.

Every selected body representation needs directly accessible source/derivation and actual established protection coverage. A valid signature over one protected representation must not imply coverage of a native fallback. Origin and coverage are separate facts: native data can itself be covered by another enclosing protection layer. Ordinary readers keep simple getters; callers assessing protection need a direct body-level answer rather than a MIME/source-tree traversal.

An expected fallback by itself is not a processing Problem. Actual interpretation/read/limit failures retain their Problems. The model must distinguish genuinely absent content from unvisited or unavailable content; a nullable convenience getter alone does not establish inspection completeness. Getters remain passive and do not synthesize new alternatives merely to avoid absence.

Existing protection summaries describe input facts and cannot silently change their subject to make a fallback appear verified. Any changed assumption for released callers needs explicit compatibility/migration treatment. Calendar ownership, embedded resources and header selection remain separate decisions. Copy/export/forwarding cases must show how the chosen readable view differs from retained original representations before implementation is authorized.

## Agreed direction: shared field and component inspection

The maintainer accepted a common provenance/protection model for received fields and components, then requested a simple shared API for HTML, subject and an attachment without duplicating all Email getters. HTML is not unique: subject/address/calendar selection and individual resource occurrences also need an honest connection to their contributing source evidence. This replaces the earlier body-only BodyContent proposal. The maintainer then explicitly rejected an enum selector: the inspectable field set is small and fixed, so explicit getters are more convenient and can share one return type. Inspection is the preferred grouping name, pending final naming.

Keep ordinary values in their existing getters. Inspection supplies metadata about the current selected value or resource occurrence, using the existing source evidence rather than a second source tree. Field inspection need not duplicate the value in another wrapper. A field with no ordinary value can still have source evidence explaining unavailable interpretation.

### Agreed inspection access shape

The maintainer accepted this access shape. The complete getter inventory and detailed result interfaces still need the eventual API expansion/review packet:

```java
EmailInspection inspection = email.getInspection();

ValueInspection html = inspection.getHTMLText();
ValueInspection subject = inspection.getSubject();
ValueInspection attachment = inspection.getAttachment(0);

// The same metadata shape applies to all three.
html.getOrigin();
html.getSources();
html.getSignatureStatus();
html.getEncryptionStatus();
```

Use explicit metadata getters for the agreed small set of inspectable fields. Do not introduce EmailField, reuse the template-policy EmailProperty enum, expose catch-all String keys, or reproduce unrelated Email configuration getters. These accessors all return ValueInspection and do not duplicate their content values. Exact getter membership belongs in the eventual approved API inventory.

`getAttachment(0)` describes the first occurrence in this Email's ordinary `getAttachments()` list. Match occurrences without filenames, AttachmentResource.equals(), content hashing or payload reads. Duplicate names and equal/reused resource instances must not conflate occurrence provenance. The same principle applies when designing other collections; do not make a collection-level summary stand in for each item.

Return immutable metadata for supported field/item lookups, without a public builder, conversion overload or getter-triggered work. Unknown/uncaptured history remains explicit; absence is not proof that inspection succeeded. Out-of-range item positions remain caller errors. Origin describes the selected source and derivation; it must not stand in for protection coverage. Source references point directly to existing captured occurrences, including multiple contributions where needed.

Use family-neutral protection facts so ordinary callers do not branch on S/MIME versus OpenPGP. Preserve established absence, known but unverified protection, actual invalidity, and unknown coverage. A verified summary must apply to all contributing content of the selected value and remain conservative about failed or unresolved covering layers. It does not establish signer trust.

Attachment inspection describes the selected ordinary payload, including a successfully opened representation under the refined attachment contract. Retained original input and a recursively parsed child have their own facts; a valid signature on a child body must not verify the entire message/file attachment by implication. Distinguish protection established for the selected content from protection inside an opaque payload. Generalizing body metadata requires explicit encryption/partial-coverage semantics for opened content and unavailable protected files; do not freeze the earlier body-only status examples as the complete shared enum contract.

Exact origin/derivation representation, status enums, aggregation precedence and links to detailed protection observations remain to be agreed. Preserve observations for multiple/nested layers without another public source tree or an ambiguous whole-message validity Boolean. Copy/edit behavior must ensure a replacement value does not acquire old source protection claims merely because the Email retains input history. Historical source evidence remains available separately.

### Agreed: unverified, invalid and unknown signature results

The maintainer accepted these status names and meanings on 10 November 2026. Report a recognized signature known to cover the selected value as UNVERIFIED when verification has not established validity or invalidity. Deliberately skipped processing is not a fabricated failure; actually encountered verification limitations retain their appropriate source-linked observations. INVALID means invalidity was established, rather than merely unavailable verification. UNKNOWN is needed when signature presence/coverage itself cannot be established, such as an unopened/unvisited source layer; it is not interchangeable with a recognized but unverified signature.

Keep the result specific to the selected field/resource. A signature associated with selected protected plain text does not mark native fallback HTML as signed. These states do not prevent ordinary best-effort reading or establish signer trust. The complete enum/result type, partial coverage, multiple signatures and aggregate precedence remain to be settled before treating this as a frozen status interface.

### Agreed: multiple signatures with different results

The maintainer accepted MIXED on 10 November 2026. For a selected value with multiple associated signatures, use MIXED when their results differ, retaining the individual outcomes rather than hiding one behind a single success/failure. The concrete example is one verified signature and one invalid signature covering the same HTML: the readable HTML remains available and its summary is MIXED. This is not a blanket trust/acceptance verdict; the application can inspect which signatures produced which outcomes under its own policy. Partial content coverage and exact aggregate precedence still need their own acceptance cases.

### Next proposal: individual signatures behind a field summary

The detailed accessor inventory is not yet agreed. On 10 November 2026 the maintainer asked to reconsider the caller-facing granularity and then accepted the domain-level requirements checks below as the primary API. The individual-signature proposal remains optional evidence/advanced-usage material, rather than the normal acceptance-check recipe. Finalize detailed accessors only where the concrete diagnostic/custom-policy use cases justify them.

The evidence proposal is an immutable getSignatures() list on the common ValueInspection, exposing the signature observations associated with the selected field/resource behind VERIFIED, INVALID, UNVERIFIED or MIXED. Each SignatureInspection would expose its verification result, coverage of this selected value and available signer/key information, with links to captured detailed observations. The illustrative accessor shape is getStatus(), getCoverage() and getSigner(); exact signer type/evidence members and detailed algorithm/failure links still require the public inventory before implementation.

Keep coverage distinct from verification. Propose FULL, PARTIAL and UNKNOWN for an associated signature's coverage of the selected value; a successful signature check alone does not imply FULL coverage. A per-signature result is not MIXED merely because the field summary combines several signatures. Preserve child-versus-parent ownership and do not list an independent child's body signature as protection of the parent's complete attachment payload. Do not invent a signer or key when unavailable; claimed sender text is not an established signing identity or automatic trust result.

A caller policy can require one VERIFIED, FULL-coverage signature from an application-approved signer, while a stricter application can hold MIXED input for review. Application approval is application-owned and is not a new library trust engine. An empty observation list alone does not prove signature absence; the field summary retains its established-absence versus unknown meaning. Getters expose materialized immutable observations and never verify again. Reuse the same underlying captured signature evidence across field and family views, with field-specific coverage, rather than duplicating or recomputing cryptographic results.

### Agreed primary API direction: pure requirements checks over captured evidence

The maintainer accepted this primary API direction and caller workflow on 10 November 2026. Design the primary security-consumer API around an application question such as whether a selected partner document meets supplied protection requirements. Keep the received facts and individual observations inspectable for diagnostics/custom policy, while providing a small reusable immutable requirements value and one evaluation entry point with an explanatory result. Do not add a method for every signature/encryption/field combination or a generic whole-Email secure/trusted Boolean.

Accepted caller shape; the complete signatures and signer/result member inventory still need completion before implementation:

```java
ProtectionRequirements partnerDocument = ProtectionRequirements.builder()
    .requireVerifiedSignatureFrom(approvedPartner)
    .build();

ProtectionCheck check = partnerDocument.check(
    email.getInspection().getAttachment(0));

if (check.isSatisfied()) {
    processDocument(attachment);
} else {
    holdForReview(check.getReasons());
}
```

The expected-partner input represents application-supplied key/certificate expectations, not a claimed From address or an automatic assertion of partner trust. Prefer immutable captured requirements/evidence; do not silently invoke network trust discovery or key loading. The check evaluates already materialized facts without input reads, cryptographic reprocessing, mutation or automatic rejection/suppression of the Email. Repeatable evaluation over the same immutable inputs yields the same result.

The named verified-signer requirement must bind success, full coverage and signer approval to the same signature. A passing signature elsewhere, a different approved-but-invalid signature, partial coverage or missing/unknown evidence must not satisfy that requirement. MIXED may satisfy an explicit at-least-one-qualifying-signature requirement; stricter mixed/invalid-signature policy can be separately expressed if the concrete use cases require it. Unmet versus indeterminate requirements must remain explainable rather than flattening unavailable evidence into an INVALID claim. Reuse requirements for selected body/header/resource inspections one value at a time; do not infer coverage of unselected fields or independent children. The proposed multi-value joint-signature API below is withdrawn from the initial scope pending a demonstrated consumer need.

Use the receiving use case, tactical recipe and protected-partners application to test this level of convenience before expanding public methods. Their primary example should evaluate supplied requirements and explain its decision, with low-level observation navigation used for diagnosis. This acceptance covers the primary requirements/check workflow; complete the concrete signer-input and result contracts, evidence inventory and execution packet before implementation or delegation.

### Agreed: a small initial requirements vocabulary

The maintainer accepted this initial requirements vocabulary on 10 November 2026. Start with requireVerifiedSignatureFrom(approvedPartner), requireDecryptedContent() and an optional stricter requireAllSignaturesVerified(). Keep one evaluation entry point and an explicit selected-value scope, rather than convenience methods for every combination or a general rule-expression language.

requireDecryptedContent() means that the selected readable content is established as having been recovered from encrypted input. It evaluates the captured DECRYPTED/coverage observations; it does not load credentials or attempt decryption. A plain unencrypted PDF does not satisfy it, and an opaque unopened protected resource does not satisfy it merely because encryption is recognized. Missing/uncaptured history remains indeterminate. A PDF recovered from an encrypted whole message and one recovered from an independently encrypted file use this same check, with their actual per-resource coverage.

requireAllSignaturesVerified() is an optional stricter condition on the approved-signer requirement: every relevant associated signature must verify, while the original requirement still demands one fully covering signature from an approved key. A known invalid signature prevents satisfaction; an unresolved relevant signature prevents an affirmative result. This strict condition does not make every signer approved, broaden scope into independent children, or replace the main full-coverage/approved-signer requirement. Its exact empty/presence/incomplete-inspection and builder-combination cases belong in the frozen contract before implementation.

Illustrative common policy:

```java
ProtectionRequirements partnerDocument = ProtectionRequirements.builder()
    .requireVerifiedSignatureFrom(approvedPartner)
    .requireDecryptedContent()
    .build();
```

The normal policy accepts one qualifying signature even when other signature outcomes are MIXED; the strict addition is a deliberate application choice. Use assessment reasons to explain unmet or indeterminate conditions. Keep this vocabulary small and reuse it for body/header/resource inspections without changing parser behavior, outgoing protection configuration or generic Email methods. Construction/removal/copy semantics and every affected public surface still require the eventual API-expansion inventory.

### Withdrawn from initial scope: grouped same-signature checks

The maintainer questioned the practical use case on 10 November 2026 and observed that the proposal was beginning to resemble an assertion library. The lead withdrew the proposed varargs/group check, and the maintainer accepted keeping the initial scope focused before asking what remains. The group proposal was never an accepted API and is excluded from the initial design.

Keep the ordinary entry point scoped to one selected ValueInspection. The accepted small mail-protection requirements and explanatory result remain useful for the concrete partner-document decision; do not expand them into a general assertion/matcher or cross-field correlation framework.

Checking that several pieces were signed together can be a legitimate specialist requirement, but it has not been established by an actual consumer requirement or the current protected-partners application, which verifies and archives a captured message. The planned receiving workflow can demonstrate checking one required document without manufacturing a joint-content business requirement to exercise new API machinery.

Preserve captured signature/source evidence for advanced application-specific work where available. Reconsider a joint-content helper only when a concrete use case demonstrates repeated mail-specific logic that belongs in SJM. The website use case/tactical recipe/integration example should follow real application needs rather than exist to justify the withdrawn helper. Detailed evidence accessors likewise need demonstrated utility before expanding their public inventory.

### Agreed: explicitly approved signing keys as the signer expectation

The maintainer accepted this signer-expectation direction and S/MIME caller shape on 10 November 2026. Make the expected partner an immutable reusable description of application-approved signing keys, rather than requiring a per-message low-level lookup/predicate. ApprovedSigners can accept explicitly supplied S/MIME certificates or OpenPGP public-key material and permit any one qualifying key in the set. Application configuration establishes which keys belong to the partner; the check does not infer approval from the received From address or discover trusted identities from incoming data.

Accepted S/MIME setup, with a second approved key for rotation:

```java
ApprovedSigners approvedPartner = ApprovedSigners.builder()
    .addSmimeCertificate(currentCertificate)
    .addSmimeCertificate(replacementCertificate)
    .build();
```

Match the actual signing public key. A supplied certificate authorizes its signing key for this expectation; a renewed certificate with the same key can still match, while a different replacement key must be explicitly approved. This is key pinning, not automatic endorsement of every claim in another certificate using the same key. Certificate-chain, validity-time and revocation/organization identity policy remain application responsibilities unless separately designed. Exact certificate pinning or a broader PKI trust facility is not silently implied.

Normalize/snapshot expected key material before evaluating message evidence, so the accepted check remains a pure operation. OpenPGP input needs a precise approved signing-key/subkey and fingerprint contract in the eventual packet; do not casually equate a short key ID or unvalidated subkey binding with approval. Complete the ApprovedSigners member inventory, construction/module boundaries and supported key-material routes before implementation; avoid duplicating every file/stream overload merely to supply this small requirements value.

### Agreed: protection-check outcomes and reasons

The maintainer accepted these outcomes and their distinction on 10 November 2026. Use SATISFIED when the supplied requirements are established, NOT_SATISFIED when evidence establishes an unmet requirement, and INDETERMINATE when unavailable evidence prevents a decision. isSatisfied() is true only for SATISFIED; getOutcome() allows an application to distinguish a known mismatch from insufficient evidence, and getReasons() explains the result. Exact reason value/code members remain to be inventoried without making callers interpret free-form exception text.

For a verified-approved-signer requirement, a fully covering verified signature from an approved key is a passing witness even if another signature is invalid/unverified and the field summary is MIXED. No qualifying witness after complete inspection is NOT_SATISFIED. If uninspected/unverified candidates could still satisfy the requirement, absence of a proven witness can be INDETERMINATE instead. A positively unmet required condition can establish NOT_SATISFIED even if another required condition is unknown. Keep per-signature matching/coverage/approval bound together and map the combinations explicitly in acceptance cases.

Requirement-check reasons belong to the returned assessment, not Email.getProblems(). An ordinary unsigned message can parse successfully and have no Problems while failing an application rule that requires a signature. Evaluating a requirement must not add input-processing Problems or change the Email. Application process/hold/reject decisions remain separate from parser recovery and SMTP outcomes.

### Agreed: encryption status after transparent decryption

The maintainer accepted these meanings and basic states on 10 November 2026. Make getEncryptionStatus() describe established incoming content-encryption coverage and processing for the selected value/resource, rather than the outgoing encryption configuration or a promise that its currently readable bytes are ciphertext. A successfully opened independently protected PDF is available through ordinary attachment reads and reports DECRYPTED: the represented content came from known encrypted input that was opened. Reusing/sending that resource still uses its exposed PDF bytes and requires outgoing protection under the already agreed helper policy.

The agreed basic states are NOT_ENCRYPTED for established absence of covering content encryption, DECRYPTED for selected content recovered from known encrypted input, ENCRYPTED for a retained recognized encrypted payload that has not been opened, and UNKNOWN when coverage cannot be established. An actual inability/failure to decrypt retains its appropriate detailed observation/Problem; deliberate deferral is not fabricated failure. These statuses do not settle which failures stay per-file versus abort an explicitly requested operation, or the remaining partial/multiple-layer aggregation cases. Keep field/resource scope: a decrypted protected alternative does not mark an unrelated native fallback as decrypted.

### Agreed: current-field inspection after copying and editing

The maintainer accepted these concrete copy/edit semantics on 10 November 2026. An ordinary copy with unchanged represented fields retains those fields' source associations and received protection observations. This remains input evidence, not a claim that reconstructed outgoing MIME is covered by an old signature or encryption.

Replacing a field through the builder makes its current origin caller-supplied and stops that field from inheriting the old source's verification/decryption claims. Use UNKNOWN for the replacement's incoming signature/encryption status because no incoming protection evidence is associated with that current value. Do not label an untouched original signature invalid because a new field was supplied. Other unchanged fields retain their own actual observations, and captured originals plus original processing results remain historical evidence under inspection. A clear/removal similarly stops a current-value claim, without deleting historical input.

Treat an explicit setter as replacement even when supplied text equals the previous text; do not infer source association from text/resource equality or reopen payloads to prove equality. Unchanged ordinary copies retain evidence without such setter calls. Resource/collection occurrence edits, direct and generic header setters, defaults/overrides, transformations and supported Java snapshots need explicit acceptance cases in the final packet. Changing field-inspection metadata must not silently remove an agreed reply/forward outgoing-confidentiality requirement; its editing/override lifecycle remains a separate explicit contract.

### Agreed direction: incoming protection details under inspection

The maintainer accepted incoming S/MIME and OpenPGP information under the same inspection entry point, with explicit family-specific accessors `inspection.getSmime()` and `inspection.getOpenPgp()`. These describe captured incoming protection processing; outgoing signing/encryption configuration retains its existing Email/builder ownership.

Family-specific results and field/component coverage answer different questions. A valid S/MIME signature over protected plain text can coexist with no signature covering a selected native HTML fallback. The family view must identify its logical-message/source scope, exclude independent child-message facts from parent success, and retain relevant signer/algorithm/failure observations. Field inspections use the same source-associated observations to establish actual coverage; they do not copy a global validity flag onto every value.

Do not make the legacy flattened OriginalSmimeDetails summary the new internal source model. Preserve useful information as source/layer-associated observations and expose appropriate summaries/details without requiring ordinary callers to navigate nested MIME structures. Exact new family return types and detailed layer access remain design decisions.

The maintainer explicitly rejected preserving `Email.getOriginalSmimeDetails()` as a deprecated view: it is removed in 10.0.0. Treat 10.0.0 as a clean API break and follow the repository review workflow and OpenRewrite implementation/migration requirements. The eventual subagent handoff must include the exact removal/replacement inventory, released-baseline and frozen-target signature checks, safe automatic recipe changes where justified, and manual/report guidance for semantic changes. Do not invent migration recipes for signatures that existed only in unreleased 10.0.0. Resolve other superseded incoming-detail accessors and supporting types in that same inventory without assuming compatibility aliases are needed. This acceptance does not authorize starting the overhaul before the remaining contracts and execution plan are approved.

## Agreed website deliverable: a workflow for checking received-message protection

On 10 November 2026 the maintainer requested website documentation showing how to use these checks together, for developers interested in the security inspection layer. Include this as a required implementation/review/upkeep deliverable, rather than leaving callers to assemble a policy from isolated getter descriptions. Ordinary Email reading remains convenient and best effort; the guide explains the optional checks needed by applications that rely on protection claims.

The main home is the website's [security guide](../../simplejavamail.org/src/pages/security.hbs), with entry links from the S/MIME and OpenPGP reading sections and the relevant conversion material in [features](../../simplejavamail.org/src/pages/features.hbs). Reconcile existing status/merging examples with the final API. Keep useful existing anchors working. Update [10.0.0 migration notes](../../simplejavamail.org/src/pages/migration-notes-10.0.0.hbs) for released-user removals and changed semantics; keep the complete usage workflow in the feature guide.

Explain one connected application journey in plain English:

1. Convert the input with the appropriate incoming credentials/read settings, and inspect actual processing limitations. Successful parsing and an empty Problems list are not security certificates.
2. Identify the exact values the application will display or process: selected plain/HTML text, subject, attachment occurrence or parsed child. Available native fallback remains readable but has its own origin and protection.
3. Check signature results, encryption observations and coverage for those selected values. Distinguish UNVERIFIED, INVALID and UNKNOWN; explain MIXED and the remaining partial/multiple-layer rules once settled. Do not infer whole-message protection from one successful component.
4. Apply the application's signer/key trust and identity policy separately from mathematical signature validity. A claimed From address or a successful decryption alone does not establish the sender's identity. Use the family-neutral field facts first, with detailed S/MIME/OpenPGP observations when needed.
5. Show how an application can display best-effort content with an honest indication of its status, or hold/reject a sensitive automated action under its own requirements. Missing/uncertain evidence and actual failure must remain distinguishable; inspecting a failed independent child does not turn parent processing into a global gate.
6. Finish with copy/edit and reply/forward usage: current represented values versus retained originals, the effect of edits on protection claims, explicit original forwarding, and the agreed outgoing-confidentiality requirement/operation-scoped override. Incoming processing does not provide outgoing credentials or automatically protect new content; body encryption does not hide every outer header.

Provide end-to-end examples for a wholly protected value, protected plain text with native unsigned/unverified HTML fallback, and a protected independent attachment/child email. Include MIXED signatures and partial coverage once its remaining contract is agreed. Use the final public API and actual caller-visible outcomes; do not expose internal source-tree/caching machinery or present unimplemented draft signatures as current supported usage. Validate the final examples against the combined implementation and review the rendered guide as part of the same release packet. Record execution evidence privately under the review workflow rather than in public issue/release prose.

### Required application context: use case, tactical recipe and integration pattern

On 10 November 2026 the maintainer additionally required this workflow to connect to a use case, have a tactical pattern and appear in an integration pattern, then accepted the concrete mapping below. This focused scope does not authorize a general website or pattern-portfolio redesign.

- Add a receiving-oriented use case, "Inspect incoming mail before acting on it", in [use-cases](../../simplejavamail.org/src/pages/use-cases.hbs). The existing security entry is explicitly about sending. Give receiving callers a direct route to the inspection guide, tactical recipe and runnable pattern instead of making them infer that route from sending material. Preserve existing anchors.
- Add a focused tactical recipe, "Check a partner document before processing it", in [the workload field guide](../../simplejavamail.org/src/guides/email-workloads.md), with its directory metadata in [email-workloads.11tydata.json](../../simplejavamail.org/src/guides/email-workloads.11tydata.json). Show the required-content check, coverage/signature interpretation, an application-owned approved-signer rule and a process/hold decision. Link the existing outbound-confidentiality and original-byte-retention recipes as related cases; retain their distinct purpose. Keep the [downloadable Java examples](../../simplejavamail.org/src/assets/guides/email-workloads/FieldGuideExamples.java) and usage notes aligned with the final API.
- Extend [Exchange protected partner documents](../../simplejavamail.org/src/strategic-patterns/protected-partners.md) and its canonical [PartnerDocuments application](../../tools/integration-patterns/protected-partners/src/main/java/org/simplejavamail/examples/patterns/PartnerDocuments.java). The current Camel example sends a signed message, reads the local test server's captured bytes, checks one flat legacy S/MIME validity status and archives those bytes. The expansion should exercise a real application decision over received protected content, not merely replace that legacy getter with another global Boolean.

The planned application journey is receive, interpret/decrypt, check the exact required document and approved signer under an explicit application policy, process or hold for review, and retain the original input as appropriate. SJM supplies readable content, coverage/processing evidence and outgoing protection; the example application owns partner approval, business processing, persistence and review decisions. Keep document acceptance distinct from SMTP submission/delivery results. Preserve the current original-byte archive and uncertain-submission controls; a successful cryptographic check must not upgrade an unknown transport result.

Include observable scenarios for an acceptable protected document, a mathematically valid signature from an unapproved signer, unavailable verification/decryption, MIXED outcomes and content outside the required signature coverage. A demonstrated strict application policy may hold these cases, but document that MIXED is not a universal SJM rejection rule and that applications may assess individual approved signatures. Use synthetic identities and local inputs, without implying production partner trust from the fixture certificate.

The eventual implementation packet must include the canonical application and README, any necessary shared protection helpers/fixture scenarios, the website article and tactical cross-links, [pattern demonstration metadata](../../simplejavamail.org/src/_lib/pattern-demonstrations.mjs), downloadable tactical examples and the generated [integration-example archive](../../simplejavamail.org/src/assets/guides/integration-patterns/strategic-patterns.zip). Regenerate the archive through [package_examples.py](../../tools/integration-patterns/package_examples.py) and check consistency. Review the article, executable scenario and actual outcomes together, preserve existing compatibility anchors and unrelated dirty website work, and leave broad layout/style changes out of this feature.

## Agreed: calendar selection

The maintainer agreed to carry forward the existing calendar pairing and fallback rule: prefer an available interpreted protected calendar; otherwise use the available native calendar of this logical message. Calendar text and method are selected together from one view. A present empty String is not absence. Never take an action such as CANCEL from one view and calendar text from another.

Keep ordinary `getCalendarText()` and `getCalendarMethod()` access. `inspection.getCalendar()` describes the selected calendar as one unit using the common ValueInspection contract. Preserve its actual origin/coverage independently from the selected plain text and HTML. An independent attached message remains outside the parent's calendar selection.

This mostly preserves the current behavior recorded in ADR 0040; it needs an explicit place in the coherent source-selection model rather than an incidental constructor fallback. Detailed incomplete-calendar/processing-stop cases remain part of the eventual acceptance inventory.

## Agreed: embedded resources and selected HTML

The maintainer accepted selecting HTML with its associated resources, best-effort unambiguous same-message resource fallback, source-linked Problems for unresolved ambiguity, and independent per-resource protection facts. The maintainer additionally said unused images should become ordinary attachments, asking whether import already does so. Inspection of current MIME/EML and Outlook classification confirms that existing rule; the overhaul must apply it to the final selected HTML and its actual bound occurrences. After that clarification the maintainer explicitly confirmed: "Agreed. That settles embedded images."

Select HTML together with its known related resource occurrences. Protected HTML uses its associated images; native fallback HTML uses its associated images. Use actual source/container relationships before resolving identifier collisions. A missing resource may be recovered from another already parsed view of the same logical message when the match is unambiguous. Do not borrow resources from an independently attached child message. Unresolved ambiguity retains a source-linked Problem and the available candidates, without inventing a verified association.

`getEmbeddedImages()` exposes the images used by the selected HTML. Ordinary `getAttachments()` retains ordinary attachments, including materialized images not used by that selected presentation. Preserve each unused occurrence's payload, filename and source/protection facts; do not deduplicate by filename, Content-ID, object equality or content reads. An image used only by an unselected HTML view is unused for the current presentation even when its historical Content-ID matches another selected image. Explicit ordinary-attachment ownership is not erased solely because a resource also serves a reference.

Use `inspection.getEmbeddedImage(index)` for common ValueInspection metadata of ordinary embedded-image occurrences, matching the indexed lookup pattern for attachments. Inspection of an unused image follows its ordinary attachment occurrence. Verification of HTML markup does not implicitly cover a recovered image. Expected reclassification of an unused image is not itself a Problem; encountered ambiguity and processing failures retain their actual observations.

Current source evidence: `MimeMessageParser.moveInvalidEmbeddedResourcesToAttachments` classifies against the parsed HTML; `InlineResourceMetadataTest.assertImported` expects `unused.png` in ordinary attachments with its metadata and payload retained. Outlook's `referencedImages`/`ordinaryAttachments` split similarly selects referenced images and retains other files. Legacy protected/native merging later concatenates resource lists. The new association/classification must therefore run at the correct final-selection boundary, not merely assume the per-source initial split remains valid.

Copy/edit/reconstructed-export cases remain part of the eventual acceptance inventory. In particular, moving an unused colliding resource to ordinary attachments must not reintroduce an ambiguous active HTML reference in the rendered MIME. Resolve outgoing metadata versus historical Content-ID ownership explicitly while preserving original source facts and payload bytes. No blanket payload rewrite, eager resource read, automatic change to exact/protected source bytes or new CID-renaming setting is accepted here. Received-content classification does not silently redefine unrelated caller-composed resource choices.

## Agreed: subject selection

The maintainer accepted this subject rule before pausing to triage #979. Current legacy merging preserves the outer/native typed subject and addresses while inner custom-header lists replace matching outer names. The retained inner Email can be an implementation-generated adapter for a MIME body, so its existence does not establish actual authored message headers.

Use a present, readable subject from recognised protected headers of this same logical message; otherwise use the available native/outer subject. A present empty subject remains present. A generated wrapper's helper headers and headers belonging to an independently attached message are not candidates. Preserve all actual source header occurrences and expose the selected subject's own origin/derivation/protection through the agreed `inspection.getSubject()`.

This is source selection rather than a verification gate: readable selected data retains its actual unverified/invalid/unknown protection result where applicable. An intentionally obscured outer subject differing from the protected subject is not itself a Problem. Actual interpretation failures and uncertain ownership keep their observations and do not justify inventing an authored subject.

Protected message headers are a real input possibility: RFC 8551 section 3.1 discusses protected message/rfc822 headers and RFC 9788 updates header protection. This contract does not claim current SJM support for every such format or approve full generation/conformance work. Specify supported recognition/input forms and source/layer ownership in the eventual implementation packet. Ordinary body-only protection continues to use native headers where no real protected subject was present.

The authored-address, identity/date/threading, parsed-header and semantic copy/emission contracts are agreed in their respective sections below. Copy/reply/forward/export handling must still account for a selected confidential subject before populating newly composed cleartext headers. This remaining output-safety decision does not reopen the agreed read selection or authorize implementation.

## Agreed: authored address view and reply destinations

The maintainer accepted this address and reply-destination policy. Select From, To/Cc/Bcc and Reply-To from one recognised authored address/header view of this logical message. When a real protected message header section supplies that view, use its address fields and meaningful omissions. When protection supplies only body content, retain the native/outer address view. A protected subject alone, generated MIME adapter or independent attached message does not establish a replacement authored address set.

Preserve each selected To/Cc/Bcc list's roles, order and occurrences; do not union protected lists with outer lists or fill genuinely absent members from the other view. In particular, absence of Reply-To in a selected protected header view uses that same view's From address(es) under the existing reply fallback. It must not import an outer Reply-To or outer Cc recipient. Keep current tolerant From/Sender/Reply-To grammar/recovery behavior inside the selected view, including explicit malformed-value handling, instead of introducing address guesses or a second parser.

From remains the message's claimed author, independently of signer identity/trust. A signer certificate or signing-key identity does not supply/overwrite From. Encryption keys/certificates, Delivered-To/trace data and source delivery observations do not invent To/Cc/Bcc or an outgoing delivery override. Preserve actual observed alternatives and field-specific protection facts through inspection. Do not claim signature validity establishes the sender's trusted identity.

For automatic replies to a readable protected-header message, build destinations from that protected address view: Reply-To when supplied, otherwise that view's From; reply-all also considers that view's To/Cc under existing duplicate/self rules. Do not automatically add Bcc recipients. RFC 5322 section 3.6.2 gives the Reply-To/From fallback, while RFC 9788 section 6.2 explicitly requires protected destination fields for replies to header-protected messages and discusses injected outer Cc destinations.

Preserve absence versus unavailable/unvisited address data. When a recognised protected address view cannot be read, available outer metadata can remain inspectable with its true source/protection status. An automatic reply leaves destinations unresolved for explicit caller selection instead of silently using outer addresses for that unreadable protected view. Import remains best effort; ordinary outgoing destination validation still applies. Exact partial/unavailable/invalid-protection cases belong in the eventual reply/output acceptance table before implementation.

Both replyingTo(Email) and replyingTo(MimeMessage) must use the same selected logical address semantics when the same information is available; the current MimeMessage overload constructs native.reply() before conversion, so source-selection work must review that actual route. The new reply's sender remains supplied by its caller/governance. Address selection does not inherit incoming signing/encryption configuration, infer SMTP envelope recipients or approve implementing every header-protection format. Other output/format scopes remain explicit decisions.

## Related maintenance case: #979 and body derivation

The separately approved Java 8-compatible native-body metadata patch under #979 is released as 9.3.10 at `ec6cb6ddf258a294800679779b4a09ce8282fe40`, with website source at `1328fa073c55aa1346c9d73f427f4beae26a04b0`. It adds native HTML and reader-provided RTF to the existing specialised result, preserving current fallback. The issue is closed/completed and its public communication previews the planned `email.getInspection().getHTMLText()` shape. It does not implement this 10.0.0 overhaul or authorize cross-branch runtime changes. Its completed coordination plan remains in place under an automatic cleanup-review hold.

Carry the confirmed source cases into the eventual 10.0.0 inspection contract: native HTML; original HTML extracted from RTF encapsulation; HTML generated from encapsulated plain text; genuine RTF rendered as HTML; and absent/unknown source facts. Missing native HTML or RTF is not proof that the original message was plain text. Metadata must describe the actual selected HTML's contributing sources and derivation, including protected/nested paths, without requiring a second MSG parse. Detailed type/enum choices remain unapproved until the inspection-result design is completed.

## Agreed: Message-ID ownership

The maintainer accepted this Message-ID rule. Dates and threading details follow separately. For ordinary interpreted import, the normal `Email.getId()` identifies the logical message selected for presentation. Use its genuine protected Message-ID when a recognised protected message header view supplies the authored headers; use the native/outer Message-ID when that native view supplies the message headers. Do not promote a generated parser-wrapper ID or an independently attached message's ID into the parent's identity.

Keep a genuinely missing Message-ID absent in the selected authored view, while retaining other observed IDs through source inspection. Do not silently splice a carrier ID into an otherwise selected protected identity/threading context. Distinguish a complete observed absence from an unread/unavailable header. Exact read/partial-interpretation cases must be covered alongside the chosen header-view policy in the execution packet.

The library's stable source UUID and each actually observed source Message-ID remain historical evidence; selecting/changing the current Email ID does not overwrite them or imply global uniqueness/trust of received identifiers. A generated helper ID is not an observed source fact. New outgoing message/reply identity and exact relay behavior remain output contracts; a reply's own ID must not simply reuse its parent's ID. Automatic threading must use the selected parent identity consistently, with confidential-header handling resolved in the later reply/output rules. Acceptance does not authorize implementation or incidental changes to getId()/render-time mutation semantics before the execution packet is approved.

## Agreed: the message's stated sent date

The maintainer accepted this sent-date contract. `getSentDate()` and its Instant convenience describe the selected logical message's usable stated/submission date. Use the Date from the recognised selected authored header view. A missing or uninterpretable date in a selected protected message view stays unavailable; other observed timestamps remain accessible through inspection. Do not fill it from a generated wrapper's Date, an independently attached email, item creation/modification, receipt/delivery time or a cryptographic signing-time attribute.

For a native MSG header view, retain the existing preference for an actual client-submit timestamp; if absent, use a usable actual Date header. A generic native reader date is not sufficient evidence of its semantic origin. The released 1.16.2 source and the current 1.16.3 artifact show setCreationDate also populating the generic date field, so getDate() can conflate creation metadata with a message date. The overhaul must select known sent-date sources rather than silently inherit that fallback. This is not a request to change the released 9.x patch or unrelated source-reader getters now.

An absent date alone is not a Problem. An encountered interpretation failure retains its actual Problem and source value; keep current supported legacy date grammar and the prohibition on impossible calendar rollover or guessed unknown timezones. Do not add a new general date parser or a current-clock plausibility filter. A verified signature covering a Date authenticates the covered value's integrity, not the accuracy of the sender's clock or actual transport time. RFC 5322 section 3.6.1 describes origination readiness, not receipt or transport timestamps.

`inspection.getSentDate()` uses the shared ValueInspection contract for the selected value and known derivation. Other timestamps preserve their actual kinds and source owners. Existing ordinary outgoing preparation may supply its current-date default when no date is fixed; that generated value is not received evidence, and exact relay retains its authoritative source bytes. Include an unsent MSG with only creation metadata, competing native/protected dates, missing/invalid/unknown-zone dates, and output-generated dates in the eventual acceptance cases.

## Agreed: threading follows the selected message identity

The maintainer accepted this logical-threading contract. Select In-Reply-To and References from the same authored header view as the logical message's Message-ID. A protected authored view supplies its own threading fields and meaningful omissions; a native authored view supplies the native fields when protection covers only body content. Do not union unrelated chains, import outer threading into a selected protected view merely because a field is absent, promote generated adapter identities, or borrow an independently attached message's ancestry. Keep all actually observed source alternatives available through inspection.

For a newly constructed single-parent reply, use the RFC 5322 section 3.6.4 logical derivation: the new reply has its own Message-ID; In-Reply-To names the selected parent's Message-ID when available; References starts with the selected parent's References and appends the parent's Message-ID when available. If the parent has no References but its selected In-Reply-To contains exactly one usable identifier, that identifier supplies the fallback ancestry before the parent ID. Do not turn multiple parents into an invented linear chain. With no usable source threading fields, omit the corresponding derived field rather than invent an ID or infer a conversation from Subject text.

Example: parent ID C with References A B produces reply ID D, In-Reply-To C, and References A B C. An outer chain X Y must not be joined to that selected protected ancestry. Parent absence and unavailable/unread fields remain distinct; retain actual interpretation failures and source evidence rather than create false completeness or verification claims. Preserve existing input-header representation where suitable and define the precise common inspection accessor inventory later; this agreement does not approve an additional public threading value model.

This specifies logical reply ancestry, not permission to disclose confidential identifiers in a new cleartext header. Final reply/forward/export rules must explicitly retain or deliberately resolve incoming confidentiality requirements before writing wire headers. Field-level coverage remains attached to the actual selected sources; a valid signature over an unrelated body does not verify native fallback threading. Outlook conversation/topic/index metadata remains native evidence and does not silently manufacture standard References/In-Reply-To.

Characterize both current reply overloads and the mail provider's actual header derivation against these cases before implementation. Reuse supported grammar/provider behavior where it satisfies the contract; do not smuggle an unrelated general parser or chain-repair policy into the source-model change. Any material difference, including multi-parent, malformed or partial data, must be visible in the approved execution/compatibility inventory.

## Agreed: transport data remains directly readable

The maintainer requires useful parsed transport/message data to remain directly readable on Email. Preserve actual received headers/facts and available interpretations, with supplementary source owners, observed order and repeated occurrences available through inspection. Do not flatten separate carrier/protected/attached histories into a fabricated single hop chain or treat reported transport/authentication claims as locally established facts. Source evidence can itself have cryptographic coverage without proving the reported events occurred.

This corrects the earlier proposal to remove the currently useful parsed return-path value and replace it with a received-transport read API inside inspection. That relocation is withdrawn. Keep best-effort parsed bounce/return-path access on Email, with the current getBounceToRecipient() interpretation as the baseline, and keep representable received header values directly accessible rather than requiring a source-tree walk or reparsing. Explicit null reverse path <> and malformed/uninterpretable originals retain their available header/source representations; do not invent a Recipient placeholder. If a future public contract distinguishes a received-return-path value from configured bounce intent by name, both useful data access and semantics must be presented explicitly at the Email level, not hidden in provenance. No renamed getter or additional transport model is approved here.

Preserving received values and choosing which values a newly created message reuses are distinct contracts. Any change to Return-Path-to-Bounce-To interpretation or use during copy/reply/forward/send remains an explicit design/compatibility decision; outgoing suitability alone does not justify withdrawing parsed input. Do not quietly ignore a normal getter's value during sending because of hidden provenance or clear copied values without an agreed operation contract. Existing explicit caller/governance Bounce-To settings retain their ownership. Reconcile incoming facts, ordinary represented values and outgoing envelope choices in the later copy/output design rather than inventing a second read model now.

The copy/header-reuse agreement below defines the semantic outgoing boundary for Received/Return-Path, Authentication-Results, DKIM/ARC signatures and related delivery history. Their direct availability for reading is separate from suitability for automatic emission. Complete the exact reserved/output-owned header inventory and explicit advanced composition cases in the implementation plan. Exact relay and original retained payloads preserve their actual bytes/history. Configured outgoing signing/envelope choices remain separate from received source evidence; do not introduce blanket source-data deletion or silently rewrite protected originals.

An Authentication-Results dkim=pass value is a reported upstream result, not evidence that SJM verified DKIM or established a trusted sender. No new DKIM/ARC verification/trust configuration is approved by this source-model proposal. RFC 5321 section 4.4 explains Return-Path/delivery ownership, RFC 8601 section 1.2 explains authentication-results trust boundaries, and RFC 9788 section 7 keeps transit-added fields distinct from authored protected headers. Reconcile the existing ADR0035 Return-Path retention/typed-value rules through focused, dated feature upkeep after agreement; preserve the old record identity and historical decision.

## Agreed: getHeaders as a parsed header view

The maintainer accepted this header-read contract. Make normal Email-level header access useful for reading the parsed message, including received transport and unfamiliar header values, rather than limiting it by suitability for a new send. `getHeaders()` is a case-insensitive read view of the header data represented by this logical Email, using the already agreed authored-field and transport-source ownership rules. It is not an exact byte dump or a fabricated union of every MIME/source layer. Preserve best-effort available values and actual per-name value order/occurrences; exact original spelling/folding/interleaving and displaced alternatives remain source evidence.

Use one consistent value authority for known fields. The header view and typed getters must agree on the chosen Subject/From/Message-ID/date/address/threading data when a typed interpretation is available. Builder edits to a typed field must not leave a stale conflicting active header value. Where a field cannot be typed but a useful source header value is available, retain that readable value and the actual interpretation limitation instead of hiding input. Design acceptance does not authorize implementation or an incidental second mutable value model before the execution packet is approved.

For ordinary additional/custom names, use the selected authored view's complete value list when present, comparing names case-insensitively. Retain useful nonconflicting carrier/native data with its actual source status, following the field's role; do not append a competing outer value list to a selected protected one or present it as protected. Transport histories preserve their own carrier/source context rather than becoming part of a false chronological chain. Attachment/body-part headers and independent child-message headers remain with those components, not the parent's logical header view.

Accepted examples: `email.getHeaders().get("Received")` can inspect that represented message's received trace; `get("X-Ticket-ID")` returns the selected ticket header values; a protected `X-Ticket-ID: 42` wins over an outer `X-Ticket-ID: 99` without yielding an invented list `[99, 42]`. Original alternatives and their source/protection remain inspectable. Supported raw fallback and exact map/list shape, reserved structural MIME fields, null values, repeated headers and typed/generic setter interaction require explicit inventory/acceptance cases before implementation.

Read availability does not settle automatic writing. MIME preparation/new-message/copy/reply/forward rules must deliberately define which represented headers are emitted, regenerated or omitted and how explicit caller choices work. Do not implement the new read contract by blindly writing the entire parsed header view into outgoing MIME, or by using hidden provenance as activation policy. Generated MIME structure/encoding fields must not be overwritten by stale source structure. Original raw relay and retained payload ownership keep their separate contracts. No getHeaders return-type change, new getter or outgoing-header filtering contract is approved solely by this read agreement.

## Agreed: copying data versus constructing outgoing MIME

The maintainer accepted these copy/header-reuse operation boundaries. Ordinary copying preserves the Email's represented values, including parsed bounce/return-path data and received headers, together with its immutable source/protection history under the copy contract. Do not clear or reinterpret a copied field solely because provenance says it was imported or previously completed. Copy is not a promise of original MIME bytes, continuing signature validity or automatically new message identity.

Normal send/reconstructed export constructs MIME from the represented message under a documented semantic header-ownership policy: typed fields are emitted once from their current values; current MIME structure/encoding fields are generated by the producer; appropriate additional headers are emitted; received trace/authentication history and obsolete signatures are not automatically emitted as current delivery/verification claims. Accepted examples include Received, Return-Path, Authentication-Results and old DKIM/ARC headers. Rules must be explicit by header role/operation rather than a hidden parsed-versus-composed provenance switch. Define how explicit advanced header choices interact with reserved/output-owned fields in the final inventory; do not silently weaken selected caller settings.

The represented Bounce-To value remains honored for the SMTP envelope during a normal send, whether supplied by parsing, copying or configuration. Filtering a Return-Path header field must not quietly suppress that ordinary typed value. Existing governance, locks and explicitly selected envelope/delivery overrides still apply. This deliberately differs from the withdrawn proposal to remove the interpreted return path or ignore it during sending.

Replying and forwarding construct a new outer message, not a clone of every received header/transport choice. Derive the selected reply destinations, subject and logical threading under their agreed rules; leave the new sender/Bounce-To to caller/governance rather than importing them from the original delivery. New message identity/date follow their outgoing defaults. Arbitrary old custom/transport/authentication headers are not blanket-copied to a new outer message. A forwarded retained original message/file keeps its original payload internally, under the earlier forwarding agreement; passing an interpreted Email view still does not promise original source bytes.

Exact relay keeps authoritative MIME bytes and its independently chosen SMTP envelope, retaining existing bypass/validation boundaries. It must not be filtered or regenerated merely because the ordinary parsed header view is richer. Original retained attachment payloads likewise do not acquire new byte rewriting through a header-emission policy.

This agreement covers representation/copy/header-reuse rules only. The reply- and forwarding-helper confidentiality rules are agreed separately below; partial component failures and the complete output acceptance table still require explicit decisions. No new configuration flags, broad copy resets or additional public constructor/source types are approved solely by the copy/header-reuse agreement. Characterize existing provider/producer behavior and every affected supported surface in the eventual API/review packet before implementation.

## Agreed: confidentiality when helpers prepare replies

On 10 November 2026 the maintainer accepted the middle ground: an ordinary unencrypted reply containing the caller's new text is allowed, but automatically including decrypted confidential content requires suitable outgoing protection by default. The application may deliberately permit that disclosure through an explicit choice on the reply operation. Do not add reply-only sending-mode methods to the generic Email API. ReplyOptions.allowUnencryptedContent() was an illustration of placement, not an approved type, signature or final name.

Keep the boundary at the convenience helper that automatically includes the content. The resulting draft remains readable/editable. Before transmission, resolve the actual outgoing protection settings and require protection covering the confidential material, or an explicit caller decision to omit/replace it or permit cleartext disclosure. Missing outgoing credentials must not silently turn that generated confidential content into an unencrypted send. Removing a quotation does not resolve another confidential value still reused in the reply, such as a private Subject. Exact lifecycle and editing/copying behavior require acceptance cases before implementation.

Message inspection and literal copying remain available and do not silently redact/change represented values. This agreement does not impose a new confidentiality guard on all parsed/copied/manually composed Email values. Incoming encryption/signature observations are not outgoing credentials or configuration: use the application's outgoing protection configuration and do not guess keys, recipients, trust or algorithms. Signature-only input does not by itself establish a confidentiality requirement.

Protect the actual field: normal S/MIME/OpenPGP MIME-body encryption does not alone hide outer Subject/References/other header values. A confidential generated subject needs supported header protection or a deliberately public replacement; body-only encryption is not sufficient evidence of header confidentiality. Distinguish composer-declared confidentiality, public counterparts and unknown facts; an encrypted container does not automatically make every observed header private. RFC 9788 sections 4.1/4.3/6 discuss this distinction and avoiding confidential-header leakage; RFC 9787 sections 5.4/6.2 discuss quoted decrypted content.

The comparison informing this choice distinguishes library and mail-client responsibilities. MailKit's [reply example](https://github.com/jstedfast/MailKit/blob/master/FAQ.md#q-how-can-i-reply-to-a-message) constructs a normal reply, while [MimeKit encryption](https://mimekit.net/docs/html/Working-With-SMime.htm) is an explicit application step; that example does not specifically establish a policy for encrypted input. Microsoft documents [inherited encryption in new Outlook](https://learn.microsoft.com/en-us/powershell/module/exchangepowershell/set-smimeconfig?view=exchange-ps#-nosignonreply) for replies to encrypted-and-signed S/MIME messages. [RFC 9787 section 5.4](https://www.rfc-editor.org/rfc/rfc9787.html#section-5.4) recommends encrypted replies or omitting decrypted quotations unless disclosure is deliberate. Its mail-client guidance informs this helper contract without making SJM a complete mail client.

The operation-specific permission and safe private-subject default are agreed below. Complete their exact API inventory, defaults-resolution/copy lifecycle and other confidential-header output cases in the implementation packet. The remaining behavioral proposal concerns non-sending artifact output. Preserve accepted best-effort artifact recovery; no complete RFC 9788 outbound-generation feature is authorized by these agreements.

## Agreed: confidentiality when forwarding

On 10 November 2026 the maintainer accepted the same confidentiality rule for content automatically included by the forwarding helper. This adds the outgoing-protection contract to the previously settled attachment/payload ownership rules; it does not reopen or replace them.

Forwarding a retained encrypted original preserves that payload's existing protection. The outer message may be unencrypted provided its Subject, body and other derived values do not repeat confidential material. Merely forwarding an intact protected attachment does not require encryption of the entire outer message. Its existing encryption also does not automatically grant a new recipient access.

Passing a parsed/decrypted Email to forwarding(Email) deliberately reconstructs its interpreted view. When the helper includes confidential content in that reconstruction, require suitable outgoing protection or an explicit caller decision to omit/replace it or permit cleartext disclosure. The deliberate override belongs to the forwarding operation, without forwarding-only sending controls on generic Email. Apply the actual-field coverage rule, including confidential outer headers; normal body encryption does not automatically protect a private Subject.

Do not silently substitute an old retained source payload for a requested interpreted forward, discard caller edits or guess outgoing credentials from incoming processing. The exact override signature, lifecycle, copy/edit handling and output acceptance cases still belong in the eventual approved API/review packet. This design agreement does not authorize starting implementation.

### Agreed: operation-specific permission and removing reused content

On 10 November 2026 the maintainer accepted the concrete permission/clear-body examples and reaffirmed that the underlying permission principle was already settled. Treat this as closed rather than reopening the principle. Use immutable ReplyOptions and ForwardOptions on the helper operations for deliberate permission to include confidential content without encryption. Accepted usage is replyingTo(received, ReplyOptions.allowUnencryptedContent()) and forwarding(received, ForwardOptions.allowUnencryptedContent()); the equivalent reply-all path uses the reply options. Keep the ordinary no-options calls as the safe default and add no generic Email permission setter or sending-mode getter.

ReplyOptions should accommodate the existing custom quoting choice so permission and quote settings do not create a Cartesian product of public overloads. Complete the exact source/options/default overload and released-to-10.0.0 migration inventory in the packet. The opt-out only waives the helper-derived incoming-content confidentiality requirement. Explicit/mandatory outgoing encryption remains binding; this permission does not disable configured encryption or authorize a downgrade when it fails.

Preserve the draft's helper-derived requirement and any deliberate permission through ordinary copying and supported snapshots. Editing text, changing recipients or resetting inspection claims does not automatically grant permission to disclose reused content. A replacement setter can make current incoming protection UNKNOWN under the accepted inspection contract while the separate outgoing requirement remains. In particular, changing plain text must not ignore a still-present HTML quote, and rewriting HTML must not be presumed to remove every fragment of the confidential original.

Explicitly clearing a generated body/resource removes that component's contribution to automatic confidential reuse. When every automatically reused confidential component is removed, that helper-derived requirement has nothing left to protect; a remaining private header or other component still counts. Distinguish explicit removal from replacing a string whose content may retain the quote. Examples use the existing clearPlainText(), clearHTMLText() and clearEmailToForward() operations as applicable. Do not scan arbitrary caller-written strings or promise general confidential-data/taint tracking.

A new reply/forward operation uses its own default/options and actual reused-content evidence; it does not inherit an unrelated prior operation's cleartext permission merely because its input was an earlier draft. This differs from copying the same draft, which preserves its choice. Exact component/clear/add/replace, copy/serialization and defaults-resolution cases need the final acceptance table. The private-subject default is agreed below; export enforcement remains the last behavioral proposal in this contract.

### Agreed: a safe public subject for a reply or forward

The maintainer accepted this default on 10 November 2026. This concerns the new message's outer Subject, not the already agreed readable received Subject. When the selected received subject is established as confidential, use a positively known public subject for the same received message if available; otherwise use a neutral generated subject: "Re: Your message" for a reply and "Fwd: Message" for a forward. Ordinary public subjects keep the normal Re:/Fwd: behavior.

Concrete example: the received logical Subject is "Acquisition of Acme", while its known public outer Subject is "Encrypted message". received.getSubject() continues returning the former, but the new reply defaults to "Re: Encrypted message". The caller can supply another subject through the existing withSubject(...) method. Permission to reuse body content without encryption does not itself change this safe subject-selection default.

Do not identify a subject as public merely because a reader exposes an unencrypted native/cache property or a generated adapter header; require actual source evidence for the public counterpart. Preserve original private values in the read model/source history. Ordinary MIME-body encryption does not protect the new outer Subject, and this proposal does not add full outbound RFC 9788 header protection. Reconcile other automatically derived private-header cases by actual role in the final output inventory, without substituting unrelated carrier identities or silently claiming protection.

### Agreed: helper confidentiality on final MIME/EML output and scoped permission

The maintainer accepted the final-output boundary and the revised conversion-scoped permission on 10 November 2026. Apply the helper-derived confidentiality requirement when SJM produces a final outgoing MIME/EML artifact as well as when sending. The boundary is release of the completed outgoing message, after the applicable outgoing settings/governance have resolved. Merely building/copying/editing an Email draft or reading its ordinary/inspection getters remains available. This is not a new blanket gate on all parsed/copied/manually composed Email values.

```java
byte[] eml = mail.converter()
    .fromEmail(reply)
    .toEmlBytes();
```

If reply includes automatically reused confidential content and has neither suitable outgoing protection nor permission applicable to this output, refuse this output as ordinary sending would. An exported artifact can be handed to another sender after Java-only metadata has been lost; it should not silently bypass the agreed safeguard. The accepted ReplyOptions/ForwardOptions permission is a choice for the resulting draft and applies to its outputs; it must not be the only way to permit an individual conversion.

After challenging the missing export-side permission, the maintainer agreed to allowUnencryptedContent() on the existing EmailConversion builder, applying to that conversion's MIME/EML terminals without changing the source Email, its helper-derived requirement or its draft permission. This lets an application hand unencrypted output to another component for subsequent encryption without granting permission for a later unencrypted send of the same draft.

```java
byte[] eml = mail.converter()
    .fromEmail(reply)
    .allowUnencryptedContent()
    .toEmlBytes();
```

The local permission belongs to the conversion object, not a consumed one-shot token; a fresh fromEmail(reply) has no such permission unless the source draft itself already allowed it. Putting the choice on the existing conversion builder avoids separate permission overloads for every output terminal. Complete its clear/reset/inspection and state-copy contract in the public inventory. Both draft permission and conversion permission only waive the helper-derived confidentiality requirement, preserving explicitly configured/mandatory outgoing encryption and existing configuration-failure behavior. Responsibility for encryption performed after the artifact leaves SJM remains with the application.

The corresponding diagnostic artifact terminal should report the recognized refusal without a completed unprotected output value, retaining useful diagnostics/source evidence under its existing result contract. Do not present a partial buffer or silently substitute original payload/lose edits as successful requested output. Preserve unrelated best-effort conversion/recovery and existing invalid-configuration/programming/cancellation boundaries. The original Email and its historical Problems remain unchanged.

Internal reconstruction while assembling a new reply/forward is not a prematurely finalized output boundary. Carry the derived requirement into the new outer message and assess actual final protection, including suitable enclosing encryption, rather than require inner encryption before the outer message exists. Standalone conversion uses its explicitly supplied Email/conversion governance rather than assuming an unrelated Mailer's defaults; sending uses its resolved Mailer governance. Map actual send, standalone artifact, CustomMailer preparation and nested-forward routes in the final packet.

## Summit progress companion: assessed impact and required upkeep

The maintainer requested an impact assessment on 10 November 2026 because this has become a further proper overhaul, then clarified that the important assessment is its contribution toward the ambition of the best SMTP library/framework overall, rather than only its documentation/upkeep footprint. The relevant companion is [Simple Java Mail 10.0.0 competitive progress report](../research/simple-java-mail-world-class-smtp-progress-report.md), whose introduction explicitly pairs it with the historical comparison PDF. The report already credits the implemented #961 source/content/result foundation and separately discusses application-integration teaching. The new design is a substantial further API/semantic and security-usage stage, not a reason to erase that implemented baseline or claim the new contracts are already delivered.

Competitive assessment: this is a meaningful but moderate overall step toward that ambition, with a much larger direct gain for applications reading, reusing or forwarding protected mail. The engineering scope is large; it does not follow that the competitive leap is equally large. It mainly deepens SJM's existing message-handling/convenience strengths and removes inconsistent security semantics, rather than closing the largest SMTP protocol/transport gaps. Ordinary SMTP-only sending gains less directly.

The strategically useful contribution is a consistent usable Email model across protected input, explicit evidence about exactly what a protection claim covers, small pure requirements checks that reduce caller interpretation code, and deliberate original-versus-interpreted reuse. This strengthens the case for the best integrated client experience while leaving completeness/efficiency of the SMTP engine a separate comparison axis. The use case, tactical recipe and runnable application demonstrate that gain; they are not separate cryptographic capabilities to count again.

Existing competitors already provide substantial underlying content protection. A scoped primary-source check on 10 November confirmed that [MimeKit's S/MIME guide](https://mimekit.net/docs/html/Working-With-SMime.htm) documents decryption, signed-content extraction and individual signature verification. The planned gain is therefore how SJM packages and coordinates evidence/intent for application code, not the existence of signing/decryption as a newly unique feature. This is a qualitative design assessment, not proof that no competitor has equivalent conveniences or a fresh whole-market ranking.

Upkeep assessment against the actual current companion:

| Companion area | Impact and proposed upkeep |
| --- | --- |
| Dated refresh/status and implemented-progress summary | Add a scoped design-agreed/pending-implementation update for this follow-on. Keep the implemented #961 foundation separate from planned field inspection, normalized protected input, pure requirements checks and explicit interpreted/original forwarding. Do not count this design twice as completed features or broadly refresh unrelated feature/issue status. |
| Source inspection and recoverable conversion | Explain the next stage beyond generic source enumeration/flat family summaries: ordinary usable values after decryption, field-specific origin/protection, domain-level checks, current-value versus historical input after edits, and expanded automatic raw-original retention. Its current selective-retention description remains the implemented baseline until the accepted replacement is implemented/reviewed. |
| Message integrity and API coherence | After delivery, assess the contribution of explicit original-versus-interpreted forwarding and confidential reply/forward requirements to trustworthy content reuse. Treat this as usability/semantic safety around existing cryptographic support, without claiming new algorithms, a full PKI trust engine or security coverage beyond the selected fields. |
| Application-integration teaching | Record the planned receiving use case, tactical recipe and expanded protected-partners example. Credit working application guidance only after the article, canonical runnable code, failure/uncertainty cases and downloads agree. Business acceptance and partner approval stay application-owned. |
| Remaining limits, memory and evidence | Account for retained raw originals alongside interpreted/decoded content, shared-storage lifetime, incomplete capture, copy/edit/snapshot behavior and migration/OpenRewrite work. Earlier outbound-soak results are not evidence for this retention model. Keep whole-Email optional-module portability distinct; this design does not by itself complete that separate work. |

The report should credit this as consolidation and improvement of an existing competitive strength, with the size of the real gain assessed after the caller workflow and retention costs are demonstrated. It is not SMTP protocol progress: PIPELINING/CHUNKING/BINARYMIME, throughput comparisons and detailed transport measurements retain their existing independent scope. Parsing supplied incoming messages adds no POP3/IMAP retrieval capability. Preserve the original PDF and recorded competitor baselines; do not improve a competitive ranking or claim unique superiority without fresh primary-source comparison and actual implementation evidence.

Concrete proposed companion note for the design stage: "The further received-message overhaul agreed in design on 10 November 2026 would strengthen SJM's integrated message-handling experience: protected mail remains usable through the ordinary Email model, protection claims have explicit scope, and reusable requirements checks and deliberate original/interpreted forwarding reduce application-side interpretation. This is a meaningful refinement of an existing strength, especially for protected-mail automation, rather than a new lead in cryptographic capability or SMTP protocol breadth. Implementation and validation, including the cost of retained originals, remain pending."

Add the companion to the eventual implementation/review/upkeep packet: introduce a dated planned-scope note at the appropriate design checkpoint, then update implemented progress and its qualitative assessment only after the corresponding code, migration and documentation work is accepted. Keep implementation checklists here rather than turning the progress report into another execution plan. The companion already has unrelated uncommitted integration/source-inspection edits; this assessment has not rewritten them. Its existing report/competitor dates and other tracked work need their own scope before refresh.

## Design complete: implementation/review packet

The caller-facing behavior decisions are now agreed, including operation-specific permission/clear-body behavior, the safe private-subject default, final MIME/EML enforcement and conversion-scoped permission. Keep the initial protection-check API scoped to one selected value and the three accepted mail-specific requirements; grouped/correlation/assertion machinery is excluded. Proceed to the consolidated implementation/review packet. Preserve passive readable drafts, best-effort incoming inspection, existing artifact recovery and explicit outgoing configuration requirements. Do not reopen the accepted behavior merely to discuss each supporting getter/type; bring back only a genuine new public behavior or a conflict discovered while making the packet concrete.

The [execution packet](received-email-overhaul-implementation-20261110.md) consolidates the implementation responsibilities instead of reopening a discussion for each getter/type. Its pre-code checkpoint completes the full public API/minimal evidence member inventory, reason codes, origin/derivation and conservative partial/multiple-layer coverage rules, OpenPGP approved signing-key normalization, reserved/output-owned headers and unchanged/edited/copied values. Keep unknown/uninspected facts honest. Bring a genuinely new public behavior or conflict back to the maintainer rather than broadening the accepted contract silently.

The packet must also map actual conversion, reply/forward, copy/completion, resource ownership, import-budget/retained-original and supported serialization routes, including old snapshots with uncaptured history. Carry settled body/HTML/resource/calendar/header/source rules into representative acceptance cases. Remove superseded flat/legacy incoming-detail accessors with the agreed clean 10.0.0 migration and required OpenRewrite treatment; do not invent released 9.x signatures for target-only APIs.

Then provide the minimum internal-model design, owned source/surface map, meaningful test and combined-review sequence, tracking/upkeep scope and full handoff instructions. Include website security workflow, receiving use case, tactical recipe, runnable protected-partners expansion/downloads and the assessed Summit companion contribution. Preserve ADR paths/history under the canceled restructuring directive and use focused architectural upkeep only. Design completion and preparation of this packet do not authorize runtime implementation/delegation or delivery before their existing separate approvals.

Evidence for current behavior: [legacy merged-view ownership](../adr/0040-retained-protected-content-merging.md), [source/problem ownership](../adr/0041-email-source-inspection-and-problems.md), [ordinary versus exact input](../adr/0013-exact-eml-submission.md) and the current `ReadSmimeAttachmentsTest` fixture. These describe the baseline; they must not be mistaken for implementation of the agreed future attachment contract.
