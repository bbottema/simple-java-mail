# ADR 0008: Build minimal MIME structures, then protect the finalized content

- Status: Accepted (retrospective record of implemented design)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Composed messages and inbound conversion, including unreleased 10.0.0 protection processing
- Implementation status: Existing MIME/protection pipeline implemented; S/MIME and OpenPGP are alternative outbound protection families. Unavailable inspection under [#822](https://github.com/bbottema/simple-java-mail/issues/822) and recipient-triggered build validation under [#704](https://github.com/bbottema/simple-java-mail/issues/704) are implemented for unreleased 10.0.0.

## Context

A universally nested `mixed` / `related` / `alternative` message can be valid but still confuse email clients when layers contain no corresponding feature. [#144](https://github.com/bbottema/simple-java-mail/issues/144) records Outlook 365 showing an attachment icon simply because `multipart/mixed` existed, even without attachments. Separately, callers expect Email getters to return their supplied values, and signatures require stable MIME bytes once encoding and structure have been determined.

## Content preservation follow-ups (accepted 2 November 2026)

Before S/MIME signing, safely encode binary content without changing decoded bytes; ADR 0007 owns this pre-signature exception (#704). Readable replies preserve explicitly preencoded selected resources in owned snapshots with matching bytes and preencoded markers, retaining metadata/ownership without reading unrelated content or applying outgoing protection (#950).

An incoming signed/decrypted OpenPGP content entity may have no MIME headers. Its initial empty line terminates that area; later body blank lines cannot substitute for it. Preserve protected bytes, default content interpretation and distinct cryptographic facts (#704).

The effective-root rule covers plain text, HTML, calendar and multipart roots even when their disposition says attachment. Explicitly attached children retain their resource boundary (#954).

Completed native decryption of an Outlook message envelope establishes DECRYPTED independently of whether the legacy materializer can produce a readable attachment. Preserve the documented protected-resource fallback, existing body/merge/view behavior and actual signature facts. Wrong keys, passwords and content I/O failures retain their existing exceptions; independent encrypted attachments do not promote a plain parent's facts. Copying/serialization retain newly established status without inventing evidence for historical snapshots. This facts-only correction completes #704; it does not add single-part plaintext materialization or recursive decryption.

## Decision

Keep the Email model separate from wire encoding. Choose the least complex MIME producer that exactly matches three content dimensions: attachments/forwarding require mixed content, embedded images require related content, and multiple plain/HTML/calendar bodies require alternatives. Eight producers cover their combinations. Security processing wraps the resulting MIME entity; it does not create a second set of body producers for each security combination.

Finalize the ordinary MIME entity, then apply **either S/MIME or OpenPGP/MIME**, signing before encryption within that family, and apply DKIM last. The builder rejects simultaneous outbound S/MIME and OpenPGP configuration. The sequential checks inside the producer are not an API promise to support both families on one composed message.

The [finalized S/MIME body-encoding clarification](0007-provider-neutral-mime-boundary.md#finalized-smime-body-encodings-accepted-1-november-2026)
under [#704](https://github.com/bbottema/simple-java-mail/issues/704) preserves relevant content headers when the ordinary entity becomes an S/MIME
body part. A selected body encoding must not depend on whether that content was the message root or already a multipart child.

Clarification accepted 2026-10-21: recipient certificates remain active S/MIME encryption requests, including certificates supplied through
recipient-group policies. A composed Email build rejects their combination with outbound OpenPGP signing or encryption, just as it rejects
conflicting email-level protection choices. Check the stored choices without cryptographic work or content reads, and explain the conflicting
sources in the argument error. This clarification is implemented under [#704](https://github.com/bbottema/simple-java-mail/issues/704) for unreleased 10.0.0.

Completion checks the effective choices again after defaults and overrides, including the selected Mailer's governance at send time.
Such a later conflict is rejected before MIME protection or submission; the initial build cannot inspect policy that has not been applied.
Same-family signing/encryption and DKIM remain supported. Clearing email-level S/MIME still leaves recipient certificates intact; callers
deliberately remove the unwanted choice or strip certificates from copied recipients. No protection request is silently ignored.

On input, recognize protection and verify/decrypt the original representation before passing effective clear content to the general MIME parser. Preserve original protected bytes and expose result metadata independently of readable content. Cryptographic signature validity is not an application trust decision; key discovery and trust remain application-owned. Do not interpret an OpenPGP `multipart/signed` as S/MIME merely because its container is signed.

The clarification accepted on 21 October 2026 distinguishes retained input inspection from defaults/overrides completion. Original
cryptographic results and protected bytes, decrypted-attachment inspection lists and retained S/MIME signed-content views are input history.
Ordinary copying and supported serialization preserve them; completion resolves outgoing content and settings without carrying those
results into its composed output. Outgoing protection configurations are selected normally, and cryptography runs during MIME preparation.
Fresh plain/unsigned metadata on a completed Email does not establish whether its input was protected or its outgoing bytes were verified.
Readable bodies, headers and ordinary resources already extracted or merged from input remain outgoing content under [ADR 0002](0002-email-defaults-and-overrides.md).

[ADR 0040](0040-retained-protected-content-merging.md) owns the bounded retained-inner-content merge contract and carries forward this record's protection, effective-root, retained-signature and materialization boundaries.

## Disposition-free related images (accepted 28 October 2026)

An image inside an interpreted `multipart/related` container does not need `Content-Disposition: inline` to be an embedded resource.
When HTML references its Content-ID and the image has no disposition, retain it only in `Email.getEmbeddedImages()` so ordinary re-export
does not create a second attachment for the same source image. An unreferenced image or one without a Content-ID remains an attachment.

Preserve the separate downloadable view for explicitly attached resources established under
[#491](https://github.com/bbottema/simple-java-mail/issues/491#issuecomment-1938694377), including a referenced attachment that appears in both
lists. Keep classification outside related containers, explicit inline behavior and intentional source occurrences unchanged. Ordinary nested
body containers carry their enclosing related context; explicitly attached multipart containers retain their complete-resource boundary.

Retain metadata, bytes, ordering and eager/lazy ownership under [ADR 0035](0035-tolerant-reading-and-strict-production.md) and
[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md). Protection preprocessing and authoritative exact/protected representations
keep their existing boundaries. The maintainer accepted this narrow clarification and authorized full workflow delivery under
[#881](https://github.com/bbottema/simple-java-mail/issues/881) for unreleased 10.0.0; the issue tracks implementation and delivery.

## Complete incoming OpenPGP signature results (accepted 28 October 2026)

Retain individual signature results from both combined OpenPGP packets and MIME-nested signing. Use supplied verification keys during
decryption as well as detached verification. A present packet-level signature must not be reported as absent merely because the decrypted
content has no `multipart/signed` wrapper. Keep every represented occurrence, including repeated signers, in a detached immutable result list.

The core-owned `OpenPgpSignatureResult` reading contract carries status, available signer identity, algorithms and failure information.
`OriginalOpenPgpDetails.getSignatures()` exposes those facts without cryptographic work or module loading. The existing summary remains
conservative: `VALID` requires at least one signature, complete inspection and every signature valid. Known invalid results dominate processing
errors, then missing verification keys, then unknown/incomplete inspection. Complete inspection with no signatures yields `NOT_PRESENT`;
an empty list alone establishes no absence. Singular signer and signature/hash algorithm getters are shortcuts for exactly one represented
signature result and otherwise return null. Signature construction stays internal under [ADR 0031](0031-public-api-and-internal-construction.md).

Keep decryption facts independent of signature results: established decryption remains `DECRYPTED` when signature verification fails or its
key is missing. Genuine ciphertext/integrity failures keep their existing boundary. Retain original protected bytes and readable content where
existing import behavior permits it. Key ownership and trust remain application concerns; no automatic key discovery is introduced.

Successful native close establishes decryption and integrity only when native metadata also confirms an encrypted layer. Processing an
unencrypted literal, compressed literal or signed-only packet does not establish decryption merely because its MIME wrapper declares
`multipart/encrypted`. With receive keys selected, retain that inconsistent wrapper's original bytes and unopened view with `FAILED`
decryption and conservative unknown signature facts; do not expose its payload as successfully decrypted content. Missing-key handling and
actual encrypted content retain their existing outcomes. This clarification was accepted on 1 November 2026 as follow-up work on the
unreleased OpenPGP feature [#704](https://github.com/bbottema/simple-java-mail/issues/704); it adds no receive setting or public result state.

A typed signature-processing abort before established decryption retains known error facts
with unknown decryption; an observed native integrity failure, including one suppressed during close, retains the existing failed-decryption
and unknown-inner-signature boundary. Keep native armor/provider interpretation, the no-decompression boundary for detached containers and
bounded inspection. A skipped or stopped signature container cannot establish a complete positive summary; retain established outer results.

Copying and supported serialization preserve detached facts. Older snapshots must not invent complete inspection, absent signatures or
successful aggregate verification from a formerly selected scalar result. Missing-module and unopened-layer inspection retain truthful
unknown states. Apply these rules consistently across formats while preserving the transfer-decoding and stream boundaries below.

The maintainer approved this API design, implementation by subagent and full workflow delivery under
[#704](https://github.com/bbottema/simple-java-mail/issues/704). Implemented for unreleased 10.0.0, with no released 9.x OpenPGP migration.
The issue records development-branch delivery.

## Transfer encoding on incoming OpenPGP parts (accepted 28 October 2026)

Decode a signature or ciphertext part's MIME Content-Transfer-Encoding before passing its payload to the OpenPGP implementation.
Use Jakarta Mail's existing MIME interpretation rather than a new decoder. Keep the first signed MIME entity and retained original protected
EML bytes unchanged; decoding a protection payload does not authorize reserializing or decoding the signed entity before verification.
Retain the established raw-payload tolerance when a part has no MIME header separator and no declared transfer encoding. Declared encodings
still follow Jakarta Mail's interpretation and failure behavior.

Preserve ordinary unencoded input, outbound production and the existing missing-key, invalid-signature, decryption-failure and unavailable-module
results. Malformed or unsupported encodings retain the existing failure boundary and must not become successful cryptographic results.
Managed decoding reads close streams they open under [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md); caller-owned top-level
streams retain their existing lifetime. [ADR 0035](0035-tolerant-reading-and-strict-production.md) owns tolerant input interpretation.

The maintainer approved implementation and full workflow delivery under [#704](https://github.com/bbottema/simple-java-mail/issues/704).
This correction is implemented for unreleased 10.0.0 without adding public APIs, configuration options or dependencies. Combined packet-level
signing and encryption is a separate finding and remains outside this decision. The issue records development-branch delivery.

## Locale-independent image metadata (accepted 23 October 2026)

Guessing an embedded image's MIME type from its filename must not depend on the JVM's language or country. Interpret filename extensions
and charset labels with locale-independent case conversion. This applies to classpath image resolution and to URL resolution when the
connection supplies no content type. A content type reported by the connection remains authoritative.

This corrects metadata classification without changing resource lookup, path case, names, content IDs, image bytes or content ownership.
The existing disk mapping and explicitly supplied MIME types retain their behavior. No application setting or global locale change is
introduced. [#860](https://github.com/bbottema/simple-java-mail/issues/860) tracks implementation and delivery for unreleased 10.0.0.

## Shared upstream DKIM engine (accepted 23 October 2026)

The upstream DKIM library owns signing semantics; SJM owns MIME finalization, configuration translation and protection ordering.
Future DKIM algorithm support, canonicalization corrections and signing fixes belong in that shared engine. SJM consumes the finalized-byte
entry point without acquiring Angus linkage or allowing a signing wrapper to reconstruct protected MIME. Its Angus submission adapter remains
a separate transport concern under [ADR 0007](0007-provider-neutral-mime-boundary.md).

Preserve upstream's existing callers through an additive capability, including public/protected contracts, wrapper inheritance, subclass hooks,
Java 8, defaults and dependency transitivity. SJM explicitly maps its existing choices and keeps its application API. The new route must not
register cryptographic providers globally, including during DNS-key handling. A published upstream capability precedes SJM's declared dependency.

This supersedes provisional retention of SJM's local engine, which followed the August 2026 MIME refactor and maintainer-approved triage.
The later Ed25519 prehash correction reached upstream while SJM's separate implementation diverged. Sharing ownership prevents that split from
recurring while retaining the finalized-content boundary. This does not rewrite the earlier approval or change the separate S/MIME helper contract.

The maintainer accepted this direction on 23 October 2026, conditional on backwards compatibility and signing without Angus. Implementation and
delivery are tracked in [SJM #855](https://github.com/bbottema/simple-java-mail/issues/855) and
[upstream #28](https://github.com/simple-java-mail/java-utils-mail-dkim/issues/28). Concrete public API and execution details belong in the feature
design and active plan, rather than this ownership decision.

### Manual DKIM helper result (clarified 2 November 2026)

`MailerHelper.signMessageWithDKIM(MimeMessage, Email)` returns a new message containing the signed representation; the supplied message does not
receive that signature. Callers retain the returned value for application-owned sending or writing. In the unreleased 10.0.0 finalized-byte path,
finalization and signing complete during the helper call, and repeated writes retain the signed bytes. The Email supplies already selected DKIM
configuration and the From identity without completing Mailer templates. The optional-module boundary, signing engine and protection order remain
unchanged. This clarifies the existing helper contract and its documentation under [#855](https://github.com/bbottema/simple-java-mail/issues/855);
it introduces no runtime API or behavior change.

## Protecting an existing MIME message (accepted 22 October 2026)

The supported `MailerHelper.signAndOrEncryptMessageWithSmime(Session, MimeMessage, Email)` utility protects the supplied MIME message,
using the Email's outgoing S/MIME choices and optional configured Message-ID. Signing and encryption are independently optional; when both are requested, sign first and
finalize each transformed stage before the next. Apply the same recipient-certificate selection, Email-level fallback and configured algorithms
as ordinary conversion. A request with no S/MIME operation returns the supplied message instance without reading or finalizing its content or
loading the optional module. An active protection request retains the focused missing-module failure under [ADR 0006](0006-optional-modules.md).

The supplied MIME content and applicable headers remain the input, including application changes made after conversion; Message-ID follows the configuration rule below. The Email supplies configuration;
it does not replace the MIME input or cause Mailer templates to be completed. This utility does not apply unrelated DKIM or OpenPGP choices.
Cryptographic implementation and configuration validation stay at their existing feature boundaries. Main-runtime orchestration is shared with
ordinary conversion under [ADR 0031](0031-public-api-and-internal-construction.md), preserving that path's established ordering and finalization.

The public helper retains its existing signature. A MIME finalization failure is an owned preparation failure with its original cause retained;
the converter keeps its existing conversion-failure mapping. Module-loading and cryptographic runtime failures retain their existing types.
This corrects behavior already present in released 9.3.6 without introducing a configuration switch or changing ordinary conversion's selection.
[#850](https://github.com/bbottema/simple-java-mail/issues/850) tracks implementation and delivery for unreleased 10.0.0.

### Message-ID selection for existing MIME protection (clarified 3 November 2026)

During active signing or encryption, a nonempty ID configured on the Email argument supplies the result's Message-ID. Without that value,
generate a new ID instead of implicitly retaining a header observed on the supplied MIME message. The Email's choice wins when it differs
from the MIME header. Repeated writes of the finalized result retain its selected identity. A no-op request keeps returning the supplied
message unchanged, without finalization, content access or optional-module loading.

Conversion and copying can already retain an ID on an Email, so a present configured value does not establish that the caller directly used
`fixingMessageId`. Callers can use `clearId()` on a copied builder for a new outgoing identity or deliberately select
`fixingMessageId(message.getMessageID())` when retaining an existing message's identity. This clarifies the helper's current behavior and the
Message-ID exception to header preservation; it changes neither runtime selection nor ordinary copy/import defaults. No new API or setting
is introduced.

## Root message and child header ownership (accepted 22 October 2026)

After existing protection preprocessing selects the effective root message, its headers alone supply the Email's message-level facts and
generic headers. Headers on attachment parts, body parts and nested multipart containers describe those parts; they cannot replace the root
bounce address, add root receipt destinations or appear as root custom headers. A separately imported attached message has its own root.

Continue extracting established per-part resource metadata and content through their existing paths. Readable messages with misplaced child
headers remain importable; ignore those fields at the parent level without adding an arbitrary attachment-header storage API or warning scheme.
This keeps source ownership consistent with [ADR 0035](0035-tolerant-reading-and-strict-production.md#misplaced-child-headers-accepted-22-october-2026).
It does not alter protection selection, verification, original/exact byte boundaries or lazy resource ownership. The maintainer approved this
correction under [#840](https://github.com/bbottema/simple-java-mail/issues/840) for unreleased 10.0.0; the issue tracks implementation and delivery.

## Explicit child multipart attachments (accepted 22 October 2026)

After protection preprocessing selects the effective message, preserve a child `multipart/*` entity explicitly marked
`Content-Disposition: attachment` as one attachment. Its children do not supply parent bodies or separate parent resources.
Keep the complete readable resource, filename and existing type/description/ID/encoding metadata through the normal attachment path.

The effective root still supplies the message content even if its own disposition says attachment. Ordinary nested body containers,
inline and filename-only multipart entities retain established recursive interpretation and repeated text/HTML concatenation. Email remains
a convenient content model rather than a lossless MIME tree. This retains the interoperability rationale behind
[#139](https://github.com/bbottema/simple-java-mail/issues/139) while respecting the complete-entity disposition defined by
[RFC 2183 section 2.9](https://www.rfc-editor.org/rfc/rfc2183.html#section-2.9).

This correction adds no switch or public API. Existing protection preprocessing, attached-message handling, report ownership and authoritative
exact/protected bytes retain their separate contracts. [ADR 0035](0035-tolerant-reading-and-strict-production.md#explicit-multipart-attachment-boundaries-accepted-22-october-2026)
owns interpretation; [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#multipart-attachment-content-ownership-accepted-22-october-2026)
owns eager/lazy resources. [#849](https://github.com/bbottema/simple-java-mail/issues/849) tracks implementation and delivery for unreleased 10.0.0.

## Unavailable S/MIME inspection (accepted 2026-10-21)

Ordinary inbound conversion remains tolerant when the optional S/MIME module is absent. Expose an honest indicator of unavailable
inspection, and keep the returned protection facts consistent with it: uninspected content must not be represented as confirmed plain,
unsigned or unencrypted content. Module absence is not evidence of failed signature verification or failed decryption. This extends the
ordinary-inspection boundary retained by [#784](https://github.com/bbottema/simple-java-mail/issues/784); explicitly requested decryption
still requires the module under [ADR 0006](0006-optional-modules.md).

Preserve header-recognizable protected input through the existing original-source boundary without claiming that MIME header declarations
establish cryptographic facts. Verification and decryption remain with the optional implementation. Original facts retain their existing
copying, serialization and completion ownership; this decision does not turn unrelated protected attachments into the owning Email's facts.

Use `UNKNOWN` in the existing S/MIME mode, verification and decryption result enums when the available inspection has not established the
corresponding fact. A separately checked availability flag must not leave the existing getters making contradictory assertions. Recognizable
headers may establish an outer encryption mode without establishing verification or decryption outcomes. The legacy signature-valid Boolean
is absent for unknown inspection. Known failures remain distinguishable, and an unknown represented layer prevents an aggregate all-valid
or all-decrypted claim. This clarification concerns S/MIME; the separate OpenPGP decision below was accepted on 22 October 2026.

This clarification is implemented under [#822](https://github.com/bbottema/simple-java-mail/issues/822) for unreleased 10.0.0.

### Unopened S/MIME ciphertext (accepted 23 October 2026)

The same distinction applies when the S/MIME implementation is available but encrypted content cannot be inspected. Retain `KEY_MISSING`
when no decryption key was supplied and `FAILED` for an actual decryption failure, with `UNKNOWN` verification while the ciphertext remains
unopened. An encrypted envelope does not establish the presence or absence of an inner signature.

After successful decryption, report the established content facts: `NOT_SIGNED` for inspected unsigned content, `VALID` or `INVALID` for an
inspected signature, and `ERROR` for an actual verification error. A later verification problem must retain the established decryption fact.
Preserve the original protected representation, failure information, copying and supported snapshot ownership. Existing result enums and
public methods suffice; no separate availability flag is introduced. This clarification is implemented under
[#704](https://github.com/bbottema/simple-java-mail/issues/704) for unreleased 10.0.0.

#### Nested encrypted content (accepted 31 October 2026)

Successful outer decryption can expose another encrypted S/MIME entity. Its unopened ciphertext cannot establish that the content is unsigned,
so retain `UNKNOWN` verification and an absent signature-valid Boolean. Keep the established outer `DECRYPTED` result, the retained original
protected representation and the existing content/attachment behavior. Copying and supported serialization preserve these historical facts,
including when automatic content merging is disabled.

This is a narrow application of the unopened-ciphertext distinction, accepted under [#704](https://github.com/bbottema/simple-java-mail/issues/704)
for unreleased 10.0.0. It adds no recursive decryption and does not change the separate rules for combining represented decryption results.
Inspected unsigned content and existing signature, missing-key and failure results retain their meanings.

## Unavailable OpenPGP inspection (accepted 22 October 2026)

Use `UNKNOWN` when OpenPGP inspection could not happen, including ordinary import without the optional implementation. Keep missing-key
results and actual verification/decryption failures distinct. Recognizable outer headers can establish encryption or a visible signed envelope;
an unreadable encrypted layer does not establish the presence or absence of an inner signature. Its signature status remains unknown.

For an observed outer signed envelope without the module, retain SIGNED with UNKNOWN verification and NOT_ENCRYPTED decryption for that outer
layer. An observed encrypted envelope retains ENCRYPTED with UNKNOWN verification and decryption. A structural inspection failure that cannot
classify the envelope uses UNKNOWN for all three facts rather than inventing plain content. Missing decryption keys and actual decryption
failures retain their corresponding result, with UNKNOWN signature status while their ciphertext remains uninspected. Ordinary confirmed plain
input and successfully decrypted unsigned content retain their established absence results.

Preserve accessible content and original protected bytes, existing copying/snapshot ownership and explicit-operation module requirements.
Do not add a separate availability Boolean that leaves the existing result getters asserting contradictory facts. Key discovery and trust policy
remain outside this decision. [#704](https://github.com/bbottema/simple-java-mail/issues/704) tracks this accepted design for unreleased 10.0.0;
implementation follows this distinction on the 10.0.0 development branch. This does not reopen the completed S/MIME repair or the original
OpenPGP feature.

## Exact EML incoming facts (accepted 1 November 2026)

[ADR 0013](0013-exact-eml-submission.md#automatic-incoming-protection-inspection-accepted-1-november-2026) clarifies that exact construction
automatically records available incoming protection facts without applying readable-content replacements or outgoing transformations. The
feature implementation owns cryptographic interpretation; the exact builder keeps its raw parsed view and authoritative bytes. Missing
modules produce recognized declarations with UNKNOWN unestablished outcomes, while actual missing-key and failure results remain distinct.

For exact inspection, an uninspected signed envelope cannot establish the absence of inner encryption. The S/MIME facts-only hook also treats
clearly declared ciphertext exposed by a verified outer signature as unopened: combine UNKNOWN with the outer results under the existing
severity ordering, retain INVALID/ERROR when already established, and keep the outer original bytes and signer metadata. Do not recursively
decrypt that content, inspect unrelated attachments or broaden ordinary conversion's existing processing. This clarification is accepted for
unreleased 10.0.0 under [#713](https://github.com/bbottema/simple-java-mail/issues/713).

## MIME producer selection

Protection facts describe the owning Email's represented layers, not an aggregate of unrelated attachments. Successful envelope decryption
is independent of signature validity; recognizing encryption without keys does not report plain content. Explicit requests require the module
under [ADR 0006](0006-optional-modules.md). Metadata ownership follows
[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md), without cloning arbitrary borrowed resources.

The selector's three booleans map to these producers. Mixed content means attachments or a forwarded email; related content means embedded images; alternative content means more than one body variant among plain text, HTML, and calendar text.

| Producer | Mixed | Related | Alternative |
| --- | --- | --- | --- |
| `MimeMessageProducerSimple` | no | no | no |
| `MimeMessageProducerAlternative` | no | no | yes |
| `MimeMessageProducerRelated` | no | yes | no |
| `MimeMessageProducerMixed` | yes | no | no |
| `MimeMessageProducerMixedRelated` | yes | yes | no |
| `MimeMessageProducerMixedAlternative` | yes | no | yes |
| `MimeMessageProducerRelatedAlternative` | no | yes | yes |
| `MimeMessageProducerMixedRelatedAlternative` | yes | yes | yes |

The base producer uses `MessageIdFixingMimeMessage` so a caller-supplied Message-ID survives later wrapping. S/MIME encryption also considers recipient-level certificates when deciding whether the module is needed; an absent global certificate does not mean no recipient requested encryption.

## Rationale and evidence

- [#144](https://github.com/bbottema/simple-java-mail/issues/144) explicitly chooses structure based on actual content to resolve #133's client behavior. [fc714cfe](https://github.com/bbottema/simple-java-mail/commit/fc714cfe4937c80660b3a1ebed54cc01bf002fc7) introduced the dynamic producers, and the [completion comment](https://github.com/bbottema/simple-java-mail/issues/144#issuecomment-394168654) confirms coverage of all combinations.
- [#232](https://github.com/bbottema/simple-java-mail/issues/232) explains why encoding attachment names during building made getters surprising and incoming Outlook conversion awkward. [ccd2ef12](https://github.com/bbottema/simple-java-mail/commit/ccd2ef120e1877fa23a6c2a574fdbfe71a4c3d2d) deliberately moved MIME encoding to the sending phase to keep the Email model clean.
- [8bf8043b](https://github.com/bbottema/simple-java-mail/commit/8bf8043bd21f8ac7f57ad19b962f26610bf10adc) repaired S/MIME/DKIM and envelope-sender interactions. The [maintainer's #297 comment](https://github.com/bbottema/simple-java-mail/issues/297#issuecomment-1440042345) confirms the established S/MIME signing, encryption, then DKIM order.
- [#704](https://github.com/bbottema/simple-java-mail/issues/704) requires exact signed bytes, final encodings/boundaries, explicit mixed-family behavior, and distinct missing-key/invalid-signature states. The [completed pipeline plan](https://github.com/bbottema/simple-java-mail/blob/127b2128a38a592f166b6ca451ef33f283963185/MIME_PIPELINE_IMPROVEMENT_PLAN/README.md) selects alternative protection families and pre-parse protection handling; [883814aa](https://github.com/bbottema/simple-java-mail/commit/883814aadcf65252f3163d3a04ed4846f2a98dd6) implements them.

Keeping DKIM last follows the requirement that its body hash cover the outgoing protected representation; mutating MIME afterward risks invalidation. This explains the documented order, rather than inventing a separate historical debate about every permutation.

### Delegate OpenPGP cryptographic orchestration

[af7c94b8](https://github.com/bbottema/simple-java-mail/commit/af7c94b8e01cad5ba2e019650e9d992cea20a56b), 2026-09-06, replaces direct low-level Bouncy Castle signing, encryption, key selection, verification, and decryption orchestration with a [PGPainless adapter](../../modules/openpgp-module/src/main/java/org/simplejavamail/internal/openpgpsupport/PgpainlessOpenPgpAdapter.java). Simple Java Mail still owns MIME framing, exact entity selection, nesting limits, configuration translation, and result states. Bouncy Castle remains underneath and in internal key/signature handling; the change does not remove it entirely or expose PGPainless in the public configuration API.

This narrows the library's responsibility from maintaining the cryptographic workflow to adapting a specialist implementation. That maintenance rationale is inferred from the removed implementation and the new boundary: the commit states the delegation but provides no comparative explanation of why PGPainless was selected over other libraries. The accompanying tests explicitly cover expired-key rejection, signing-key capabilities, selected subkeys, ciphertext integrity, configured algorithms, and concurrent use.

The tradeoff is an additional optional dependency and reliance on its key-validation and verification policy. Upgrades therefore need compatibility and interoperability checks, not only MIME round trips. Applications still own identity trust; a valid result under the cryptographic policy is not proof that a key belongs to a claimed sender.

## Alternatives

**One universal multipart structure** was the prior implementation and was replaced for demonstrated client compatibility. **Encoding values in builders** was also replaced in response to #232. **Arbitrary outbound nesting of S/MIME and OpenPGP** was explicitly excluded from the first OpenPGP release by the plan. **Parsing and reserializing before verification** would discard the original byte boundary and is incompatible with the documented protection requirements.

## Consequences

Simple messages stay simple and clients see multipart containers that correspond to real content. Adding another body concept requires revisiting selector dimensions and affected producers. Encoding belongs at conversion; resource names, content IDs, and attachment classification still have separate compatibility rules documented in the [resource naming history](../research/MIME_RESOURCE_NAMING_REPORT.md).

Security and conversion must preserve Message-ID and exact protected bytes across repeated serialization. Missing optional modules must not silently downgrade requested outbound protection. On incoming mail, accessible content and unavailable verification/decryption facts remain distinct; successful parsing alone is not evidence of trust. Exact EML bypasses this composition pipeline and uses its own preservation contract.

## Interpreted mail facts remain part of Email (accepted 2026-10-16)

This addendum is implemented under [#763](https://github.com/bbottema/simple-java-mail/issues/763), verified and accepted in production review on 2026-10-18;
the earlier MIME/protection decisions remain implemented. The independent integration programme is [#764](https://github.com/bbottema/simple-java-mail/issues/764).

### Context and decision drivers

An application already converts MIME/EML into Email to inspect bodies, addresses and protection results. Delivery-status notifications are specialized
mail content, not a second transport outcome. Exposing their interpreted facts through another converter facade would fragment the gateway without
changing who owns the content. Turning those facts into workflow transitions, however, requires application-specific persistence, trust and business rules.

### Decision

Enrich the common Email read model with immutable source facts during existing inbound conversion. Apply the established protection-before-interpretation
order: inspect the effective outer notification, retain authoritative protected/exact bytes and do not promote an attached report into its parent's facts.
Keep source metadata separate from outgoing configuration, SMTP submission results and application state. Ordinary templates, locks and outbound MIME
producers must not manufacture source facts. Ordinary copies and supported serialization preserve provenance, while new replies/forwards
describe their own source rather than their subject. Defaults/overrides completion follows ADR 0002's outgoing-data boundary and omits
received reports and their nested facts. [#763](https://github.com/bbottema/simple-java-mail/issues/763) tracks the correction of their earlier carryover.

Core owns the reading contracts; the main conversion implementation owns parsing and assembly. Reuse Jakarta Mail's MIME/header machinery without an
optional experimental DSN dependency. Missing/unusable facts remain absent; a recognized malformed notification can retain usable facts and warnings.
[ADR 0035](0035-tolerant-reading-and-strict-production.md) makes the inbound tolerance boundary explicit: recover unambiguous formatting and retain
completed facts when optional interpretation fails, with warnings. Failure to obtain required content still fails conversion rather than returning
silently truncated content. Received claims are not authenticated delivery proof or permission to retry.

Use cohesive read types for interpreted values rather than leaving applications to split status strings or keep parallel type/value getters aligned.
An enhanced status exposes checked numeric components and broad classification while preserving unfamiliar subject/detail combinations. Reported
addresses, servers and diagnostics retain their extensible format identifiers alongside their values; only recognized usable formats offer optional
Internet-mailbox or SMTP-response views. Those conveniences must not invent absent names, outgoing preferences or delivery evidence. Their construction
stays internal to conversion, and automatic rendering excludes reported identities and prose.

### Alternatives considered

- **A parallel delivery-report extractor API:** explicit, but forces callers to select another gateway for facts that belong to the converted Email.
- **Application-only raw parsing:** avoids library work but duplicates mail-format/protection knowledge across integrations.
- **An SJM handler registry, dispatcher or durable workflow:** convenient for one example, but makes mail interpretation own application persistence,
  scheduling, correlation and business state. Teach those integrations instead, using their existing platforms.

### Consequences and boundaries

Specialized interpretation is discoverable through the same Email object, without getter-side I/O or new configuration layers. It does not turn Email
into a mutable delivery tracker. Callers choose correlation, trust, serialization, logging and downstream action; no missing-report success inference or
automatic retry follows. Metadata must survive copying and supported serialization without retaining MIME objects or changing wire bytes.

Related: [optional modules](0006-optional-modules.md), [provider-neutral MIME](0007-provider-neutral-mime-boundary.md),
[DSN identifiers](0022-dsn-identifiers-belong-to-send-attempts.md), [safe retries](0030-safe-bounded-mail-send-retries.md),
and [public/internal boundaries](0031-public-api-and-internal-construction.md). Acceptance tests and feature contracts belong in the implementation plan,
not this decision record. Sending ownership, state machines and the shared concurrency infographic's resource layers remain unchanged.

## S/MIME media-type capitalization (accepted 28 October 2026)

Recognize equivalent MIME type/subtype spellings throughout installed S/MIME preprocessing and metadata inspection. A mixed-case
`Multipart/Signed` or `Application/PKCS7-Mime` declaration must not turn protected input into plain-mail results. Preserve original
header text and protected bytes, and retain the distinctions between unavailable inspection, missing keys and cryptographic failure.
This applies the existing source-fact boundary under [#884](https://github.com/bbottema/simple-java-mail/issues/884) for unreleased 10.0.0;
the issue tracks implementation and delivery.

## S/MIME parameter capitalization (accepted 28 October 2026)

Interpret the supported `smime-type` values `signed-data` and `enveloped-data` without regard to capitalization throughout incoming
S/MIME processing and metadata inspection, including the shared attachment and Outlook paths. Changing only this spelling must not
select decryption for opaque signed content or describe encrypted content as signed. Preserve the original parameter spelling and
protected bytes; missing and unknown values retain their existing interpretation. The distinctions between unavailable inspection,
missing keys and cryptographic failure remain unchanged. This extends the media-type decision under
[#890](https://github.com/bbottema/simple-java-mail/issues/890) for unreleased 10.0.0; the issue tracks implementation and delivery.

## Opaque signatures inside encrypted S/MIME (accepted 29 October 2026)

Recognize standard `application/pkcs7-mime` and legacy `application/x-pkcs7-mime` opaque signed entities consistently at the
top level and after decryption. An opened `signed-data` entity contributes its actual signature result to the outer Email's
`SIGNED_ENCRYPTED` facts. Retain established `DECRYPTED` status when signature verification fails or cannot be completed.
Signed-content merging changes the content view, not those protection facts. MIME/EML and Outlook message-level envelopes share
this rule. Keep the existing Outlook message-envelope identification; other protected files do not supply the parent message's
signature facts merely because that parent is encrypted. Preserve readable content,
exact outer protected bytes when the source supplies EML, and the existing stream ownership. Outlook does not invent original
EML bytes. The correction is tracked under [#905](https://github.com/bbottema/simple-java-mail/issues/905)
for unreleased 10.0.0 and introduces no new configuration or public API.

## Retained detached-signed content after decryption (accepted 29 October 2026)

When a decrypted Outlook MSG contains a detached `multipart/signed` entity, retain that signed container and its signature material in the historical signed-content view and generated decrypted EML. Signature inspection records the actual result independently from extracting readable content. A valid signature remains independently verifiable after exporting and reimporting the retained EML; ordinary readable/merged body content and resource interpretation keep their existing roles.

Preserve the outer message's established decryption and signature facts, including the standard and legacy opaque handling accepted under #905. This restoration does not broaden the existing MIME/EML or opaque-artifact retention policy, introduce public APIs or settings, change trust decisions, or invent original EML bytes for Outlook MSG. Copying, serialization and source/stream ownership continue under ADR 0036. [#918](https://github.com/bbottema/simple-java-mail/issues/918) tracks implementation and delivery for unreleased 10.0.0.

## Detached S/MIME attachment occurrences (accepted 31 October 2026)

Keep a detached signature and every ordinary attachment from the signed content as distinct source occurrences in the imported Email's
ordinary attachment list. A shared filename, media type or payload does not establish that one occurrence already represents another.
Retain supplied names and ordering rather than renaming either resource or suppressing signature material because a clear attachment has
the same name. The ordinary parser visits the readable signed content; its attachments do not represent the enclosing signature part.

This applies [ADR 0036's structural occurrence rule](0036-structural-message-snapshots-and-borrowed-content.md#copy-the-structure-the-message-owns)
without changing readable content, established verification results, historical inspection views, protected source bytes or eager/lazy content
ownership. It does not expand the protected-artifact retention policy or add another representation of the same source occurrence.
The correction targets unreleased 10.0.0 under [#704](https://github.com/bbottema/simple-java-mail/issues/704).

## Missing reply-body alternatives (accepted 28 October 2026)

Reply priming keeps both plain and HTML quotes useful when the interpreted original contains only one body format. Generate the
missing format once before quoting: escape plain text into HTML while preserving line breaks, or derive readable plain text from HTML
with paragraph/list breaks, link destinations and useful image alternative text. Script and style content do not contribute to that text.
When both formats exist, preserve their independent content. Apply the existing HTML quote template once after generation.

Keep this conversion small and internal to the existing Email/MimeMessage reply and reply-all paths, using the existing HTML dependency.
It does not fetch links/images or add public conversion settings, a rendering framework or dependencies. Later body editing and ordinary
Email governance retain their existing rules; this is not ongoing synchronization of body alternatives. Keep routing, thread headers,
resource ownership, authoritative source representations and current empty-part interpretation unchanged under [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md).

This deliberately changes the longstanding empty-quote result for single-format originals while retaining the public signatures in
[ADR 0031](0031-public-api-and-internal-construction.md). The maintainer accepted the behavior and authorized workflow completion for
unreleased 10.0.0 under [#894](https://github.com/bbottema/simple-java-mail/issues/894); the issue tracks implementation and delivery.

## Readable composed replies (accepted 1 November 2026)

Reply and reply-all priming from a composed Email quotes the readable content already represented by that value. It may project the source's
visible headers into an internal reply-routing message, but does not apply outgoing signing or encryption merely to obtain the quote. Preserve
the existing reply address/thread rules, quote templates, generated missing alternative and referenced-resource selection. Selected quoted
images retain the ordinary import path's eager owned byte snapshots, including managed stream closure and original read/close failures.
Unrelated attachments need not be consumed to quote readable text. Exact Email and MimeMessage inputs retain their authoritative source and
receive-side interpretation boundaries.

This correction must not alter email protection. The original Email retains its configuration, source facts and content, and ordinary public
conversion, rehearsal and sending still apply its configured protection. A reply's own explicit protection and normal governance remain separate
choices. The temporary preparation view is internal and cannot replace a protected send representation. Incoming ciphertext still requires
explicit receive-side decryption; reply priming does not weaken that boundary or introduce a new public API.

The maintainer accepted this condition under [#950](https://github.com/bbottema/simple-java-mail/issues/950) for unreleased 10.0.0. The issue
tracks delivery. [ADR 0007](0007-provider-neutral-mime-boundary.md) retains the finalized protected-send boundary and
[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md) retains source/resource ownership.

## Implementation anchors

[DeliveryStatusReportParser](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/DeliveryStatusReportParser.java)
implements interpreted notification facts within the shared MIME pass. [DeliveryStatusReportTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/converter/DeliveryStatusReportTest.java)
covers this extension, governed by the [tolerant-reading decision](0035-tolerant-reading-and-strict-production.md). Its presence is not a claim of a new test run.

The international `message/global-delivery-status` part needs a narrow decoding adapter: Jakarta Mail normally ignores transfer encoding on
`message/*`, while RFC 6533 permits it for this subtype. Delegate decoding to Jakarta Mail's utilities for that part only; do not introduce a
global Session/JVM switch or change authoritative protected bytes to make optional report extraction succeed.

[MimeMessageProducerHelper](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageProducerHelper.java), [SpecializedMimeMessageProducer](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java), [builder validation](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java), and [EmailConverter](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java) implement these boundaries. [OpenPgpMimeTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/openpgp/OpenPgpMimeTest.java) and [OpenSSL S/MIME interoperability tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/internal/smimesupport/OpenSslSmimeInteroperabilityTest.java) are existing verification anchors, not newly executed tests for this record.
