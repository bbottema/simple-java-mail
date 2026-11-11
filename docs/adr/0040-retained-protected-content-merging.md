# ADR 0040: Define the merged view of retained protected-message content

- Status: Historical retained-content decision; logical selection amendment accepted 10 November 2026
- Decision date: 2026-11-03
- Implementation status: Calendar projection implemented for unreleased 10.0.0 under [#960](https://github.com/bbottema/simple-java-mail/issues/960)
- Proposal date: 2026-11-03
- Target: 10.0.0
- Scope: An outer Email and a retained inner Email that the importer treats as the same logical message
- Related decisions: [0008](0008-minimal-mime-structures-and-protection-order.md), [0035](0035-tolerant-reading-and-strict-production.md), [0036](0036-structural-message-snapshots-and-borrowed-content.md), [0037](0037-repeatable-email-completion.md), [0002](0002-email-defaults-and-overrides.md)

This record consolidates the established retained-content design for 10.0.0 after reconstructing its history. It carries forward the existing view, ownership and copy contracts and the earlier agreed calendar fallback. It does not introduce a general recursive merge, change ordinary copy/import ID policy or claim support for every protected-header representation. Conformance is checked separately from accepting the contract.

## One logical readable Email (accepted 10 November 2026)

The received-message continuation of [#961](https://github.com/bbottema/simple-java-mail/issues/961) replaces the legacy two-Email
merge below with deliberate selection of one logical message and its available representations. The earlier sections remain decision
history; their sole-attachment heuristic, public merge controls, retained-inner Email access and flattened decrypted list are superseded.
This amendment was approved for implementation and delivery for unreleased 10.0.0; the issue records delivery status.

Ordinary getters expose usable selected content after supported incoming decryption. The grammar/source relationship identifies this
message's body envelope, ordinary file, independent attached message and related inline resource. A filename, sole attachment or generated
EML adapter does not establish a body envelope. An independent protected file stays one attachment; a recognized attached message can
expose its already materialized child through `AttachmentResource.getEmail()`. Child fields/resources remain child-owned. Passive getters
do not parse, decrypt, verify or read external content.

Prefer each present protected body alternative, including an empty String; use a materialized native alternative when that protected
alternative is absent or unavailable. Select calendar text and method together. Select HTML with its associated resource occurrences,
using only unambiguous same-message fallback. Unused materialized inline images become ordinary attachments; independent children do
not supply parent images. Each selected value/resource keeps its own source, derivation and established protection facts.

A present readable protected subject wins, including an explicitly empty value; an absent/unavailable protected subject retains an
available earlier/native subject with that candidate's own source/protection. A truly absent/unavailable subject stays null rather than
acquiring the old parsed-empty sentinel. A recognized real protected authored-header view supplies address roles, Message-ID, stated sent
date and logical threading. Meaningful omission in that authored identity/address view stays omitted; do not union outer recipients or
splice in outer identity/ancestry. A protected body-only entity does not replace the native authored headers. A protected subject alone
does not establish an entire authored-address view.
Generated adapter headers and independently attached messages never supply parent authored identity. MSG sent-date interpretation uses
an actual client-submit value or usable Date header, not creation/modification time or an unqualified reader-date fallback.

`Email.getInspection()` explains current selected values and incoming family processing; `getSourceData()` remains captured history.
Inspection contains no duplicate content model, public evidence builder or whole-Email trust verdict. Full signature verification,
coverage and explicitly approved actual signing-key identity must belong to the same observation to satisfy a signer requirement.
Known partial coverage cannot qualify; unavailable evidence is not invalidity or proof of absence. Independent child success does not
become parent success. [ADR 0041](0041-email-source-inspection-and-problems.md#complete-original-input-and-selected-value-inspection-accepted-10-november-2026)
owns complete-original retention and Problems; [ADR 0031](0031-public-api-and-internal-construction.md#received-value-inspection-and-application-requirements-accepted-10-november-2026)
owns the small application checking API.

Copy the current represented view without reapplying selection. Unchanged fields retain associations; an explicit setter, even with an
equal value, makes that field caller-supplied with unknown incoming protection. Occurrences are not matched by names, equality or payload
reads. Historical originals survive edits. New helper-derived confidentiality requirements are separate from incoming facts, so resetting
inspection claims cannot permit disclosure; [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#selected-values-originals-and-helper-requirements-accepted-10-november-2026)
owns that copy/snapshot lifecycle.

Completion retains the submitted draft's historical source/Problems/originals and unchanged selected associations; defaults/overrides
replacements have caller-supplied/unknown incoming facts. Templates cannot donate received evidence or disclosure consent. This supersedes
the earlier legacy completion-history reset below; [ADR 0037's dated amendment](0037-repeatable-email-completion.md#preserve-helper-requirements-without-inheriting-consent-accepted-10-november-2026)
owns this refinement without changing ordinary collection/suppression/lock resolution.

Interpreted forwarding renders current represented content and selected usable payloads. `forwardingOriginal(Email)` carries a complete
retained original in its actual EML/MSG format and fails clearly if one is unavailable. Neither operation is exact SMTP relay. Reply/forward
helpers that automatically reuse confidential content require suitable final outgoing protection or explicit operation consent. A private
received subject stays readable, while the new outer subject uses a positively known public counterpart or neutral wording. Body encryption
does not protect every outer header. [ADR 0008](0008-minimal-mime-structures-and-protection-order.md#confidential-content-at-final-output-accepted-10-november-2026)
owns final output ordering and the narrower conversion-local permission.

An extracted child's original may be plaintext because the enclosing parent's encryption is absent from that file. Original forwarding
preserves its actual bytes/format while retaining the helper's confidentiality requirement for such exposed input. Child body encryption
does not protect ancestor-private outer headers; an intact root encrypted original and signing-only input have different coverage. The
existing conversion-local permission permits a deliberate individual export without a new original-forward overload or generic Email
mode. Safe generated carrier filename/description/Content-ID do not rewrite the original getter's historical metadata.

## Context

Some incoming S/MIME paths retain both an outer Email and an extracted inner Email. The default merged view makes readable content available on the outer Email while keeping the inner view for inspection. Other protection paths parse an effective clear MIME root directly. These are different construction paths and must not acquire competing content rules accidentally.

The merge is selective. It is not a recursive union of all Email fields, ordinary Email defaults/overrides completion, or a new cryptographic operation. A field needs an explicit owner: outer message metadata, selected readable content, resource collections, retained inspection history, or outgoing application policy. Adding an Email field requires deciding its merge ownership rather than relying on omission from a constructor.

Earlier fixes settled important parts of this contract, but their decisions are distributed across protection, snapshot and configuration records. A calendar-only change would leave other content fields and the copy transition without a single policy to check.

### Why the merge was introduced

The original [Outlook parser report](https://github.com/bbottema/outlook-message-parser/issues/4), opened in November 2018, described a MSG with message metadata and a single `smime.p7m` attachment, but no readable body. The content and ordinary attachments were inside that protected part. Returning an empty body plus the protected file did not give callers a convenient readable Email.

On [12 April 2019](https://github.com/bbottema/outlook-message-parser/issues/4#issuecomment-482714207), the maintainer sketched combining outer From/To/Subject with body and attachments extracted from the protected content. The [21 April implementation](https://github.com/bbottema/simple-java-mail/commit/ba2af3ce899f97cc18a750a1d7dd917adc7bea18) followed that division and added a separate-view option. The merge supplied different pieces of one logical message; it was not intended to choose one whole Email object over another.

A protected MIME entity need not contain a complete message-header block. Replacing the outer Email wholesale could therefore lose its sender, recipients or subject. Conversely, the outer MSG can contain its own body representation; selecting the protected part's readable content makes that source choice deliberate. A missing inner view and an inner view missing one body alternative are different situations.

S/MIME processing was placed in Simple Java Mail because it applies to MIME/EML as well as MSG and needs optional Mail/cryptographic dependencies, rather than belonging in the Outlook parser. The original example was signed content, readable without a decryption key; the discussion explicitly corrected the initial encrypted-versus-signed confusion. The retained-content mechanism consequently covers signed as well as decrypted content.

Calendar parsing and the Email calendar fields already existed in that implementation, but its merge selected only inner plain text and HTML, retaining outer calendar fields. No historical decision deliberately excluding calendar from readable-content promotion has been located. This is an observed difference in field selection, not proof of a settled calendar policy.

## Historical decisions carried forward

| Source | Constraint retained by this record |
| --- | --- |
| [S/MIME import, #187](https://github.com/bbottema/simple-java-mail/issues/187), implemented in [2019](https://github.com/bbottema/simple-java-mail/commit/ba2af3ce899f97cc18a750a1d7dd917adc7bea18) | A supported extracted message can have a default merged view and an explicit separate-view option. The original implementation establishes this feature, not a complete field-by-field precedence decision. |
| [Legacy opaque signing, #252](https://github.com/bbottema/simple-java-mail/issues/252#issuecomment-663378734) | A signed message can itself be an opaque `pkcs7-mime` entity. Readable-content extraction must account for supported signing representations, not equate an opaque part with encryption. |
| [Optional-module independence, #493](https://github.com/bbottema/simple-java-mail/issues/493) | Copying a message or handling a header collection must not require the optional S/MIME module merely because merge-related helpers exist. |
| [Invalid signatures, #571](https://github.com/bbottema/simple-java-mail/issues/571#issuecomment-5197432328), and [unsupported liberated content, #572](https://github.com/bbottema/simple-java-mail/issues/572#issuecomment-4881242129) | Keep extractable readable content despite an invalid signature, with honest outcomes. If supported readable-message materialization is unavailable, retain the protected attachment and outer view instead of promoting null or discarding the input. |
| [Copying follow-up, #786](https://github.com/bbottema/simple-java-mail/issues/786#issuecomment-6556573226), ADR 0036 | A copy starts with its visible values. Later replacements, clears, additions and encoding edits lead. Repeated copying must not reapply retained content or multiply resource occurrences. Explicit merge reset on a separate copy incorporates the inner view once before later edits. This later decision supersedes older copy reconstruction bookkeeping. |
| [Header resolution, #867](https://github.com/bbottema/simple-java-mail/issues/867), ADRs 0036/0039 | Header names compare case-insensitively. An inner custom header replaces the complete outer value list; unrelated headers remain. Preserve the established display spelling and legacy flat-map interpretation without inventing lost provenance. |
| [Retained detached signatures, #918](https://github.com/bbottema/simple-java-mail/issues/918#issuecomment-6499381434), ADRs 0008/0036 | Keep the signed MIME container and signature material in retained decrypted EML and the inner inspection view, independently of merging. Generated MSG-derived EML is not an exact original EML source. |
| [Opaque signature facts, #905](https://github.com/bbottema/simple-java-mail/issues/905#issuecomment-6491750378), ADR 0008 | Changing the merged/separate presentation must not change established signature/decryption outcomes. Independent protected files do not supply their plain parent's signature result. |
| [Unavailable inspection, #822](https://github.com/bbottema/simple-java-mail/issues/822), and [Outlook decryption facts, #704](https://github.com/bbottema/simple-java-mail/issues/704#issuecomment-6567991286), ADR 0008 | Readable presentation does not establish verification or decryption. Missing support and unopened content keep conservative facts. Successful decryption need not imply that the legacy materializer can expose a clear message. Do not add recursive decryption or single-part materialization through this merge contract. |
| [Empty imported bodies, #935](https://github.com/bbottema/simple-java-mail/issues/935), ADR 0035 | An empty String is a present body alternative; null means absent. Merge selection must preserve that distinction. |
| [Calendar pairing, #852](https://github.com/bbottema/simple-java-mail/issues/852), ADR 0002 | Calendar method and text must come from one selected calendar. This does not make content-transfer encoding part of template calendar resolution; template encoding and lock rules keep their existing owner. |
| [Effective roots, #954](https://github.com/bbottema/simple-java-mail/issues/954), and [parameterized protected attachments, #927](https://github.com/bbottema/simple-java-mail/issues/927), ADRs 0008/0035 | Keep effective-message content separate from explicitly attached child entities. Recognize supported media types without discarding their parameters, source bytes or ownership. |
| [Explicit attached containers, #849](https://github.com/bbottema/simple-java-mail/issues/849), and [repeated unreferenced CIDs, #891](https://github.com/bbottema/simple-java-mail/issues/891), ADR 0035 | Ordinary attached child content must not leak into the parent body. Repeated source identifiers must not erase resource occurrences. HTML-referenced duplicate-CID selection remains a separately deferred policy. |

## Decision

[ADR 0041](0041-email-source-inspection-and-problems.md) supplies enumerable source evidence, selectively retained originals and processing
observations. Source entries remain owned by their received/extracted/generated views. Qualified inner findings enter the represented root
once while preserving their immutable origins; copying or resetting a materialized view does not reaggregate them. Retained source content
need not have a Problem, and captured-source getters do not repeat parsing, reads or protection work.

Keep the existing caller-facing merged/separate views. Make their field ownership explicit and verify both initial import construction and explicit copied-view promotion against the same rules. Preserve the compatibility choices below instead of broadening the redesign during a field-completeness repair.

### Eligibility and separate views

Apply promotion only when the existing importer has supplied a retained inner message as the content view of the outer message. The legacy attachment path uses the single-S/MIME-attachment/readable-message heuristic; that heuristic is an assumption about presentation, not cryptographic proof of ownership. Do not extend it to arbitrary attachments or automatically merge every decrypted resource. A stronger envelope-classification policy would require its own decision.

`notMergingSingleSMIMESignedAttachment()` keeps the outer and inner views separate during import. It does not prevent supported inspection/decryption or remove retained artifacts. Unsupported materialization, missing keys/modules and actual content failures keep their existing boundaries.

The [independent-attachment clarification accepted on 5 November in ADR 0008](0008-minimal-mime-structures-and-protection-order.md#independent-attachments-after-detached-signature-unwrapping-accepted-5-november-2026)
keeps processing separate from promotion after a detached signature is opened. Scan the clear parent's independent attachments while retaining
the parent's signature resources and facts. Do not use the sole-attachment heuristic to replace that already opened parent's body, create an
inner content view or combine an unrelated child's encryption with the parent mode. The existing merged/separate selection does not suppress
this supported child processing. [#704](https://github.com/bbottema/simple-java-mail/issues/704) tracks this unreleased correction.

The [7 November OpenPGP clarification in ADR 0008](0008-minimal-mime-structures-and-protection-order.md#independent-smime-files-after-opening-openpgp-mail-accepted-7-november-2026)
preserves the same separation for independent S/MIME files after OpenPGP exposes readable content. Their processing does not supply
a retained parent content view or enable sole-file promotion, and it does not open a protected entity at the effective message root.

If no readable inner Email was produced, enabling merging does not synthesize one or erase the outer body. That fallback is distinct from choosing between body alternatives of an available inner Email.

### Field ownership

| Field family | Owner/rule |
| --- | --- |
| Plain text, HTML and their specific encodings | When an inner view is merged, select the inner values including absence. An empty String remains present. An absent inner specific encoding clears the outer specific choice; normal later encoding fallback remains separate. |
| Calendar text and method | Select a present inner calendar as one value. If the inner calendar is absent, retain the outer calendar. Never combine a method from one message with text from another. This explicitly retains the earlier agreed calendar fallback. |
| Calendar-specific encoding | When the inner calendar is selected, take its specific encoding including an omitted choice; otherwise retain the outer calendar-specific encoding. Ordinary later encoding resolution remains separate. |
| Generic content encoding | Preserve its existing outer/application-policy role; do not silently copy all inner configuration together with the body. |
| Custom headers | Inner complete value lists replace matching outer names case-insensitively; keep unrelated outer headers, display spelling, value order, duplicates and detached collection ownership. |
| Attachments, embedded images and decrypted-resource inspection lists | Retain the existing outer-then-inner occurrence order and borrowed content identities. Do not deduplicate by filename, equality or content reads. Materialize an occurrence only once across copying/reset transitions. |
| Typed message/header metadata: ID, subject, dates, From, visible recipients, Reply-To, bounce and receipt destinations | Preserve the outer view's established ownership. Custom-header merging is not permission to overwrite typed metadata or reroute delivery. |
| Delivery overrides, notification requests, onward TLS, recipient policy, template suppressions and outgoing signing/encryption settings | Preserve outer/application choices. The merge does not activate additional outgoing policy from the inner view. |
| Received delivery-report data and other typed inspection facts | Keep the facts on the view that parsed them. The outer facade retains its report; an inner report remains inspectable through the retained inner Email. Body promotion alone does not promote every inner fact or a report belonging to an attached message. New typed facts require an explicit owner. |
| Cryptographic outcomes, protected source bytes, retained inner Email and retained decrypted artifacts | Preserve their source-specific history under ADRs 0008/0036. Do not infer trust, combine unrelated attachment facts into parent success, or claim that a newly composed/edited output retains an original signature's validity. |

Duplicate Content-IDs retain the existing lookup/presentation boundary deferred by [ADR 0035](0035-tolerant-reading-and-strict-production.md#unreferenced-inline-resources-with-repeated-content-ids-accepted-28-october-2026). This decision introduces no automatic CID renaming, arbitration or deduplication. Resource occurrence preservation does not claim that ambiguous duplicate identifiers become unambiguous.

### Import, copying and explicit merge reset

Initial import can select its merged view while constructing the Email. Rebuilding an unchanged import builder must not mutate either source or add another copy of inner resources.

Copying is different: the copied Email already supplies visible content. Retained history must not run over later edits, resurrect cleared values or append inner resources again. Copying and repeated copying preserve the visible state and retained source facts without new content reads. Supported serialization retains that state under its existing content-snapshot contract; it can read attachment or forwarded-message content when capturing a serializable snapshot. Merge selection must not add reads of its own.

An explicit merge reset on a copied separate view incorporates the retained content once at that call. Later edits win. Repeating reset does not re-add cleared content. Disabling merging after content has been materialized does not remove that visible content. Older serialized values do not acquire guessed edit history or automatic healing by re-merging.

Defaults/overrides completion remains a separate operation under ADRs 0002/0037: it resolves outgoing content/policy and drops input inspection history while keeping already materialized ordinary content. Its additive matching rules are not the import merge's collection rules.

### Ownership and protected representations

Merge operations copy owned metadata structures and preserve ordering/occurrences, without mutating the outer/inner source values. They borrow content sources and do not acquire streams, serialize messages, load optional cryptographic modules or rerun verification/decryption merely to choose a view.

Original protected bytes and retained signed containers stay authoritative for inspection. A merged Email is an editable content model, not a byte-preserving replacement for retained source. Exact EML retains ADR 0013's separate contract. Later ordinary export may reconstruct MIME; independent verification of a retained signature must use its retained representation.

## Compatibility choices

The body rule intentionally preserves existing plain/HTML replacement and the earlier agreed calendar fallback. It is not a new uniform per-alternative fallback rule. A missing inner view keeps the whole outer view; an available inner view missing plain/HTML clears those alternatives under the established contract. A present empty inner value is not absence.

Initial import builders retain their deferred merge timing. Edits that must lead over retained content use a copied materialized Email, or explicitly choose the separate view before editing. Explicit reset on a copied separate view materializes once and then permits later edits. This record does not silently broaden the later-edits guarantee from copied builders to every import-builder sequence.

Received report data stays source-specific. Exposing all inner typed facts on a unified facade would be a separate design change; this record does not infer it from the calendar correction.

## Representation and standards boundary

This is the legacy retained-content facade contract, not a universal rule that outer headers override authenticated protected headers. S/MIME processing can expose a MIME body entity or a full protected message. [RFC 8551 section 3.1](https://www.rfc-editor.org/rfc/rfc8551.html#section-3.1) describes the distinction; [RFC 9788 section 1.1](https://www.rfc-editor.org/rfc/rfc9788.html#section-1.1) updates the header-protection scheme.

A generated Outlook-derived retained artifact labelled `message/rfc822` is not evidence that the original wire payload used a protected-header scheme. Retain the original representation and separate views, and keep metadata provenance explicit. This work adds no RFC 9788 interpretation or new authenticated-header API. A required supported-input behavior that cannot be represented honestly must be reported as a conformance/design gap before a larger redesign is undertaken.

## Alternatives and consequences

A calendar-only assignment leaves no policy for the next body alternative or derived-content field. Blindly copying every inner getter would replace routing, message identity and outgoing policy and would blur original cryptographic history. A generic recursive merge or content-based deduplication would also conflict with established field-specific rules and borrowed-resource ownership.

One explicit field/operation contract makes both import construction and explicit copied-view promotion checkable. It adds no public API or new merge switches. Later changes to these compatibility choices, eligible representations or ownership require an explicit decision; accepting this record is not evidence that the implementation already covers every field.
